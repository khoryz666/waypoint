package com.khoryz666.waypoint.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.khoryz666.waypoint.appContainer
import com.khoryz666.waypoint.backup.DriveException
import com.khoryz666.waypoint.backup.NeedsAuthorization
import com.khoryz666.waypoint.domain.HOUR_MS
import java.util.concurrent.TimeUnit

/**
 * Silent encrypted backup (spec 9.3): 10 minutes after the last write (debounced) plus daily.
 * Needs a network, not an unmetered one. A failure older than 48 h is surfaced on Today.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.appContainer
        val s = c.settings.current()
        if (!s.backup.hasKey) return Result.success()
        return try {
            c.backup.backup(c.googleAuth.token())
            Result.success()
        } catch (e: NeedsAuthorization) {
            c.settings.update { it.copy(backup = it.backup.copy(needsAuthorization = true, lastError = e.message, lastAttemptAt = c.clock.now())) }
            Result.success()
        } catch (e: DriveException) {
            if (e.unauthorized) Result.success() else retryOrNotify(c)
        } catch (e: Exception) {
            c.settings.update { it.copy(backup = it.backup.copy(lastError = e.message, lastAttemptAt = c.clock.now())) }
            retryOrNotify(c)
        }
    }

    private suspend fun retryOrNotify(c: com.khoryz666.waypoint.AppContainer): Result {
        val s = c.settings.current().backup
        if (s.lastSuccessAt != null && c.clock.now() - s.lastSuccessAt > 48 * HOUR_MS) c.notifications.backupFailed(s.lastError.orEmpty())
        return if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        private const val DEBOUNCED = "backup-after-write"
        private const val DAILY = "backup-daily"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Called on every database write; each call pushes the backup 10 minutes later. */
        fun afterWrite(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                DEBOUNCED, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BackupWorker>().setInitialDelay(10, TimeUnit.MINUTES).setConstraints(network).build(),
            )
        }

        fun now(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(DEBOUNCED, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(network).build())
        }

        fun scheduleDaily(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                DAILY, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).setConstraints(network).build(),
            )
        }
    }
}
