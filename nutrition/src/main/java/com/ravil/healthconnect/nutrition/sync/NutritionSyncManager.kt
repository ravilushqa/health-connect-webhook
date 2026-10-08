package com.ravil.healthconnect.nutrition.sync

import android.content.Context
import com.ravil.healthconnect.nutrition.bookmarks.InsertBookmarks
import com.ravil.healthconnect.nutrition.crypto.TokenStore
import com.ravil.healthconnect.nutrition.data.SyncResult
import com.ravil.healthconnect.nutrition.health.HealthConnectWriter
import com.ravil.healthconnect.nutrition.network.NutritionApiClient
import com.ravil.healthconnect.nutrition.prefs.NutritionPreferences

class NutritionSyncManager(private val context: Context) {

    private val prefs = NutritionPreferences(context)
    private val tokenStore = TokenStore(context)
    private val writer = HealthConnectWriter(context)

    suspend fun performSync(): SyncResult {
        val baseUrl = prefs.getBaseUrl()
            ?: return fail("no_base_url")
        if (!baseUrl.startsWith("https://"))
            return fail("url_not_https")
        val token = tokenStore.getToken()
            ?: return fail("no_token")

        if (!writer.isAvailable())
            return fail("hc_not_available")
        if (!writer.hasWritePermission())
            return fail("missing_write_nutrition_permission")

        val apiClient = NutritionApiClient(baseUrl, token)
        val records = apiClient.fetchQueue().getOrElse { return fail("fetch_error") }

        prefs.setPendingCount(records.size)
        val bookmarks = InsertBookmarks.fromMap(prefs.getBookmarks())

        val plan = planSync(records, bookmarks)
        var applied = 0
        var errors = 0
        var ackFailed = 0
        var updatedBookmarks = bookmarks

        for (action in plan) {
            when (action) {
                is RecordAction.Skip -> {
                    errors++
                }

                is RecordAction.AckOnly -> {
                    val ack = apiClient.ackRecord(
                        action.record.clientRecordId,
                        action.record.clientRecordVersion,
                    )
                    if (ack.isFailure) ackFailed++
                }

                is RecordAction.WriteAndAck -> {
                    val write = writer.insertRecord(action.record)
                    if (write.isSuccess) {
                        // Persist bookmark BEFORE acking.
                        // If ack fails here, next run detects AckOnly and retries ack.
                        updatedBookmarks = updatedBookmarks.withApplied(
                            action.record.clientRecordId,
                            action.record.clientRecordVersion,
                        )
                        prefs.saveBookmarks(updatedBookmarks.toMap())
                        applied++

                        val ack = apiClient.ackRecord(
                            action.record.clientRecordId,
                            action.record.clientRecordVersion,
                        )
                        if (ack.isFailure) ackFailed++
                    } else {
                        errors++
                    }
                }
            }
        }

        prefs.addApplied(applied)
        prefs.setLastSyncErrorCount(errors)
        val msg = if (errors == 0 && ackFailed == 0) "ok" else "ok_errors=${errors}_ackfail=${ackFailed}"
        prefs.setLastSyncMessage(msg)

        return SyncResult.Success(
            applied = applied,
            errors = errors,
            ackFailed = ackFailed,
            pending = plan.count { it is RecordAction.WriteAndAck } - applied,
        )
    }

    private fun fail(reason: String): SyncResult.Error {
        prefs.setLastSyncMessage("error:$reason")
        return SyncResult.Error(reason)
    }
}
