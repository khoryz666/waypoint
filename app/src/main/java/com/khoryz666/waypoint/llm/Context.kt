package com.khoryz666.waypoint.llm

import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.data.TrackUnit
import com.khoryz666.waypoint.data.UserProfile
import com.khoryz666.waypoint.domain.AppClock
import com.khoryz666.waypoint.domain.Structured
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Rough token count: ~4 Latin characters or ~1.4 CJK characters per token. Good enough for budgets. */
fun estimateTokens(text: String): Int {
    val cjk = text.count { it.code > 0x2E80 }
    return ((text.length - cjk) / 4.0 + cjk / 1.4).toInt() + 1
}

/** Input budget for a Flash call, excluding images (spec 8.3). */
const val FLASH_INPUT_TOKENS = 5_000
const val RECENT_TOKENS = 1_500

/**
 * Renders the context blocks of spec 8.3 in their fixed order: profile, track, recent logs. The
 * system prompt (system_core + the prompt file) comes before these and never varies, so the
 * request prefix stays cacheable.
 */
class ContextBuilder(private val prompts: PromptBuilder, private val clock: AppClock) {

    fun profile(profile: UserProfile, sleepHint: Boolean = false): String = prompts.render(
        "block_profile",
        mapOf(
            "name" to profile.displayName,
            "now" to DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm", java.util.Locale.ENGLISH).format(Instant.ofEpochMilli(clock.now()).atZone(clock.zone())),
            "zone" to clock.zone().id,
            "language" to profile.language.tag,
            "sleepApp" to profile.sleepAppHint.takeIf { sleepHint },
        ),
    )

    fun track(track: Track, units: List<TrackUnit>?, includeSummary: Boolean = true): String = prompts.render(
        "block_track",
        mapOf(
            "id" to track.id,
            "title" to track.title,
            "domain" to track.domain.name,
            "goal" to track.goal,
            "criteria" to track.goalCriteria.joinToString("\n") { c -> "- ${c.label}" + listOfNotNull(c.metric, c.target).joinToString(" ", prefix = " (", postfix = ")").takeIf { c.metric != null || c.target != null }.orEmpty() + if (c.met) " ✓" else "" },
            "initialState" to track.initialState,
            "planNotes" to track.planNotes,
            "summary" to track.rollingSummary.takeIf { includeSummary },
            "outline" to units?.joinToString("\n") { "${it.id}: ${it.title} [${it.status}]" },
        ),
    )

    /** Compact JSON lines of the newest logs, dropped from the oldest until under [maxTokens]. */
    fun recent(logs: List<LogEntry>, maxTokens: Int = RECENT_TOKENS): String {
        val lines = logs.map { log ->
            val date = DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(log.occurredAt).atZone(clock.zone()))
            "$date ${log.structured?.let(Structured::encode) ?: log.rawText.orEmpty()}"
        }.toMutableList()
        while (lines.isNotEmpty() && estimateTokens(lines.joinToString("\n")) > maxTokens) lines.removeAt(lines.lastIndex)
        if (lines.isEmpty()) return ""
        return prompts.render("block_recent", mapOf("logs" to lines.joinToString("\n")))
    }

    /**
     * Joins blocks, shrinking to [budget] tokens: first the recent block (block 5), then the
     * track summary (block 4) by re-rendering without it, as spec 8.3 orders.
     */
    fun fit(profile: String, track: () -> String, trackWithoutSummary: () -> String, recent: List<LogEntry>, input: String, budget: Int = FLASH_INPUT_TOKENS, onTruncate: (String) -> Unit = {}): String {
        val fixed = estimateTokens(profile) + estimateTokens(input)
        var trackBlock = track()
        var recentBudget = minOf(RECENT_TOKENS, budget - fixed - estimateTokens(trackBlock))
        if (recentBudget < 200 && recent.isNotEmpty()) {
            onTruncate("dropping track summary to fit $budget tokens")
            trackBlock = trackWithoutSummary()
            recentBudget = minOf(RECENT_TOKENS, budget - fixed - estimateTokens(trackBlock))
        }
        val recentBlock = if (recentBudget > 0) recent(recent, recentBudget) else ""
        if (recentBlock.isEmpty() && recent.isNotEmpty()) onTruncate("dropped recent logs to fit $budget tokens")
        return listOf(profile, trackBlock, recentBlock, input).filter { it.isNotBlank() }.joinToString("\n")
    }
}
