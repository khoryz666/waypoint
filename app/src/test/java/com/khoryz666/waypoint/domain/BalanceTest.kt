package com.khoryz666.waypoint.domain

import com.khoryz666.waypoint.data.BalanceEvent
import com.khoryz666.waypoint.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BalanceTest {
    private fun delta(id: Long, at: Long, amount: Long) = BalanceEvent(id, at, BalanceKind.DELTA, amount)

    @Test
    fun `balance is the last SET plus later deltas, deleted rows ignored`() {
        val events = listOf(
            delta(1, 10, 500),
            BalanceEvent(2, 20, BalanceKind.SET, 4000_00),
            delta(3, 30, 350_00),
            delta(4, 40, -42_50),
            delta(5, 50, -1_000_00).copy(deletedAt = 60),
        )
        assertEquals(4307_50, balanceAt(events))
        assertEquals(4000_00, balanceAt(events, at = 25))
        assertEquals(500, balanceAt(events, at = 15))
    }

    @Test
    fun `a period opens at the starting balance set inside it`() {
        val events = listOf(BalanceEvent(1, 20, BalanceKind.SET, 1200_00), delta(2, 30, -42_50))
        assertEquals(1200_00, openingBalance(events, from = 10))
        assertEquals(0, openingBalance(listOf(delta(1, 20, 500)), from = 10))
        assertEquals(1157_50, openingBalance(events, from = 40))
    }

    @Test
    fun `net change over a window`() {
        val events = listOf(BalanceEvent(1, 0, BalanceKind.SET, 1000_00), delta(2, 40 * DAY_MS, 620_00), delta(3, 50 * DAY_MS, -20_00))
        assertEquals(600_00, netChange(events, now = 55 * DAY_MS, days = 30))
        assertEquals(-20_00, netChange(events, now = 55 * DAY_MS, days = 7))
    }

    @Test
    fun `a SET correction is not income`() {
        val events = listOf(BalanceEvent(1, 10 * DAY_MS, BalanceKind.SET, 1000_00), delta(2, 11 * DAY_MS, -42_50))
        assertEquals(-42_50, netChange(events, now = 12 * DAY_MS, days = 7))
    }

    @Test
    fun `money shortcuts parse locally in minor units`() {
        assertEquals(MoneyShortcut(BalanceKind.DELTA, 350_00, "freelance payment"), parseMoneyShortcut("+350 freelance payment", "MYR"))
        assertEquals(MoneyShortcut(BalanceKind.DELTA, -42_50, "groceries"), parseMoneyShortcut("-42.50 groceries", "MYR"))
        assertEquals(MoneyShortcut(BalanceKind.SET, 4_210_00, null), parseMoneyShortcut("=4,210", "MYR"))
        assertEquals(MoneyShortcut(BalanceKind.DELTA, 1200, null), parseMoneyShortcut("+1200", "JPY"))
        assertNull(parseMoneyShortcut("-42.505 too precise", "MYR"))
        assertNull(parseMoneyShortcut("今天收到 1200 稿费", "CNY"))
        assertNull(parseMoneyShortcut("- not a number", "MYR"))
    }

    @Test
    fun `goal progress and required monthly saving`() {
        val track = Track(1, Domain.MONEY, "Fund", goalCriteria = listOf(GoalCriterion("Balance", "balance", "RM 10,000")), createdAt = 0, updatedAt = 0)
        val goal = moneyGoalMinor(track, "MYR")!!
        assertEquals(10_000_00, goal)
        val p = moneyGoalProgress(goal, 4_000_00, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1))
        assertEquals(6_000_00, p.remainingMinor)
        assertEquals(6, p.monthsLeft)
        assertEquals(true, p.requiredPerMonthMinor!! in 1_000_00..1_010_00)
        assertNull(moneyGoalProgress(goal, 0, LocalDate.of(2026, 1, 1), null).requiredPerMonthMinor)
    }

    @Test
    fun `money formatting`() {
        assertEquals("RM 4,210", formatMoney(4_210_00, "MYR"))
        assertEquals("RM 42.50", formatMoney(42_50, "MYR"))
        assertEquals("+RM 620", formatMoneyDelta(620_00, "MYR"))
        assertEquals("-RM 42", formatMoneyDelta(-42_00, "MYR"))
    }
}
