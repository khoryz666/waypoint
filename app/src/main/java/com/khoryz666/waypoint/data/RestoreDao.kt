package com.khoryz666.waypoint.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Bulk writes for restoring a backup with its original ids (spec 9.5). */
@Dao
interface RestoreDao {
    @Query("DELETE FROM review_questions") suspend fun clearQuestions()
    @Query("DELETE FROM units") suspend fun clearUnits()
    @Query("DELETE FROM tracks") suspend fun clearTracks()
    @Query("DELETE FROM logs") suspend fun clearLogs()
    @Query("DELETE FROM feedback") suspend fun clearFeedback()
    @Query("DELETE FROM next_steps") suspend fun clearNextSteps()
    @Query("DELETE FROM sessions") suspend fun clearSessions()
    @Query("DELETE FROM day_types") suspend fun clearDayTypes()
    @Query("DELETE FROM day_assignments") suspend fun clearAssignments()
    @Query("DELETE FROM sleep_records") suspend fun clearSleep()
    @Query("DELETE FROM balance_events") suspend fun clearBalance()
    @Query("DELETE FROM reviews") suspend fun clearReviews()
    @Query("DELETE FROM usage_records") suspend fun clearUsage()
    @Query("DELETE FROM commits") suspend fun clearCommits()

    suspend fun clearAll() {
        clearQuestions(); clearUnits(); clearTracks(); clearLogs(); clearFeedback(); clearNextSteps()
        clearSessions(); clearDayTypes(); clearAssignments(); clearSleep(); clearBalance(); clearReviews(); clearUsage(); clearCommits()
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun tracks(rows: List<Track>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun units(rows: List<TrackUnit>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun logs(rows: List<LogEntry>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun feedback(rows: List<Feedback>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun nextSteps(rows: List<NextStep>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun sessions(rows: List<Session>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun dayTypes(rows: List<DayType>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun dayAssignments(rows: List<DayAssignment>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun sleep(rows: List<SleepRecord>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun balance(rows: List<BalanceEvent>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun reviews(rows: List<Review>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun usage(rows: List<UsageRecord>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun questions(rows: List<ReviewQuestion>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun commits(rows: List<GitCommit>)
}
