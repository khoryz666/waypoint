package com.khoryz666.waypoint.domain

import com.khoryz666.waypoint.data.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningRulesTest {
    @Test
    fun `stuck problems stay open until solved, 70 percent moves on`() {
        val first = Structured.Math(problemsAttempted = "1-10", attemptedCount = 10, solved = (1..6).map(Int::toString), stuck = listOf("7", "9"))
        assertFalse(sectionProgress(listOf(first)).readyToMoveOn)
        assertEquals(listOf("7", "9"), sectionProgress(listOf(first)).openStuck)
        val second = Structured.Math(solved = listOf("7"))
        val progress = sectionProgress(listOf(first, second))
        assertTrue(progress.readyToMoveOn)
        assertEquals(listOf("9"), progress.openStuck)
    }

    @Test
    fun `problem ranges count`() {
        assertEquals(15, countProblems("1-15"))
        assertEquals(17, countProblems("1–15, 18, 20"))
        assertEquals(0, countProblems("some"))
    }

    @Test
    fun `interest signals accumulate case-insensitively`() {
        fun log(at: Long, vararg topics: String) = LogEntry(occurredAt = at, createdAt = at, structured = Structured.Reading(interestSignals = topics.toList()))
        val result = interests(listOf(log(1, "Stoicism", "Rome"), log(2, "stoicism"), log(3, "Stoicism ")))
        assertEquals(Interest("Stoicism", 3, 3), result.first())
        assertEquals(2, result.size)
    }
}
