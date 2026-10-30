package com.khoryz666.waypoint.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.khoryz666.waypoint.appContainer
import com.khoryz666.waypoint.ingest.ParseOutcome
import com.khoryz666.waypoint.ingest.ParseScheduler
import java.util.concurrent.TimeUnit

/**
 * Drains the parse queue (spec 5.2): every PENDING entry gets one parse attempt. Network errors
 * retry with exponential backoff; a paused queue (no key, 401, 402) waits until settings change.
 */
class ParseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        var retry = false
        for (log in container.db.logs().pending()) {
            when (container.parser.parse(log)) {
                ParseOutcome.RETRY_LATER -> retry = true
                ParseOutcome.PAUSED -> return Result.success()
                else -> Unit
            }
        }
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "parse-queue"

        fun scheduler(context: Context) = ParseScheduler {
            val request = OneTimeWorkRequestBuilder<ParseWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // APPEND_OR_REPLACE: an entry saved while a run is in progress still gets its own run.
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
