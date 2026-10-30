package com.khoryz666.waypoint.backup

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.Instant

data class BackupFile(val id: String, val name: String, val createdAt: Long, val size: Long)

/**
 * Retention (spec 9.3): the newest backup of each of the last 7 days that have one, plus the first
 * backup of each of the last 12 months that have one. Everything else is returned for deletion.
 */
fun backupsToDelete(files: List<BackupFile>, zone: ZoneId): List<BackupFile> {
    fun date(f: BackupFile): LocalDate = Instant.ofEpochMilli(f.createdAt).atZone(zone).toLocalDate()
    val daily = files.groupBy(::date).toSortedMap(reverseOrder()).values.take(7).map { day -> day.maxBy { it.createdAt } }
    val monthly = files.groupBy { YearMonth.from(date(it)) }.toSortedMap(reverseOrder()).values.take(12).map { month -> month.minBy { it.createdAt } }
    val keep = (daily + monthly).map { it.id }.toSet()
    return files.filter { it.id !in keep }
}
