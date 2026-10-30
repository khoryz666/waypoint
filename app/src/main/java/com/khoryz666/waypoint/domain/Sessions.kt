package com.khoryz666.waypoint.domain

import com.khoryz666.waypoint.data.Session
import java.time.LocalDate

/** Time tracked on one date: focus per track (null key = focus without a track) and Joy. */
data class DayTime(val focusMs: Map<Long?, Long> = emptyMap(), val joyMs: Long = 0) {
    val totalFocusMs: Long get() = focusMs.values.sum()
    fun focusMs(trackId: Long): Long = focusMs[trackId] ?: 0

    operator fun plus(other: DayTime) = DayTime(
        (focusMs.keys + other.focusMs.keys).associateWith { (focusMs[it] ?: 0) + (other.focusMs[it] ?: 0) },
        joyMs + other.joyMs,
    )
}

/**
 * Totals per date. A session counts in full toward the date it ends on (v2 rule, spec 5.5), so
 * one that crosses midnight lands on the later day; the running session ends at [now].
 */
fun dailyTime(sessions: List<Session>, now: Long, clock: AppClock): Map<LocalDate, DayTime> =
    sessions.groupBy { clock.dateOf(it.endOrNow(now)) }.mapValues { (_, day) ->
        val joy = day.filter { it.category == SessionCategory.JOY }.sumOf { it.durationMs(now) }
        val focus = day.filter { it.category == SessionCategory.FOCUS }
            .groupBy { it.trackId }
            .mapValues { (_, list) -> list.sumOf { it.durationMs(now) } }
        DayTime(focus, joy)
    }

fun timeOn(date: LocalDate, sessions: List<Session>, now: Long, clock: AppClock): DayTime =
    dailyTime(sessions, now, clock)[date] ?: DayTime()
