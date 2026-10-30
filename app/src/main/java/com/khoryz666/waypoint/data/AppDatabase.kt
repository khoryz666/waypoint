package com.khoryz666.waypoint.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        Track::class, TrackUnit::class, LogEntry::class, Feedback::class, NextStep::class, Session::class,
        DayType::class, DayAssignment::class, SleepRecord::class, BalanceEvent::class, Review::class,
        UsageRecord::class, Pillar::class, ChecklistQuestion::class, GitCommit::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun units(): UnitDao
    abstract fun logs(): LogDao
    abstract fun feedback(): FeedbackDao
    abstract fun nextSteps(): NextStepDao
    abstract fun sessions(): SessionDao
    abstract fun dayTypes(): DayTypeDao
    abstract fun sleep(): SleepDao
    abstract fun balance(): BalanceDao
    abstract fun reviews(): ReviewDao
    abstract fun usage(): UsageDao
    abstract fun checklist(): ChecklistDao
    abstract fun commits(): CommitDao
    abstract fun restore(): RestoreDao

    companion object {
        const val NAME = "waypoint.db"

        /** Every migration, oldest first. Add one per schema version and a test in MigrationTest. */
        val MIGRATIONS = arrayOf<androidx.room.migration.Migration>()

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
