package com.khoryz666.waypoint.ingest

import com.khoryz666.waypoint.data.AppDatabase
import com.khoryz666.waypoint.data.Feedback
import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.NextStep
import com.khoryz666.waypoint.data.SettingsStore
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.data.TrackUnit
import com.khoryz666.waypoint.domain.AppClock
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.domain.Grade
import com.khoryz666.waypoint.domain.LogKind
import com.khoryz666.waypoint.domain.ParseStatus
import com.khoryz666.waypoint.domain.RecallSource
import com.khoryz666.waypoint.domain.Structured
import com.khoryz666.waypoint.domain.applyGrade
import com.khoryz666.waypoint.domain.sectionProgress
import com.khoryz666.waypoint.llm.BudgetExceeded
import com.khoryz666.waypoint.llm.ContextBuilder
import com.khoryz666.waypoint.llm.ExerciseOutput
import com.khoryz666.waypoint.llm.GradeOutput
import com.khoryz666.waypoint.llm.LlmService
import com.khoryz666.waypoint.llm.Part
import com.khoryz666.waypoint.llm.QuestionsOutput
import com.khoryz666.waypoint.llm.StreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class CommentState { STREAMING, DONE, SKIPPED_BUDGET, NO_API, FAILED }

/** The feedback shown after a save: the instant receipt, then the comment as it streams in. */
data class FeedbackCard(val logId: Long, val receipt: String, val comment: String = "", val state: CommentState = CommentState.STREAMING)

/** The "今天做不了" reasons (spec 6.1). */
enum class SkipReason(val en: String, val zh: String, val restOnly: Boolean = false) {
    TIRED("tired", "累"), PAIN("pain", "痛", true), NO_TIME("no time", "没时间"), NO_EQUIPMENT("no equipment", "没器材"),
    NO_POOL("no pool", "没泳池"), ILL("ill", "生病", true),
}

/**
 * Everything that happens after a log is confirmed (spec 5.4, 6.x, 8.4): the receipt first, then
 * the Flash comment, the next step, recall questions and the rolling summary. Follow-ups run in
 * the app scope so leaving the screen doesn't cancel them.
 */
