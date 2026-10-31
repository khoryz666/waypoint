package com.khoryz666.waypoint.domain

/** The fixed set of domains a Track belongs to (spec 3). Sleep is deliberately not one. */
enum class Domain {
    EXERCISE, MONEY, PROJECT, FUNDAMENTAL, MATH, READING;

    /** Completing a track of these domains offers "Start next level" (spec 3, lifecycle step 4). */
    val incremental: Boolean get() = this == EXERCISE || this == MONEY || this == MATH

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

enum class QuestionType { AUTO, YES_NO, FREE_TEXT }

/** What an AUTO checklist question computes, or the special role of a FREE_TEXT one (spec 7.2). */
enum class QuestionKey {
    SLEEP_NIGHTS_IN_RANGE,
    EXERCISE_SESSIONS,
    BALANCE_NET_30,
    MINIMUM_PLAN_DAYS,
    UNITS_DONE_PER_TRACK,
    MILESTONES_PER_PROJECT,
    OWNERSHIP_CHECKS,
    FOCUS_TIME,
    PILLAR_TRACKS,
    ONE_THING_TO_FIX,
    TOP_3,
}

const val MAX_ACTIVE_TRACKS = 8
