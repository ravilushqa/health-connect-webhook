package com.ravil.healthconnect.nutrition.sync

import android.content.Context
import com.ravil.healthconnect.nutrition.bookmarks.InsertBookmarks
import com.ravil.healthconnect.nutrition.crypto.TokenStore
import com.ravil.healthconnect.nutrition.data.SyncResult
import com.ravil.healthconnect.nutrition.health.HealthConnectWriter
import com.ravil.healthconnect.nutrition.network.NutritionApiClient
import com.ravil.healthconnect.nutrition.prefs.NutritionPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NutritionSyncManager(private val context: Context) {

    private val prefs = NutritionPreferences(context)
    private val tokenStore = TokenStore(context)
    private val writer = HealthConnectWriter(context)

    /**
     * Single process-wide lock — prevents concurrent syncs from worker and manual trigger.
     * Bookmarks are durable: they are persisted to disk before each ack attempt so that a
     * crash between write and ack is recoverable on the next run (AckOnly path).
     */
    suspend fun performSync(): SyncResult = syncMutex.withLock { performSyncLocked() }

    private suspend fun performSyncLocked(): SyncResult {
        val baseUrl = prefs.getBaseUrl() ?: return fail("no_base_url")
        if (!NutritionApiClient.isCanonicalHttpsUrl(baseUrl)) return fail("invalid_url")

        val token = tokenStore.getToken() ?: return fail("no_token")

        if (!writer.isAvailable()) return fail("hc_not_available")
        if (!writer.hasWritePermission()) return fail("missing_write_nutrition_permission")

        // Pairing reset: URL change means a different server/account — invalidate bookmarks
        val lastSyncUrl = prefs.getLastSyncBaseUrl()
        if (lastSyncUrl != null && lastSyncUrl != baseUrl) {
            prefs.saveBookmarks(emptyMap())
        }
        prefs.setLastSyncBaseUrl(baseUrl)

        val apiClient = NutritionApiClient(baseUrl, token)
        val records = apiClient.fetchQueue().getOrElse { return fail("fetch_error") }

        prefs.setPendingCount(records.size)
        val bookmarks = InsertBookmarks.fromMap(prefs.getBookmarks())

        val result = executeSyncCycle(
            records = records,
            bookmarks = bookmarks,
            transport = apiClient,
            hcWriter = writer,
            onBookmarkPersist = { updatedBm -> prefs.saveBookmarks(updatedBm.toMap()) },
        )

        prefs.addApplied(result.applied)
        prefs.setPendingCount(result.errors + result.ackFailed)
        prefs.setLastSyncErrorCount(result.errors)
        val msg = if (result.errors == 0 && result.ackFailed == 0) "ok"
        else "ok_errors=${result.errors}_ackfail=${result.ackFailed}"
        prefs.setLastSyncMessage(msg)

        return SyncResult.Success(
            applied = result.applied,
            errors = result.errors,
            ackFailed = result.ackFailed,
            pending = result.errors + result.ackFailed,
        )
    }

    private fun fail(reason: String): SyncResult.Error {
        prefs.setLastSyncMessage("error:$reason")
        return SyncResult.Error(reason)
    }

    companion object {
        internal val syncMutex = Mutex()
    }
}
