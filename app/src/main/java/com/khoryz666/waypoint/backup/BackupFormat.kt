package com.khoryz666.waypoint.backup

import androidx.room.withTransaction
import com.khoryz666.waypoint.data.AppDatabase
import com.khoryz666.waypoint.data.BalanceEvent
import com.khoryz666.waypoint.data.ChecklistQuestion
import com.khoryz666.waypoint.data.DayAssignment
import com.khoryz666.waypoint.data.DayType
import com.khoryz666.waypoint.data.Feedback
import com.khoryz666.waypoint.data.GitCommit
import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.NextStep
import com.khoryz666.waypoint.data.Pillar
import com.khoryz666.waypoint.data.Review
import com.khoryz666.waypoint.data.Session
import com.khoryz666.waypoint.data.SleepRecord
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.data.TrackUnit
import com.khoryz666.waypoint.data.UsageRecord
import com.khoryz666.waypoint.domain.WaypointJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** Bump with every export shape change, and add a migration in [BackupFormat.migrate]. */
const val BACKUP_SCHEMA_VERSION = 1

@Serializable
data class Tables(
    val tracks: List<Track> = emptyList(),
    val units: List<TrackUnit> = emptyList(),
    val logs: List<LogEntry> = emptyList(),
    val feedback: List<Feedback> = emptyList(),
    val nextSteps: List<NextStep> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val dayTypes: List<DayType> = emptyList(),
    val dayAssignments: List<DayAssignment> = emptyList(),
    val sleep: List<SleepRecord> = emptyList(),
    val balance: List<BalanceEvent> = emptyList(),
    val reviews: List<Review> = emptyList(),
    val usage: List<UsageRecord> = emptyList(),
    val pillars: List<Pillar> = emptyList(),
    val questions: List<ChecklistQuestion> = emptyList(),
    val commits: List<GitCommit> = emptyList(),
)

/** The versioned export document (spec 9.2). Secrets are never part of it (spec 9.1). */
@Serializable
data class BackupDocument(val schemaVersion: Int, val exportedAt: Long, val appVersion: String, val tables: Tables)

class NewerBackup(val version: Int) : Exception("Backup schema $version is newer than this app ($BACKUP_SCHEMA_VERSION); update the app")

object BackupFormat {
    suspend fun export(db: AppDatabase, now: Long, appVersion: String): ByteArray {
        val usageSince = now - 396L * 24 * 3600_000 // 13 months
        val tables = db.withTransaction {
            Tables(
                tracks = db.tracks().all(), units = db.units().all(), logs = db.logs().all(), feedback = db.feedback().all(),
                nextSteps = db.nextSteps().all(), sessions = db.sessions().all(), dayTypes = db.dayTypes().all(),
                dayAssignments = db.dayTypes().allAssignments(), sleep = db.sleep().all(), balance = db.balance().all(),
                reviews = db.reviews().all(), usage = db.usage().since(usageSince), pillars = db.checklist().pillars(),
                questions = db.checklist().questions(), commits = db.commits().all(),
            )
        }
        return WaypointJson.encodeToString(BackupDocument.serializer(), BackupDocument(BACKUP_SCHEMA_VERSION, now, appVersion, tables)).toByteArray()
    }

    /** Parses and migrates forward; refuses a document from a newer app (spec 9.5). */
    fun decode(bytes: ByteArray): BackupDocument {
        val text = bytes.decodeToString()
        val version = WaypointJson.parseToJsonElement(text).let { (it as JsonObject)["schemaVersion"]!!.jsonPrimitive.int }
        if (version > BACKUP_SCHEMA_VERSION) throw NewerBackup(version)
        return WaypointJson.decodeFromString(BackupDocument.serializer(), migrate(text, version))
    }

    /** Rewrites older export JSON to the current shape, one version at a time. */
    fun migrate(json: String, from: Int): String {
        var text = json
        for (v in from until BACKUP_SCHEMA_VERSION) {
            text = when (v) {
                else -> text
            }
        }
        return text
    }

    /** Replaces every table with the backup's rows in one transaction. */
    suspend fun import(db: AppDatabase, doc: BackupDocument) = db.withTransaction {
        val t = doc.tables
        val r = db.restore()
        r.clearAll()
        r.tracks(t.tracks); r.units(t.units); r.logs(t.logs); r.feedback(t.feedback); r.nextSteps(t.nextSteps)
        r.sessions(t.sessions); r.dayTypes(t.dayTypes); r.dayAssignments(t.dayAssignments); r.sleep(t.sleep)
        r.balance(t.balance); r.reviews(t.reviews); r.usage(t.usage); r.pillars(t.pillars); r.questions(t.questions); r.commits(t.commits)
    }
}
