package com.khoryz666.waypoint.domain

import com.khoryz666.waypoint.data.BalanceEvent
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/**
 * Balance after every live event up to [at]: the last SET plus the DELTAs after it (spec 3).
 * Events at the same instant apply in id order.
 */
fun balanceAt(events: List<BalanceEvent>, at: Long = Long.MAX_VALUE): Long {
    val live = events.filter { it.deletedAt == null && it.occurredAt <= at }.sortedWith(compareBy({ it.occurredAt }, { it.id }))
    val lastSet = live.indexOfLast { it.kind == BalanceKind.SET }
    val base = if (lastSet >= 0) live[lastSet].amount else 0
    return base + live.drop(lastSet + 1).filter { it.kind == BalanceKind.DELTA }.sumOf { it.amount }
}

/**
 * Balance at the start of a period beginning at [from]. When the first entry ever falls inside
 * the period and is a SET (the starting balance), the period opens at that amount, not at zero.
 */
fun openingBalance(events: List<BalanceEvent>, from: Long): Long {
    val live = events.filter { it.deletedAt == null }
    if (live.any { it.occurredAt < from }) return balanceAt(live, from - 1)
    val first = live.minWithOrNull(compareBy({ it.occurredAt }, { it.id })) ?: return 0
    return if (first.kind == BalanceKind.SET) first.amount else 0
}

/**
 * Net change over the [days] before [now]: the sum of DELTA entries in that window. SET entries
 * only correct drift, so they never count as income or spending. The unit of judgment for money
 * is the 30-day net (6.3).
 */
fun netChange(events: List<BalanceEvent>, now: Long, days: Int): Long =
    events.filter { it.deletedAt == null && it.kind == BalanceKind.DELTA && it.occurredAt <= now && it.occurredAt > now - days * DAY_MS }.sumOf { it.amount }

/** A money shortcut typed in the input box: `+350 note`, `-42.50 note`, `=4210`. */
data class MoneyShortcut(val kind: BalanceKind, val amountMinor: Long, val note: String?)

private val shortcut = Regex("""^\s*([+\-=＋－＝])\s*([0-9][0-9,]*(?:\.[0-9]+)?)\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)

/** Parses a money shortcut locally, without the LLM (spec 5.2). Null when [text] isn't one. */
fun parseMoneyShortcut(text: String, currencyCode: String): MoneyShortcut? {
    val match = shortcut.matchEntire(text) ?: return null
    val (sign, number, rest) = match.destructured
    val minor = parseMinor(number, currencyCode) ?: return null
    val note = rest.trim().ifEmpty { null }
    return when (sign) {
        "+", "＋" -> MoneyShortcut(BalanceKind.DELTA, minor, note)
        "-", "－" -> MoneyShortcut(BalanceKind.DELTA, -minor, note)
        else -> MoneyShortcut(BalanceKind.SET, minor, note)
    }
}


/** A savings goal set on the Foundation tab: an amount, optionally by a date. */
data class SavingsGoal(val amountMinor: Long, val by: LocalDate? = null)

data class SavingsProgress(val goalMinor: Long, val remainingMinor: Long, val monthsLeft: Int?, val requiredPerMonthMinor: Long?)

/** Distance to the goal and the average monthly saving needed to reach it by its date. */
fun savingsProgress(goal: SavingsGoal, balance: Long, today: LocalDate): SavingsProgress {
    val remaining = (goal.amountMinor - balance).coerceAtLeast(0)
    val months = goal.by?.let { ChronoUnit.DAYS.between(today, it).coerceAtLeast(0) / 30.44 }
    val required = when {
        months == null -> null
        remaining == 0L -> 0
        months <= 0.0 -> remaining
        else -> ceil(remaining / months.coerceAtLeast(1.0)).toLong()
    }
    return SavingsProgress(goal.amountMinor, remaining, months?.let { ceil(it).toInt() }, required)
}
