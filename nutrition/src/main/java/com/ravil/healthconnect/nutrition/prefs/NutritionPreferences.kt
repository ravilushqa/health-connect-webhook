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
        val json = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyMap()
        return try {
            Json.decodeFromString<Map<String, Int>>(json)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveBookmarks(bookmarks: Map<String, Int>) {
        prefs.edit().putString(KEY_BOOKMARKS, Json.encodeToString(bookmarks)).apply()
    }

    // --- pairing: track which server URL bookmarks belong to ---

    fun getLastSyncBaseUrl(): String? = prefs.getString(KEY_LAST_SYNC_URL, null)

    fun setLastSyncBaseUrl(url: String) {
        prefs.edit().putString(KEY_LAST_SYNC_URL, url).apply()
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
        private const val KEY_LAST_SYNC_URL = "last_sync_base_url"
        private const val DEFAULT_INTERVAL_MIN = 60
    }
}
