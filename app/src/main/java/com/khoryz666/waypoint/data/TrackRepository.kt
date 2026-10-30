package com.khoryz666.waypoint.data

import androidx.room.withTransaction
import com.khoryz666.waypoint.domain.AppClock
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.domain.MAX_ACTIVE_TRACKS
import com.khoryz666.waypoint.domain.NextStepStatus
import com.khoryz666.waypoint.domain.TrackStatus
import com.khoryz666.waypoint.domain.UnitStatus
import com.khoryz666.waypoint.domain.ValidDraft

/** Result of trying to activate a track: the 9th activation asks the user to pause one (spec 3). */
sealed interface Activation {
    data object Done : Activation
    data class TooMany(val active: List<Track>) : Activation
}

/** Track lifecycle (spec 3): create from a handoff draft, activate, pause, complete, abort, succeed. */
class TrackRepository(private val db: AppDatabase, private val clock: AppClock) {
    private val tracks = db.tracks()
    private val units = db.units()

    val all = tracks.observeAll()
    val active = tracks.observeActive()

    fun observe(id: Long) = tracks.observe(id)
    fun observeUnits(id: Long) = units.observeFor(id)

    suspend fun createFromDraft(draft: ValidDraft, predecessorId: Long? = null, repos: List<String> = emptyList()): Long = db.withTransaction {
        val now = clock.now()
        val id = tracks.insert(
            Track(
                domain = draft.domain, title = draft.title, initialState = draft.initialState, goal = draft.goal,
                goalCriteria = draft.goalCriteria, targetDate = draft.targetDate, planNotes = draft.planNotes,
                predecessorId = predecessorId, githubRepos = repos.takeIf { draft.domain == Domain.PROJECT }.orEmpty(),
                createdAt = now, updatedAt = now,
            ),
        )
        units.insertAll(draft.units.mapIndexed { i, title -> TrackUnit(trackId = id, order = i, title = title) })
        id
    }

    suspend fun activate(id: Long): Activation {
        val active = tracks.active()
        if (active.none { it.id == id } && active.size >= MAX_ACTIVE_TRACKS) return Activation.TooMany(active)
        edit(id) { it.copy(status = TrackStatus.ACTIVE, pauseReason = null) }
        return Activation.Done
    }

    suspend fun pause(id: Long, reason: String?) = edit(id) { it.copy(status = TrackStatus.PAUSED, pauseReason = reason?.ifBlank { null }) }

    suspend fun complete(id: Long, finalState: String) {
        require(finalState.isNotBlank()) { "finalState is required" }
        edit(id) { it.copy(status = TrackStatus.COMPLETED, finalState = finalState.trim(), endedAt = clock.now()) }
        db.nextSteps().setStatus(id, NextStepStatus.OPEN, NextStepStatus.DROPPED)
    }

    suspend fun abort(id: Long, reason: String) {
        require(reason.isNotBlank()) { "endReason is required" }
        edit(id) { it.copy(status = TrackStatus.ABORTED, endReason = reason.trim(), endedAt = clock.now()) }
        db.nextSteps().setStatus(id, NextStepStatus.OPEN, NextStepStatus.DROPPED)
    }

    /**
     * A revision track (spec 6.5): a new DRAFT after a completed fundamentals track whose outline
     * is only the chosen units (default: the WEAK ones).
     */
    suspend fun createRevision(predecessor: Track, unitIds: List<Long>): Long = db.withTransaction {
        val now = clock.now()
        val chosen = units.forTrack(predecessor.id).filter { it.id in unitIds }
        val id = tracks.insert(
            Track(
                domain = predecessor.domain, title = predecessor.title + " · revision", initialState = predecessor.finalState.orEmpty(),
                goal = predecessor.goal, goalCriteria = predecessor.goalCriteria.map { it.copy(met = false) }, planNotes = predecessor.planNotes,
                predecessorId = predecessor.id, rollingSummary = predecessor.rollingSummary, createdAt = now, updatedAt = now,
            ),
        )
        units.insertAll(chosen.mapIndexed { i, u -> TrackUnit(trackId = id, order = i, title = u.title) })
        id
    }

    suspend fun update(track: Track) = tracks.update(track.copy(updatedAt = clock.now()))

    suspend fun addUnit(trackId: Long, title: String): Long =
        units.insert(TrackUnit(trackId = trackId, order = units.maxOrder(trackId) + 1, title = title.trim()))

    suspend fun setUnitStatus(unit: TrackUnit, status: UnitStatus) =
        units.update(if (status == UnitStatus.DONE) com.khoryz666.waypoint.domain.markDone(unit, clock.now()) else unit.copy(status = status))

    suspend fun renameUnit(unit: TrackUnit, title: String) = units.update(unit.copy(title = title.trim()))
    suspend fun deleteUnit(unit: TrackUnit) = units.delete(unit.id)

    suspend fun setCriterionMet(track: Track, index: Int, met: Boolean) =
        update(track.copy(goalCriteria = track.goalCriteria.mapIndexed { i, c -> if (i == index) c.copy(met = met) else c }))

    private suspend fun edit(id: Long, change: (Track) -> Track) {
        val track = tracks.get(id) ?: return
        tracks.update(change(track).copy(updatedAt = clock.now()))
    }
}
