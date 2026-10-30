package com.khoryz666.waypoint.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.khoryz666.waypoint.data.AppDatabase
import com.khoryz666.waypoint.data.BalanceEvent
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.data.TrackUnit
import com.khoryz666.waypoint.domain.BalanceKind
import com.khoryz666.waypoint.domain.DAY_MS
import com.khoryz666.waypoint.domain.Domain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class BackupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

    @After fun tearDown() = db.close()

    // Fewer iterations keep the tests fast; the format and algorithm are the same.
    private val iterations = 1_000

    @Test
    fun `encrypt then decrypt round-trips, the file starts with the magic`() {
        val key = BackupCrypto.deriveKey("correct horse battery".toCharArray(), iterations = iterations)
        val sealed = BackupCrypto.seal("hello 你好".toByteArray(), key)
        assertArrayEquals("WPB1".toByteArray(), sealed.copyOfRange(0, 4))
        assertArrayEquals(key.salt, sealed.copyOfRange(4, 20))
        assertEquals("hello 你好", BackupCrypto.open(sealed, "correct horse battery".toCharArray(), iterations).decodeToString())
    }

    @Test
    fun `a wrong passphrase fails cleanly`() {
        val sealed = BackupCrypto.seal("x".toByteArray(), BackupCrypto.deriveKey("right passphrase".toCharArray(), iterations = iterations))
        assertTrue(runCatching { BackupCrypto.open(sealed, "wrong passphrase".toCharArray(), iterations) }.exceptionOrNull() is WrongPassphrase)
        assertTrue(runCatching { BackupCrypto.open("nope".toByteArray(), "x".toCharArray(), iterations) }.exceptionOrNull() is NotABackup)
    }

    @Test
    fun `export and import restore every row with ids`() = runTest {
        val id = db.tracks().insert(Track(domain = Domain.READING, title = "Book", createdAt = 1, updatedAt = 1))
        db.units().insert(TrackUnit(trackId = id, order = 0, title = "Ch 1"))
        db.balance().insert(BalanceEvent(occurredAt = 5, kind = BalanceKind.SET, amount = 100))
        val bytes = BackupFormat.export(db, now = 10, appVersion = "3.0.0")

        val other = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        other.tracks().insert(Track(domain = Domain.MATH, title = "To be replaced", createdAt = 1, updatedAt = 1))
        BackupFormat.import(other, BackupFormat.decode(bytes))
        assertEquals(listOf("Book"), other.tracks().all().map { it.title })
        assertEquals(id, other.units().all().single().trackId)
        assertEquals(100L, other.balance().all().single().amount)
        other.close()
    }

    @Test
    fun `a backup from a newer schema is refused`() {
        val doc = """{"schemaVersion": ${BACKUP_SCHEMA_VERSION + 1}, "exportedAt": 0, "appVersion": "9", "tables": {}}"""
        assertTrue(runCatching { BackupFormat.decode(doc.toByteArray()) }.exceptionOrNull() is NewerBackup)
    }

    @Test
    fun `an older export without new tables still decodes`() {
        val doc = """{"schemaVersion": 1, "exportedAt": 0, "appVersion": "3.0.0", "tables": {"tracks": []}}"""
        assertEquals(0, BackupFormat.decode(doc.toByteArray()).tables.commits.size)
    }

    @Test
    fun `retention keeps the last 7 days and the first of the last 12 months`() {
        val zone = ZoneId.of("UTC")
        val start = LocalDateTime.of(2025, 1, 1, 12, 0).atZone(zone).toInstant().toEpochMilli()
        // Two backups a day for 500 days.
        val files = (0 until 1000).map { i -> BackupFile("f$i", "waypoint-$i", start + (i / 2) * DAY_MS + (i % 2) * 3_600_000, 1) }
        val kept = files - backupsToDelete(files, zone).toSet()
        val lastDays = files.takeLast(14).filterIndexed { i, _ -> i % 2 == 1 }
        assertTrue(kept.containsAll(lastDays))
        // The last day is 2026-05-15, so the monthly firsts (Jun 2025 – May 2026) don't overlap the last 7 days.
        assertEquals(7 + 12, kept.size)
        val firsts = kept - lastDays.toSet()
        assertTrue(firsts.all { java.time.Instant.ofEpochMilli(it.createdAt).atZone(zone).let { d -> d.dayOfMonth == 1 && d.hour == 12 } })
    }
}
