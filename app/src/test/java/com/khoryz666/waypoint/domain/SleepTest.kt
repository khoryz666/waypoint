package com.khoryz666.waypoint.domain

import com.khoryz666.waypoint.data.SleepRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SleepTest {
    private val clock = FixedClock(LocalDateTime.of(2026, 11, 1, 12, 0))
    private val start = LocalDate.of(2026, 10, 26)
    private val targets = SleepTargets()

    private fun night(day: Int, total: Int, bedHour: Int = 23, bedMin: Int = 30): SleepRecord {
        val date = start.plusDays(day.toLong())
        val bedDate = if (bedHour >= 12) date.minusDays(1) else date
        return SleepRecord(date = date, bedAt = clock.at(bedDate.atTime(bedHour, bedMin)), wakeAt = null, totalMin = total, source = SleepSource.MANUAL)
    }

    @Test
    fun `fewer than 4 nights is not enough data`() {
        assertEquals(SleepStatus.NOT_ENOUGH_DATA, sleepWeek(start, (0..2).map { night(it, 450) }, targets, clock).status)
    }

    @Test
    fun `five good nights with steady bedtimes is on track`() {
        val week = sleepWeek(start, (0..4).map { night(it, 450) }, targets, clock)
        assertEquals(SleepStatus.ON_TRACK, week.status)
        assertEquals(5, week.nightsInRange)
    }

    @Test
    fun `average fine but only 4 nights in range is watch`() {
        val records = (0..3).map { night(it, 450) } + night(4, 400) + night(5, 520)
        assertEquals(SleepStatus.WATCH, sleepWeek(start, records, targets, clock).status)
    }

    @Test
    fun `average more than 30 min below target is off track`() {
        assertEquals(SleepStatus.OFF_TRACK, sleepWeek(start, (0..5).map { night(it, 380) }, targets, clock).status)
    }

    @Test
    fun `missing nights are gaps, not zeros`() {
        val week = sleepWeek(start, listOf(night(0, 420), night(3, 480), night(5, 450), night(6, 450)), targets, clock)
        assertEquals(450, week.averageMin)
        assertEquals(4, week.recorded)
    }

    @Test
    fun `bedtimes either side of midnight are close`() {
        val sd = bedtimeSd(listOf(night(0, 450, 23, 30).bedAt!!, night(1, 450, 0, 30).bedAt!!), clock)!!
        assertEquals(30.0, sd, 0.01)
        val scattered = (0..5).map { night(it, 450, if (it % 2 == 0) 21 else 2, 0) }
        assertTrue(sleepWeek(start, scattered, targets, clock).bedtimeSdMin!! > 60)
        assertEquals(SleepStatus.WATCH, sleepWeek(start, scattered, targets, clock).status)
    }
}
