package com.khoryz666.waypoint.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.khoryz666.waypoint.domain.NextStepStatus
import com.khoryz666.waypoint.domain.ReviewPeriod
import com.khoryz666.waypoint.domain.TrackStatus
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE status = 'ACTIVE' ORDER BY createdAt")
    fun observeActive(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE status = 'ACTIVE' ORDER BY createdAt")
    suspend fun active(): List<Track>

    @Query("SELECT * FROM tracks")
    suspend fun all(): List<Track>

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observe(id: Long): Flow<Track?>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: Long): Track?

    @Query("SELECT COUNT(*) FROM tracks WHERE status = :status")
    suspend fun countWithStatus(status: TrackStatus): Int

    @Insert
    suspend fun insert(track: Track): Long

    @Update
    suspend fun update(track: Track)
}

@Dao
interface UnitDao {
    @Query("SELECT * FROM units WHERE trackId = :trackId ORDER BY `order`")
    fun observeFor(trackId: Long): Flow<List<TrackUnit>>

    @Query("SELECT * FROM units WHERE trackId = :trackId ORDER BY `order`")
    suspend fun forTrack(trackId: Long): List<TrackUnit>

    @Query("SELECT * FROM units")
    suspend fun all(): List<TrackUnit>

    @Query("SELECT * FROM units")
    fun observeAll(): Flow<List<TrackUnit>>

    @Query("SELECT * FROM units WHERE id = :id")
    suspend fun get(id: Long): TrackUnit?

