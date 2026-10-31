package com.khoryz666.waypoint.domain

import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.data.TrackUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptsTest {
    private val track = Track(1, Domain.FUNDAMENTAL, "Rust book", createdAt = 0, updatedAt = 0)

    @Test
    fun `learning receipt compares with last week`() {
        val units = (1..20).map { TrackUnit(it.toLong(), 1, it, "ch.$it", if (it <= 9) UnitStatus.DONE else UnitStatus.TODO) }
        assertEquals(
            "Rust book: ch.8 完成 · 9/20 · 本周 3 个（上周 1 个）↑",
            Receipts.learning(track, units, listOf(units[7]), 3, 1, firstLog = false, Lang.ZH),
        )
        assertEquals("Rust book · first record", Receipts.learning(track, emptyList(), emptyList(), 0, 0, firstLog = true, Lang.EN))
    }

    @Test
    fun `focus, sleep and balance receipts`() {
        assertEquals("Focus today 2h 03m · minimum day ✓ · streak 4 days", Receipts.focus(123 * MINUTE_MS, true, 4, Lang.EN))
        val week = SleepWeek(java.time.LocalDate.MIN, 6, 4, 425, null, SleepStatus.WATCH)
        assertEquals("睡眠 6h 40m · 本周均值 7h 05m · 目标内 4/6 晚", Receipts.sleep(400, week, Lang.ZH))
        assertEquals("Balance RM 4,210 · 7 days +RM 0 · 30 days +RM 620", Receipts.balance(4_210_00, 0, 620_00, "MYR", false, Lang.EN))
    }

    @Test
    fun `best set per lift vs last week`() {
        fun log(vararg sets: Pair<Double, Int>) = LogEntry(occurredAt = 0, createdAt = 0, structured = Structured.Exercise(listOf(ExerciseItem("Goblet squat", sets.map { ExerciseSet(it.second, it.first) }))))
        val now = log(12.0 to 10, 12.0 to 8)
        val best = compareBestSets(now.structured as Structured.Exercise, listOf(now), listOf(log(10.0 to 10)))
        assertEquals("12 kg × 10", best.single().now.describe())
        assertEquals("10 kg × 10", best.single().lastWeek!!.describe())
    }
}
