package com.khoryz666.waypoint.domain

import com.khoryz666.waypoint.data.DayAssignment
import com.khoryz666.waypoint.data.DayType
import com.khoryz666.waypoint.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DayRulesTest {
    private val deep = DayType(1, "Deep", minimum = DayTarget(anyFocusMinutes = 25, anyUnits = 1, anyDomains = emptyList()), weekdays = listOf(1, 2))
    private val light = DayType(2, "Light", weekdays = listOf(6, 7), nudges = false)
    private val rust = Track(1, Domain.FUNDAMENTAL, "Rust book", TrackStatus.ACTIVE, createdAt = 0, updatedAt = 0)
    private val app = Track(2, Domain.PROJECT, "App", TrackStatus.ACTIVE, createdAt = 0, updatedAt = 0)

    @Test
    fun `assignment beats weekday default, unassigned weekday has no type`() {
        val monday = LocalDate.of(2026, 10, 26)
        assertEquals(deep, dayTypeFor(monday, listOf(deep, light), emptyList()))
        assertEquals(light, dayTypeFor(monday, listOf(deep, light), listOf(DayAssignment(monday, 2))))
        assertNull(dayTypeFor(monday.plusDays(2), listOf(deep, light), emptyList()))
    }

    @Test
    fun `minimum met needs every line`() {
        val half = DayProgress(DayTime(mapOf(2L to 30 * MINUTE_MS)))
        val result = evaluate(deep.minimum, half, listOf(rust, app), Lang.EN)
        assertEquals(2, result.lines.size)
        assertFalse(result.met)
        val full = half.copy(unitsDone = mapOf(1L to 1))
        assertTrue(evaluate(deep.minimum, full, listOf(rust, app), Lang.EN).met)
    }

    @Test
    fun `per-track targets ignore paused tracks`() {
        val target = DayTarget(focusMinutes = mapOf(1L to 30, 3L to 30))
        val result = evaluate(target, DayProgress(), listOf(rust), Lang.EN)
        assertEquals(listOf("Focus · Rust book"), result.lines.map { it.label })
    }

    @Test
    fun `any-domain targets only count those domains`() {
        val target = DayTarget(anyFocusMinutes = 25, anyDomains = listOf(Domain.PROJECT))
        val progress = DayProgress(DayTime(mapOf(1L to HOUR_MS)))
        assertFalse(evaluate(target, progress, listOf(rust, app), Lang.EN).met)
    }

    @Test
    fun `streak skips neutral days, an unmet today doesn't break it`() {
        val d = DayOutcome.entries
        assertEquals(3, streak(listOf(DayOutcome.MISSED, DayOutcome.MET, DayOutcome.NEUTRAL, DayOutcome.MET, DayOutcome.MET, DayOutcome.MISSED, DayOutcome.MET)))
        assertEquals(1, streak(listOf(DayOutcome.MET, DayOutcome.MISSED)))
        assertEquals(0, streak(listOf(DayOutcome.MISSED, DayOutcome.MISSED, DayOutcome.MET)))
        assertEquals(3, d.size)
    }

    @Test
    fun `days without a minimum are neutral`() {
        assertEquals(DayOutcome.NEUTRAL, outcome(light, evaluate(light.minimum, DayProgress(), listOf(rust), Lang.EN)))
        assertEquals(DayOutcome.NEUTRAL, outcome(null, TargetResult(emptyList())))
        assertEquals(DayOutcome.MISSED, outcome(deep, evaluate(deep.minimum, DayProgress(), listOf(rust), Lang.EN)))
    }
}
