package com.khoryz666.waypoint.timer

import com.khoryz666.waypoint.data.DayStatus
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.domain.Lang
import com.khoryz666.waypoint.domain.formatDuration

sealed interface QuickAction {
    val label: String

    data class Switch(val trackId: Long, override val label: String) : QuickAction
    data class Stop(override val label: String) : QuickAction
}

/**
 * What the persistent notification shows (spec 5.5): the running session as a chronometer, the
 * day's minimum, and buttons for the last 3 tracks + Stop. Whatever runs is left out
 * of the switches so each button keeps its place.
 */
data class NotificationContent(
    val title: String,
    val text: String?,
    val subText: String?,
    val runningSince: Long?,
    val actions: List<QuickAction>,
) {
    companion object {
        val Loading = NotificationContent("Waypoint", null, null, null, emptyList())

        fun of(status: DayStatus, recentFocus: List<Long>, tracks: List<Track>, lang: Lang): NotificationContent {
            val byId = tracks.associateBy { it.id }
            val running = status.running
            val title = when {
                running == null -> lang.t("Not tracking", "未计时")
                else -> byId[running.trackId]?.title ?: lang.t("Focus", "专注")
            }
            val minimum = status.minimum.takeIf { it.lines.isNotEmpty() }?.let { lang.t("minimum ", "最低 ") + if (it.met) "✓" else "${(it.fraction * 100).toInt()}%" }
            val text = listOfNotNull(lang.t("Focus ", "专注 ") + formatDuration(status.time.totalFocusMs), minimum, lang.t("streak ${status.streak}", "连续 ${status.streak} 天")).joinToString(" · ")
            val focusTargets = (recentFocus.filter { it in byId && byId.getValue(it).status.name.let { s -> s == "ACTIVE" || s == "PAUSED" } } +
                status.activeTracks.map { it.id }).distinct().take(3)
            val switches = focusTargets.filter { it != running?.trackId }.map { QuickAction.Switch(it, byId.getValue(it).title) }
            val actions = listOfNotNull(QuickAction.Stop(lang.t("Stop", "停止")).takeIf { running != null }) + switches
            return NotificationContent(title, text, null, running?.startAt, actions)
        }
    }
}
