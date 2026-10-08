package com.ravil.healthconnect.nutrition.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NutritionPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBaseUrl(): String? = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }

    fun setBaseUrl(url: String) {
        prefs.edit().putString(KEY_BASE_URL, url.trim()).apply()
    }

    fun isSyncEnabled(): Boolean = prefs.getBoolean(KEY_SYNC_ENABLED, false)

    fun setSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply()
    }

    fun getSyncIntervalMinutes(): Int = prefs.getInt(KEY_SYNC_INTERVAL, DEFAULT_INTERVAL_MIN)

    fun setSyncIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_SYNC_INTERVAL, minutes.coerceAtLeast(15)).apply()
    }

    // --- counters ---

    fun getPendingCount(): Int = prefs.getInt(KEY_PENDING, 0)

    fun setPendingCount(count: Int) {
        prefs.edit().putInt(KEY_PENDING, count.coerceAtLeast(0)).apply()
    }

    fun getAppliedTotal(): Int = prefs.getInt(KEY_APPLIED_TOTAL, 0)

    fun addApplied(count: Int) {
        val next = (getAppliedTotal() + count).coerceAtLeast(0)
        prefs.edit().putInt(KEY_APPLIED_TOTAL, next).apply()
    }

    fun getLastSyncErrorCount(): Int = prefs.getInt(KEY_LAST_ERROR, 0)

    fun setLastSyncErrorCount(count: Int) {
        prefs.edit().putInt(KEY_LAST_ERROR, count.coerceAtLeast(0)).apply()
    }

    fun getLastSyncMessage(): String = prefs.getString(KEY_LAST_MSG, "") ?: ""

    fun setLastSyncMessage(msg: String) {
        prefs.edit().putString(KEY_LAST_MSG, msg).apply()
    }

    // --- bookmarks: Map<clientRecordId, appliedVersion> ---

    fun getBookmarks(): Map<String, Int> {
        val raw = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyMap()
        return try {
            Json.decodeFromString<Map<String, Int>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Persists bookmarks synchronously (commit, not apply) so the bookmark is durably on disk
     * before the caller proceeds to send an ACK. This ensures crash-recovery correctness:
     * if the process dies between the commit and the ACK, the next run finds the bookmark and
     * retries only the ACK (AckOnly path), not the write. Called on Dispatchers.IO; blocking
     * is intentional and acceptable here.
     */
    @Suppress("CommitPrefEdits")
    fun saveBookmarks(bookmarks: Map<String, Int>) {
        prefs.edit().putString(KEY_BOOKMARKS, Json.encodeToString(bookmarks)).commit()
    }

    // --- pairing: fingerprint ties bookmarks to a specific URL+token combination ---

    /**
     * Returns the stored pairing fingerprint for the last successful sync setup.
     * The fingerprint is a truncated SHA-256 of (url + \0 + token) — changing either
     * the server URL or the token will produce a different fingerprint and trigger a
     * bookmark reset. The token itself is never stored here.
     */
    fun getPairingFingerprint(): String? = prefs.getString(KEY_PAIRING_FINGERPRINT, null)

    @Suppress("CommitPrefEdits")
    fun setPairingFingerprint(fingerprint: String) {
        prefs.edit().putString(KEY_PAIRING_FINGERPRINT, fingerprint).commit()
    }

    companion object {
        private const val PREFS_NAME = "nutrition_prefs"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_SYNC_INTERVAL = "sync_interval_min"
        private const val KEY_PENDING = "counter_pending"
        private const val KEY_APPLIED_TOTAL = "counter_applied_total"
        private const val KEY_LAST_ERROR = "counter_last_error"
        private const val KEY_LAST_MSG = "last_sync_msg"
        private const val KEY_BOOKMARKS = "insert_bookmarks"
        private const val KEY_PAIRING_FINGERPRINT = "pairing_fingerprint"
        private const val DEFAULT_INTERVAL_MIN = 60
    }
}
