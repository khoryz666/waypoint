package com.khoryz666.waypoint.backup

import com.khoryz666.waypoint.data.AppDatabase
import com.khoryz666.waypoint.data.SettingsStore
import com.khoryz666.waypoint.domain.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Minimum passphrase length (spec 9.4). */
const val MIN_PASSPHRASE = 12

/**
 * Encrypted backup to Drive (spec 9): export → gzip → encrypt with the cached key → upload to
 * appDataFolder → apply retention. Restore downloads, decrypts with the passphrase and imports.
 */
class BackupManager(
    private val db: AppDatabase,
    private val drive: DriveClient,
    private val settings: SettingsStore,
    private val clock: AppClock,
    private val appVersion: String,
) {
    /** Derives and caches a new key: the start of a backup chain (setup or forgotten passphrase). */
    suspend fun setPassphrase(passphrase: String) {
        require(passphrase.length >= MIN_PASSPHRASE)
        val key = withContext(Dispatchers.Default) { BackupCrypto.deriveKey(passphrase.toCharArray()) }
        settings.setBackupKey(key.encoded())
    }

    suspend fun backup(token: String) {
        val key = settings.backupKey()?.let(BackupKey::decode) ?: error("No backup passphrase set")
        val now = clock.now()
        settings.update { it.copy(backup = it.backup.copy(lastAttemptAt = now)) }
        try {
            val sealed = withContext(Dispatchers.Default) { BackupCrypto.seal(BackupFormat.export(db, now, appVersion), key) }
            drive.upload(token, fileName(now), sealed)
            val files = drive.list(token).filter { it.name.startsWith(PREFIX) }
            backupsToDelete(files, clock.zone()).forEach { runCatching { drive.delete(token, it.id) } }
            settings.update { it.copy(backup = it.backup.copy(lastSuccessAt = now, lastError = null, needsAuthorization = false)) }
        } catch (e: Exception) {
            settings.update { it.copy(backup = it.backup.copy(lastError = e.message, needsAuthorization = (e as? DriveException)?.unauthorized == true)) }
            throw e
        }
    }

    suspend fun list(token: String): List<BackupFile> = drive.list(token).filter { it.name.startsWith(PREFIX) }.sortedByDescending { it.createdAt }

    /**
     * Restores [file]: decrypts with [passphrase], refuses newer schemas, imports in one
     * transaction, and caches the key so the backup chain continues (spec 9.5).
     */
    suspend fun restore(token: String, file: BackupFile, passphrase: String) {
        val bytes = drive.download(token, file.id)
        val (doc, key) = withContext(Dispatchers.Default) {
            val key = BackupCrypto.deriveKey(passphrase.toCharArray(), BackupCrypto.salt(bytes))
            BackupFormat.decode(BackupCrypto.open(bytes, key)) to key
        }
        BackupFormat.import(db, doc)
        settings.setBackupKey(key.encoded())
    }

    private fun fileName(now: Long) = PREFIX + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC)) + ".wpb"

    companion object {
        const val PREFIX = "waypoint-"
    }
}
