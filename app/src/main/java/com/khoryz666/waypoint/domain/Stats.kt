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
    val joyMinutes: Int,
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
data class BalanceStats(val startMinor: Long, val endMinor: Long, val net30Minor: Long, val currency: String)

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

    val trackStats = input.tracks.filter { t -> t.status == TrackStatus.ACTIVE || logs.any { it.trackId == t.id } || times.values.any { it.focusMs(t.id) > 0 } }.map { t ->
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
        joyMinutes = (times.values.sumOf { it.joyMs } / MINUTE_MS).toInt(),
        tracks = trackStats,
        minimumDays = minimumDays,
        planDays = planDays,
        daysWithMinimum = daysWithMinimum,
        sleep = SleepStats(sleep.size, dates.size, sleep.count { input.sleepTargets.inRange(it.totalMin) }, sleep.takeIf { it.isNotEmpty() }?.map { it.totalMin }?.average()?.toInt(), sleepStatus),
        balance = BalanceStats(balanceAt(input.balance, range.start - 1), balanceAt(input.balance, endAt), netChange(input.balance, endAt, 30), input.currency),
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

/** The read-only answer of an AUTO checklist question (spec 7.2), from the period's stats. */
fun autoAnswer(key: QuestionKey, stats: ReviewStats, lang: Lang, pillarTracks: List<Long> = emptyList()): String = when (key) {
    QuestionKey.SLEEP_NIGHTS_IN_RANGE -> lang.t("${stats.sleep.inRange}/${stats.sleep.recorded} recorded nights in range (${stats.sleep.recorded}/${stats.sleep.nights} recorded)", "目标内 ${stats.sleep.inRange}/${stats.sleep.recorded} 晚（${stats.sleep.recorded}/${stats.sleep.nights} 晚有记录）") +
        (stats.sleep.averageMin?.let { lang.t(", average ", "，均值 ") + formatMinutes(it.toLong()) } ?: "")
    QuestionKey.EXERCISE_SESSIONS -> stats.tracks.filter { it.domain == Domain.EXERCISE }.joinToString("; ") { "${it.title}: ${it.exerciseSessions}" + if (it.plannedExerciseDays > 0) "/${it.plannedExerciseDays}" else "" }
        .ifEmpty { lang.t("no exercise tracks", "没有运动 track") }
    QuestionKey.BALANCE_NET_30 -> formatMoneyDelta(stats.balance.net30Minor, stats.balance.currency)
    QuestionKey.MINIMUM_PLAN_DAYS -> lang.t("minimum ${stats.minimumDays}/${stats.daysWithMinimum} days, plan ${stats.planDays}", "最低 ${stats.minimumDays}/${stats.daysWithMinimum} 天，计划 ${stats.planDays} 天")
    QuestionKey.UNITS_DONE_PER_TRACK -> stats.tracks.filter { it.domain != Domain.PROJECT && it.domain != Domain.MONEY && it.domain != Domain.EXERCISE }
        .joinToString("; ") { "${it.title}: ${it.unitsDone}" }.ifEmpty { "—" }
    QuestionKey.MILESTONES_PER_PROJECT -> stats.tracks.filter { it.domain == Domain.PROJECT }.joinToString("; ") { "${it.title}: ${it.unitsDone}" + lang.t(" (${it.commits} commits)", "（${it.commits} 个 commit）") }.ifEmpty { "—" }
    QuestionKey.OWNERSHIP_CHECKS -> lang.t("${stats.ownershipAnswered} answered, ${stats.ownershipSkipped} skipped", "回答 ${stats.ownershipAnswered}，跳过 ${stats.ownershipSkipped}")
    QuestionKey.FOCUS_TIME -> formatMinutes(stats.focusMinutes.toLong())
    QuestionKey.JOY_TIME -> lang.t("Joy ", "Joy ") + formatMinutes(stats.joyMinutes.toLong()) +
        stats.tracks.filter { it.id in pillarTracks }.joinToString("") { "; ${it.title} ${formatMinutes(it.focusMinutes.toLong())}, ${it.logs} logs" }
    QuestionKey.ONE_THING_TO_FIX, QuestionKey.TOP_3 -> ""
}

/** Successor chains for the yearly review: "Pull-up: 0 reps → 1 rep → 5 reps". */
fun successorChains(tracks: List<Track>): List<String> {
    val byPredecessor = tracks.filter { it.predecessorId != null }.groupBy { it.predecessorId }
    return tracks.filter { it.predecessorId == null && byPredecessor.containsKey(it.id) }.map { root ->
        val chain = generateSequence(root) { byPredecessor[it.id]?.firstOrNull() }.toList()
        "${root.title}: " + (listOf(root.initialState) + chain.map { it.finalState ?: "…" }).joinToString(" → ")
    }
}
