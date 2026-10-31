package com.khoryz666.waypoint.domain

/** The fixed set of domains a Track belongs to (spec 3). Sleep and money are not tracks. */
enum class Domain {
    EXERCISE, PROJECT, FUNDAMENTAL, MATH, READING;

    /** Completing a track of these domains offers "Start next level" (spec 3, lifecycle step 4). */
    val incremental: Boolean get() = this == EXERCISE || this == MATH

    /** Domains whose DONE units get recall checks and spaced revision (spec 6.5, 6.6). */
    val recallByDefault: Boolean get() = this == FUNDAMENTAL || this == MATH
}

enum class TrackStatus { DRAFT, ACTIVE, PAUSED, COMPLETED, ABORTED }

enum class UnitStatus { TODO, DOING, DONE, WEAK }

enum class LogKind { REPORT, EXERCISE, BALANCE, COMMITS, RECALL, REVIEW_ANSWER }

enum class ParseStatus { PENDING, PARSED, CONFIRMED, FAILED, MANUAL }

enum class NextStepStatus { OPEN, DONE, DROPPED }

enum class SleepSource { SCREENSHOT, MANUAL }

enum class BalanceKind { DELTA, SET }

enum class ReviewPeriod { WEEK, MONTH, YEAR }

const val MAX_ACTIVE_TRACKS = 8
