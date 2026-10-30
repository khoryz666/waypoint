package com.khoryz666.waypoint.domain

import com.khoryz666.waypoint.data.TrackUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RevisionTest {
    private val unit = TrackUnit(1, 1, 0, "ch.8")

    @Test
    fun `done then three passed reviews at 3, 10 and 30 days, then no more`() {
        var u = markDone(unit, 0)
        assertEquals(3 * DAY_MS, u.nextReviewAt)
        u = applyGrade(u, Grade.SOLID, isReview = false, now = 0)
        assertEquals(3 * DAY_MS, u.nextReviewAt)
        u = applyGrade(u, Grade.PARTIAL, isReview = true, now = 3 * DAY_MS)
        assertEquals(13 * DAY_MS, u.nextReviewAt)
        assertEquals(1, u.reviewStep)
        u = applyGrade(u, Grade.SOLID, isReview = true, now = 13 * DAY_MS)
        assertEquals(43 * DAY_MS, u.nextReviewAt)
        u = applyGrade(u, Grade.SOLID, isReview = true, now = 43 * DAY_MS)
        assertNull(u.nextReviewAt)
        assertEquals(3, u.reviewStep)
    }

    @Test
    fun `weak is due tomorrow and keeps its step`() {
        val reviewed = applyGrade(markDone(unit, 0), Grade.SOLID, isReview = true, now = 3 * DAY_MS)
        val weak = applyGrade(reviewed, Grade.WEAK, isReview = true, now = 13 * DAY_MS)
        assertEquals(UnitStatus.WEAK, weak.status)
        assertEquals(14 * DAY_MS, weak.nextReviewAt)
        assertEquals(1, weak.reviewStep)
    }
}
