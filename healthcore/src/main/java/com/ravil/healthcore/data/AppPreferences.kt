package com.ravil.healthcore.data

import android.content.Context
import android.content.SharedPreferences
import com.ravil.healthcore.core.model.HealthDataType
import com.ravil.healthcore.core.model.Resolutions
import com.ravil.healthcore.core.model.defaultResolutionMinutes
import com.ravil.healthcore.core.net.HealthCoreHttp
import com.ravil.healthcore.core.status.RunStatus
import java.time.Instant

/**
 * Plain (unencrypted) settings and status. Holds no secrets: tokens live in [TokenStore],
 * and statuses are codes and counts only.
 */
class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null) ?: HealthCoreHttp.DEFAULT_BASE_URL
        set(v) { prefs.edit().putString(KEY_BASE_URL, v).apply() }

    var ingestEnabled: Boolean
        get() = prefs.getBoolean(KEY_INGEST_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_INGEST_ENABLED, v).apply() }

    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MINUTES).coerceAtLeast(MIN_INTERVAL_MINUTES)
        set(v) { prefs.edit().putInt(KEY_INTERVAL, v.coerceAtLeast(MIN_INTERVAL_MINUTES)).apply() }

    fun tokenUpdatedAt(which: Token): Instant? =
        prefs.getLong(which.key, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    fun setTokenUpdatedAt(which: Token, at: Instant?) {
        prefs.edit().apply { if (at == null) remove(which.key) else putLong(which.key, at.toEpochMilli()) }.apply()
    }

    // ── ingest cursors (epoch ms of the newest delivered record, per type) ────────────

    fun cursor(type: HealthDataType): Instant? =
        prefs.getLong(KEY_CURSOR + type.name, -1L).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)

    /** Committed synchronously: a cursor that is lost re-sends data (harmless), one written early loses it. */
    fun saveCursors(cursors: Map<HealthDataType, Long>): Boolean {
        val e = prefs.edit()
        cursors.forEach { (t, ms) -> e.putLong(KEY_CURSOR + t.name, ms) }
        return e.commit()
    }

    /**
     * One-off migrations of stored state, run at start-up.
     * v2: interval types switched from daily buckets to raw records; their cursors are reset
     * so the next run sends the raw records of the whole 48-hour window, not only new ones.
     */
    fun migrate() {
        val v = prefs.getInt(KEY_STATE_VERSION, 1)
        if (v < 2) {
            val e = prefs.edit()
            Resolutions.withResolution.filter { Resolutions.default(it) == 0 && it.defaultResolutionMinutes != 0 }
                .forEach { e.remove(KEY_CURSOR + it.name) }
            e.putInt(KEY_STATE_VERSION, 2).commit()
        }
    }

    fun resetCursors(): Boolean {
        val e = prefs.edit()
        HealthDataType.entries.forEach { e.remove(KEY_CURSOR + it.name) }
        return e.commit()
    }

    // ── export granularity ─────────────────────────────────────────────────────────

    fun resolution(type: HealthDataType): Int =
        Resolutions.effective(type, prefs.getInt(KEY_RESOLUTION + type.name, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE })

    /** Every type that has a resolution, so the reader never falls back to HC Webhook's defaults. */
    fun resolutions(): Map<HealthDataType, Int> = Resolutions.withResolution.associateWith(::resolution)

    fun setResolutions(values: Map<HealthDataType, Int>) {
        val e = prefs.edit()
        values.forEach { (t, v) -> e.putInt(KEY_RESOLUTION + t.name, v) }
        e.apply()
    }

    // ── last runs ──────────────────────────────────────────────────────────────────

    fun lastRun(job: Job): RunStatus? {
        val at = prefs.getLong(job.key + "_at", 0L).takeIf { it > 0 } ?: return null
        return RunStatus(
            at = Instant.ofEpochMilli(at),
            code = prefs.getString(job.key + "_code", null) ?: return null,
            ok = prefs.getBoolean(job.key + "_ok", false),
            records = prefs.getInt(job.key + "_records", 0),
            errors = prefs.getInt(job.key + "_errors", 0),
        )
    }

    fun setLastRun(job: Job, s: RunStatus) {
        prefs.edit()
            .putLong(job.key + "_at", s.at.toEpochMilli())
            .putString(job.key + "_code", s.code)
            .putBoolean(job.key + "_ok", s.ok)
            .putInt(job.key + "_records", s.records)
            .putInt(job.key + "_errors", s.errors)
            .apply()
    }

    enum class Token(val key: String) { INGEST("ingest_token_updated_at"), NUTRITION("nutrition_token_updated_at") }

    enum class Job(val key: String) { INGEST("run_ingest"), MEALS("run_meals") }

    companion object {
        private const val PREFS_NAME = "healthcore_prefs"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_INGEST_ENABLED = "ingest_enabled"
        private const val KEY_INTERVAL = "interval_min"
        private const val KEY_CURSOR = "cursor_"
        private const val KEY_RESOLUTION = "resolution_"
        private const val KEY_STATE_VERSION = "state_version"
        const val DEFAULT_INTERVAL_MINUTES = 60
        const val MIN_INTERVAL_MINUTES = 15
    }
}
