package com.ravil.healthcore.sync

import android.content.Context
import com.ravil.healthcore.core.nutrition.data.SyncResult
import com.ravil.healthcore.core.status.RunStatus
import com.ravil.healthcore.data.AppPreferences
import com.ravil.healthcore.nutrition.NutritionPreferences
import com.ravil.healthcore.nutrition.NutritionSyncManager
import java.time.Instant

/**
 * One meal write-back run (GET /nutrition/queue → Health Connect → POST /nutrition/ack),
 * done by the nutrition sync manager carried over from :nutrition unchanged.
 */
class MealRunner(private val context: Context, private val prefs: AppPreferences = AppPreferences(context)) {

    fun isEnabled(): Boolean = NutritionPreferences(context).isSyncEnabled()

    suspend fun run(): RunResult {
        val now = Instant.now()
        val result = NutritionSyncManager(context).performSync()
        val run = when (result) {
            is SyncResult.Success -> RunResult(
                RunStatus(
                    at = now,
                    code = if (result.errors == 0 && result.ackFailed == 0) "ok" else "ok_with_errors",
                    ok = result.errors == 0 && result.ackFailed == 0,
                    records = result.applied,
                    errors = result.errors + result.ackFailed,
                ),
                // A failed ack is retried by the AckOnly path of the next run.
                retryable = result.ackFailed > 0,
            )
            is SyncResult.Error -> RunResult(RunStatus(now, result.reason, ok = false), retryable = result.reason !in FINAL)
        }
        prefs.setLastRun(AppPreferences.Job.MEALS, run.status)
        return run
    }

    companion object {
        /** Errors that a retry cannot fix (the list :nutrition's worker used). */
        private val FINAL = setOf(
            "no_base_url", "invalid_url", "url_not_https", "no_token", "sync_disabled",
            "hc_not_available", "missing_write_nutrition_permission",
        )
    }
}
