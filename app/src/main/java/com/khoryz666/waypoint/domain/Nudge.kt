package com.khoryz666.waypoint.domain

import java.time.LocalTime

/** Waking hours for nudges (spec 5.5), default 08:00-23:30. */
data class WakingHours(val start: LocalTime = LocalTime.of(8, 0), val end: LocalTime = LocalTime.of(23, 30)) {
    operator fun contains(time: LocalTime) = !time.isBefore(start) && time.isBefore(end)
}

const val NUDGE_IDLE_MS = 3 * HOUR_MS

/**
 * One quiet nudge after 3 waking hours with no session or log, on day types with nudges on, and
 * at most once per idle stretch. Idle time is counted from the later of the last activity and
 * the start of today's waking hours. No LLM involved.
 */
fun shouldNudge(
    now: Long,
    lastActivity: Long?,
    timerRunning: Boolean,
    dayTypeNudges: Boolean,
    waking: WakingHours,
    lastNudgeAt: Long?,
    clock: AppClock,
): Boolean {
    if (!dayTypeNudges || timerRunning || clock.timeOf(now) !in waking) return false
    val wakingStart = clock.at(clock.dateOf(now), waking.start)
    val since = maxOf(lastActivity ?: wakingStart, wakingStart)
    if (now - since < NUDGE_IDLE_MS) return false
    return lastNudgeAt == null || lastNudgeAt < since
}
