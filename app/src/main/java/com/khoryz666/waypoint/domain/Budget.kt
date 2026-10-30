package com.khoryz666.waypoint.domain

import kotlinx.serialization.Serializable
import java.time.YearMonth

/** CNY per 1M tokens for one model: cache-hit input, cache-miss input, output (spec 8.5). */
@Serializable
data class ModelPrice(val cacheHit: Double, val cacheMiss: Double, val output: Double)

/**
 * DeepSeek's CNY price list on 2026-10-30, peak-hour rates so the budget errs high
 * (api-docs.deepseek.com/zh-cn/quick_start/pricing). Editable in settings.
 */
val DEFAULT_PRICES = mapOf(
    "deepseek-flash" to ModelPrice(0.04, 2.0, 8.0),
    "deepseek-v4-pro" to ModelPrice(0.30, 9.0, 27.0),
)

fun costCny(price: ModelPrice?, cacheHit: Int, cacheMiss: Int, output: Int): Double {
    val p = price ?: return 0.0
    return (cacheHit * p.cacheHit + cacheMiss * p.cacheMiss + output * p.output) / 1_000_000.0
}

/** Prompts that keep calling the API after the budget is reached (spec 8.5). */
val ESSENTIAL_PROMPTS = setOf("parse_input", "parse_sleep_image", "review_week", "review_month", "review_year")

enum class BudgetLevel { OK, WARNING, CAPPED }

data class BudgetState(val spentCny: Double, val budgetCny: Double, val projectedCny: Double) {
    val level: BudgetLevel
        get() = when {
            spentCny >= budgetCny -> BudgetLevel.CAPPED
            spentCny >= budgetCny * 0.8 -> BudgetLevel.WARNING
            else -> BudgetLevel.OK
        }

    fun allows(promptName: String): Boolean = level != BudgetLevel.CAPPED || promptName in ESSENTIAL_PROMPTS
}

/** Month-to-date spend projected linearly over the whole month. */
fun budgetState(spentCny: Double, budgetCny: Double, month: YearMonth, dayOfMonth: Int, fractionOfDay: Double): BudgetState {
    val elapsed = (dayOfMonth - 1 + fractionOfDay).coerceAtLeast(0.5)
    return BudgetState(spentCny, budgetCny, spentCny / elapsed * month.lengthOfMonth())
}
