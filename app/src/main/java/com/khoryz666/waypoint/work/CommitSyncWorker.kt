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
import java.util.concurrent.TimeUnit

/** GitHub commit sync every 3 hours on network and at app open (spec 6.4), then the ownership check. */
class CommitSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.appContainer
        c.commits.sync()
        runCatching { c.commits.ensureOwnershipCheck() }
        return Result.success()
    }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "commit-sync", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<CommitSyncWorker>(3, TimeUnit.HOURS).setConstraints(network).build(),
            )
        }

        fun now(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("commit-sync-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<CommitSyncWorker>().setConstraints(network).build())
        }
    }
}
