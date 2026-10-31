package com.khoryz666.waypoint.timer

import com.khoryz666.waypoint.data.AppDatabase
import com.khoryz666.waypoint.data.Session
import com.khoryz666.waypoint.domain.AppClock
import com.khoryz666.waypoint.domain.MINUTE_MS

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

    suspend fun switchTo(trackId: Long) {
        if (db.sessions().running()?.trackId == trackId) return
        db.sessions().switchTo(trackId, clock.now())?.let { ended(it) }
    }

    suspend fun stop() {
        db.sessions().switchTo(null, clock.now())?.let { ended(it) }
    }

    private suspend fun ended(session: Session) {
        // Under a minute is a mis-tap or a quick switch; keeping it would only list "0m".
        if (session.durationMs(clock.now()) < MINUTE_MS) return db.sessions().deleteFinished(session.id)
        if (session.trackId == null) return
        if (session.durationMs(clock.now()) < ASK_AFTER_MS) return
        val track = db.tracks().get(session.trackId) ?: return
        prompts.askWhatWasDone(session, track.title)
    }

    companion object {
        const val ASK_AFTER_MS = 10 * MINUTE_MS
    }
}
