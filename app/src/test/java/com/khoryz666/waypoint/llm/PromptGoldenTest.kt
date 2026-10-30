package com.khoryz666.waypoint.llm

import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.data.TrackUnit
import com.khoryz666.waypoint.data.UserProfile
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.domain.FixedClock
import com.khoryz666.waypoint.domain.GoalCriterion
import com.khoryz666.waypoint.domain.Lang
import com.khoryz666.waypoint.domain.Structured
import com.khoryz666.waypoint.domain.TrackStatus
import com.khoryz666.waypoint.domain.UnitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * Renders every prompt template with fixtures and compares it to a golden file (spec 8.2). Run
 * with -Dgolden.update=true to rewrite the goldens after an intended prompt change.
 */
class PromptGoldenTest {
    private val assets = File("src/main/assets/prompts")
    private val goldens = File("src/test/resources/golden")
    private val prompts = PromptBuilder { File(assets, "$it.md").readText() }
    private val clock = FixedClock(LocalDateTime.of(2026, 10, 30, 21, 15))
    private val context = ContextBuilder(prompts, clock)

    private val track = Track(
        id = 3, domain = Domain.FUNDAMENTAL, title = "Rust book", status = TrackStatus.ACTIVE,
        initialState = "Read ch.1-3 last year", goal = "Finish all 20 chapters with exercises",
        goalCriteria = listOf(GoalCriterion("20 chapters done", "units", "20"), GoalCriterion("Explain ownership without notes")),
        planNotes = "3 chapters a week", rollingSummary = "Done ch.1-7. Struggles with lifetimes.", createdAt = 0, updatedAt = 0,
    )
    private val units = listOf(TrackUnit(41, 3, 7, "Ch 8 Collections", UnitStatus.DOING), TrackUnit(42, 3, 8, "Ch 9 Errors"))
    private val logs = listOf(
        LogEntry(occurredAt = clock.now() - 86_400_000, createdAt = 0, structured = Structured.Fundamental(unitsDone = listOf(40), notes = "ch7 modules")),
    )

    private fun check(name: String, rendered: String) {
        val golden = File(goldens, "$name.txt")
        if (System.getProperty("golden.update") == "true" || !golden.exists()) golden.writeText(rendered)
        assertEquals("golden $name", golden.readText(), rendered)
    }

    @Test
    fun `static prompts have no unfilled placeholders`() {
        assets.listFiles()!!.filter { !it.name.startsWith("block_") && !it.name.startsWith("handoff_") }.forEach { file ->
            val name = file.nameWithoutExtension
            val rendered = prompts.render(name)
            assertTrue("$name has placeholders", !rendered.contains("{{"))
            check(name, rendered)
        }
    }

    @Test
    fun `json prompts mention json and show an example`() {
        listOf("parse_input", "parse_sleep_image", "exercise_next", "recall_question", "recall_grade", "repair_track_json", "review_week", "review_month", "review_year").forEach {
            val text = prompts.template(it)
            assertTrue("$it must say json", text.contains("json"))
            assertTrue("$it must show an example", text.contains("{\""))
        }
    }

    @Test
    fun `context blocks`() {
        check("block_profile", context.profile(UserProfile(displayName = "H", language = Lang.ZH, sleepAppHint = "Huawei Health"), "Deep", sleepHint = true))
        check("block_track", context.track(track, units))
        check("block_recent", context.recent(logs))
    }

    @Test
    fun `handoff templates`() {
        val values = mapOf("domain" to "FUNDAMENTAL", "title" to "Rust book", "initialState" to "读过 1-3 章", "notes" to "I have the PDF")
        check("handoff_track.zh", prompts.render("handoff_track.zh", values))
        check("handoff_track.en", prompts.render("handoff_track.en", values + mapOf("predecessor" to true, "prevGoal" to "g", "prevFinalState" to "f", "prevSummary" to "s")))
    }

    @Test
    fun `fit drops recent logs before the summary`() {
        val huge = (1..400).map { LogEntry(occurredAt = clock.now(), createdAt = 0, rawText = "a long log line about ownership and borrowing rules number $it") }
        val notes = mutableListOf<String>()
        val text = context.fit(context.profile(UserProfile(), null), { context.track(track, units) }, { context.track(track, units, includeSummary = false) }, huge, "input", onTruncate = notes::add)
        assertTrue(estimateTokens(text) <= FLASH_INPUT_TOKENS)
        assertTrue(text.contains("Summary so far"))
    }
}
