package com.khoryz666.waypoint.domain

import com.khoryz666.waypoint.data.BalanceEvent
import com.khoryz666.waypoint.data.DayAssignment
import com.khoryz666.waypoint.data.DayType
import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.Session
import com.khoryz666.waypoint.data.SleepRecord
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.data.TrackUnit
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Aggregates for one review period, computed in code (spec 7). The LLM only narrates these. */
@Serializable
data class ReviewStats(
    val start: String,
    val end: String,
    val focusMinutes: Int,
    val tracks: List<TrackStats>,
    val minimumDays: Int,
    val planDays: Int,
    val daysWithMinimum: Int,
    val sleep: SleepStats,
    val balance: BalanceStats,
    val ownershipAnswered: Int,
    val ownershipSkipped: Int,
    val recallSolid: Int,
    val recallPartial: Int,
    val recallWeak: Int,
    val started: List<String>,
    val completed: List<String>,
    val aborted: List<String>,
    val interestTopics: List<String> = emptyList(),
)

@Serializable
data class TrackStats(
    val id: Long,
    val title: String,
    val domain: Domain,
    val status: TrackStatus,
    val focusMinutes: Int,
    val unitsDone: Int,
    val unitsTotal: Int,
    val logs: Int,
    val exerciseSessions: Int = 0,
    val plannedExerciseDays: Int = 0,
    val commits: Int = 0,
)

@Serializable
data class SleepStats(val recorded: Int, val nights: Int, val inRange: Int, val averageMin: Int?, val status: SleepStatus?)

@Serializable
/** [netMinor] sums the period's DELTA entries; SET corrections are not income or spending. */
data class BalanceStats(val startMinor: Long, val endMinor: Long, val net30Minor: Long, val currency: String, val netMinor: Long = 0)

/** Raw rows for one period. [until] is exclusive. */
class StatsInput(
    val from: LocalDate,
    val until: LocalDate,
    val tracks: List<Track>,
    val units: List<TrackUnit>,
    val logs: List<LogEntry>,
    val sessions: List<Session>,
    val sleep: List<SleepRecord>,
    val balance: List<BalanceEvent>,
    val dayTypes: List<DayType>,
    val assignments: List<DayAssignment>,
    val commitsByTrack: Map<Long, Int>,
    val sleepTargets: SleepTargets,
    val currency: String,
    val now: Long,
)

fun reviewStats(input: StatsInput, clock: AppClock): ReviewStats {
    val range = clock.bounds(input.from, input.until)
    val dates = generateSequence(input.from) { it.plusDays(1) }.takeWhile { it < input.until }.toList()
    val times = dailyTime(input.sessions, input.now, clock).filterKeys { it >= input.from && it < input.until }
    val logs = input.logs.filter { it.occurredAt in range }
    val exerciseDays = logs.filter { it.kind == LogKind.EXERCISE }.map { clock.dateOf(it.occurredAt) }.toSet()
    val active = input.tracks.filter { it.status == TrackStatus.ACTIVE }

    var minimumDays = 0
    var planDays = 0
    var daysWithMinimum = 0
    val plannedExercise = mutableMapOf<Long, Int>()
    dates.filter { it <= clock.dateOf(input.now) }.forEach { date ->
        val type = dayTypeFor(date, input.dayTypes, input.assignments) ?: return@forEach
        val progress = dayProgress(date, times, input.units, exerciseDays, clock)
        if (!type.minimum.isEmpty) {
            daysWithMinimum++
            if (evaluate(type.minimum, progress, active, Lang.EN).met) minimumDays++
        }
        if (!type.plan.isEmpty && evaluate(type.plan, progress, active, Lang.EN).met) planDays++
        if (type.minimum.exercise || type.plan.exercise) active.filter { it.domain == Domain.EXERCISE }.forEach { plannedExercise.merge(it.id, 1, Int::plus) }
    }

    val trackStats = input.tracks.filter { t -> (t.status == TrackStatus.ACTIVE && t.createdAt < range.end) || logs.any { it.trackId == t.id } || times.values.any { it.focusMs(t.id) > 0 } }.map { t ->
        val units = input.units.filter { it.trackId == t.id }
        val trackLogs = logs.filter { it.trackId == t.id && it.kind != LogKind.RECALL }
        TrackStats(
            id = t.id, title = t.title, domain = t.domain, status = t.status,
            focusMinutes = (times.values.sumOf { it.focusMs(t.id) } / MINUTE_MS).toInt(),
            unitsDone = units.count { it.doneAt != null && it.doneAt in range },
            unitsTotal = units.size,
            logs = trackLogs.size,
            exerciseSessions = trackLogs.count { it.kind == LogKind.EXERCISE },
            plannedExerciseDays = plannedExercise[t.id] ?: 0,
            commits = input.commitsByTrack[t.id] ?: 0,
        )
    }

    val sleep = input.sleep.filter { it.date >= input.from && it.date < input.until }
    val sleepStatus = if (dates.size <= 7) sleepWeek(input.from, sleep, input.sleepTargets, clock).status else null
    val recalls = logs.mapNotNull { it.structured as? Structured.Recall }
    val ownership = recalls.filter { it.source == RecallSource.OWNERSHIP }
    val endAt = minOf(range.end, input.now)

    return ReviewStats(
        start = input.from.toString(),
        end = input.until.minusDays(1).toString(),
        focusMinutes = (times.values.sumOf { it.totalFocusMs } / MINUTE_MS).toInt(),
        tracks = trackStats,
        minimumDays = minimumDays,
        planDays = planDays,
        daysWithMinimum = daysWithMinimum,
        sleep = SleepStats(sleep.size, dates.size, sleep.count { input.sleepTargets.inRange(it.totalMin) }, sleep.takeIf { it.isNotEmpty() }?.map { it.totalMin }?.average()?.toInt(), sleepStatus),
        balance = BalanceStats(
            openingBalance(input.balance, range.start), balanceAt(input.balance, endAt), netChange(input.balance, endAt, 30), input.currency,
            input.balance.filter { it.deletedAt == null && it.kind == BalanceKind.DELTA && it.occurredAt in range.start until endAt + 1 }.sumOf { it.amount },
        ),
        ownershipAnswered = ownership.count { it.answer != null },
        ownershipSkipped = ownership.count { it.skipped },
        recallSolid = recalls.count { it.grade == Grade.SOLID },
        recallPartial = recalls.count { it.grade == Grade.PARTIAL },
        recallWeak = recalls.count { it.grade == Grade.WEAK },
        started = input.tracks.filter { it.createdAt in range }.map { it.title },
        completed = input.tracks.filter { it.status == TrackStatus.COMPLETED && it.endedAt != null && it.endedAt in range }.map { "${it.title}: ${it.finalState.orEmpty()}" },
        aborted = input.tracks.filter { it.status == TrackStatus.ABORTED && it.endedAt != null && it.endedAt in range }.map { "${it.title}: ${it.endReason.orEmpty()}" },
    )
}

