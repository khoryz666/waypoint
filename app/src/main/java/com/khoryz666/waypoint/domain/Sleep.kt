package com.khoryz666.waypoint.domain

import com.khoryz666.waypoint.data.SleepRecord
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlin.math.sqrt

/** Sleep targets from settings (spec 6.2). Defaults: 7h-8h30m, bedtime window ±45 min. */
data class SleepTargets(
    val minTotalMin: Int = 420,
    val maxTotalMin: Int = 510,
    val bedtime: LocalTime? = null,
    val bedtimeWindowMin: Int = 45,
) {
    fun inRange(totalMin: Int) = totalMin in minTotalMin..maxTotalMin
}

enum class SleepStatus { ON_TRACK, WATCH, OFF_TRACK, NOT_ENOUGH_DATA }

data class SleepWeek(
    val start: LocalDate,
    val recorded: Int,
    val nightsInRange: Int,
    /** Average over recorded nights only: missing nights are gaps, not zeros. */
    val averageMin: Int?,
    val bedtimeSdMin: Double?,
    val status: SleepStatus,
)

/** The status rules of spec 6.2, applied to the records of one week (any number of days). */
fun sleepWeek(start: LocalDate, records: List<SleepRecord>, targets: SleepTargets, clock: AppClock): SleepWeek {
    val recorded = records.size
    val inRange = records.count { targets.inRange(it.totalMin) }
    val average = records.takeIf { it.isNotEmpty() }?.map { it.totalMin }?.average()?.toInt()
    val sd = bedtimeSd(records.mapNotNull { it.bedAt }, clock)
    val status = when {
        recorded < 4 || average == null -> SleepStatus.NOT_ENOUGH_DATA
        average < targets.minTotalMin - 30 || inRange < 3 -> SleepStatus.OFF_TRACK
        recorded >= 5 && targets.inRange(average) && inRange >= 5 && (sd == null || sd <= 60) -> SleepStatus.ON_TRACK
        else -> SleepStatus.WATCH
    }
    return SleepWeek(start, recorded, inRange, average, sd, status)
}

/**
 * Standard deviation of bedtimes in minutes. Times are measured from noon so 23:30 and 00:30 are
 * an hour apart, not 23.
 */
fun bedtimeSd(bedtimes: List<Long>, clock: AppClock): Double? {
    if (bedtimes.size < 2) return null
    val minutes = bedtimes.map { ms ->
        val t = clock.timeOf(ms)
        ((t.hour * 60 + t.minute) - 12 * 60 + 24 * 60) % (24 * 60)
    }
    val mean = minutes.average()
    return sqrt(minutes.sumOf { (it - mean) * (it - mean) } / minutes.size)
}

data class SleepMonth(val month: YearMonth, val weeks: List<SleepWeek>, val averageMin: Int?, val recorded: Int)

fun sleepMonth(month: YearMonth, weekStarts: List<LocalDate>, records: List<SleepRecord>, targets: SleepTargets, clock: AppClock): SleepMonth {
    val inMonth = records.filter { YearMonth.from(it.date) == month }
    val weeks = weekStarts.map { start -> sleepWeek(start, records.filter { it.date >= start && it.date < start.plusDays(7) }, targets, clock) }
    return SleepMonth(month, weeks, inMonth.takeIf { it.isNotEmpty() }?.map { it.totalMin }?.average()?.toInt(), inMonth.size)
}

/** Nights shown as "5/7 晚有记录": records within [from, until). */
fun recordedNights(records: List<SleepRecord>, from: LocalDate, until: LocalDate) = records.count { it.date >= from && it.date < until }

/**
 * A parsed night as a record. Bed times from noon onwards belong to the evening before the wake
 * date; earlier ones to the wake date itself. Totals stay as reported; naps are kept apart.
 */
fun Night.toRecord(clock: AppClock, source: SleepSource): SleepRecord? {
    val date = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
    fun time(text: String?) = text?.let { runCatching { LocalTime.parse(it.take(5)) }.getOrNull() }
    val bed = time(bedAt)?.let { t -> clock.at(if (t.hour >= 12) date.minusDays(1) else date, t) }
    val wake = time(wakeAt)?.let { clock.at(date, it) }
    val total = totalMin.takeIf { it > 0 } ?: if (bed != null && wake != null && wake > bed) ((wake - bed) / MINUTE_MS).toInt() else return null
    return SleepRecord(date = date, bedAt = bed, wakeAt = wake, totalMin = total, deepMin = deepMin, lightMin = lightMin, remMin = remMin, awakeMin = awakeMin, napMin = napMin, score = score, source = source)
}
