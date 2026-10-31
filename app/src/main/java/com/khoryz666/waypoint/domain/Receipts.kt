package com.khoryz666.waypoint.domain

import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.data.TrackUnit
import java.util.Locale

/**
 * Deterministic receipts (spec 5.4): built from local data in well under 100 ms, no network.
 * They always compare with the user's own past, and say "first record" when there is none.
 */
object Receipts {
    private const val SEP = " · "

    private fun trend(now: Int, before: Int) = when {
        now > before -> "↑"
        now < before -> "↓"
        else -> "="
    }

    /** "Rust book: ch.8 done · 9/20 · this week 3 (last week 1) ↑" */
    fun learning(track: Track, units: List<TrackUnit>, doneNow: List<TrackUnit>, doneThisWeek: Int, doneLastWeek: Int, firstLog: Boolean, lang: Lang): String {
        val parts = mutableListOf<String>()
        val head = if (doneNow.isEmpty()) track.title else "${track.title}: ${doneNow.joinToString(", ") { it.title }} ${lang.t("done", "完成")}"
        parts += head
        if (units.isNotEmpty()) parts += "${units.count { it.status == UnitStatus.DONE || it.status == UnitStatus.WEAK }}/${units.size}"
        parts += if (firstLog) lang.t("first record", "第一次记录")
        else lang.t("this week $doneThisWeek (last week $doneLastWeek) ", "本周 $doneThisWeek 个（上周 $doneLastWeek 个）") + trend(doneThisWeek, doneLastWeek)
        return parts.joinToString(SEP)
    }

    /** "Focus today 2h 03m · at least: ✓ · streak 4 days" */
    fun focus(focusTodayMs: Long, minimumMet: Boolean?, streak: Int, lang: Lang): String = listOfNotNull(
        lang.t("Focus today ", "今日专注 ") + formatDuration(focusTodayMs),
        minimumMet?.let { lang.t("at least: ", "至少: ") + if (it) "✓" else "✗" },
        lang.t("streak $streak days", "连续 $streak 天"),
    ).joinToString(SEP)

    /** "Balance RM 4,210 · 30 days +RM 620 · goal RM 5,790 to go, RM 970/month" */
    fun balance(balance: Long, net7: Long, net30: Long, goal: SavingsProgress?, currency: String, firstEntry: Boolean, lang: Lang): String {
        val locale = Locale.getDefault()
        val parts = mutableListOf(lang.t("Balance ", "余额 ") + formatMoney(balance, currency, locale))
        if (firstEntry) {
            parts += lang.t("first record", "第一次记录")
        } else {
            parts += lang.t("7 days ", "7 天 ") + formatMoneyDelta(net7, currency, locale)
            parts += lang.t("30 days ", "30 天 ") + formatMoneyDelta(net30, currency, locale)
        }
        if (goal != null) {
            parts += if (goal.remainingMinor == 0L) lang.t("goal reached", "目标已达成")
            else lang.t("goal ", "距目标 ") + formatMoney(goal.remainingMinor, currency, locale) +
                (goal.requiredPerMonthMinor?.let { lang.t(", ", "，每月需 ") + formatMoney(it, currency, locale) + lang.t("/month", "") } ?: "")
        }
        return parts.joinToString(SEP)
    }

    /** "Sleep 6h 40m · week average 7h 05m · in range 4/6 nights" */
    fun sleep(totalMin: Int, week: SleepWeek, lang: Lang): String = listOfNotNull(
        lang.t("Sleep ", "睡眠 ") + formatMinutes(totalMin.toLong()),
        week.averageMin?.let { lang.t("week average ", "本周均值 ") + formatMinutes(it.toLong()) },
        lang.t("in range ${week.nightsInRange}/${week.recorded} nights", "目标内 ${week.nightsInRange}/${week.recorded} 晚"),
    ).joinToString(SEP)

    /** "3 sessions this week (last week 2) ↑ · Goblet squat best 12 kg × 10 (last week 10 kg × 10)" */
    fun exercise(sessionsThisWeek: Int, sessionsLastWeek: Int, best: List<BestSet>, closest: GoalCriterion?, firstLog: Boolean, lang: Lang): String {
        val parts = mutableListOf<String>()
        parts += if (firstLog) lang.t("first record", "第一次记录")
        else lang.t("$sessionsThisWeek sessions this week (last week $sessionsLastWeek) ", "本周 $sessionsThisWeek 次（上周 $sessionsLastWeek 次）") + trend(sessionsThisWeek, sessionsLastWeek)
        best.take(3).forEach { b ->
            parts += "${b.name} ${b.now.describe()}" + (b.lastWeek?.let { lang.t(" (last week ", "（上周 ") + it.describe() + lang.t(")", "）") } ?: "")
        }
        closest?.let { parts += lang.t("next goal: ", "最接近的目标: ") + it.label }
        return parts.joinToString(SEP)
    }

    /** "Project today 1h 20m · this week 6h · 3 commits today · milestones 2/7" */
    fun project(track: Track, focusTodayMs: Long, focusWeekMs: Long, commitsToday: Int, milestonesDone: Int, milestones: Int, lang: Lang): String = listOfNotNull(
        "${track.title} ${lang.t("today", "今天")} ${formatDuration(focusTodayMs)}",
        lang.t("this week ", "本周 ") + formatDuration(focusWeekMs),
        lang.t("$commitsToday commits today", "今天 $commitsToday 个 commit").takeIf { track.githubRepos.isNotEmpty() },
        (lang.t("milestones ", "里程碑 ") + "$milestonesDone/$milestones").takeIf { milestones > 0 },
    ).joinToString(SEP)
}

/** A lift's best set this week and the same lift's best last week. */
data class BestSet(val name: String, val now: SetScore, val lastWeek: SetScore?)

data class SetScore(val reps: Int?, val weightKg: Double?, val seconds: Int?) : Comparable<SetScore> {
    private val load get() = (weightKg ?: 0.0) * 1000 + (reps ?: 0) + (seconds ?: 0) / 10.0
    override fun compareTo(other: SetScore) = load.compareTo(other.load)

    fun describe(): String = listOfNotNull(
        weightKg?.let { (if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()) + " kg" },
        reps?.let { "× $it" },
        seconds?.let { "${it}s" },
    ).joinToString(" ")
}

/** Best set per exercise name (case-insensitive) across [logs], by weight then reps. */
fun bestSets(logs: List<LogEntry>): Map<String, Pair<String, SetScore>> =
    logs.mapNotNull { it.structured as? Structured.Exercise }
        .flatMap { e -> e.exercises.flatMap { item -> item.sets.map { item.name.trim() to SetScore(it.reps, it.weightKg, it.seconds) } } }
        .groupBy { it.first.lowercase() }
        .mapValues { (_, sets) -> sets.maxBy { it.second } }

/** This week's bests with last week's for comparison, in the order of this log's exercises. */
fun compareBestSets(thisLog: Structured.Exercise, thisWeek: List<LogEntry>, lastWeek: List<LogEntry>): List<BestSet> {
    val now = bestSets(thisWeek)
    val before = bestSets(lastWeek)
    return thisLog.exercises.mapNotNull { item ->
        val key = item.name.trim().lowercase()
        now[key]?.let { (name, score) -> BestSet(name, score, before[key]?.second) }
    }
}
