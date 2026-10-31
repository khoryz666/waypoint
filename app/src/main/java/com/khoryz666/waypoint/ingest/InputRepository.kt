package com.khoryz666.waypoint.ingest

import android.net.Uri
import androidx.room.withTransaction
import com.khoryz666.waypoint.data.AppDatabase
import com.khoryz666.waypoint.data.BalanceEvent
import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.Session
import com.khoryz666.waypoint.data.SettingsStore
import com.khoryz666.waypoint.data.SleepRecord
import com.khoryz666.waypoint.domain.AppClock
import com.khoryz666.waypoint.domain.LogKind
import com.khoryz666.waypoint.domain.ParseStatus
import com.khoryz666.waypoint.domain.SleepSource
import com.khoryz666.waypoint.domain.Structured
import com.khoryz666.waypoint.domain.UnitStatus
import com.khoryz666.waypoint.domain.markDone
import com.khoryz666.waypoint.domain.parseMoneyShortcut
import com.khoryz666.waypoint.domain.toRecord

/** Something that runs the parse queue: WorkManager in the app, a counter in tests. */
fun interface ParseScheduler {
    fun schedule()
}

/** How a confirmed sleep night should treat an existing record for the same date (spec 6.2). */
enum class SleepConflict { KEEP_OLD, USE_NEW }

/**
 * The universal input (spec 5.2): writes the raw entry first so nothing typed is lost, then
 * queues the parse. Money shortcuts skip the LLM entirely. Confirming applies the structured
 * result to units, sessions, sleep and balance.
 */
