package com.khoryz666.waypoint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HandoffTest {
    private val paste = """
        好的，定稿如下：
        <<<WAYPOINT_TRACK
        {
          "domain": "FUNDAMENTAL",
          "title": " Rust book ",
          "initialState": "读过 1-3 章",
          "goal": "读完 20 章并完成练习",
          "checkpoints": [{"title": "读完前 10 章", "by": "2027-01-15"}, {"title": "  "}, {"title": "练习过半", "by": "null"}],
          "targetDate": "2027-03-01",
          "units": [{"title": "Ch 1"}, {"title": "Ch 2"}, {"title": "Ch 1"}, {"title": " "}],
          "planNotes": "每周 3 章"
        }
        WAYPOINT_TRACK>>>
        祝你顺利！
    """.trimIndent()

    @Test
    fun `a well-formed paste parses, trims and de-duplicates`() {
        val draft = (parseHandoff(paste, Domain.FUNDAMENTAL, Lang.ZH) as HandoffResult.Ok).draft
        assertEquals("Rust book", draft.title)
        assertEquals(listOf("Ch 1", "Ch 2"), draft.units)
        assertEquals(listOf(GoalCriterion("读完前 10 章", target = "2027-01-15"), GoalCriterion("练习过半")), draft.goalCriteria)
        assertEquals(LocalDate.of(2027, 3, 1), draft.targetDate)
        assertEquals("每周 3 章", draft.planNotes)
        assertTrue(draft.warnings.isEmpty())
    }

    @Test
    fun `old pastes with goalCriteria still parse`() {
        val old = "<<<WAYPOINT_TRACK {\"title\": \"x\", \"goal\": \"g\", \"goalCriteria\": [{\"label\": \"20 章\", \"target\": \"20\"}]} WAYPOINT_TRACK>>>"
        val draft = (parseHandoff(old, Domain.READING, Lang.EN) as HandoffResult.Ok).draft
        assertEquals(listOf(GoalCriterion("20 章", target = "20")), draft.goalCriteria)
    }

    @Test
    fun `domain mismatch warns and keeps the chosen domain`() {
        val draft = (parseHandoff(paste, Domain.READING, Lang.EN) as HandoffResult.Ok).draft
        assertEquals(Domain.READING, draft.domain)
        assertEquals(1, draft.warnings.size)
    }

    @Test
    fun `missing markers, broken JSON and empty goal fail with a reason`() {
        assertTrue(parseHandoff("{\"title\": \"x\"}", Domain.READING, Lang.EN) is HandoffResult.Failed)
        assertTrue(parseHandoff("<<<WAYPOINT_TRACK {\"title\": } WAYPOINT_TRACK>>>", Domain.READING, Lang.EN) is HandoffResult.Failed)
        val noGoal = (parseHandoff("<<<WAYPOINT_TRACK {\"title\": \"x\", \"goal\": \" \"} WAYPOINT_TRACK>>>", Domain.READING, Lang.EN) as HandoffResult.Failed)
        assertEquals("goal is empty", noGoal.reason)
    }

    @Test
    fun `units cap at 200 and a placeholder date is left empty`() {
        val units = (1..250).joinToString(",") { "{\"title\": \"P$it\"}" }
        val json = "<<<WAYPOINT_TRACK {\"title\": \"NC\", \"goal\": \"g\", \"targetDate\": \"YYYY-MM-DD 或 null\", \"units\": [$units]} WAYPOINT_TRACK>>>"
        val draft = (parseHandoff(json, Domain.FUNDAMENTAL, Lang.EN) as HandoffResult.Ok).draft
        assertEquals(200, draft.units.size)
        assertEquals(null, draft.targetDate)
        assertEquals(2, draft.warnings.size)
    }
}
