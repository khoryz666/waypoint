package com.khoryz666.waypoint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class BudgetTest {
    @Test
    fun `cost uses the three prices per million tokens`() {
        val cost = costCny(ModelPrice(0.04, 2.0, 8.0), cacheHit = 1_000_000, cacheMiss = 500_000, output = 100_000)
        assertEquals(0.04 + 1.0 + 0.8, cost, 1e-9)
        assertEquals(0.0, costCny(null, 1, 1, 1), 0.0)
    }

    @Test
    fun `warning at 80 percent, only essential prompts at 100`() {
        val month = YearMonth.of(2026, 11)
        assertEquals(BudgetLevel.OK, budgetState(10.0, 50.0, month, 10, 0.0).level)
        assertEquals(BudgetLevel.WARNING, budgetState(40.0, 50.0, month, 20, 0.0).level)
        val capped = budgetState(50.0, 50.0, month, 25, 0.0)
        assertEquals(BudgetLevel.CAPPED, capped.level)
        assertTrue(capped.allows("parse_input"))
        assertTrue(capped.allows("review_week"))
        assertFalse(capped.allows("comment_log"))
        assertFalse(capped.allows("ask_track"))
    }

    @Test
    fun `projection is linear over the month`() {
        assertEquals(30.0, budgetState(10.0, 50.0, YearMonth.of(2026, 11), 11, 0.0).projectedCny, 1e-9)
    }
}
