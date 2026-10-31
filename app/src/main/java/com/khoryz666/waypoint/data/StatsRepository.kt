package com.khoryz666.waypoint.data

import com.khoryz666.waypoint.domain.AppClock
import com.khoryz666.waypoint.domain.DayProgress
import com.khoryz666.waypoint.domain.DayTime
import com.khoryz666.waypoint.domain.Lang
import com.khoryz666.waypoint.domain.LogKind
import com.khoryz666.waypoint.domain.TargetResult
import com.khoryz666.waypoint.domain.dailyTime
import com.khoryz666.waypoint.domain.dayProgress
import com.khoryz666.waypoint.domain.dayTypeFor
import com.khoryz666.waypoint.domain.evaluate
import com.khoryz666.waypoint.domain.outcomes
import com.khoryz666.waypoint.domain.streak
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** Today's day type, targets, time and streak (spec 5.1, 5.6). */
data class DayStatus(
    val date: LocalDate,
    val now: Long,
    val dayType: DayType?,
    val time: DayTime,
    val progress: DayProgress,
    val minimum: TargetResult,
    val plan: TargetResult,
    val streak: Int,
    val running: Session?,
    val activeTracks: List<Track>,
)

/** Everything the day screen, the notification and receipts need, recomputed from rows. */
class StatsRepository(private val db: AppDatabase, private val settings: SettingsStore, private val clock: AppClock) {

    /** Re-emits on any relevant change, every [tickMs] while a session runs, and at midnight. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun today(tickMs: Long): Flow<DayStatus> =
        clock.ticks(60_000).map { clock.today() }.distinctUntilChanged().flatMapLatest { date ->
            val from = date.minusDays(STREAK_DAYS.toLong())
            val range = clock.bounds(from, date.plusDays(1))
            combine(
                db.sessions().observeEndingIn(range.start, range.end),
                db.units().observeAll(),
                db.logs().observeConfirmedIn(range.start, range.end),
                combine(db.dayTypes().observeAll(), db.dayTypes().observeAssignments(from, date.plusDays(1)), ::Pair),
                combine(db.tracks().observeActive(), settings.settings, ::Pair),
            ) { sessions, units, logs, (types, assignments), (active, s) ->
                val running = sessions.find { it.isRunning }
                running to { now: Long -> build(date, now, sessions, units, logs, types, assignments, active, s.profile.language) }
            }.flatMapLatest { (running, at) ->
                (if (running != null) clock.ticks(tickMs) else flowOf(clock.now())).map(at)
            }
        }

    suspend fun todayOnce(): DayStatus = today(Long.MAX_VALUE).first()

    /** Status of any past [date], for the daily close and reviews. */
    suspend fun dayOn(date: LocalDate): DayStatus {
        val from = date.minusDays(STREAK_DAYS.toLong())
        val range = clock.bounds(from, date.plusDays(1))
        val s = settings.current()
        return build(
            date, minOf(clock.now(), range.end - 1), db.sessions().endingIn(range.start, range.end).filter { !it.isRunning || date == clock.today() },
            db.units().all(), db.logs().confirmedIn(range.start, range.end), db.dayTypes().all(),
            db.dayTypes().assignments(from, date.plusDays(1)), db.tracks().active(), s.profile.language,
        )
    }

    private fun build(
        date: LocalDate, now: Long, sessions: List<Session>, units: List<TrackUnit>, logs: List<LogEntry>,
        types: List<DayType>, assignments: List<DayAssignment>, active: List<Track>, lang: Lang,
    ): DayStatus {
        val times = dailyTime(sessions, now, clock)
        val exerciseDays = logs.filter { it.kind == LogKind.EXERCISE }.map { clock.dateOf(it.occurredAt) }.toSet()
        val progressOf = { d: LocalDate -> dayProgress(d, times, units, exerciseDays, clock) }
        val type = dayTypeFor(date, types, assignments)
        val progress = progressOf(date)
        return DayStatus(
            date = date,
            now = now,
            dayType = type,
            time = progress.time,
            progress = progress,
            minimum = type?.let { evaluate(it.minimum, progress, active, lang) } ?: TargetResult(emptyList()),
            plan = type?.let { evaluate(it.plan, progress, active, lang) } ?: TargetResult(emptyList()),
            streak = streak(outcomes(date, STREAK_DAYS, types, assignments, progressOf, active)),
            running = sessions.find { it.isRunning },
            activeTracks = active,
        )
    }

    private companion object {
        /** How far back streaks look; long enough for any realistic streak. */
        const val STREAK_DAYS = 120
    }
}
