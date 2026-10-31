package com.khoryz666.waypoint.domain

private val checkbox = Regex("""^[-*]\s*\[[ xX]?\]\s*(.+)$""")

/** A pillar and its questions before they are saved (onboarding, Markdown import). */
data class PillarDraft(val name: String, val noTargets: Boolean, val questions: List<QuestionDraft>)

data class QuestionDraft(val text: String, val type: QuestionType, val key: QuestionKey? = null)

/**
 * Imports a Markdown checklist: `#` headings are pillars, `- [ ]` lines are questions (spec 5.0).
 * Every question is yes/no + note, except lines mentioning "top 3" or "one thing to fix", which
 * become the two special free-text questions.
 * A pillar named "Joy" (any case) is marked no-targets.
 */
fun parseChecklistMarkdown(markdown: String): List<PillarDraft> {
    val pillars = mutableListOf<PillarDraft>()
    var name: String? = null
    var questions = mutableListOf<QuestionDraft>()
    fun flush() {
        val n = name ?: return
        val joy = n.trim().equals("joy", ignoreCase = true) || n.contains("乐")
        pillars += PillarDraft(n, joy, questions)
    }
    markdown.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line.startsWith("#") -> {
                flush()
                name = line.trimStart('#').trim()
                questions = mutableListOf()
            }
            checkbox.matches(line) -> {
                val text = checkbox.find(line)!!.groupValues[1].trim()
                if (name == null) name = "Checklist"
                questions += classify(text)
            }
        }
    }
    flush()
    return pillars.filter { it.questions.isNotEmpty() || it.noTargets }
}

private fun classify(text: String): QuestionDraft {
    val lower = text.lowercase()
    return when {
        "top 3" in lower || "top3" in lower || "前三" in text || "三件" in text -> QuestionDraft(text, QuestionType.FREE_TEXT, QuestionKey.TOP_3)
        "one thing to fix" in lower || "要改" in text || "改进的一件" in text -> QuestionDraft(text, QuestionType.FREE_TEXT, QuestionKey.ONE_THING_TO_FIX)
        else -> QuestionDraft(text, QuestionType.YES_NO)
    }
}

/**
 * The starter template (spec 7.2): Foundation, Engine and Joy with 2-3 generic questions each.
 * Auto questions compute from the user's own tracks; the rest are edited by the user.
 */
fun starterChecklist(lang: Lang): List<PillarDraft> = listOf(
    PillarDraft(
        lang.t("Foundation", "基础"), noTargets = false,
        listOf(
            QuestionDraft(lang.t("Sleep nights in range", "睡眠达标晚数"), QuestionType.AUTO, QuestionKey.SLEEP_NIGHTS_IN_RANGE),
            QuestionDraft(lang.t("Exercise sessions vs plan", "运动次数 vs 计划"), QuestionType.AUTO, QuestionKey.EXERCISE_SESSIONS),
            QuestionDraft(lang.t("30-day balance net", "30 天余额净变化"), QuestionType.AUTO, QuestionKey.BALANCE_NET_30),
            QuestionDraft(lang.t("Deadlines for the next 2 weeks in my calendar?", "未来两周的截止日期都在日历里了吗?"), QuestionType.YES_NO),
        ),
    ),
    PillarDraft(
        lang.t("Engine", "引擎"), noTargets = false,
        listOf(
            QuestionDraft(lang.t("Minimum / plan days met", "最低 / 计划达成天数"), QuestionType.AUTO, QuestionKey.MINIMUM_PLAN_DAYS),
            QuestionDraft(lang.t("Units done per active track", "各 track 完成单元"), QuestionType.AUTO, QuestionKey.UNITS_DONE_PER_TRACK),
            QuestionDraft(lang.t("Milestones done per project", "各项目完成里程碑"), QuestionType.AUTO, QuestionKey.MILESTONES_PER_PROJECT),
            QuestionDraft(lang.t("Ownership checks answered vs skipped", "代码理解检查: 回答 vs 跳过"), QuestionType.AUTO, QuestionKey.OWNERSHIP_CHECKS),
            QuestionDraft(lang.t("Shipped anything I can't explain?", "有没有交付自己解释不了的东西?"), QuestionType.YES_NO),
        ),
    ),
    PillarDraft(
        lang.t("Joy", "乐趣"), noTargets = true,
        listOf(QuestionDraft(lang.t("Time on these tracks", "这些 track 的时间"), QuestionType.AUTO, QuestionKey.PILLAR_TRACKS)),
    ),
    PillarDraft(
        lang.t("Next week", "下周"), noTargets = false,
        listOf(
            QuestionDraft(lang.t("One thing to fix next week", "下周要改的一件事"), QuestionType.FREE_TEXT, QuestionKey.ONE_THING_TO_FIX),
            QuestionDraft(lang.t("Top 3 priorities for next week", "下周最重要的三件事"), QuestionType.FREE_TEXT, QuestionKey.TOP_3),
        ),
    ),
)
