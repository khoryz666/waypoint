package com.khoryz666.waypoint.domain

import com.khoryz666.waypoint.data.Session
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SessionsTest {
    private val clock = FixedClock(LocalDateTime.of(2026, 10, 28, 12, 0))
    private fun at(d: Int, h: Int, m: Int = 0) = clock.at(LocalDateTime.of(2026, 10, d, h, m))

    @Test
    fun `a session crossing midnight counts in full on the day it ends`() {
        val s = Session(category = SessionCategory.FOCUS, trackId = 1, startAt = at(27, 23, 0), endAt = at(28, 1, 0))
        val totals = dailyTime(listOf(s), clock.now(), clock)
        assertEquals(setOf(LocalDate.of(2026, 10, 28)), totals.keys)
        assertEquals(2 * HOUR_MS, totals.getValue(LocalDate.of(2026, 10, 28)).focusMs(1))
    }

    @Test
    fun `the running session counts up to now, focus per track, joy apart`() {
        val sessions = listOf(
            Session(category = SessionCategory.FOCUS, trackId = 1, startAt = at(28, 9), endAt = at(28, 10)),
            Session(category = SessionCategory.JOY, startAt = at(28, 10), endAt = at(28, 10, 30)),
            Session(category = SessionCategory.FOCUS, trackId = 2, startAt = at(28, 11, 15)),
        )
        val day = timeOn(LocalDate.of(2026, 10, 28), sessions, clock.now(), clock)
        assertEquals(HOUR_MS, day.focusMs(1))
        assertEquals(45 * MINUTE_MS, day.focusMs(2))
        assertEquals(30 * MINUTE_MS, day.joyMs)
        assertEquals(105 * MINUTE_MS, day.totalFocusMs)
    }

    @Test
    fun `day boundaries follow the clock's current zone`() {
        val s = Session(category = SessionCategory.FOCUS, trackId = 1, startAt = at(28, 6), endAt = at(28, 7))
        clock.moveTo(java.time.ZoneId.of("Europe/London"))
        // 07:00 in UTC+8 is 23:00 the previous day in London (UTC+0 in late October).
        assertEquals(setOf(LocalDate.of(2026, 10, 27)), dailyTime(listOf(s), clock.now(), clock).keys)
    }

    @Test
    fun `formatting`() {
        assertEquals("0m", formatDuration(0))
        assertEquals("45m", formatDuration(45 * MINUTE_MS + 59_000))
        assertEquals("2h 03m", formatDuration(123 * MINUTE_MS))
        assertEquals("3h", formatDuration(3 * HOUR_MS))
        assertEquals("1:02:03", formatElapsed(3_723_000))
    }
}
