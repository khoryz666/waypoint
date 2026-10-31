package com.khoryz666.waypoint.data

import androidx.room.withTransaction
import com.khoryz666.waypoint.domain.AppClock
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.domain.LogKind
import com.khoryz666.waypoint.domain.PillarDraft
import com.khoryz666.waypoint.domain.QuestionKey
import com.khoryz666.waypoint.domain.ReviewPeriod
import com.khoryz666.waypoint.domain.ReviewStats
import com.khoryz666.waypoint.domain.StatsInput
import com.khoryz666.waypoint.domain.TrackStatus
import com.khoryz666.waypoint.domain.WaypointJson
import com.khoryz666.waypoint.domain.interests
import com.khoryz666.waypoint.domain.reviewStats
import com.khoryz666.waypoint.domain.successorChains
import com.khoryz666.waypoint.domain.weekStart
import com.khoryz666.waypoint.llm.LlmService
import com.khoryz666.waypoint.llm.Part
import com.khoryz666.waypoint.llm.ReviewOutput
import com.khoryz666.waypoint.llm.Suggestion
import com.khoryz666.waypoint.llm.Tier
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate
import java.time.YearMonth

/** One checklist answer: yes/no for YES_NO, text for FREE_TEXT, a note on anything. */
@Serializable
data class ReviewAnswer(val yes: Boolean? = null, val text: String = "", val note: String = "")

/**
 * Reviews (spec 7): fills in every number from rows, stores the user's answers, and asks Pro for
 * the narrative from aggregates only (weekly ≤ 6k tokens, monthly ≤ 10k, yearly ≤ 15k).
 */
class ReviewRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val llm: LlmService,
    private val clock: AppClock,
) {
    val all = db.reviews().observeAll()
    val pillars = db.checklist().observePillars()
    val questions = db.checklist().observeQuestions()

    fun observe(id: Long) = db.reviews().observe(id)

    suspend fun currentWeekStart(): LocalDate = clock.today().weekStart(settings.current().profile.weekStart)

    fun range(period: ReviewPeriod, start: LocalDate): Pair<LocalDate, LocalDate> = when (period) {
        ReviewPeriod.WEEK -> start to start.plusDays(7)
        ReviewPeriod.MONTH -> start to start.plusMonths(1)
        ReviewPeriod.YEAR -> start to start.plusYears(1)
    }

    /** Stats for a period, computed fresh from rows (soft-deleted rows are already excluded). */
    suspend fun stats(period: ReviewPeriod, start: LocalDate): ReviewStats {
        val (from, until) = range(period, start)
        val s = settings.current()
        val range = clock.bounds(from, until)
        val all = clock.bounds(from.minusDays(1), until)
        val readingLogs = db.logs().confirmedIn(0, range.end).filter { it.structured is com.khoryz666.waypoint.domain.Structured.Reading }
        val tracks = db.tracks().all()
        val stats = reviewStats(
            StatsInput(
                from = from, until = until, tracks = tracks, units = db.units().all(),
                logs = db.logs().confirmedIn(range.start, range.end), sessions = db.sessions().endingIn(all.start, all.end),
                sleep = db.sleep().between(from, until), balance = db.balance().live(), dayTypes = db.dayTypes().all(),
                assignments = db.dayTypes().assignments(from, until),
                commitsByTrack = db.commits().between(range.start, range.end).groupingBy { it.trackId }.eachCount(),
                sleepTargets = s.profile.sleep, currency = s.profile.currency, now = clock.now(),
            ),
            clock,
        )
        return stats.copy(interestTopics = if (period == ReviewPeriod.WEEK) interests(readingLogs).filter { it.count >= 3 }.take(3).map { it.topic } else emptyList())
    }

    /** The review row for a period, created (or its stats refreshed) while it is unsubmitted. */
    suspend fun prepare(period: ReviewPeriod, start: LocalDate): Review {
        val existing = db.reviews().find(period, start)
        if (existing?.submittedAt != null) return existing
        val (from, until) = range(period, start)
        val review = (existing ?: Review(period = period, start = from, end = until.minusDays(1), stats = "{}"))
            .copy(stats = WaypointJson.encodeToString(ReviewStats.serializer(), stats(period, start)))
        val id = db.reviews().upsert(review)
        return review.copy(id = if (review.id == 0L) id else review.id)
    }

    suspend fun saveAnswers(review: Review, answers: Map<Long, ReviewAnswer>, reflection: String = review.reflection) =
        db.reviews().upsert(review.copy(answers = encodeAnswers(answers), reflection = reflection))

    /**
     * Submits a review: Pro writes the narrative, "one thing to fix" and "top 3" are taken from
     * the user's answers (or proposed by Pro when left empty). Returns reading suggestions.
     */
    suspend fun submit(review: Review, answers: Map<Long, ReviewAnswer>, reflection: String): Pair<Review, List<Suggestion>> {
        val questions = db.checklist().questions()
        val fix = questions.firstOrNull { it.key == QuestionKey.ONE_THING_TO_FIX }?.let { answers[it.id]?.text }.orEmpty().trim()
        val top3 = questions.firstOrNull { it.key == QuestionKey.TOP_3 }?.let { answers[it.id]?.text }.orEmpty()
            .lines().map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ')', ' ') }.filter { it.isNotEmpty() }.take(3)
        val saved = review.copy(answers = encodeAnswers(answers), reflection = reflection)
        val out = runCatching { narrate(saved, questions, answers) }.getOrNull()
        val final = saved.copy(
            narrative = out?.narrative.orEmpty(),
            oneThingToFix = fix.ifEmpty { out?.oneThingToFix.orEmpty() },
            top3 = top3.ifEmpty { out?.top3.orEmpty().take(3) },
            submittedAt = clock.now(),
        )
        db.reviews().upsert(final)
        return final to out?.suggestions.orEmpty().take(3)
    }

    /** Writes or rewrites only the narrative (e.g. after the budget or network blocked it). */
    suspend fun renarrate(review: Review): Review {
        val out = narrate(review, db.checklist().questions(), decodeAnswers(review.answers))
        val updated = review.copy(narrative = out.narrative)
        db.reviews().upsert(updated)
        return updated
    }

    private suspend fun narrate(review: Review, questions: List<ChecklistQuestion>, answers: Map<Long, ReviewAnswer>): ReviewOutput {
        val s = settings.current()
        val prompt = when (review.period) {
            ReviewPeriod.WEEK -> "review_week"
            ReviewPeriod.MONTH -> "review_month"
            ReviewPeriod.YEAR -> "review_year"
        }
        val text = buildString {
            append("UI language: ${s.profile.language.tag}\n")
            append("## This period (${review.start} – ${review.end})\n").append(review.stats).append("\n")
            when (review.period) {
                ReviewPeriod.WEEK -> {
                    val last = db.reviews().find(ReviewPeriod.WEEK, review.start.minusDays(7))
                    append("## Last week\n").append(last?.stats ?: stats(ReviewPeriod.WEEK, review.start.minusDays(7)).let { WaypointJson.encodeToString(ReviewStats.serializer(), it) }).append("\n")
                    append("## Last week's one thing to fix\n").append(last?.oneThingToFix?.ifEmpty { null } ?: "(none)").append("\n")
                }
                ReviewPeriod.MONTH -> {
                    db.reviews().submittedBetween(ReviewPeriod.WEEK, review.start.minusDays(6), review.end.plusDays(1)).forEach {
                        append("## Week of ${it.start}\n${it.narrative}\nFix: ${it.oneThingToFix}\n")
                    }
                }
                ReviewPeriod.YEAR -> {
                    db.reviews().submittedBetween(ReviewPeriod.MONTH, review.start, review.end.plusDays(1)).forEach {
                        append("## ${YearMonth.from(it.start)}\n${it.narrative}\n")
                    }
                    append("## Successor chains\n").append(successorChains(db.tracks().all()).joinToString("\n").ifEmpty { "(none)" }).append("\n")
                    append("## Sleep per month\n")
                    (1..12).forEach { m ->
                        val month = YearMonth.of(review.start.year, m)
                        val records = db.sleep().between(month.atDay(1), month.plusMonths(1).atDay(1))
                        if (records.isNotEmpty()) append("$month: ${records.map { it.totalMin }.average().toInt()} min avg, ${records.size} nights\n")
                    }
                }
            }
            append("## Checklist answers\n")
            questions.forEach { q ->
                val a = answers[q.id] ?: return@forEach
                val value = listOfNotNull(a.yes?.let { if (it) "yes" else "no" }, a.text.ifEmpty { null }, a.note.ifEmpty { null }).joinToString(" · ")
                if (value.isNotEmpty()) append("- ${q.text}: $value\n")
            }
            if (review.reflection.isNotBlank()) append("## Reflection\n${review.reflection}\n")
        }
        val maxInput = when (review.period) { ReviewPeriod.WEEK -> 6_000; ReviewPeriod.MONTH -> 10_000; ReviewPeriod.YEAR -> 15_000 }
        val trimmed = if (com.khoryz666.waypoint.llm.estimateTokens(text) > maxInput) text.take(maxInput * 3) else text
        return llm.json(prompt, listOf(Part.Text(trimmed)), 1500, ReviewOutput.serializer(), Tier.REVIEW, thinking = true)
    }

    /** "Top 3" stays pinned on Today for the week after its review (spec 7.2). */
    fun observePinnedTop3() = db.reviews().observeLatestSubmitted(ReviewPeriod.WEEK)

    /** Checklist editing (spec 7.2): replaces everything with [drafts]. */
    suspend fun replaceChecklist(drafts: List<PillarDraft>) = db.withTransaction {
        db.checklist().clear()
        drafts.forEachIndexed { i, p ->
            val id = db.checklist().insertPillar(Pillar(name = p.name, order = i, noTargets = p.noTargets))
            p.questions.forEachIndexed { j, q -> db.checklist().insertQuestion(ChecklistQuestion(pillarId = id, order = j, text = q.text, type = q.type, key = q.key)) }
        }
    }

    suspend fun updatePillar(p: Pillar) = db.checklist().updatePillar(p)
    suspend fun addQuestion(q: ChecklistQuestion) = db.checklist().insertQuestion(q)
    suspend fun updateQuestion(q: ChecklistQuestion) = db.checklist().updateQuestion(q)
    suspend fun deleteQuestion(id: Long) = db.checklist().deleteQuestion(id)
    suspend fun addPillar(name: String) = db.checklist().insertPillar(Pillar(name = name, order = db.checklist().pillars().size))
    suspend fun deletePillar(id: Long) = db.checklist().deletePillar(id)

    /** Accepting a reading suggestion makes it a DRAFT reading track (spec 6.7). */
    suspend fun acceptSuggestion(s: Suggestion): Long {
        val now = clock.now()
        return db.tracks().insert(Track(domain = Domain.READING, title = s.book ?: s.topic, goal = s.topic, initialState = "", status = TrackStatus.DRAFT, createdAt = now, updatedAt = now))
    }

    /** Whether last week's review is still open after its reminder window. */
    suspend fun lastWeekUnreviewed(): Boolean {
        val last = currentWeekStart().minusDays(7)
        return db.reviews().find(ReviewPeriod.WEEK, last)?.submittedAt == null && db.logs().confirmedIn(clock.startOf(last), clock.startOf(last.plusDays(7))).any { it.kind != LogKind.RECALL }
    }

    companion object {
        private val answerMap = MapSerializer(String.serializer(), ReviewAnswer.serializer())

        fun encodeAnswers(answers: Map<Long, ReviewAnswer>): String = WaypointJson.encodeToString(answerMap, answers.mapKeys { it.key.toString() })

        fun decodeAnswers(json: String): Map<Long, ReviewAnswer> =
            runCatching { WaypointJson.decodeFromString(answerMap, json).mapKeys { it.key.toLong() } }.getOrDefault(emptyMap())

        fun decodeStats(json: String): ReviewStats? = runCatching { WaypointJson.decodeFromString(ReviewStats.serializer(), json) }.getOrNull()
    }
}
