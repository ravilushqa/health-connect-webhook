package com.ravil.healthconnect.nutrition.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ravil.healthconnect.nutrition.data.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class NutritionSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val syncResult = NutritionSyncManager(applicationContext).performSync()
            when (syncResult) {
                is SyncResult.Success -> Result.success()
                is SyncResult.Error -> when (syncResult.reason) {
                    "no_base_url", "url_not_https", "no_token",
                    "hc_not_available", "missing_write_nutrition_permission" ->
                        Result.failure()
                    else -> Result.retry()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        }
    }
}
