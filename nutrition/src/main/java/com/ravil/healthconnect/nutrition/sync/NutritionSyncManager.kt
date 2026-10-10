package com.ravil.healthconnect.nutrition.sync

import android.content.Context
import com.ravil.healthconnect.nutrition.bookmarks.InsertBookmarks
import com.ravil.healthconnect.nutrition.crypto.TokenStore
import com.ravil.healthconnect.nutrition.crypto.TokenStorePort
import com.ravil.healthconnect.nutrition.data.SyncResult
import com.ravil.healthconnect.nutrition.health.HealthConnectWriter
import com.ravil.healthconnect.nutrition.health.HcWriterPort
import com.ravil.healthconnect.nutrition.network.NutritionApiClient
import com.ravil.healthconnect.nutrition.prefs.NutritionPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Immutable settings update ticket created by [beginSettingsUpdate].
 *
 * The generation is snapshotted at the moment [beginSettingsUpdate] increments
 * [consentGeneration]. [applySettings] rejects any ticket whose generation no longer
 * matches the current generation — meaning a newer settings request was initiated after
 * this ticket was issued.
 */
data class SettingsRequest internal constructor(
    internal val generation: Long,
    val baseUrl: String,
    val tokenInput: String?,
    val syncEnabled: Boolean,
    val intervalMinutes: Int,
)

