package com.ravil.healthconnect.nutrition

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ravil.healthconnect.nutrition.prefs.NutritionPreferences
import com.ravil.healthconnect.nutrition.sync.NutritionSyncWorker
import java.util.concurrent.TimeUnit

class NutritionApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        rescheduleWorkerIfEnabled()
    }

    fun rescheduleWorkerIfEnabled() {
        val prefs = NutritionPreferences(this)
        val workManager = WorkManager.getInstance(this)

        if (prefs.isSyncEnabled()) {
            val intervalMin = prefs.getSyncIntervalMinutes().coerceAtLeast(15).toLong()
            val request = PeriodicWorkRequestBuilder<NutritionSyncWorker>(
                intervalMin, TimeUnit.MINUTES,
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).setInitialDelay(1, TimeUnit.MINUTES)
            .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        } else {
            workManager.cancelUniqueWork(WORK_NAME)
        }
    }

    companion object {
        const val WORK_NAME = "nutrition_sync"
    }
}