/**
 * The review's summary: one line per track that is active or saw activity, then one line each
 * for sleep and money, compared with [previous] (the period before) when there is one.
 */
fun reviewSummary(stats: ReviewStats, previous: ReviewStats?, period: ReviewPeriod, lang: Lang): List<String> {
    val last = when (period) {
        ReviewPeriod.WEEK -> lang.t("last week", "上周")
        ReviewPeriod.MONTH -> lang.t("the month before", "上个月")
        ReviewPeriod.YEAR -> lang.t("the year before", "去年")
    }
    val tracks = stats.tracks.filter { it.status == TrackStatus.ACTIVE || it.focusMinutes > 0 || it.logs > 0 || it.unitsDone > 0 }.map { t ->
        val parts = listOfNotNull(
            formatMinutes(t.focusMinutes.toLong()).takeIf { t.focusMinutes > 0 },
            lang.t("${t.unitsDone} done", "完成 ${t.unitsDone}").takeIf { t.unitsDone > 0 },
            lang.t(plural(t.exerciseSessions, "session"), "${t.exerciseSessions} 次").takeIf { t.domain == Domain.EXERCISE && t.exerciseSessions > 0 },
            lang.t(plural(t.commits, "commit"), "${t.commits} 个 commit").takeIf { t.commits > 0 },
            lang.t(plural(t.logs, "log"), "${t.logs} 条记录").takeIf { t.logs > 0 },
        )
        t.title + SEP + parts.ifEmpty { listOf(lang.t("nothing logged", "没有记录")) }.joinToString(SEP)
    }
    val sleep = stats.sleep.averageMin?.let { avg ->
        lang.t("Sleep", "睡眠") + SEP + lang.t("average ", "平均 ") + formatMinutes(avg.toLong()) + SEP +
            lang.t("${stats.sleep.inRange}/${stats.sleep.recorded} nights in range", "${stats.sleep.inRange}/${stats.sleep.recorded} 晚达标") +
            (previous?.sleep?.averageMin?.let { " (" + last + " " + formatMinutes(it.toLong()) + ")" } ?: "")
    } ?: (lang.t("Sleep", "睡眠") + SEP + lang.t("no nights recorded", "没有记录"))
    val b = stats.balance
    val money = lang.t("Money", "钱") + SEP + formatMoney(b.endMinor, b.currency) + SEP + formatMoneyDelta(b.netMinor, b.currency) +
        (previous?.balance?.takeIf { it.endMinor != 0L || it.netMinor != 0L }?.let { " (" + last + " " + formatMoneyDelta(it.netMinor, b.currency) + ")" } ?: "")
    return tracks + sleep + money
}

private const val SEP = " · "

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

/** Successor chains for the yearly review: "Pull-up: 0 reps → 1 rep → 5 reps". */
fun successorChains(tracks: List<Track>): List<String> {
    val byPredecessor = tracks.filter { it.predecessorId != null }.groupBy { it.predecessorId }
    return tracks.filter { it.predecessorId == null && byPredecessor.containsKey(it.id) }.map { root ->
        val chain = generateSequence(root) { byPredecessor[it.id]?.firstOrNull() }.toList()
        "${root.title}: " + (listOf(root.initialState) + chain.map { it.finalState ?: "…" }).joinToString(" → ")
    }
}