class InputRepository(
    private val db: AppDatabase,
    private val images: ImageStore,
    private val settings: SettingsStore,
    private val scheduler: ParseScheduler,
    private val clock: AppClock,
) {
    /** Result of a submit: a log waiting for its card, or a balance entry saved straight away. */
    sealed interface Submitted {
        data class Queued(val logId: Long) : Submitted
        data class Balance(val logId: Long) : Submitted
    }

    suspend fun submit(text: String, imageUris: List<Uri>, trackId: Long?, sessionId: Long? = null, occurredAt: Long? = null): Submitted {
        val now = clock.now()
        if (imageUris.isEmpty()) {
            parseMoneyShortcut(text, settings.current().profile.currency)?.let { m ->
                val structured = Structured.Balance(m.kind, m.amountMinor, m.note)
                val id = db.withTransaction {
                    db.balance().insert(BalanceEvent(occurredAt = now, kind = m.kind, amount = m.amountMinor, note = m.note))
                    db.logs().insert(LogEntry(kind = LogKind.BALANCE, occurredAt = now, createdAt = now, rawText = text, structured = structured, parseStatus = ParseStatus.CONFIRMED))
                }
                return Submitted.Balance(id)
            }
        }
        val paths = imageUris.take(MAX_IMAGES).mapNotNull(images::import)
        val id = db.logs().insert(
            LogEntry(trackId = trackId, occurredAt = occurredAt ?: now, createdAt = now, rawText = text.trim().ifEmpty { null }, rawImageUris = paths, sessionId = sessionId),
        )
        scheduler.schedule()
        return Submitted.Queued(id)
    }

    /** "Edit raw": puts corrected text back through the parser. */
    suspend fun reparse(log: LogEntry, text: String, trackId: Long?) {
        db.logs().update(log.copy(rawText = text, trackId = trackId, structured = null, parseStatus = ParseStatus.PENDING, parseError = null))
        scheduler.schedule()
    }

    suspend fun discard(log: LogEntry) {
        db.logs().update(log.copy(deletedAt = clock.now()))
        images.delete(log.rawImageUris)
    }

    /** Undo for a balance shortcut or any confirmed log: soft delete; stats recompute from rows. */
    suspend fun delete(log: LogEntry) = db.withTransaction {
        db.logs().update(log.copy(deletedAt = clock.now()))
        if (log.structured is Structured.Balance) {
            db.balance().live().lastOrNull { it.occurredAt == log.occurredAt || it.occurredAt == log.createdAt }?.let { db.balance().softDelete(it.id, clock.now()) }
        }
    }

    /**
     * ✓ Save: applies [structured] (as edited on the card) and marks the log CONFIRMED. Images
     * are deleted here (spec 5.3); the returned data URLs let the comment still check math work.
     */
    suspend fun confirm(log: LogEntry, structured: Structured, trackId: Long?, sleepChoices: Map<String, SleepConflict> = emptyMap()): Confirmed {
        val dataUrls = log.rawImageUris.mapNotNull(images::dataUrl)
        val now = clock.now()
        val confirmed = db.withTransaction {
            val touched = apply(structured, trackId, log.occurredAt, now, sleepChoices)
            val saved = log.copy(trackId = trackId, structured = structured, kind = Parser.kindOf(structured), unitIds = touched, parseStatus = ParseStatus.CONFIRMED, rawImageUris = emptyList())
            db.logs().update(saved)
            trackId?.let { id -> db.tracks().get(id)?.let { db.tracks().update(it.copy(logsSinceSummary = it.logsSinceSummary + 1, updatedAt = now)) } }
            saved
        }
        images.delete(log.rawImageUris)
        return Confirmed(confirmed, dataUrls)
    }

    data class Confirmed(val log: LogEntry, val imageDataUrls: List<String>)

    /** Writes the side effects of a structured log; returns the unit ids it touched. */
    private suspend fun apply(s: Structured, trackId: Long?, occurredAt: Long, now: Long, sleepChoices: Map<String, SleepConflict>): List<Long> {
        val units = db.units()
        suspend fun done(ids: List<Long>) = units.getAll(ids).filter { it.trackId == trackId }.forEach { units.update(markDone(it, occurredAt)) }
        suspend fun started(ids: List<Long>) = units.getAll(ids).filter { it.trackId == trackId && it.status == UnitStatus.TODO }.forEach { units.update(it.copy(status = UnitStatus.DOING)) }
        return when (s) {
            is Structured.Fundamental -> {
                val added = if (trackId == null) emptyList() else s.newUnits.map { title ->
                    units.insert(com.khoryz666.waypoint.data.TrackUnit(trackId = trackId, order = units.maxOrder(trackId) + 1, title = title.trim()))
                }
                // A problem the log names that isn't in the outline was worked on in that log: done.
                done(s.unitsDone + added)
                started(s.unitsStarted)
                (s.unitsDone + s.unitsStarted + added).distinct()
            }
            is Structured.Math -> { done(s.unitsDone); started(s.unitsStarted); (s.unitsDone + s.unitsStarted).distinct() }
            is Structured.Reading -> { done(s.unitsDone); s.unitsDone }
            is Structured.Project -> { done(s.milestonesDone); s.milestonesDone }
            is Structured.Sleep -> {
                s.nights.mapNotNull { it.toRecord(clock, SleepSource.SCREENSHOT) }.forEach { record -> putSleep(record, sleepChoices[record.date.toString()]) }
                emptyList()
            }
            is Structured.Balance -> {
                db.balance().insert(BalanceEvent(occurredAt = occurredAt, kind = s.kind, amount = s.amountMinor, note = s.note))
                emptyList()
            }
            is Structured.Session -> {
                db.sessions().insert(Session(trackId = trackId, startAt = s.startAt, endAt = maxOf(s.endAt, s.startAt), note = s.notes.ifEmpty { null }))
                emptyList()
            }
            else -> emptyList()
        }
    }

    private suspend fun putSleep(record: SleepRecord, choice: SleepConflict?) {
        val existing = db.sleep().onDates(listOf(record.date)).firstOrNull()
        if (existing != null && choice != SleepConflict.USE_NEW) return
        db.sleep().put(record.copy(id = existing?.id ?: 0))
    }

    /** Manual sleep entry: bed and wake time pickers for nights without a wearable (6.2). */
    suspend fun addSleep(record: SleepRecord) = putSleep(record.copy(source = SleepSource.MANUAL), SleepConflict.USE_NEW)

    private companion object {
        const val MAX_IMAGES = 7
    }
}
