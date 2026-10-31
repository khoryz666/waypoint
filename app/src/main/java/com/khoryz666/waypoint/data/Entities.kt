package com.khoryz666.waypoint.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.khoryz666.waypoint.domain.BalanceKind
import com.khoryz666.waypoint.domain.DayTarget
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.domain.GoalCriterion
import com.khoryz666.waypoint.domain.LogKind
import com.khoryz666.waypoint.domain.NextStepStatus
import com.khoryz666.waypoint.domain.ParseStatus
import com.khoryz666.waypoint.domain.QuestionKey
import com.khoryz666.waypoint.domain.QuestionType
import com.khoryz666.waypoint.domain.ReviewPeriod
import com.khoryz666.waypoint.domain.SleepSource
import com.khoryz666.waypoint.domain.Structured
import com.khoryz666.waypoint.domain.TrackStatus
import com.khoryz666.waypoint.domain.UnitStatus
import kotlinx.serialization.Serializable
import java.time.LocalDate

// Every entity is @Serializable so the backup exporter can write tables as JSON (spec 9.2).
// Timestamps are epoch ms (UTC instants); calendar dates are LocalDate stored as ISO text.

@Serializable
@Entity(tableName = "tracks", indices = [Index("status"), Index("predecessorId")])
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain: Domain,
    val title: String,
    val status: TrackStatus = TrackStatus.DRAFT,
    val initialState: String = "",
    val goal: String = "",
    val goalCriteria: List<GoalCriterion> = emptyList(),
    @Serializable(with = LocalDateSerializer::class) val targetDate: LocalDate? = null,
    val planNotes: String = "",
    val predecessorId: Long? = null,
    val endedAt: Long? = null,
    val endReason: String? = null,
    val pauseReason: String? = null,
    val finalState: String? = null,
    val rollingSummary: String = "",
    val summaryUpdatedAt: Long? = null,
    /** Confirmed logs since the summary was last written (spec 8.4: refresh every 10). */
    val logsSinceSummary: Int = 0,
    val githubRepos: List<String> = emptyList(),
    /** Last HTTP failure of the commit sync, shown as "同步失败" (spec 10.1). Null when fine. */
    val syncError: String? = null,
    val lastSyncAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "units",
    foreignKeys = [ForeignKey(entity = Track::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("trackId"), Index("nextReviewAt")],
)
data class TrackUnit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val order: Int,
    val title: String,
    val status: UnitStatus = UnitStatus.TODO,
    val doneAt: Long? = null,
    val nextReviewAt: Long? = null,
    val reviewStep: Int = 0,
)

@Serializable
@Entity(tableName = "logs", indices = [Index("trackId"), Index("occurredAt"), Index("parseStatus")])
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long? = null,
    val kind: LogKind = LogKind.REPORT,
    val occurredAt: Long,
    val createdAt: Long,
    val rawText: String? = null,
    /** App-private image file paths; deleted once the card is handled (5.3). */
    val rawImageUris: List<String> = emptyList(),
    val structured: Structured? = null,
    val unitIds: List<Long> = emptyList(),
    val parseStatus: ParseStatus = ParseStatus.PENDING,
    /** Routing confidence from parse_input; below 0.7 the card asks for the track. */
    val confidence: Double? = null,
    /** The FOCUS session this log answers ("做了什么?", 5.5). */
    val sessionId: Long? = null,
    val parseError: String? = null,
    val deletedAt: Long? = null,
)

@Serializable
@Entity(tableName = "feedback", indices = [Index("logId"), Index("trackId")])
data class Feedback(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val logId: Long? = null,
    val reviewId: Long? = null,
    val trackId: Long? = null,
    val question: String? = null,
    val text: String,
    val model: String,
    val promptName: String,
    val createdAt: Long,
)

@Serializable
@Entity(tableName = "next_steps", indices = [Index("trackId", "status")])
data class NextStep(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val text: String,
    val dueHint: String? = null,
    val status: NextStepStatus = NextStepStatus.OPEN,
    val createdAt: Long,
)

