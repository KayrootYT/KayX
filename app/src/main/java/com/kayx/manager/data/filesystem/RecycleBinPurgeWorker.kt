package com.kayx.manager.data.filesystem

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Daily retryable purge: expired entries are removed even if the user does not reopen KayX. */
class RecycleBinPurgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val result = RecycleBin.purgeExpired()
        if (result.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK = "kayx-recycle-bin-retention-daily"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RecycleBinPurgeWorker>(24, TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
