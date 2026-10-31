package com.khoryz666.waypoint.ingest

import com.khoryz666.waypoint.data.AppDatabase
import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.SettingsStore
import com.khoryz666.waypoint.data.StatsRepository
import com.khoryz666.waypoint.domain.AppClock
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.domain.LogKind
import com.khoryz666.waypoint.domain.Receipts
import com.khoryz666.waypoint.domain.Structured
import com.khoryz666.waypoint.domain.UnitStatus
import com.khoryz666.waypoint.domain.balanceAt
import com.khoryz666.waypoint.domain.compareBestSets
import com.khoryz666.waypoint.domain.dailyTime
import com.khoryz666.waypoint.domain.netChange
import com.khoryz666.waypoint.domain.sleepWeek
import com.khoryz666.waypoint.domain.weekStart

/** Builds the deterministic receipt for a confirmed log from local rows only (spec 5.4). */
class ReceiptBuilder(
    private val db: AppDatabase,
    private val stats: StatsRepository,
    private val settings: SettingsStore,
    private val clock: AppClock,
) {
    suspend fun receipt(log: LogEntry): String {
        val s = settings.current()
        val lang = s.profile.language
        val today = clock.today()
        val week = today.weekStart(s.profile.weekStart)
        val thisWeek = clock.bounds(week, week.plusDays(7))
        val lastWeek = clock.bounds(week.minusDays(7), week)
        val track = log.trackId?.let { db.tracks().get(it) }
        return when (val st = log.structured) {
            is Structured.Balance -> {
                val events = db.balance().live()
                val balance = balanceAt(events)
                Receipts.balance(balance, netChange(events, clock.now(), 7), netChange(events, clock.now(), 30), s.profile.currency, events.size <= 1, lang)
            }
            is Structured.Sleep -> {
                val records = db.sleep().between(week, week.plusDays(7))
                val latest = st.nights.maxByOrNull { it.date }
                Receipts.sleep(latest?.totalMin ?: 0, sleepWeek(week, records, s.profile.sleep, clock), lang)
            }
            is Structured.Session -> focusReceipt()
            is Structured.Exercise -> {
                val logsThisWeek = db.logs().confirmedIn(thisWeek.start, thisWeek.end).filter { it.kind == LogKind.EXERCISE && it.trackId == log.trackId }
                val logsLastWeek = db.logs().confirmedIn(lastWeek.start, lastWeek.end).filter { it.kind == LogKind.EXERCISE && it.trackId == log.trackId }
                val first = log.trackId?.let { db.logs().recentConfirmed(it, 2).size <= 1 } ?: true
                Receipts.exercise(logsThisWeek.size, logsLastWeek.size, compareBestSets(st, logsThisWeek, logsLastWeek), track?.goalCriteria?.firstOrNull { !it.met }, first, lang)
            }
            is Structured.Project -> projectReceipt(log)
            is Structured.Fundamental, is Structured.Math, is Structured.Reading -> {
                track ?: return focusReceipt()
                val units = db.units().forTrack(track.id)
                fun doneIn(range: com.khoryz666.waypoint.domain.EpochRange) = units.count { it.doneAt != null && it.doneAt in range }
                val doneNow = units.filter { it.id in log.unitIds && (it.status == UnitStatus.DONE || it.status == UnitStatus.WEAK) }
                val first = db.logs().recentConfirmed(track.id, 2).size <= 1
                Receipts.learning(track, units, doneNow, doneIn(thisWeek), doneIn(lastWeek), first, lang)
            }
            else -> if (track?.domain == Domain.PROJECT) projectReceipt(log) else focusReceipt()
        }
    }

    suspend fun focusReceipt(): String {
        val day = stats.dayOn(clock.today())
        return Receipts.focus(day.time.totalFocusMs, day.minimum.takeIf { it.lines.isNotEmpty() }?.met, day.streak, settings.current().profile.language)
    }

    private suspend fun projectReceipt(log: LogEntry): String {
        val track = log.trackId?.let { db.tracks().get(it) } ?: return focusReceipt()
        val s = settings.current()
        val today = clock.today()
        val week = today.weekStart(s.profile.weekStart)
        val range = clock.bounds(week, today.plusDays(1))
        val times = dailyTime(db.sessions().endingIn(range.start, range.end), clock.now(), clock)
        val todayMs = times[today]?.focusMs(track.id) ?: 0
        val weekMs = times.values.sumOf { it.focusMs(track.id) }
        val day = clock.dayBounds(today)
        val commits = db.commits().forTrackBetween(track.id, day.start, day.end).size
        val units = db.units().forTrack(track.id)
        return Receipts.project(track, todayMs, weekMs, commits, units.count { it.status == UnitStatus.DONE }, units.size, s.profile.language)
    }

}
