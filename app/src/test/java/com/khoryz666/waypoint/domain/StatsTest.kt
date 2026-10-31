package com.khoryz666.waypoint.domain

import com.khoryz666.waypoint.data.BalanceEvent
import com.khoryz666.waypoint.data.DayType
import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.Session
import com.khoryz666.waypoint.data.SleepRecord
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.data.TrackUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class StatsTest {
    private val clock = FixedClock(LocalDateTime.of(2026, 11, 1, 21, 0))
    private val monday = LocalDate.of(2026, 10, 26)
    private fun at(day: Int, h: Int) = clock.at(monday.plusDays(day.toLong()).atTime(h, 0))

    @Test
    fun `week stats count focus, units, minimum days, sleep and ownership, ignoring deleted rows`() {
        val rust = Track(1, Domain.FUNDAMENTAL, "Rust", TrackStatus.ACTIVE, createdAt = 0, updatedAt = 0)
        val gym = Track(2, Domain.EXERCISE, "Gym", TrackStatus.ACTIVE, createdAt = at(0, 8), updatedAt = 0)
        val deep = DayType(1, "Deep", minimum = DayTarget(anyFocusMinutes = 25), weekdays = (1..7).toList())
        val input = StatsInput(
            from = monday, until = monday.plusDays(7),
            tracks = listOf(rust, gym),
            units = listOf(TrackUnit(1, 1, 0, "ch1", UnitStatus.DONE, doneAt = at(1, 10)), TrackUnit(2, 1, 1, "ch2", UnitStatus.DONE, doneAt = at(-3, 10))),
            logs = listOf(
                LogEntry(trackId = 2, kind = LogKind.EXERCISE, occurredAt = at(2, 18), createdAt = 0, structured = Structured.Exercise()),
                LogEntry(trackId = 1, kind = LogKind.RECALL, occurredAt = at(3, 9), createdAt = 0, structured = Structured.Recall(RecallSource.OWNERSHIP, "q", skipped = true)),
            ),
            sessions = listOf(
                Session(trackId = 1, startAt = at(0, 9), endAt = at(0, 10)),
                Session(trackId = 1, startAt = at(1, 9), endAt = at(1, 9) + 10 * MINUTE_MS),
            ),
            sleep = (0..4).map { SleepRecord(date = monday.plusDays(it.toLong()), bedAt = null, wakeAt = null, totalMin = 450, source = SleepSource.MANUAL) },
            balance = listOf(BalanceEvent(1, at(-10, 9), BalanceKind.SET, 1000_00), BalanceEvent(2, at(2, 9), BalanceKind.DELTA, 50_00)),
            dayTypes = listOf(deep), assignments = emptyList(), commitsByTrack = emptyMap(),
            sleepTargets = SleepTargets(), currency = "MYR", now = clock.now(),
        )
        val stats = reviewStats(input, clock)
        assertEquals(70, stats.focusMinutes)
        assertEquals(1, stats.tracks.single { it.id == 1L }.unitsDone)
        assertEquals(1, stats.minimumDays)
        assertEquals(7, stats.daysWithMinimum)
        assertEquals(1, stats.tracks.single { it.id == 2L }.exerciseSessions)
        assertEquals(SleepStatus.ON_TRACK, stats.sleep.status)
        assertEquals(1, stats.ownershipSkipped)
        assertEquals(1000_00L, stats.balance.startMinor)
        assertEquals(1050_00L, stats.balance.endMinor)
        assertEquals(listOf("Gym"), stats.started)
        assertTrue(autoAnswer(QuestionKey.MINIMUM_PLAN_DAYS, stats, Lang.EN).startsWith("minimum 1/7"))
    }

    @Test
    fun `successor chains follow predecessors`() {
        val a = Track(1, Domain.EXERCISE, "Pull-up", initialState = "0", finalState = "1", createdAt = 0, updatedAt = 0)
        val b = Track(2, Domain.EXERCISE, "Pull-up 2", initialState = "1", finalState = "5", predecessorId = 1, createdAt = 0, updatedAt = 0)
        assertEquals(listOf("Pull-up: 0 → 1 → 5"), successorChains(listOf(a, b)))
    }
}
