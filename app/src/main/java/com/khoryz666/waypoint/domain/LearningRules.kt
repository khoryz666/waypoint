package com.khoryz666.waypoint.domain

import com.khoryz666.waypoint.data.LogEntry

/** The math section rule (spec 6.6): move on once ≥ 70% of attempted problems are solved. */
data class SectionProgress(val attempted: Int, val solved: Int, val openStuck: List<String>) {
    val solvedFraction: Double get() = if (attempted == 0) 0.0 else solved.toDouble() / attempted
    val readyToMoveOn: Boolean get() = attempted > 0 && solvedFraction >= 0.7
}

/**
 * Progress on one section from its math logs, oldest first. A problem stays "stuck" until a
 * later log solves it; attempted counts each problem once.
 */
fun sectionProgress(logs: List<Structured.Math>): SectionProgress {
    val solved = mutableSetOf<String>()
    val stuck = linkedSetOf<String>()
    var attemptedMax = 0
    logs.forEach { log ->
        log.solved.forEach { solved += it; stuck -= it }
        log.stuck.forEach { if (it !in solved) stuck += it }
        attemptedMax = maxOf(attemptedMax, log.attemptedCount, (solved + stuck).size)
    }
    return SectionProgress(attemptedMax, solved.size, stuck.toList())
}

/** Counts problems in "1-15", "1–15, 18, 20-22". Unparseable text counts as 0. */
fun countProblems(range: String): Int = range.split(',', '，', '、').sumOf { part ->
    val bounds = part.trim().split('-', '–', '—', '~').mapNotNull { it.trim().toIntOrNull() }
    when (bounds.size) {
        1 -> 1
        2 -> (bounds[1] - bounds[0] + 1).coerceAtLeast(0)
        else -> 0
    }.toInt()
}

/** One interest topic across reading logs (spec 6.7). */
data class Interest(val topic: String, val count: Int, val lastSeen: Long)

/** Accumulates interest signals per topic, case-insensitively, most frequent first. */
fun interests(readingLogs: List<LogEntry>): List<Interest> =
    readingLogs.flatMap { log -> (log.structured as? Structured.Reading)?.interestSignals.orEmpty().map { it.trim() to log.occurredAt } }
        .filter { it.first.isNotEmpty() }
        .groupBy { it.first.lowercase() }
        .map { (_, hits) -> Interest(hits.first().first, hits.size, hits.maxOf { it.second }) }
        .sortedWith(compareByDescending<Interest> { it.count }.thenByDescending { it.lastSeen })
