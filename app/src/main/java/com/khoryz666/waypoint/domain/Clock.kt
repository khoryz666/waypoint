package com.khoryz666.waypoint.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** A half-open span of epoch milliseconds: [start, end). */
data class EpochRange(val start: Long, val end: Long) {
    operator fun contains(ms: Long) = ms in start until end
}

/**
 * Wall-clock time and the zone day boundaries are computed in, injectable so time math can be
 * tested. Every timestamp is stored as a UTC instant (epoch ms); only day boundaries use a zone.
 */
interface AppClock {
    fun now(): Long
    fun zone(): ZoneId

    fun today(): LocalDate = dateOf(now())
    fun dateOf(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone()).toLocalDate()
    fun timeOf(epochMs: Long): LocalTime = Instant.ofEpochMilli(epochMs).atZone(zone()).toLocalTime()

    /** From the start of [date] to the start of the next day. */
    fun dayBounds(date: LocalDate): EpochRange = bounds(date, date.plusDays(1))

    /** From the start of [from] to the start of [until]. */
    fun bounds(from: LocalDate, until: LocalDate): EpochRange = EpochRange(startOf(from), startOf(until))

    fun startOf(date: LocalDate): Long = date.atStartOfDay(zone()).toInstant().toEpochMilli()

    fun at(date: LocalDate, time: LocalTime): Long = date.atTime(time).atZone(zone()).toInstant().toEpochMilli()

    /** Emits [now] immediately, then every [periodMs]. */
    fun ticks(periodMs: Long): Flow<Long> = flow {
        while (true) {
            emit(now())
            delay(periodMs)
        }
    }
}

/** The first day of the week containing [this], for a week that starts on [weekStart]. */
fun LocalDate.weekStart(weekStart: DayOfWeek): LocalDate = with(TemporalAdjusters.previousOrSame(weekStart))

/** The 7 dates of the week starting on [start]. */
fun weekDates(start: LocalDate): List<LocalDate> = (0L until 7L).map(start::plusDays)
