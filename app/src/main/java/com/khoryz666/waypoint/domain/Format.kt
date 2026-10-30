package com.khoryz666.waypoint.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

const val MINUTE_MS = 60_000L
const val HOUR_MS = 60 * MINUTE_MS
const val DAY_MS = 24 * HOUR_MS

/** "0m", "45m", "3h", "3h 12m" - rounded down to the minute. */
fun formatDuration(ms: Long): String = formatMinutes(ms.coerceAtLeast(0) / MINUTE_MS)

fun formatMinutes(totalMinutes: Long): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L -> "${minutes}m"
        minutes == 0L -> "${hours}h"
        else -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
    }
}

/** A running stopwatch: "4:05", "1:02:03". */
fun formatElapsed(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** Minor units per major unit for an ISO 4217 code (100 for MYR, 1 for JPY). */
fun minorUnitFactor(currencyCode: String): Int {
    val digits = runCatching { Currency.getInstance(currencyCode).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
    return BigDecimal.TEN.pow(digits).toInt()
}

/** Parses "42.50" into minor units for [currencyCode], or null if it has more decimals than the currency allows. */
fun parseMinor(amount: String, currencyCode: String): Long? {
    val value = amount.replace(",", "").toBigDecimalOrNull() ?: return null
    val scaled = value.multiply(BigDecimal(minorUnitFactor(currencyCode)))
    return runCatching { scaled.setScale(0, RoundingMode.UNNECESSARY).longValueExact() }.getOrNull()
}

/** "RM 4,210" / "RM 4,210.50" / "-RM 42" - the symbol in the locale's style, decimals only when non-zero. */
fun formatMoney(minor: Long, currencyCode: String, locale: Locale = Locale.getDefault()): String {
    val factor = minorUnitFactor(currencyCode)
    val major = BigDecimal(minor).divide(BigDecimal(factor))
    val symbol = currencySymbol(currencyCode, locale)
    val number = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = if (minor % factor == 0L) 0 else (factor.toString().length - 1)
        maximumFractionDigits = factor.toString().length - 1
    }.format(major.abs())
    return (if (minor < 0) "-" else "") + "$symbol $number"
}

/** Signed money for deltas: "+RM 620", "-RM 42". */
fun formatMoneyDelta(minor: Long, currencyCode: String, locale: Locale = Locale.getDefault()): String =
    (if (minor >= 0) "+" else "") + formatMoney(minor, currencyCode, locale)

private fun currencySymbol(code: String, locale: Locale): String = when (code) {
    "MYR" -> "RM"
    "CNY" -> "¥"
    else -> runCatching { Currency.getInstance(code).getSymbol(locale) }.getOrDefault(code)
}
