package com.khoryz666.waypoint.domain

import com.khoryz666.waypoint.data.TrackUnit

/** Days until the next review after passing step 0, 1, 2 (spec 6.5); a fourth pass ends reviews. */
val REVIEW_INTERVAL_DAYS = listOf(3L, 10L, 30L)
const val WEAK_RETRY_DAYS = 1L

/**
 * Marks [unit] done at [now], before any recall grade arrives. The first review is scheduled
 * straight away so a skipped recall check still gets revised (DECISIONS.md).
 */
fun markDone(unit: TrackUnit, now: Long): TrackUnit =
    if (unit.status == UnitStatus.DONE) unit
    else unit.copy(status = UnitStatus.DONE, doneAt = now, reviewStep = 0, nextReviewAt = now + REVIEW_INTERVAL_DAYS[0] * DAY_MS)

/**
 * Applies a recall grade. [isReview] is true when the question came from a due revision, false
 * when it followed the unit being done. Weak: due again tomorrow. Solid/partial on a review:
 * next interval, and after the third interval is passed, no more reviews.
 */
fun applyGrade(unit: TrackUnit, grade: Grade, isReview: Boolean, now: Long): TrackUnit = when {
    grade == Grade.WEAK -> unit.copy(status = UnitStatus.WEAK, nextReviewAt = now + WEAK_RETRY_DAYS * DAY_MS)
    !isReview -> unit.copy(status = UnitStatus.DONE, reviewStep = 0, nextReviewAt = now + REVIEW_INTERVAL_DAYS[0] * DAY_MS)
    else -> {
        val step = unit.reviewStep + 1
        unit.copy(
            status = UnitStatus.DONE,
            reviewStep = step.coerceAtMost(3),
            nextReviewAt = REVIEW_INTERVAL_DAYS.getOrNull(step)?.let { now + it * DAY_MS },
        )
    }
}
