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
import java.security.MessageDigest

class NutritionSyncManager(private val context: Context) {

    private val prefs = NutritionPreferences(context)
    private val tokenStore = TokenStore(context)
    private val writer = HealthConnectWriter(context)

    /**
     * Single process-wide lock — prevents concurrent syncs from the background worker and any
     * manual trigger. Settings/pairing checks are also inside the lock so a URL or token change
     * made while a sync is in flight is observed only by the *next* sync, not the current one.
     *
     * isSyncEnabled is checked here for both manual and worker callers: explicit user consent
     * to write nutrition data must be active at the time of every sync.
     */
    suspend fun performSync(): SyncResult = syncMutex.withLock { performSyncLocked() }

    private suspend fun performSyncLocked(): SyncResult {
        if (!prefs.isSyncEnabled()) return fail("sync_disabled")

        val baseUrl = prefs.getBaseUrl() ?: return fail("no_base_url")
        if (!NutritionApiClient.isCanonicalHttpsUrl(baseUrl)) return fail("invalid_url")

        val token = tokenStore.getToken() ?: return fail("no_token")

        if (!writer.isAvailable()) return fail("hc_not_available")
        if (!writer.hasWritePermission()) return fail("missing_write_nutrition_permission")

        // Pairing reset: if URL or token has changed since last sync, existing bookmarks
        // belong to a different server/account combination and must be discarded.
        // The fingerprint is SHA-256(url \0 token) — one-way, never logged.
        val fingerprint = computePairingFingerprint(baseUrl, token)
        val storedFingerprint = prefs.getPairingFingerprint()
        if (storedFingerprint != null && storedFingerprint != fingerprint) {
            prefs.saveBookmarks(emptyMap())
        }
        prefs.setPairingFingerprint(fingerprint)

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

        internal fun computePairingFingerprint(url: String, token: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(url.toByteArray(Charsets.UTF_8))
            md.update(0.toByte())
            md.update(token.toByteArray(Charsets.UTF_8))
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
