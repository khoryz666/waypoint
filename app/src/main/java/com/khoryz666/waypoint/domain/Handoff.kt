package com.khoryz666.waypoint.domain

import kotlinx.serialization.Serializable
import java.time.LocalDate

const val HANDOFF_START = "<<<WAYPOINT_TRACK"
const val HANDOFF_END = "WAYPOINT_TRACK>>>"
const val MAX_UNITS = 200

/** The JSON block the DeepSeek app produces at the end of a goal discussion (spec 4). */
@Serializable
data class TrackDraft(
    val domain: String? = null,
    val title: String = "",
    val initialState: String = "",
    val goal: String = "",
    /** Older pastes; new prompts ask for [checkpoints]. Both end up as the track's goalCriteria. */
    val goalCriteria: List<GoalCriterion> = emptyList(),
    val checkpoints: List<DraftCheckpoint> = emptyList(),
    val targetDate: String? = null,
    val units: List<DraftUnit> = emptyList(),
    val planNotes: String = "",
)

@Serializable
data class DraftUnit(val title: String = "")

/** A milestone on the way to the goal, with an optional "by when". */
@Serializable
data class DraftCheckpoint(val title: String = "", val by: String? = null)

/** A validated draft, ready for the editable preview. */
data class ValidDraft(
    val domain: Domain,
    val title: String,
    val initialState: String,
    val goal: String,
    /** Checkpoints: label is what, target is by when. */
    val goalCriteria: List<GoalCriterion>,
    val targetDate: LocalDate?,
    val units: List<String>,
    val planNotes: String,
    val warnings: List<String>,
)

sealed interface HandoffResult {
    data class Ok(val draft: ValidDraft) : HandoffResult
    data class Failed(val reason: String) : HandoffResult
}

/** Pulls the JSON out of a pasted DeepSeek message: between the markers, or the outermost {...}. */
fun extractHandoffJson(pasted: String): String? {
    val start = pasted.indexOf(HANDOFF_START)
    val end = pasted.indexOf(HANDOFF_END, startIndex = maxOf(start, 0))
    val inner = if (start >= 0 && end > start) pasted.substring(start + HANDOFF_START.length, end) else return null
    return inner.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}

/** Parses and validates a paste (spec 4, validation rules). [chosen] is the domain picked in step 1. */
fun parseHandoff(pasted: String, chosen: Domain, lang: Lang): HandoffResult {
    val json = extractHandoffJson(pasted)
        ?: return HandoffResult.Failed(lang.t("Markers $HANDOFF_START … $HANDOFF_END not found", "没找到 $HANDOFF_START … $HANDOFF_END 标记"))
    val draft = runCatching { WaypointJson.decodeFromString(TrackDraft.serializer(), json) }
        .getOrElse { return HandoffResult.Failed(lang.t("JSON error: ", "JSON 错误: ") + (it.message?.lineSequence()?.firstOrNull() ?: "")) }
    return validateDraft(draft, chosen, lang)
}

fun validateDraft(draft: TrackDraft, chosen: Domain, lang: Lang): HandoffResult {
    val warnings = mutableListOf<String>()
    val title = draft.title.trim()
    val goal = draft.goal.trim()
    if (title.isEmpty()) return HandoffResult.Failed(lang.t("title is empty", "title 为空"))
    if (goal.isEmpty()) return HandoffResult.Failed(lang.t("goal is empty", "goal 为空"))
    val pastedDomain = draft.domain?.trim()?.uppercase()
    if (pastedDomain != null && pastedDomain != chosen.name) {
        warnings += lang.t("Domain $pastedDomain differs from ${chosen.name}; keeping ${chosen.name}", "领域 $pastedDomain 与所选 ${chosen.name} 不一致，保留 ${chosen.name}")
    }
    val units = draft.units.map { it.title.trim() }.filter { it.isNotEmpty() }.distinct()
    if (units.size > MAX_UNITS) warnings += lang.t("${units.size} units, keeping the first $MAX_UNITS", "${units.size} 个单元，只保留前 $MAX_UNITS 个")
    val date = draft.targetDate?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (draft.targetDate != null && draft.targetDate.trim() !in listOf("", "null") && date == null) {
        warnings += lang.t("targetDate \"${draft.targetDate}\" isn't YYYY-MM-DD; left empty", "targetDate \"${draft.targetDate}\" 不是 YYYY-MM-DD，已留空")
    }
    return HandoffResult.Ok(
        ValidDraft(
            domain = chosen,
            title = title,
            initialState = draft.initialState.trim(),
            goal = goal,
            goalCriteria = (draft.goalCriteria + draft.checkpoints.map { GoalCriterion(it.title, target = it.by?.trim()?.takeIf { by -> by.isNotEmpty() && by != "null" }) })
                .map { it.copy(label = it.label.trim()) }.filter { it.label.isNotEmpty() },
            targetDate = date,
            units = units.take(MAX_UNITS),
            planNotes = draft.planNotes.trim(),
            warnings = warnings,
        ),
    )
}
