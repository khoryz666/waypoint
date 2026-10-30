package com.khoryz666.waypoint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistTest {
    @Test
    fun `headings are pillars and checkbox lines are questions`() {
        val md = """
            # Foundation
            - [ ] Deadlines for the next 2 weeks in my calendar?
            - [x] Paid the bills?
            Some prose that is ignored.
            ## Engine
            - [ ] Shipped anything I can't explain?
            # Joy
            # Next week
            - [ ] One thing to fix next week
            - [ ] Top 3 priorities
        """.trimIndent()
        val pillars = parseChecklistMarkdown(md)
        assertEquals(listOf("Foundation", "Engine", "Joy", "Next week"), pillars.map { it.name })
        assertEquals(2, pillars[0].questions.size)
        assertTrue(pillars[2].noTargets)
        assertEquals(listOf(QuestionKey.ONE_THING_TO_FIX, QuestionKey.TOP_3), pillars[3].questions.map { it.key })
        assertEquals(QuestionType.YES_NO, pillars[1].questions.single().type)
    }

    @Test
    fun `starter template has fix and top 3`() {
        val keys = starterChecklist(Lang.ZH).flatMap { it.questions }.mapNotNull { it.key }
        assertTrue(QuestionKey.ONE_THING_TO_FIX in keys && QuestionKey.TOP_3 in keys)
    }
}
