package com.ravil.healthconnect.nutrition.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.WeakHashMap

/** Immutable snapshot of the settings fields that must be accepted atomically. */
data class SettingsBundle(
    val url: String?,
    val enabled: Boolean,
    val intervalMinutes: Int,
    /** SHA-256 fingerprint of URL+token stored at last successful saveSettings — settings consent binding. */
    val settingsTokenFingerprint: String? = null,
)

/**
 * Per-SharedPreferences-instance state held in the shared registry.
 *
 * [snapshot] is the full map of all logical keys as last durably committed to disk.
 * Reads always come from here — never from SharedPreferences in-process memory, which
 * may contain phantom values after a failed commit().
 *
 * [bookmarksDurable] and [fingerprintDurable] track whether the last coupled commit of
 * those keys reached disk.
 *
 * [settingsDurable] is true when the last saveSettings() call succeeded (or at process
 * start before any saveSettings() call). It is set false on any saveSettings() commit
 * failure and stays false until a complete successful saveSettings(). When false,
 * isSyncEnabled() returns false and getSettingsTokenFingerprint() returns null, blocking
 * any new sync until the user re-saves settings successfully.
 *
 * Counter/bookmark writes do NOT affect [settingsDurable].
 */
private data class StoreState(
    val snapshot: Map<String, Any?> = emptyMap(),
    val bookmarksDurable: Boolean = true,
    val fingerprintDurable: Boolean = true,
    val settingsDurable: Boolean = true,
    val settingsBundle: SettingsBundle? = null,
)

/**
 * All settings/pairing state for the Nutrition sync feature.
 *
 * Durability model
 * ----------------
 * Every mutation builds a FULL candidate map from the last confirmed snapshot and
 * writes it via editor.clear() + put-all + commit(). The candidate is promoted to the
 * confirmed snapshot ONLY when commit() returns true.
 *
 * On commit failure:
 *  - editor.clear() + put-all old snapshot + commit() restores in-process memory to the
 *    last confirmed state (Android's commit() always writes to memory even when the disk
 *    write fails, so the restore always lands in memory).
 *  - The in-memory registry snapshot is unchanged, so all reads continue to return the
 *    last durably confirmed values regardless of what is in SharedPreferences memory.
 *
 * State is held in a per-SharedPreferences-instance registry (WeakHashMap keyed by
 * identity). Two NutritionPreferences wrappers over the same SharedPreferences instance
 * share state; wrappers over different instances (different stores) are isolated.
 */
