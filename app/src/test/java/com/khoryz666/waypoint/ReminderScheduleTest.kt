package com.khoryz666.waypoint

import com.khoryz666.waypoint.data.Settings
import com.khoryz666.waypoint.domain.FixedClock
import com.khoryz666.waypoint.work.Reminder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class ReminderScheduleTest {
    // Friday 2026-10-30 21:00.
    private val clock = FixedClock(LocalDateTime.of(2026, 10, 30, 21, 0))
    private val s = Settings()
    private fun next(r: Reminder) = r.next(clock.now(), s, clock)

    @Test
    fun `each reminder fires at its next occurrence`() {
        assertEquals(clock.at(LocalDateTime.of(2026, 10, 30, 22, 30)), next(Reminder.DAILY_CLOSE))
        assertEquals(clock.at(LocalDateTime.of(2026, 10, 31, 12, 0)), next(Reminder.SLEEP))
        assertEquals(clock.at(LocalDateTime.of(2026, 11, 1, 20, 0)), next(Reminder.WEEKLY_REVIEW))
        assertEquals(clock.at(LocalDateTime.of(2026, 11, 2, 12, 0)), next(Reminder.LATE_REVIEW))
        assertEquals(clock.at(LocalDateTime.of(2026, 11, 1, 20, 0)), next(Reminder.MONTHLY_REVIEW))
        assertEquals(clock.at(LocalDateTime.of(2027, 1, 1, 20, 0)), next(Reminder.YEARLY_REVIEW))
    }

    @Test
    fun `after today's time has passed it moves to tomorrow`() {
        clock.set(LocalDateTime.of(2026, 10, 30, 22, 31))
        assertEquals(clock.at(LocalDateTime.of(2026, 10, 31, 22, 30)), next(Reminder.DAILY_CLOSE))
    }
}