class FeedbackEngine(
    private val db: AppDatabase,
    private val llm: LlmService,
    private val context: ContextBuilder,
    private val receipts: ReceiptBuilder,
    private val settings: SettingsStore,
    private val clock: AppClock,
    private val scope: CoroutineScope,
) {
    private val _current = MutableStateFlow<FeedbackCard?>(null)
    val current: StateFlow<FeedbackCard?> = _current

    fun dismiss() {
        _current.value = null
    }

    /** Shows the receipt at once and starts the follow-ups. */
    suspend fun onConfirmed(confirmed: InputRepository.Confirmed) {
        val log = confirmed.log
        _current.value = FeedbackCard(log.id, receipts.receipt(log))
        scope.launch { followUps(log, confirmed.imageDataUrls) }
    }

    /** A balance shortcut: receipt only, no LLM (spec 5.2). */
    suspend fun onBalance(logId: Long) {
        val log = db.logs().get(logId) ?: return
        _current.value = FeedbackCard(log.id, receipts.receipt(log), state = CommentState.DONE)
    }

    private suspend fun followUps(log: LogEntry, images: List<String>) {
        val track = log.trackId?.let { db.tracks().get(it) }
        // Money notes stay on the device (spec 6.3): balance entries get no comment.
        if (log.structured is Structured.Balance) {
            finish(log.id, "", CommentState.DONE)
        } else {
            comment(log, track, images)
        }
        if (track == null) return
        if (log.structured is Structured.Exercise) runCatching { exerciseNext(track, null, null) }
        runCatching { recallQuestions(log, track) }
        if (track.logsSinceSummary >= SUMMARY_EVERY) runCatching { refreshSummary(track) }
    }

    private fun finish(logId: Long, text: String, state: CommentState) {
        _current.value = _current.value?.takeIf { it.logId == logId }?.copy(comment = text, state = state)
    }

    private suspend fun comment(log: LogEntry, track: Track?, images: List<String>) {
        val s = settings.current()
        if (!s.hasApiKey || s.apiProblem != null) return finish(log.id, "", CommentState.NO_API)
        val receipt = _current.value?.takeIf { it.logId == log.id }?.receipt.orEmpty()
        val input = buildString {
            append("## Receipt shown to the user\n").append(receipt)
            track?.let { t -> db.nextSteps().open(t.id)?.let { append("\n\n## Current next step\n").append(it.text) } }
            computedFacts(log, track)?.let { append("\n\n## Computed by the app\n").append(it) }
            append("\n\n## New log\n").append(log.rawText.orEmpty())
            log.structured?.let { append("\n").append(Structured.encode(it)) }
        }
        val text = userText(track, input)
        val check = (log.structured as? Structured.Math)?.checkProblems.orEmpty().isNotEmpty()
        val parts = listOf(Part.Text(text)) + if (check) images.take(3).map(Part::Image) else emptyList()
        val shown = StringBuilder()
        try {
            llm.stream("comment_log", parts, 300).collect { event ->
                when (event) {
                    is StreamEvent.Delta -> {
                        shown.append(event.text)
                        _current.value = _current.value?.takeIf { it.logId == log.id }?.copy(comment = visible(shown.toString()))
                    }
                    is StreamEvent.Done -> {
                        val (comment, next) = splitNext(event.content)
                        db.feedback().insert(Feedback(logId = log.id, trackId = log.trackId, text = comment, model = event.model, promptName = "comment_log", createdAt = clock.now()))
                        if (next != null && track != null && log.structured !is Structured.Exercise) {
                            db.nextSteps().replace(NextStep(trackId = track.id, text = next.first, dueHint = next.second, createdAt = clock.now()))
                        }
                        finish(log.id, comment, CommentState.DONE)
                    }
                }
            }
        } catch (e: BudgetExceeded) {
            finish(log.id, "", CommentState.SKIPPED_BUDGET)
        } catch (e: Exception) {
            finish(log.id, visible(shown.toString()), CommentState.FAILED)
        }
    }

    /** Numbers the comment must not compute itself: the math section rule (spec 6.6). */
    private suspend fun computedFacts(log: LogEntry, track: Track?): String? {
        val math = log.structured as? Structured.Math ?: return null
        track ?: return null
        val sectionLogs = db.logs().recentConfirmed(track.id, 50).reversed().mapNotNull { it.structured as? Structured.Math }
            .filter { it.section == math.section || it.unitsStarted.intersect(math.unitsStarted.toSet()).isNotEmpty() }
        val p = sectionProgress(sectionLogs)
        return "Section ${math.section}: solved ${p.solved}/${p.attempted} (${(p.solvedFraction * 100).toInt()}%). " +
            "Open stuck problems: ${p.openStuck.ifEmpty { listOf("none") }.joinToString(", ")}. " +
            "Rule: next section is proposed only at ≥ 70% solved -> ${if (p.readyToMoveOn) "allowed" else "not yet"}. " +
            "Choose only between \"revisit stuck problems first\" and \"move on\", with one sentence of reason."
    }

    private suspend fun userText(track: Track?, input: String): String {
        val s = settings.current()
        val profile = context.profile(s.profile)
        if (track == null) return profile + "\n" + input
        val recent = db.logs().recentConfirmed(track.id, 6).drop(1).take(5)
        return context.fit(profile, { context.track(track, null) }, { context.track(track, null, includeSummary = false) }, recent, input)
    }

    /**
     * Plans the next exercise session, or today's fallback when [reason] is given (spec 6.1). Pain
     * or illness asks for rest or mobility only.
     */
    suspend fun exerciseNext(track: Track, reason: SkipReason?, note: String?): ExerciseOutput {
        val lang = settings.current().profile.language
        val mode = if (reason == null) "mode: next" else "mode: fallback\nReason: ${lang.t(reason.en, reason.zh)}${if (reason.restOnly) " (rest or mobility only, no progression)" else ""}" + (note?.let { "\nUser note: $it" } ?: "")
        val recent = db.logs().recentConfirmed(track.id, 5)
        val text = context.fit(context.profile(settings.current().profile), { context.track(track, null) }, { context.track(track, null, false) }, recent, mode)
        val out = llm.json("exercise_next", listOf(Part.Text(text)), 500, ExerciseOutput.serializer())
        val step = out.text + (out.rule?.let { "\n$it" } ?: "")
        db.nextSteps().replace(NextStep(trackId = track.id, text = step, dueHint = out.dueHint, createdAt = clock.now()))
        out.comment?.let { db.feedback().insert(Feedback(trackId = track.id, text = it, model = settings.current().fastModel, promptName = "exercise_next", createdAt = clock.now())) }
        return out
    }

    /** After a log marks units DONE: 1-2 recall questions about the first of them (spec 6.5). */
    private suspend fun recallQuestions(log: LogEntry, track: Track) {
        val wants = track.domain.recallByDefault || (track.domain == Domain.READING && settings.current().readingRecall)
        if (!wants) return
        val doneIds = when (val s = log.structured) {
            is Structured.Fundamental -> s.unitsDone + log.unitIds.filter { it !in s.unitsStarted }
            is Structured.Math -> s.unitsDone
            is Structured.Reading -> s.unitsDone
            else -> emptyList()
        }.distinct()
        val unit = db.units().getAll(doneIds).firstOrNull() ?: return
        createRecall(track, unit, RecallSource.UNIT, max = 2)
    }

    /** Opens a due revision (spec 6.5): a recall question generated on open. */
    suspend fun openRevision(unit: TrackUnit): Long? {
        val track = db.tracks().get(unit.trackId) ?: return null
        return createRecall(track, unit, RecallSource.REVIEW, max = 1).firstOrNull()
    }

    private suspend fun createRecall(track: Track, unit: TrackUnit, source: RecallSource, max: Int): List<Long> {
        val lang = settings.current().profile.language
        val notes = db.logs().recentConfirmed(track.id, 30).filter { unit.id in it.unitIds }.joinToString("\n") { it.rawText.orEmpty() }.take(2000)
        val questions = try {
            val text = context.track(track, null, includeSummary = false) + "\n## Unit\n${unit.title}\n## The user's notes\n${notes.ifEmpty { "(none)" }}"
            llm.json("recall_question", listOf(Part.Text(text)), 300, QuestionsOutput.serializer()).questions.take(max)
        } catch (e: Exception) {
            // Without the API, revision still works with a plain question and self-grading.
            if (source == RecallSource.REVIEW) listOf(lang.t("Explain \"${unit.title}\" from memory, without notes.", "不看笔记，解释一下「${unit.title}」。")) else emptyList()
        }
        val now = clock.now()
        return questions.filter { it.isNotBlank() }.map { q ->
            db.logs().insert(
                LogEntry(trackId = track.id, kind = LogKind.RECALL, occurredAt = now, createdAt = now, structured = Structured.Recall(source, q, unitId = unit.id), unitIds = listOf(unit.id), parseStatus = ParseStatus.CONFIRMED),
            )
        }
    }

    /**
     * Grades an answer with Flash (spec 6.5) and applies the grade to the unit. [selfGrade] is
     * used instead when the API is unavailable.
     */
    suspend fun answerRecall(log: LogEntry, answer: String, selfGrade: Grade? = null): Structured.Recall? {
        val recall = log.structured as? Structured.Recall ?: return null
        val unit = recall.unitId?.let { db.units().get(it) }
        val graded = if (selfGrade != null) {
            recall.copy(answer = answer, grade = selfGrade)
        } else {
            val notes = unit?.let { u -> db.logs().recentConfirmed(u.trackId, 30).filter { u.id in it.unitIds && it.kind != LogKind.RECALL }.joinToString("\n") { it.rawText.orEmpty() } }.orEmpty()
            val text = "## Question\n${recall.question}\n" + (unit?.let { "## Unit\n${it.title}\n" } ?: "") + (recall.commitSha?.let { "## Commit\n$it\n" } ?: "") +
                "## The user's notes\n${notes.take(2000).ifEmpty { "(none)" }}\n## Answer\n$answer"
            val g = llm.json("recall_grade", listOf(Part.Text(text)), 300, GradeOutput.serializer())
            recall.copy(answer = answer, grade = g.grade, whatWasRight = g.whatWasRight, whatWasMissing = g.whatWasMissing, followUpQuestion = g.followUpQuestion)
        }
        db.logs().update(log.copy(structured = graded))
        if (unit != null && graded.grade != null && recall.source != RecallSource.OWNERSHIP) {
            db.units().update(applyGrade(unit, graded.grade, isReview = recall.source == RecallSource.REVIEW, now = clock.now()))
        }
        return graded
    }

    suspend fun skipRecall(log: LogEntry) {
        val recall = log.structured as? Structured.Recall ?: return
        db.logs().update(log.copy(structured = recall.copy(skipped = true)))
    }

    /** Rewrites a track's long-term memory from the old summary and the logs since (spec 8.4). */
    suspend fun refreshSummary(track: Track) {
        val since = track.summaryUpdatedAt ?: 0
        val logs = db.logs().confirmedSince(track.id, since)
        if (logs.isEmpty()) return
        val text = "## Previous summary\n${track.rollingSummary.ifEmpty { "(none)" }}\n" + context.track(track, null, includeSummary = false) + "\n" + context.recent(logs.reversed(), 3_500)
        val summary = llm.text("track_summary", listOf(Part.Text(text)), 450)
        db.tracks().get(track.id)?.let { db.tracks().update(it.copy(rollingSummary = summary.trim(), summaryUpdatedAt = clock.now(), logsSinceSummary = 0)) }
    }

    companion object {
        const val SUMMARY_EVERY = 10

        /** Hides a NEXT line that is still streaming in. */
        fun visible(text: String): String = text.substringBefore("\nNEXT:").substringBefore("NEXT:").trimEnd()

        /** Splits "comment\nNEXT: step | when" into the comment and (step, when). */
        fun splitNext(text: String): Pair<String, Pair<String, String?>?> {
            val idx = text.lastIndexOf("NEXT:")
            if (idx < 0) return text.trim() to null
            val next = text.substring(idx + 5).trim().lineSequence().first()
            val (step, hint) = next.split('|', limit = 2).map { it.trim() }.let { it[0] to it.getOrNull(1)?.ifEmpty { null } }
            return text.substring(0, idx).trim() to (step to hint).takeIf { step.isNotEmpty() }
        }
    }
}