    @Query("SELECT * FROM units WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<TrackUnit>

    /** Units due for revision on ACTIVE tracks, oldest due first. */
    @Query(
        "SELECT units.* FROM units JOIN tracks ON tracks.id = units.trackId " +
            "WHERE tracks.status = 'ACTIVE' AND units.nextReviewAt IS NOT NULL AND units.nextReviewAt < :before " +
            "ORDER BY units.nextReviewAt",
    )
    fun observeDue(before: Long): Flow<List<TrackUnit>>

    @Query("SELECT COALESCE(MAX(`order`), -1) FROM units WHERE trackId = :trackId")
    suspend fun maxOrder(trackId: Long): Int

    @Insert
    suspend fun insertAll(units: List<TrackUnit>): List<Long>

    @Insert
    suspend fun insert(unit: TrackUnit): Long

    @Update
    suspend fun update(unit: TrackUnit)

    @Query("DELETE FROM units WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface LogDao {
    @Query("SELECT * FROM logs WHERE trackId = :trackId AND deletedAt IS NULL ORDER BY occurredAt DESC")
    fun observeFor(trackId: Long): Flow<List<LogEntry>>

    @Query("SELECT * FROM logs WHERE trackId = :trackId AND deletedAt IS NULL AND parseStatus = 'CONFIRMED' ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun recentConfirmed(trackId: Long, limit: Int): List<LogEntry>

    @Query("SELECT * FROM logs WHERE trackId = :trackId AND deletedAt IS NULL AND parseStatus = 'CONFIRMED' AND occurredAt > :since ORDER BY occurredAt")
    suspend fun confirmedSince(trackId: Long, since: Long): List<LogEntry>

    /** Cards still waiting for the user (spec 5.3): anything typed but not saved or discarded. */
    @Query("SELECT * FROM logs WHERE deletedAt IS NULL AND parseStatus IN ('PENDING', 'PARSED', 'FAILED') ORDER BY createdAt")
    fun observeUnconfirmed(): Flow<List<LogEntry>>

    @Query("SELECT * FROM logs WHERE deletedAt IS NULL AND parseStatus = 'PENDING' ORDER BY createdAt")
    suspend fun pending(): List<LogEntry>

    @Query("SELECT * FROM logs WHERE deletedAt IS NULL AND parseStatus = 'CONFIRMED' AND occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt")
    suspend fun confirmedIn(from: Long, to: Long): List<LogEntry>

    @Query("SELECT * FROM logs WHERE deletedAt IS NULL AND parseStatus = 'CONFIRMED' AND occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt")
    fun observeConfirmedIn(from: Long, to: Long): Flow<List<LogEntry>>

    @Query("SELECT * FROM logs WHERE deletedAt IS NULL AND parseStatus = 'CONFIRMED' AND kind = 'RECALL'")
    fun observeRecalls(): Flow<List<LogEntry>>

    @Query("SELECT * FROM logs WHERE deletedAt IS NULL AND parseStatus = 'CONFIRMED' AND kind = 'RECALL' AND createdAt >= :from")
    suspend fun recallsSince(from: Long): List<LogEntry>

    @Query("SELECT * FROM logs WHERE id = :id")
    suspend fun get(id: Long): LogEntry?

    @Query("SELECT * FROM logs WHERE id = :id")
    fun observe(id: Long): Flow<LogEntry?>

    @Query("SELECT * FROM logs")
    suspend fun all(): List<LogEntry>

    @Query("SELECT MAX(createdAt) FROM logs WHERE deletedAt IS NULL")
    suspend fun lastCreatedAt(): Long?

    @Insert
    suspend fun insert(log: LogEntry): Long

    @Update
    suspend fun update(log: LogEntry)
}

@Dao
interface FeedbackDao {
    @Query("SELECT * FROM feedback WHERE logId = :logId ORDER BY createdAt DESC LIMIT 1")
    fun observeForLog(logId: Long): Flow<Feedback?>

    @Query("SELECT * FROM feedback WHERE trackId = :trackId AND question IS NOT NULL ORDER BY createdAt DESC")
    fun observeAnswers(trackId: Long): Flow<List<Feedback>>

    @Query("SELECT * FROM feedback")
    suspend fun all(): List<Feedback>

    @Insert
    suspend fun insert(feedback: Feedback): Long
}

@Dao
abstract class NextStepDao {
    @Query("SELECT * FROM next_steps WHERE status = 'OPEN'")
    abstract fun observeOpen(): Flow<List<NextStep>>

    @Query("SELECT * FROM next_steps WHERE trackId = :trackId AND status = 'OPEN' LIMIT 1")
    abstract suspend fun open(trackId: Long): NextStep?

    @Query("SELECT * FROM next_steps")
    abstract suspend fun all(): List<NextStep>

    /** At most one OPEN step per track: a new one drops the old (spec 3). */
    @Transaction
    open suspend fun replace(step: NextStep) {
        setStatus(step.trackId, NextStepStatus.OPEN, NextStepStatus.DROPPED)
        insert(step)
    }

    @Query("UPDATE next_steps SET status = :to WHERE trackId = :trackId AND status = :from")
    abstract suspend fun setStatus(trackId: Long, from: NextStepStatus, to: NextStepStatus)

    @Query("UPDATE next_steps SET status = 'DONE' WHERE id = :id")
    abstract suspend fun markDone(id: Long)

    @Insert
    abstract suspend fun insert(step: NextStep): Long
}

@Dao
abstract class SessionDao {
    @Query("SELECT * FROM sessions WHERE endAt IS NULL LIMIT 1")
    abstract suspend fun running(): Session?

    @Query("SELECT * FROM sessions WHERE endAt IS NULL LIMIT 1")
    abstract fun observeRunning(): Flow<Session?>

    /** Sessions ending within [from, to), plus the running one (it ends "now"). */
    @Query("SELECT * FROM sessions WHERE endAt IS NULL OR (endAt >= :from AND endAt < :to) ORDER BY startAt")
    abstract fun observeEndingIn(from: Long, to: Long): Flow<List<Session>>

    @Query("SELECT * FROM sessions WHERE endAt IS NULL OR (endAt >= :from AND endAt < :to) ORDER BY startAt")
    abstract suspend fun endingIn(from: Long, to: Long): List<Session>

    @Query("SELECT * FROM sessions WHERE trackId = :trackId ORDER BY startAt DESC")
    abstract fun observeFor(trackId: Long): Flow<List<Session>>

    /** Tracks of the most recent sessions, newest first, for the notification's switches. */
    @Query("SELECT trackId FROM sessions WHERE trackId IS NOT NULL GROUP BY trackId ORDER BY MAX(startAt) DESC LIMIT :limit")
    abstract fun observeRecentFocusTracks(limit: Int): Flow<List<Long>>

    @Query("SELECT MAX(COALESCE(endAt, :now)) FROM sessions")
    abstract suspend fun lastActivity(now: Long): Long?

    @Query("SELECT * FROM sessions")
    abstract suspend fun all(): List<Session>

    @Query("SELECT * FROM sessions WHERE id = :id")
    abstract suspend fun get(id: Long): Session?

    /** Ends whatever is running at [now] and, unless [trackId] is null, starts a session on it. Returns the ended one. */
    @Transaction
    open suspend fun switchTo(trackId: Long?, now: Long): Session? {
        val ended = running()
        endRunning(now)
        if (trackId != null) insert(Session(trackId = trackId, startAt = now))
        return ended?.let { get(it.id) }
    }

    // MAX() keeps a session from ending before it started if the clock was set back.
    @Query("UPDATE sessions SET endAt = MAX(:now, startAt) WHERE endAt IS NULL")
    protected abstract suspend fun endRunning(now: Long)

    @Insert
    abstract suspend fun insert(session: Session): Long

    @Update
    abstract suspend fun update(session: Session)

    @Query("DELETE FROM sessions WHERE id = :id AND endAt IS NOT NULL")
    abstract suspend fun deleteFinished(id: Long)
}

@Dao
interface DayTypeDao {
    @Query("SELECT * FROM day_types ORDER BY `order`")
    fun observeAll(): Flow<List<DayType>>

    @Query("SELECT * FROM day_types ORDER BY `order`")
    suspend fun all(): List<DayType>

    @Query("SELECT * FROM day_assignments WHERE date >= :from AND date < :to")
    fun observeAssignments(from: LocalDate, to: LocalDate): Flow<List<DayAssignment>>

    @Query("SELECT * FROM day_assignments WHERE date >= :from AND date < :to")
    suspend fun assignments(from: LocalDate, to: LocalDate): List<DayAssignment>

    @Query("SELECT * FROM day_assignments")
    suspend fun allAssignments(): List<DayAssignment>

    @Upsert
    suspend fun assign(assignment: DayAssignment)

    @Insert
    suspend fun insert(type: DayType): Long

    @Update
    suspend fun update(type: DayType)

    @Query("DELETE FROM day_types WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SleepDao {
    @Query("SELECT * FROM sleep_records WHERE date >= :from AND date < :to ORDER BY date")
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<SleepRecord>>

    @Query("SELECT * FROM sleep_records WHERE date >= :from AND date < :to ORDER BY date")
    suspend fun between(from: LocalDate, to: LocalDate): List<SleepRecord>

    @Query("SELECT * FROM sleep_records WHERE date IN (:dates)")
    suspend fun onDates(dates: List<LocalDate>): List<SleepRecord>

    @Query("SELECT * FROM sleep_records")
    suspend fun all(): List<SleepRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: SleepRecord): Long

    @Query("DELETE FROM sleep_records WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BalanceDao {
    @Query("SELECT * FROM balance_events WHERE deletedAt IS NULL ORDER BY occurredAt, id")
    fun observeAll(): Flow<List<BalanceEvent>>

    @Query("SELECT * FROM balance_events WHERE deletedAt IS NULL ORDER BY occurredAt, id")
    suspend fun live(): List<BalanceEvent>

    @Query("SELECT * FROM balance_events")
    suspend fun all(): List<BalanceEvent>

    @Insert
    suspend fun insert(event: BalanceEvent): Long

    @Query("UPDATE balance_events SET deletedAt = :at WHERE id = :id")
    suspend fun softDelete(id: Long, at: Long)
}

@Dao
interface ReviewDao {
    @Query("SELECT * FROM reviews ORDER BY start DESC")
    fun observeAll(): Flow<List<Review>>

    @Query("SELECT * FROM reviews WHERE period = :period AND start = :start")
    suspend fun find(period: ReviewPeriod, start: LocalDate): Review?

    @Query("SELECT * FROM reviews WHERE period = :period AND start >= :from AND start < :to AND submittedAt IS NOT NULL ORDER BY start")
    suspend fun submittedBetween(period: ReviewPeriod, from: LocalDate, to: LocalDate): List<Review>

    @Query("SELECT * FROM reviews WHERE period = :period AND submittedAt IS NOT NULL ORDER BY start DESC LIMIT 1")
    fun observeLatestSubmitted(period: ReviewPeriod): Flow<Review?>

    @Query("SELECT * FROM reviews WHERE id = :id")
    fun observe(id: Long): Flow<Review?>

    @Query("SELECT * FROM reviews")
    suspend fun all(): List<Review>

    @Upsert
    suspend fun upsert(review: Review): Long
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage_records WHERE at >= :from ORDER BY at")
    fun observeSince(from: Long): Flow<List<UsageRecord>>

    @Query("SELECT COALESCE(SUM(costCny), 0) FROM usage_records WHERE at >= :from")
    suspend fun costSince(from: Long): Double

    @Query("SELECT * FROM usage_records WHERE at >= :from")
    suspend fun since(from: Long): List<UsageRecord>

    @Insert
    suspend fun insert(record: UsageRecord)
}

@Dao
interface ChecklistDao {
    @Query("SELECT * FROM pillars ORDER BY `order`")
    fun observePillars(): Flow<List<Pillar>>

    @Query("SELECT * FROM checklist_questions ORDER BY pillarId, `order`")
    fun observeQuestions(): Flow<List<ChecklistQuestion>>

    @Query("SELECT * FROM pillars ORDER BY `order`")
    suspend fun pillars(): List<Pillar>

    @Query("SELECT * FROM checklist_questions ORDER BY pillarId, `order`")
    suspend fun questions(): List<ChecklistQuestion>

    @Insert
    suspend fun insertPillar(pillar: Pillar): Long

    @Update
    suspend fun updatePillar(pillar: Pillar)

    @Query("DELETE FROM pillars WHERE id = :id")
    suspend fun deletePillar(id: Long)

    @Insert
    suspend fun insertQuestion(question: ChecklistQuestion): Long

    @Update
    suspend fun updateQuestion(question: ChecklistQuestion)

    @Query("DELETE FROM checklist_questions WHERE id = :id")
    suspend fun deleteQuestion(id: Long)

    @Query("DELETE FROM pillars")
    suspend fun clear()
}

@Dao
interface CommitDao {
    @Query("SELECT * FROM commits WHERE trackId = :trackId AND committedAt >= :from AND committedAt < :to ORDER BY committedAt")
    suspend fun forTrackBetween(trackId: Long, from: Long, to: Long): List<GitCommit>

    @Query("SELECT * FROM commits WHERE committedAt >= :from AND committedAt < :to ORDER BY committedAt")
    fun observeBetween(from: Long, to: Long): Flow<List<GitCommit>>

    @Query("SELECT * FROM commits WHERE committedAt >= :from AND committedAt < :to ORDER BY committedAt")
    suspend fun between(from: Long, to: Long): List<GitCommit>

    @Query("SELECT * FROM commits")
    suspend fun all(): List<GitCommit>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(commits: List<GitCommit>)

    @Query("UPDATE commits SET logId = :logId WHERE sha IN (:shas)")
    suspend fun markLogged(shas: List<String>, logId: Long)
}
