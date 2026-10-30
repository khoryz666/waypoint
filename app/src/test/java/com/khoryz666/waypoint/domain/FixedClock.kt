package com.khoryz666.waypoint.domain

import java.time.LocalDateTime
import java.time.ZoneId

/** A settable clock for tests. Defaults to UTC+8 with no DST. */
class FixedClock(start: LocalDateTime, private var zone: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")) : AppClock {
    var nowMs: Long = at(start)

    override fun now() = nowMs
    override fun zone() = zone

    fun set(dateTime: LocalDateTime) {
        nowMs = at(dateTime)
    }

    fun moveTo(newZone: ZoneId) {
        zone = newZone
    }

    fun at(dateTime: LocalDateTime): Long = dateTime.atZone(zone).toInstant().toEpochMilli()
}
