package com.khoryz666.waypoint.domain

import com.khoryz666.waypoint.data.DayAssignment
import com.khoryz666.waypoint.data.DayType
import com.khoryz666.waypoint.data.Track
import java.time.LocalDate

/** The day type in force on [date]: an explicit assignment, else the weekday default, else none. */
fun dayTypeFor(date: LocalDate, types: List<DayType>, assignments: List<DayAssignment>): DayType? {
    val assigned = assignments.find { it.date == date }?.let { a -> types.find { it.id == a.dayTypeId } }
    return assigned ?: types.find { date.dayOfWeek.value in it.weekdays }
}

/** What happened on a day that targets are measured against. */
data class DayProgress(
    val time: DayTime = DayTime(),
    /** Units marked DONE that day, per track. */
    val unitsDone: Map<Long, Int> = emptyMap(),
    val exercised: Boolean = false,
)

/** One line of a target: "Focus · Rust book 25m / 45m". [label] is filled by the caller. */
data class TargetLine(val label: String, val done: Int, val target: Int, val unit: TargetUnit) {
    val met: Boolean get() = done >= target
    val fraction: Float get() = if (target == 0) 1f else (done.toFloat() / target).coerceAtMost(1f)
}

enum class TargetUnit { MINUTES, UNITS, CHECK }

data class TargetResult(val lines: List<TargetLine>) {
    val met: Boolean get() = lines.isNotEmpty() && lines.all { it.met }
    val fraction: Float get() = if (lines.isEmpty()) 0f else lines.map { it.fraction }.average().toFloat()
}

/**
 * Measures [target] against [progress]. Per-track entries only count while the track is ACTIVE
 * (paused tracks drop out of targets, spec 3). "Any" entries sum over active tracks of the
 * chosen domains.
 */
fun evaluate(target: DayTarget, progress: DayProgress, activeTracks: List<Track>, lang: Lang): TargetResult {
    val byId = activeTracks.associateBy { it.id }
    val lines = mutableListOf<TargetLine>()
    val pool = activeTracks.filter { target.anyDomains.isEmpty() || it.domain in target.anyDomains }
    val poolLabel = if (target.anyDomains.isEmpty()) lang.t("any goal", "任意目标") else target.anyDomains.joinToString("/") { it.name.lowercase() }
    if (target.anyFocusMinutes > 0) {
        val ms = pool.sumOf { progress.time.focusMs(it.id) } + if (target.anyDomains.isEmpty()) progress.time.focusMs[null] ?: 0 else 0
        lines += TargetLine(lang.t("Focus · $poolLabel", "专注 · $poolLabel"), (ms / MINUTE_MS).toInt(), target.anyFocusMinutes, TargetUnit.MINUTES)
    }
    target.focusMinutes.filter { it.value > 0 && it.key in byId }.forEach { (id, minutes) ->
        lines += TargetLine(lang.t("Focus · ", "专注 · ") + byId.getValue(id).title, (progress.time.focusMs(id) / MINUTE_MS).toInt(), minutes, TargetUnit.MINUTES)
    }
    if (target.anyUnits > 0) {
        lines += TargetLine(lang.t("Steps · $poolLabel", "步骤 · $poolLabel"), pool.sumOf { progress.unitsDone[it.id] ?: 0 }, target.anyUnits, TargetUnit.UNITS)
    }
    target.unitsDone.filter { it.value > 0 && it.key in byId }.forEach { (id, count) ->
        lines += TargetLine(lang.t("Steps · ", "步骤 · ") + byId.getValue(id).title, progress.unitsDone[id] ?: 0, count, TargetUnit.UNITS)
    }
    if (target.exercise) {
        lines += TargetLine(lang.t("Exercise", "运动"), if (progress.exercised) 1 else 0, 1, TargetUnit.CHECK)
    }
    return TargetResult(lines)
}

/** A past or current day for streak purposes. Neutral days neither break nor extend (spec 5.6). */
enum class DayOutcome { MET, MISSED, NEUTRAL }

fun outcome(dayType: DayType?, minimum: TargetResult): DayOutcome = when {
    dayType == null || dayType.minimum.isEmpty || minimum.lines.isEmpty() -> DayOutcome.NEUTRAL
    minimum.met -> DayOutcome.MET
    else -> DayOutcome.MISSED
}

/**
 * Consecutive MET days ending today, skipping NEUTRAL ones. [outcomes] is newest first and starts
 * with today; an unmet today doesn't break the streak yet because the day isn't over.
 */
fun streak(outcomes: List<DayOutcome>): Int {
    var count = 0
    outcomes.forEachIndexed { index, outcome ->
        when (outcome) {
            DayOutcome.MET -> count++
            DayOutcome.NEUTRAL -> Unit
            DayOutcome.MISSED -> if (index != 0) return count
        }
    }
    return count
}

/** What happened on [date]: timer totals, units marked DONE that day per track, and any exercise log. */
fun dayProgress(
    date: LocalDate,
    times: Map<LocalDate, DayTime>,
    units: List<com.khoryz666.waypoint.data.TrackUnit>,
    exerciseDays: Set<LocalDate>,
    clock: AppClock,
): DayProgress = DayProgress(
    time = times[date] ?: DayTime(),
    unitsDone = units.filter { it.doneAt != null && clock.dateOf(it.doneAt) == date }.groupingBy { it.trackId }.eachCount(),
    exercised = date in exerciseDays,
)

/** Day outcomes from [today] back [days] days, newest first, for [streak]. Uses today's active tracks. */
fun outcomes(
    today: LocalDate,
    days: Int,
    types: List<DayType>,
    assignments: List<DayAssignment>,
    progressOf: (LocalDate) -> DayProgress,
    activeTracks: List<Track>,
): List<DayOutcome> = (0 until days).map { back ->
    val date = today.minusDays(back.toLong())
    val type = dayTypeFor(date, types, assignments)
    outcome(type, type?.let { evaluate(it.minimum, progressOf(date), activeTracks, Lang.EN) } ?: TargetResult(emptyList()))
}
