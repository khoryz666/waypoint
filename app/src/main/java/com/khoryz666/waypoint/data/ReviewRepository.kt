package com.khoryz666.waypoint.data

import com.khoryz666.waypoint.domain.AppClock
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.domain.LogKind
import com.khoryz666.waypoint.domain.ReviewPeriod
import com.khoryz666.waypoint.domain.ReviewStats
import com.khoryz666.waypoint.domain.StatsInput
import com.khoryz666.waypoint.domain.TrackStatus
import com.khoryz666.waypoint.domain.WaypointJson
import com.khoryz666.waypoint.domain.interests
import com.khoryz666.waypoint.domain.reviewStats
import com.khoryz666.waypoint.domain.reviewSummary
import com.khoryz666.waypoint.domain.successorChains
import com.khoryz666.waypoint.domain.weekStart
import com.khoryz666.waypoint.llm.LlmService
import com.khoryz666.waypoint.llm.Part
import com.khoryz666.waypoint.llm.ReviewOutput
import com.khoryz666.waypoint.llm.ReviewQuestionsOutput
import com.khoryz666.waypoint.llm.Suggestion
import com.khoryz666.waypoint.llm.Tier
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate
import java.time.YearMonth

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
    val questions = db.reviewQuestions().observeAll()

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

    /** The previous period's stats: from its saved review when there is one, else computed. */
    suspend fun previousStats(review: Review): ReviewStats {
        val start = when (review.period) {
            ReviewPeriod.WEEK -> review.start.minusDays(7)
            ReviewPeriod.MONTH -> review.start.minusMonths(1)
            ReviewPeriod.YEAR -> review.start.minusYears(1)
        }
        return db.reviews().find(review.period, start)?.stats?.let(::decodeStats) ?: stats(review.period, start)
    }

    /** Saves what the user typed so far, keyed by question text. */
    suspend fun saveDraft(review: Review, answers: Map<String, String>, fix: String, top3: List<String>): Review =
        review.copy(answers = encodeAnswers(answers), oneThingToFix = fix, top3 = top3).also { db.reviews().upsert(it) }

    /**
     * Asks DeepSeek for 3-5 questions about the active tracks and stores them as the review's
     * questions. Without the API, falls back to one plain question per track.
     */
    suspend fun writeQuestions() {
        val tracks = db.tracks().active()
        if (tracks.isEmpty()) return
        val s = settings.current()
        val lang = s.profile.language
        val week = stats(ReviewPeriod.WEEK, currentWeekStart())
        val text = buildString {
            append("UI language: ${lang.tag}\n")
            tracks.forEach { t ->
                val units = db.units().forTrack(t.id)
                val w = week.tracks.firstOrNull { it.id == t.id }
                append("## ${t.title} (${t.domain.name})\nGoal: ${t.goal}\n")
                if (units.isNotEmpty()) append("Outline: ${units.count { it.doneAt != null }}/${units.size} done; next: ${units.firstOrNull { it.doneAt == null }?.title ?: "—"}\n")
                if (w != null) append("This week: ${w.focusMinutes} min, ${w.unitsDone} units done, ${w.logs} logs\n")
            }
        }
        val questions = runCatching { llm.json("review_questions", listOf(Part.Text(text)), 400, ReviewQuestionsOutput.serializer()).questions }
            .getOrNull()?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(5)
            ?.ifEmpty { null }
            ?: tracks.take(5).map { lang.t("How did ${it.title} go this week?", "${it.title} 这周怎么样？") }
        db.reviewQuestions().replace(questions)
    }

    suspend fun addQuestion(text: String) = db.reviewQuestions().insert(ReviewQuestion(order = db.reviewQuestions().all().size, text = text))
    suspend fun updateQuestion(q: ReviewQuestion) = db.reviewQuestions().update(q)
    suspend fun deleteQuestion(id: Long) = db.reviewQuestions().delete(id)

    /**
     * Submits a review: Pro writes the narrative; "one thing to fix" and "top 3" are the user's
     * (or proposed by Pro when left empty). Returns reading suggestions.
     */
    suspend fun submit(review: Review, answers: Map<String, String>, fix: String, top3: List<String>): Pair<Review, List<Suggestion>> {
        val saved = review.copy(answers = encodeAnswers(answers.filterValues { it.isNotBlank() }), oneThingToFix = fix.trim(), top3 = top3)
        val out = runCatching { narrate(saved) }.getOrNull()
        val final = saved.copy(
            narrative = out?.narrative.orEmpty(),
            oneThingToFix = saved.oneThingToFix.ifEmpty { out?.oneThingToFix.orEmpty() },
            top3 = top3.ifEmpty { out?.top3.orEmpty().take(3) },
            submittedAt = clock.now(),
        )
        db.reviews().upsert(final)
        return final to out?.suggestions.orEmpty().take(3)
    }

    /** Writes or rewrites only the narrative (e.g. after the budget or network blocked it). */
    suspend fun renarrate(review: Review): Review {
        val out = narrate(review)
        val updated = review.copy(narrative = out.narrative)
        db.reviews().upsert(updated)
        return updated
    }

    private suspend fun narrate(review: Review): ReviewOutput {
        val s = settings.current()
        val prompt = when (review.period) {
            ReviewPeriod.WEEK -> "review_week"
            ReviewPeriod.MONTH -> "review_month"
            ReviewPeriod.YEAR -> "review_year"
        }
        val text = buildString {
            append("UI language: ${s.profile.language.tag}\n")
            append("## This period (${review.start} – ${review.end})\n").append(review.stats).append("\n")
            decodeStats(review.stats)?.let { st -> append("## Summary shown to the user\n").append(reviewSummary(st, previousStats(review), review.period, s.profile.language).joinToString("\n")).append("\n") }
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
            val answers = decodeAnswers(review.answers)
            if (answers.isNotEmpty()) append("## The user's answers\n").append(answers.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }).append("\n")
            if (review.oneThingToFix.isNotBlank()) append("## One thing to fix (the user's)\n${review.oneThingToFix}\n")
            if (review.top3.isNotEmpty()) append("## Top 3 for next period (the user's)\n").append(review.top3.joinToString("\n")).append("\n")
        }
        val maxInput = when (review.period) { ReviewPeriod.WEEK -> 6_000; ReviewPeriod.MONTH -> 10_000; ReviewPeriod.YEAR -> 15_000 }
        val trimmed = if (com.khoryz666.waypoint.llm.estimateTokens(text) > maxInput) text.take(maxInput * 3) else text
        return llm.json(prompt, listOf(Part.Text(trimmed)), 1500, ReviewOutput.serializer(), Tier.REVIEW, thinking = true)
    }

    /** "Top 3" stays pinned on Today for the week after its review (spec 7.2). */
    fun observePinnedTop3() = db.reviews().observeLatestSubmitted(ReviewPeriod.WEEK)

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
        private val answerMap = MapSerializer(String.serializer(), String.serializer())

        fun encodeAnswers(answers: Map<String, String>): String = WaypointJson.encodeToString(answerMap, answers)

        fun decodeAnswers(json: String): Map<String, String> = runCatching { WaypointJson.decodeFromString(answerMap, json) }.getOrDefault(emptyMap())

        fun decodeStats(json: String): ReviewStats? = runCatching { WaypointJson.decodeFromString(ReviewStats.serializer(), json) }.getOrNull()
    }
}
