package com.khoryz666.waypoint.timer

import com.khoryz666.waypoint.data.AppDatabase
import com.khoryz666.waypoint.data.Session
import com.khoryz666.waypoint.domain.AppClock
import com.khoryz666.waypoint.domain.MINUTE_MS
import com.khoryz666.waypoint.domain.SessionCategory

/** What the notification and the app can start. */
sealed interface TimerTarget {
    data class Focus(val trackId: Long) : TimerTarget
    data object Joy : TimerTarget
}

/** Prompts the tracker posts after a session ends. */
interface TimerPrompts {
    /** "做了什么?" after a Focus session of 10+ minutes (spec 5.5). */
    fun askWhatWasDone(session: Session, trackTitle: String)
}

/**
 * Starts, stops and switches sessions. All state lives in the database: a running session is a
 * row with no end time, so tracking survives process death and reboots (spec 5.5, v2 design).
 */
class Tracker(private val db: AppDatabase, private val clock: AppClock, private val prompts: TimerPrompts) {

    suspend fun switchTo(target: TimerTarget) {
        val running = db.sessions().running()
        if (running != null && running.matches(target)) return
        val ended = when (target) {
            is TimerTarget.Focus -> db.sessions().switchTo(SessionCategory.FOCUS, target.trackId, clock.now())
            TimerTarget.Joy -> db.sessions().switchTo(SessionCategory.JOY, null, clock.now())
        }
        ended?.let { prompt(it) }
    }

    suspend fun stop() {
        db.sessions().switchTo(null, null, clock.now())?.let { prompt(it) }
    }

    private suspend fun prompt(ended: Session) {
        if (ended.category != SessionCategory.FOCUS || ended.trackId == null) return
        if (ended.durationMs(clock.now()) < ASK_AFTER_MS) return
        val track = db.tracks().get(ended.trackId) ?: return
        prompts.askWhatWasDone(ended, track.title)
    }

    private fun Session.matches(target: TimerTarget) = when (target) {
        is TimerTarget.Focus -> category == SessionCategory.FOCUS && trackId == target.trackId
        TimerTarget.Joy -> category == SessionCategory.JOY
    }

    companion object {
        const val ASK_AFTER_MS = 10 * MINUTE_MS
    }
}