class NutritionPreferences internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    init {
        synchronized(registryLock) {
            if (!registry.containsKey(prefs)) {
                registry[prefs] = StoreState(snapshot = loadSnapshot(prefs))
            }
        }
    }

    // Instance-level durability flags and settings bundle (isolated per underlying SharedPreferences)

    val bookmarksDurable: Boolean get() = getState().bookmarksDurable
    val fingerprintDurable: Boolean get() = getState().fingerprintDurable
    val settingsDurable: Boolean get() = getState().settingsDurable
    val settingsBundle: SettingsBundle? get() = getState().settingsBundle

    // --- settings reads — always from registry snapshot ---

    fun getBaseUrl(): String? = getState().snapshot[KEY_BASE_URL] as? String

    /** Returns false when [settingsDurable] is false — fail-closed after a failed saveSettings(). */
    fun isSyncEnabled(): Boolean {
        val state = getState()
        if (!state.settingsDurable) return false
        return state.snapshot[KEY_SYNC_ENABLED] as? Boolean ?: false
    }

    fun getSyncIntervalMinutes(): Int =
        getState().snapshot[KEY_SYNC_INTERVAL] as? Int ?: DEFAULT_INTERVAL_MIN

    /**
     * Returns the durable settings-token fingerprint, or null when [settingsDurable] is false.
     * Used by NutritionSyncManager to verify URL+token binding before executing a sync cycle.
     */
    fun getSettingsTokenFingerprint(): String? {
        if (!settingsDurable) return null
        return getState().snapshot[KEY_SETTINGS_TOKEN_FINGERPRINT] as? String
    }

    /**
     * Atomically persists URL, enabled flag, interval, and optional settings-token fingerprint
     * in a single full-snapshot commit. Returns true only if the write reaches durable storage.
     *
     * On success: [settingsDurable] is set true and [settingsBundle] is updated.
     * On failure: [settingsDurable] is set false so [isSyncEnabled] returns false until the
     * next successful saveSettings() call. Old URL and interval remain in the snapshot.
     * Counter/bookmark writes do NOT restore [settingsDurable].
     */
    @Suppress("CommitPrefEdits")
    fun saveSettings(
        url: String,
        enabled: Boolean,
        intervalMinutes: Int,
        settingsTokenFingerprint: String? = null,
    ): Boolean {
        val trimmed = url.trim()
        val interval = intervalMinutes.coerceAtLeast(15)
        return synchronized(registryLock) {
            val state = getState()
            val old = state.snapshot
            val candidate = old.toMutableMap().apply {
                if (trimmed.isNotBlank()) put(KEY_BASE_URL, trimmed) else remove(KEY_BASE_URL)
                put(KEY_SYNC_ENABLED, enabled)
                put(KEY_SYNC_INTERVAL, interval)
                if (settingsTokenFingerprint != null) {
                    put(KEY_SETTINGS_TOKEN_FINGERPRINT, settingsTokenFingerprint)
                } else {
                    remove(KEY_SETTINGS_TOKEN_FINGERPRINT)
                }
            }
            val ok = commitSnapshot(candidate)
            if (ok) {
                val bundle = SettingsBundle(
                    url = candidate[KEY_BASE_URL] as? String,
                    enabled = enabled,
                    intervalMinutes = interval,
                    settingsTokenFingerprint = settingsTokenFingerprint,
                )
                setState(state.copy(snapshot = candidate, settingsBundle = bundle, settingsDurable = true))
            } else {
                restoreSnapshot(old)
                setState(state.copy(settingsDurable = false))
            }
            // Write-through companion shim for legacy callers
            if (ok) {
                stableSettings = getState().settingsBundle
                NutritionPreferences.settingsDurable = true
            } else {
                NutritionPreferences.settingsDurable = false
            }
            ok
        }
    }

    // --- counters / status: full snapshot commit, NO apply() ---

    fun setPendingCount(count: Int) {
        mutateSnapshot { it[KEY_PENDING] = count.coerceAtLeast(0) }
    }

    fun getPendingCount(): Int = getState().snapshot[KEY_PENDING] as? Int ?: 0

    fun getAppliedTotal(): Int = getState().snapshot[KEY_APPLIED_TOTAL] as? Int ?: 0

    fun addApplied(count: Int) {
        mutateSnapshot { m ->
            val next = ((m[KEY_APPLIED_TOTAL] as? Int ?: 0) + count).coerceAtLeast(0)
            m[KEY_APPLIED_TOTAL] = next
        }
    }

    fun getLastSyncErrorCount(): Int = getState().snapshot[KEY_LAST_ERROR] as? Int ?: 0

    fun setLastSyncErrorCount(count: Int) {
        mutateSnapshot { it[KEY_LAST_ERROR] = count.coerceAtLeast(0) }
    }

    fun getLastSyncMessage(): String = getState().snapshot[KEY_LAST_MSG] as? String ?: ""

    fun setLastSyncMessage(msg: String) {
        mutateSnapshot { it[KEY_LAST_MSG] = msg }
    }

    // --- bookmarks ---

    /**
     * Returns the durable bookmark map. Returns emptyMap() when [bookmarksDurable] is false —
     * the quarantine prevents phantom in-memory state from causing stale AckOnly decisions.
     */
    fun getBookmarks(): Map<String, Int> {
        if (!bookmarksDurable) return emptyMap()
        val raw = getState().snapshot[KEY_BOOKMARKS] as? String ?: return emptyMap()
        return try {
            Json.decodeFromString<Map<String, Int>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @Suppress("CommitPrefEdits")
    fun saveBookmarks(bookmarks: Map<String, Int>): Boolean {
        return synchronized(registryLock) {
            val state = getState()
            val old = state.snapshot
            val candidate = old.toMutableMap().apply {
                put(KEY_BOOKMARKS, Json.encodeToString(bookmarks))
            }
            val ok = commitSnapshot(candidate)
            if (ok) {
                setState(state.copy(snapshot = candidate, bookmarksDurable = true))
            } else {
                restoreSnapshot(old)
                setState(state.copy(bookmarksDurable = false))
            }
            // Write-through companion shim
            NutritionPreferences.bookmarksDurable = ok
            ok
        }
    }

    // --- pairing fingerprint ---

    /**
     * Returns the stored pairing fingerprint, or null when [fingerprintDurable] is false.
     */
    fun getPairingFingerprint(): String? {
        if (!fingerprintDurable) return null
        return getState().snapshot[KEY_PAIRING_FINGERPRINT] as? String
    }

    /**
     * Coupled atomic commit: persists [fingerprint] AND clears bookmarks in a single commit.
     * Sets both [fingerprintDurable] and [bookmarksDurable] to the result.
     * This is the bookmark-reset pairing fingerprint, distinct from the settings-token binding.
     */
    @Suppress("CommitPrefEdits")
    fun savePairingReset(fingerprint: String, bookmarks: Map<String, Int> = emptyMap()): Boolean {
        return synchronized(registryLock) {
            val state = getState()
            val old = state.snapshot
            val candidate = old.toMutableMap().apply {
                put(KEY_PAIRING_FINGERPRINT, fingerprint)
                put(KEY_BOOKMARKS, Json.encodeToString(bookmarks))
            }
            val ok = commitSnapshot(candidate)
            if (ok) {
                setState(state.copy(
                    snapshot = candidate,
                    fingerprintDurable = true,
                    bookmarksDurable = true,
                ))
            } else {
                restoreSnapshot(old)
                setState(state.copy(fingerprintDurable = false, bookmarksDurable = false))
            }
            // Write-through companion shims
            NutritionPreferences.fingerprintDurable = ok
            NutritionPreferences.bookmarksDurable = ok
            ok
        }
    }

    // --- private helpers ---

    private fun getState(): StoreState = synchronized(registryLock) {
        registry[prefs] ?: StoreState(snapshot = loadSnapshot(prefs)).also { registry[prefs] = it }
    }

    private fun setState(state: StoreState) = synchronized(registryLock) { registry[prefs] = state }

    private fun mutateSnapshot(transform: (MutableMap<String, Any?>) -> Unit) {
        synchronized(registryLock) {
            val state = getState()
            val old = state.snapshot
            val candidate = old.toMutableMap().also(transform)
            val ok = commitSnapshot(candidate)
            if (ok) {
                setState(state.copy(snapshot = candidate))
            } else {
                restoreSnapshot(old)
            }
        }
    }

    /** Writes [map] as the complete SharedPreferences content via clear()+put-all+commit(). */
    private fun commitSnapshot(map: Map<String, Any?>): Boolean {
        val ed = prefs.edit().clear()
        map.forEach { (key, value) ->
            when (value) {
                is String -> ed.putString(key, value)
                is Boolean -> ed.putBoolean(key, value)
                is Int -> ed.putInt(key, value)
            }
        }
        return ed.commit()
    }

    /**
     * Restores SharedPreferences in-process memory to [old] via clear()+put-all+commit().
     * commit() always writes to memory first regardless of disk result, so this always
     * eliminates any phantom value from a prior failed commit — even if this restore's own
     * disk write fails. The registry snapshot is NOT updated here; that is the caller's
     * responsibility (or left unchanged on failure).
     */
    private fun restoreSnapshot(old: Map<String, Any?>) {
        commitSnapshot(old) // return value ignored intentionally; memory restore is unconditional
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
        private const val KEY_SETTINGS_TOKEN_FINGERPRINT = "settings_token_fingerprint"
        private const val DEFAULT_INTERVAL_MIN = 60

        // Shared per-store state registry. WeakHashMap so entries are collected when the
        // SharedPreferences instance is no longer held anywhere (test isolation, process GC).
        private val registryLock = Any()
        private val registry = WeakHashMap<SharedPreferences, StoreState>()

        private fun loadSnapshot(prefs: SharedPreferences): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }?.let { m[KEY_BASE_URL] = it }
            m[KEY_SYNC_ENABLED] = prefs.getBoolean(KEY_SYNC_ENABLED, false)
            m[KEY_SYNC_INTERVAL] = prefs.getInt(KEY_SYNC_INTERVAL, DEFAULT_INTERVAL_MIN)
            m[KEY_PENDING] = prefs.getInt(KEY_PENDING, 0)
            m[KEY_APPLIED_TOTAL] = prefs.getInt(KEY_APPLIED_TOTAL, 0)
            m[KEY_LAST_ERROR] = prefs.getInt(KEY_LAST_ERROR, 0)
            prefs.getString(KEY_LAST_MSG, null)?.let { m[KEY_LAST_MSG] = it }
            prefs.getString(KEY_BOOKMARKS, null)?.let { m[KEY_BOOKMARKS] = it }
            prefs.getString(KEY_PAIRING_FINGERPRINT, null)?.let { m[KEY_PAIRING_FINGERPRINT] = it }
            prefs.getString(KEY_SETTINGS_TOKEN_FINGERPRINT, null)
                ?.let { m[KEY_SETTINGS_TOKEN_FINGERPRINT] = it }
            return m
        }

        /**
         * Write-through compatibility shims. Production code should prefer the instance-level
         * properties which are isolated per underlying SharedPreferences instance. These companion
         * vars are updated by save methods for callers that hold a companion reference, and are
         * writable by tests to reset process-scoped state.
         */
        @Volatile @JvmField var bookmarksDurable: Boolean = true
        @Volatile @JvmField var fingerprintDurable: Boolean = true
        @Volatile @JvmField var settingsDurable: Boolean = true
        @Volatile @JvmField var stableSettings: SettingsBundle? = null
    }
}