class NutritionSyncManager internal constructor(
    private val prefs: NutritionPreferences,
    private val tokenStore: TokenStorePort,
    private val writer: HcWriterPort,
    /** Factory for the HTTP transport; default creates a NutritionApiClient. Injectable for tests. */
    private val transportFactory: (baseUrl: String, token: String) -> Transport = { url, tok ->
        NutritionApiClient(url, tok)
    },
) {
    constructor(context: Context) : this(
        NutritionPreferences(context),
        TokenStore(context),
        HealthConnectWriter(context),
    )

    /**
     * Starts a sync cycle.
     *
     * Returns [SyncResult.Error] with reason "settings_pending" immediately if a settings
     * update is in flight — no sync may run with stale or uncommitted settings.
     *
     * The consent generation is snapshotted HERE, BEFORE acquiring the mutex. This means
     * that any [beginSettingsUpdate] call that increments the generation while this call is
     * waiting for the mutex is captured in the snapshot mismatch — the cycle is rejected
     * immediately on mutex acquisition rather than running with stale consent.
     *
     * The generation is also re-checked inside the cycle at suspension points via the
     * [shouldContinue] lambda passed to [executeSyncCycle].
     */
    suspend fun performSync(): SyncResult {
        // Reject immediately if a settings update is pending — no sync with uncommitted prefs.
        if (pendingUpdate.get()) return SyncResult.Error("settings_pending")
        val myGeneration = consentGeneration.get()   // snapshot BEFORE mutex wait
        return syncMutex.withLock { performSyncLocked(myGeneration) }
    }

    /**
     * Applies a [SettingsRequest] created by [beginSettingsUpdate] under the sync mutex.
     *
     * Rejects stale generations (a newer request was initiated). On rejection the pending
     * flag is left true — only the latest successful request clears it.
     *
     * Write ordering:
     *  1. If enabling sync, validate canonical URL and available token.
     *  2. If a new token is supplied, persist it FIRST. Token failure aborts — settings
     *     are never written when the token cannot be durably stored.
     *  3. Compute settings-token binding fingerprint from URL + active token.
     *  4. Persist settings+fingerprint atomically. On failure, best-effort restore the
     *     previous token and stay fail-closed ([settingsDurable]=false → no sync).
     *  5. Only on complete success: clear [pendingUpdate].
     */
    suspend fun applySettings(request: SettingsRequest): Boolean {
        var allOk = false
        syncMutex.withLock {
            // Reject stale tickets. The latest request's coroutine will clear pending on success.
            if (consentGeneration.get() != request.generation) return@withLock

            val trimmedUrl = request.baseUrl.trim()
            val enabled = request.syncEnabled
            val interval = request.intervalMinutes
            val tokenInput = request.tokenInput?.takeIf { it.isNotBlank() }

            val existingToken = tokenStore.getToken()
            val activeToken: String? = tokenInput ?: existingToken

            // Enabling requires a valid canonical HTTPS URL and a nonblank token.
            if (enabled) {
                if (!NutritionApiClient.isCanonicalHttpsUrl(trimmedUrl)) return@withLock
                if (activeToken.isNullOrBlank()) return@withLock
            }

            // Step 1: persist new token first (if provided).
            val oldToken = existingToken
            var tokenPersisted = false
            if (tokenInput != null) {
                val tokenOk = tokenStore.setToken(tokenInput)
                if (!tokenOk) return@withLock   // token failure: do NOT write settings
                tokenPersisted = true
            }

            // Step 2: compute binding fingerprint from URL + resolved token.
            val resolvedToken: String? = if (tokenInput != null) tokenInput else existingToken
            val fingerprint: String? = if (trimmedUrl.isNotBlank() && resolvedToken != null) {
                computePairingFingerprint(trimmedUrl, resolvedToken)
            } else null

            // Step 3: persist settings+fingerprint atomically.
            val settingsOk = prefs.saveSettings(trimmedUrl, enabled, interval, fingerprint)
            if (!settingsOk) {
                // Best-effort: restore previous token so the old binding stays in place.
                if (tokenPersisted) {
                    if (oldToken != null) tokenStore.setToken(oldToken) else tokenStore.clearToken()
                }
                // settingsDurable=false (set by saveSettings failure) → isSyncEnabled()=false.
                return@withLock
            }

            // Re-check generation atomically with clearing pending. A [beginSettingsUpdate]
            // call issued during the token/settings disk IO above changes the generation;
            // this lock ensures we see the final value and never clear pending for a ticket
            // that is no longer the latest.
            synchronized(consentLock) {
                if (consentGeneration.get() == request.generation) {
                    allOk = true
                    pendingUpdate.set(false)
                }
                // else: newer ticket arrived during IO — allOk stays false, pending stays true
            }
        }
        return allOk
    }

    /**
     * Convenience wrapper: creates a settings ticket and immediately applies it.
     * Callers that need to invalidate consent synchronously before a coroutine dispatch
     * (e.g. UI Save button) should call [beginSettingsUpdate] + [applySettings] directly.
     */
    suspend fun updateSettings(
        baseUrl: String,
        tokenInput: String?,
        syncEnabled: Boolean,
        intervalMinutes: Int,
    ): Boolean {
        val request = beginSettingsUpdate(baseUrl, tokenInput, syncEnabled, intervalMinutes)
        return applySettings(request)
    }

    private suspend fun performSyncLocked(myGeneration: Long): SyncResult {
        // Re-check pending inside mutex in case a beginSettingsUpdate fired between the
        // outer check and mutex acquisition.
        if (pendingUpdate.get()) return fail("settings_pending")

        // Reject stale request ticket immediately.
        if (consentGeneration.get() != myGeneration) return fail("consent_revoked")

        if (!prefs.isSyncEnabled()) return fail("sync_disabled")

        val baseUrl = prefs.getBaseUrl() ?: return fail("no_base_url")
        if (!NutritionApiClient.isCanonicalHttpsUrl(baseUrl)) return fail("invalid_url")

        val token = tokenStore.getToken() ?: return fail("no_token")

        // Verify durable settings-token binding before any network or HC operation.
        // Missing fingerprint (no accepted saveSettings yet) or mismatch (URL/token changed
        // without re-saving settings) both block the cycle.
        val settingsFp = prefs.getSettingsTokenFingerprint() ?: return fail("no_settings_binding")
        val currentFp = computePairingFingerprint(baseUrl, token)
        if (settingsFp != currentFp) return fail("settings_binding_mismatch")

        if (!writer.isAvailable()) return fail("hc_not_available")
        if (!writer.hasWritePermission()) return fail("missing_write_nutrition_permission")

        // Post-suspension consent check: catch any beginSettingsUpdate pre-mutex increment that
        // fired during the hasWritePermission() suspension above.
        if (consentGeneration.get() != myGeneration) return fail("consent_revoked")

        // Pairing reset: clear bookmarks when URL/token changes or when durability flags
        // indicate a prior commit failed and the persisted state is uncertain.
        val fingerprint = computePairingFingerprint(baseUrl, token)
        val storedFingerprint = prefs.getPairingFingerprint()
        val pairingUncertain = !prefs.fingerprintDurable || !prefs.bookmarksDurable

        if (pairingUncertain || (storedFingerprint != null && storedFingerprint != fingerprint)) {
            if (!prefs.savePairingReset(fingerprint, emptyMap())) {
                return fail("pairing_reset_persist_failed")
            }
        } else if (storedFingerprint == null) {
            if (!prefs.savePairingReset(fingerprint, emptyMap())) {
                return fail("fingerprint_persist_failed")
            }
        }

        val transport = transportFactory(baseUrl, token)
        val records = transport.fetchQueue().getOrElse { return fail("fetch_error") }

        prefs.setPendingCount(records.size)
        val bookmarks = InsertBookmarks.fromMap(prefs.getBookmarks())

        val result = executeSyncCycle(
            records = records,
            bookmarks = bookmarks,
            transport = transport,
            hcWriter = writer,
            onBookmarkPersist = { updatedBm -> prefs.saveBookmarks(updatedBm.toMap()) },
            shouldContinue = { consentGeneration.get() == myGeneration },
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

        /**
         * Guards the compound consent-state operations: increment+setPending in
         * [beginSettingsUpdate]/[invalidateConsent], and the generation-check+clearPending
         * finalization in [applySettings]. Never held across disk IO.
         */
        private val consentLock = Any()

        /**
         * Monotonically increasing counter. Each [beginSettingsUpdate] call increments this
         * atomically before acquiring the mutex. [performSync] snapshots the value BEFORE
         * the mutex wait; any increment that arrives while the call is queued causes an
         * immediate rejection (consent_revoked) on mutex acquisition.
         */
        internal val consentGeneration = AtomicLong(0)

        /**
         * True while a settings update is in flight (after [beginSettingsUpdate], before a
         * successful [applySettings]). [performSync] returns "settings_pending" immediately
         * when this is true, preventing sync with stale or uncommitted settings.
         */
        internal val pendingUpdate = AtomicBoolean(false)

        /**
         * Creates an immutable settings ticket, invalidates any in-flight sync, and marks
         * a settings update as pending. Must be called synchronously (e.g. on the UI thread)
         * BEFORE dispatching the IO coroutine that calls [applySettings].
         *
         * The increment and [pendingUpdate] assignment are performed atomically under
         * [consentLock] so that [applySettings]'s post-IO finalization check sees either
         * the full effect of this call or none of it — never a half-written state.
         */
        fun beginSettingsUpdate(
            baseUrl: String,
            tokenInput: String?,
            syncEnabled: Boolean,
            intervalMinutes: Int,
        ): SettingsRequest {
            return synchronized(consentLock) {
                val gen = consentGeneration.incrementAndGet()
                pendingUpdate.set(true)
                SettingsRequest(gen, baseUrl, tokenInput, syncEnabled, intervalMinutes)
            }
        }

        /**
         * Synchronously invalidates any in-flight or queued sync without acquiring the mutex.
         * Also sets [pendingUpdate] true so that any new [performSync] call is blocked until
         * the user explicitly re-saves settings via [beginSettingsUpdate]+[applySettings].
         * This prevents a disable/re-enable race from silently restoring a stale sync cycle.
         */
        fun invalidateConsent() {
            synchronized(consentLock) {
                consentGeneration.incrementAndGet()
                pendingUpdate.set(true)
            }
        }

        /** Resets all companion-level sync state. Call in test setUp for isolation. */
        internal fun resetForTesting() {
            consentGeneration.set(0)
            pendingUpdate.set(false)
        }

        internal fun computePairingFingerprint(url: String, token: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(url.toByteArray(Charsets.UTF_8))
            md.update(0.toByte())
            md.update(token.toByteArray(Charsets.UTF_8))
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
