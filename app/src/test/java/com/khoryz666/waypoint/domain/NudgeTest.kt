package com.khoryz666.waypoint.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NudgeTest {
    private val clock = FixedClock(LocalDateTime.of(2026, 10, 28, 12, 0))
    private val waking = WakingHours()
    private fun at(h: Int, m: Int = 0) = clock.at(LocalDateTime.of(2026, 10, 28, h, m))

    @Test
    fun `nudges after 3 idle waking hours, once`() {
        assertTrue(shouldNudge(at(12), at(8, 59), false, true, waking, null, clock))
        assertFalse(shouldNudge(at(12), at(9, 1), false, true, waking, null, clock))
        assertFalse(shouldNudge(at(12), at(8, 59), false, true, waking, lastNudgeAt = at(11, 59), clock = clock))
    }

    @Test
    fun `idle time starts at waking hours, never on light days or while timing`() {
        assertFalse(shouldNudge(at(10), lastActivity = at(1), false, true, waking, null, clock))
        assertTrue(shouldNudge(at(11), lastActivity = at(1), false, true, waking, null, clock))
        assertFalse(shouldNudge(at(12), null, false, dayTypeNudges = false, waking = waking, lastNudgeAt = null, clock = clock))
        assertFalse(shouldNudge(at(12), null, timerRunning = true, dayTypeNudges = true, waking = waking, lastNudgeAt = null, clock = clock))
        assertFalse(shouldNudge(at(23, 45), null, false, true, waking, null, clock))
    }
}
