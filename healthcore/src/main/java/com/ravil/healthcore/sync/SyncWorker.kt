package com.ravil.healthcore.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ravil.healthcore.data.AppPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Runs the enabled jobs one after the other. Shared by the periodic worker and "Sync now". */
object SyncJobs {
    private val mutex = Mutex()

    data class Outcome(val ingest: RunResult?, val meals: RunResult?) {
        val retryable: Boolean get() = ingest?.retryable == true || meals?.retryable == true
    }

    suspend fun runAll(context: Context): Outcome = mutex.withLock {
        val app = context.applicationContext
        val prefs = AppPreferences(app)
        // Meals first: they are small and the user is waiting to see them in Health Connect.
        val meals = MealRunner(app, prefs).let { if (it.isEnabled()) it.run() else null }
        val ingest = if (prefs.ingestEnabled) IngestRunner(app, prefs).run() else null
        Outcome(ingest, meals)
    }
}

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val outcome = SyncJobs.runAll(applicationContext)
        return if (outcome.retryable && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    companion object {
        private const val MAX_RETRIES = 3
    }
}

object SyncScheduler {
    const val WORK_NAME = "healthcore_sync"

    /** Schedules the periodic sync when any job is on, cancels it otherwise. */
    fun apply(context: Context) {
        val app = context.applicationContext
        val prefs = AppPreferences(app)
        val wm = WorkManager.getInstance(app)
        if (!prefs.ingestEnabled && !MealRunner(app, prefs).isEnabled()) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(prefs.intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