/** A stretch of timed work. No [endAt] = running now; at most one. Counts toward the day it ends. */
@Serializable
@Entity(tableName = "sessions", indices = [Index("endAt"), Index("trackId")])
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long? = null,
    val startAt: Long,
    val endAt: Long? = null,
    val note: String? = null,
) {
    val isRunning: Boolean get() = endAt == null
    fun endOrNow(now: Long): Long = endAt ?: now
    fun durationMs(now: Long): Long = (endOrNow(now) - startAt).coerceAtLeast(0)
}

@Serializable
@Entity(tableName = "day_types")
data class DayType(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val order: Int = 0,
    val minimum: DayTarget = DayTarget(),
    val plan: DayTarget = DayTarget(),
    /** ISO weekdays (1 = Monday) this type is the default for. */
    val weekdays: List<Int> = emptyList(),
    /** Nudges after 3 idle waking hours (spec 5.5: on for Moderate and Deep, off for Light). */
    val nudges: Boolean = true,
)

/** A date whose day type was changed from its weekday default. */
@Serializable
@Entity(tableName = "day_assignments")
data class DayAssignment(
    @PrimaryKey @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val dayTypeId: Long,
)

@Serializable
@Entity(tableName = "sleep_records", indices = [Index(value = ["date"], unique = true)])
data class SleepRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val bedAt: Long?,
    val wakeAt: Long?,
    val totalMin: Int,
    val deepMin: Int? = null,
    val lightMin: Int? = null,
    val remMin: Int? = null,
    val awakeMin: Int? = null,
    val napMin: Int? = null,
    val score: Int? = null,
    val source: SleepSource,
)

@Serializable
@Entity(tableName = "balance_events", indices = [Index("occurredAt")])
data class BalanceEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: Long,
    val kind: BalanceKind,
    /** Integer minor units of the profile currency; signed for DELTA. */
    val amount: Long,
    val note: String? = null,
    val deletedAt: Long? = null,
)

@Serializable
@Entity(tableName = "reviews", indices = [Index("period", "start", unique = true)])
data class Review(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val period: ReviewPeriod,
    @Serializable(with = LocalDateSerializer::class) val start: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val end: LocalDate,
    /** JSON computed by domain/ (ReviewStats). */
    val stats: String,
    /** JSON map of question id to answer. */
    val answers: String = "{}",
    val reflection: String = "",
    val narrative: String = "",
    val oneThingToFix: String = "",
    val top3: List<String> = emptyList(),
    val submittedAt: Long? = null,
)

@Serializable
@Entity(tableName = "usage_records", indices = [Index("at")])
data class UsageRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val model: String,
    val promptName: String,
    val cacheHitTokens: Int,
    val cacheMissTokens: Int,
    val outputTokens: Int,
    val costCny: Double,
)

/** A user-defined group of weekly-review questions (spec 7.2). */
@Serializable
@Entity(tableName = "pillars")
data class Pillar(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val order: Int,
    /** Shows logged time and logs of [trackIds] without judgment (the starter Joy pillar). */
    val noTargets: Boolean = false,
    val trackIds: List<Long> = emptyList(),
)

@Serializable
@Entity(
    tableName = "checklist_questions",
    foreignKeys = [ForeignKey(entity = Pillar::class, parentColumns = ["id"], childColumns = ["pillarId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("pillarId")],
)
data class ChecklistQuestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pillarId: Long,
    val order: Int,
    val text: String,
    val type: QuestionType,
    val key: QuestionKey? = null,
)

/** A commit pulled from GitHub (6.4). Messages only, no diffs. */
@Serializable
@Entity(tableName = "commits", indices = [Index("trackId", "committedAt")])
data class GitCommit(
    @PrimaryKey val sha: String,
    val trackId: Long,
    val repo: String,
    val committedAt: Long,
    val message: String,
    val branch: String? = null,
    /** Set once the daily close has folded this commit into a COMMITS log. */
    val logId: Long? = null,
)
