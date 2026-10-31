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

/** One track's row in the review summary table; "–" stands for zero. */
data class TrackLine(val title: String, val time: String, val done: String, val logs: String)

/** A labelled summary row (sleep, money): the headline value and a muted detail line. */
data class KeyLine(val label: String, val value: String, val detail: String)

/** The review's summary: a table of tracks, then sleep and money against the period before. */
data class ReviewSummary(val tracks: List<TrackLine>, val sleep: KeyLine, val money: KeyLine) {
    /** Plain lines for prompts. */
    fun lines(lang: Lang): List<String> =
        tracks.map { "${it.title} · " + lang.t("time ${it.time} · done ${it.done} · logs ${it.logs}", "时间 ${it.time} · 完成 ${it.done} · 记录 ${it.logs}") } +
            listOf(sleep, money).map { "${it.label} · ${it.value} · ${it.detail}" }
}

/**
 * Builds the summary for a period: every track that is active or saw activity, then sleep and
 * money, compared with [previous] (the period before) when it has data.
 */
fun reviewSummary(stats: ReviewStats, previous: ReviewStats?, period: ReviewPeriod, lang: Lang): ReviewSummary {
    val last = when (period) {
        ReviewPeriod.WEEK -> lang.t("last week", "上周")
        ReviewPeriod.MONTH -> lang.t("the month before", "上个月")
        ReviewPeriod.YEAR -> lang.t("the year before", "去年")
    }
    val now = when (period) {
        ReviewPeriod.WEEK -> lang.t("this week", "本周")
        ReviewPeriod.MONTH -> lang.t("this month", "本月")
        ReviewPeriod.YEAR -> lang.t("this year", "今年")
    }
    fun count(n: Int) = if (n > 0) n.toString() else DASH
    val tracks = stats.tracks.filter { it.status == TrackStatus.ACTIVE || it.focusMinutes > 0 || it.logs > 0 || it.unitsDone > 0 }.map { t ->
        TrackLine(
            title = t.title,
            time = if (t.focusMinutes > 0) formatMinutes(t.focusMinutes.toLong()) else DASH,
            // Exercise has no outline to finish; its sessions are what gets done.
            done = count(if (t.domain == Domain.EXERCISE) t.exerciseSessions else t.unitsDone),
            logs = count(t.logs),
        )
    }
    val sl = stats.sleep
    val sleep = KeyLine(
        label = lang.t("Sleep", "睡眠"),
        value = sl.averageMin?.let { formatMinutes(it.toLong()) } ?: DASH,
        detail = if (sl.recorded == 0) lang.t("No nights recorded", "没有记录")
        else listOfNotNull(
            lang.t("${sl.inRange}/${sl.recorded} nights in range", "${sl.inRange}/${sl.recorded} 晚达标"),
            previous?.sleep?.averageMin?.let { "$last ${formatMinutes(it.toLong())}" },
        ).joinToString(SEP),
    )
    val b = stats.balance
    val money = KeyLine(
        label = lang.t("Money", "钱"),
        value = formatMoney(b.endMinor, b.currency),
        detail = listOfNotNull(
            "${formatMoneyDelta(b.netMinor, b.currency)} $now",
            previous?.balance?.takeIf { it.endMinor != 0L || it.netMinor != 0L }?.let { "$last ${formatMoneyDelta(it.netMinor, b.currency)}" },
        ).joinToString(SEP),
    )
    return ReviewSummary(tracks, sleep, money)
}

private const val SEP = " · "
private const val DASH = "–"

/** Successor chains for the yearly review: "Pull-up: 0 reps → 1 rep → 5 reps". */
fun successorChains(tracks: List<Track>): List<String> {
    val byPredecessor = tracks.filter { it.predecessorId != null }.groupBy { it.predecessorId }
    return tracks.filter { it.predecessorId == null && byPredecessor.containsKey(it.id) }.map { root ->
        val chain = generateSequence(root) { byPredecessor[it.id]?.firstOrNull() }.toList()
        "${root.title}: " + (listOf(root.initialState) + chain.map { it.finalState ?: "…" }).joinToString(" → ")
    }
}
