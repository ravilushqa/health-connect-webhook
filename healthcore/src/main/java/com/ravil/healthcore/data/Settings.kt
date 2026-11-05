package com.ravil.healthcore.data

import android.content.Context
import com.ravil.healthcore.core.ingest.IngestClient
import com.ravil.healthcore.core.model.HealthDataType
import com.ravil.healthcore.core.net.ConnectionCheck
import com.ravil.healthcore.core.net.HealthCoreHttp
import com.ravil.healthcore.core.nutrition.network.NutritionApiClient
import com.ravil.healthcore.nutrition.NutritionPreferences
import com.ravil.healthcore.nutrition.NutritionSyncManager
import com.ravil.healthcore.sync.SyncScheduler
import java.time.Instant

/** What the settings screen edits. Token inputs are null unless the user typed a new one. */
data class SettingsDraft(
    val baseUrl: String,
    val ingestTokenInput: String?,
    val nutritionTokenInput: String?,
    val ingestEnabled: Boolean,
    val mealsEnabled: Boolean,
    val intervalMinutes: Int,
    val resolutions: Map<HealthDataType, Int>,
)

/** Reads and saves settings across the three stores (plain prefs, two encrypted tokens, nutrition prefs). */
class Settings(context: Context) {
    private val app = context.applicationContext
    private val prefs = AppPreferences(app)
    private val ingestTokens = TokenStore(app, TokenStore.PREFS_INGEST)
    private val nutritionTokens = TokenStore(app, TokenStore.PREFS_NAME)

    fun current(): SettingsDraft = SettingsDraft(
        baseUrl = prefs.baseUrl,
        ingestTokenInput = null,
        nutritionTokenInput = null,
        ingestEnabled = prefs.ingestEnabled,
        mealsEnabled = NutritionPreferences(app).isSyncEnabled(),
        intervalMinutes = prefs.intervalMinutes,
        resolutions = prefs.resolutions(),
    )

    fun hasIngestToken(): Boolean = ingestTokens.getToken() != null
    fun hasNutritionToken(): Boolean = nutritionTokens.getToken() != null
    fun tokenUpdatedAt(which: AppPreferences.Token): Instant? = prefs.tokenUpdatedAt(which)

    /** Returns null when everything was saved, or a message for the user (never containing a token). */
    suspend fun save(d: SettingsDraft): String? {
        val url = d.baseUrl.trim().trimEnd('/')
        if (!HealthCoreHttp.isCanonicalHttpsUrl(url)) return "The server URL must start with https:// and have no query or credentials."
        if (d.intervalMinutes < AppPreferences.MIN_INTERVAL_MINUTES) return "The interval must be at least ${AppPreferences.MIN_INTERVAL_MINUTES} minutes."

        val newIngest = d.ingestTokenInput?.trim()?.takeIf { it.isNotEmpty() }
        val newNutrition = d.nutritionTokenInput?.trim()?.takeIf { it.isNotEmpty() }
        if (d.ingestEnabled && newIngest == null && !hasIngestToken()) return "Sending health data needs an ingest token."
        if (d.mealsEnabled && newNutrition == null && !hasNutritionToken()) return "Writing meals needs a nutrition token."

        if (newIngest != null) {
            if (!ingestTokens.setToken(newIngest)) return "Could not store the ingest token."
            prefs.setTokenUpdatedAt(AppPreferences.Token.INGEST, Instant.now())
        }
        if (url != prefs.baseUrl) prefs.resetCursors()
        prefs.baseUrl = url
        prefs.ingestEnabled = d.ingestEnabled
        prefs.intervalMinutes = d.intervalMinutes
        prefs.setResolutions(d.resolutions)

        // The nutrition side keeps its own consent-bound settings (URL + token fingerprint).
        val request = NutritionSyncManager.beginSettingsUpdate(url, newNutrition, d.mealsEnabled, d.intervalMinutes)
        val mealsOk = NutritionSyncManager(app).applySettings(request)
        if (mealsOk && newNutrition != null) prefs.setTokenUpdatedAt(AppPreferences.Token.NUTRITION, Instant.now())

        SyncScheduler.apply(app)
        return if (mealsOk) null else "Meal settings were not saved; meal sync stays off until they are."
    }

    /** Side-effect-free checks of both tokens against the saved URL. */
    suspend fun check(): Pair<ConnectionCheck?, ConnectionCheck?> {
        val url = prefs.baseUrl
        if (!HealthCoreHttp.isCanonicalHttpsUrl(url)) return null to null
        val ingest = ingestTokens.getToken()?.let { IngestClient(url, it).check() }
        val meals = nutritionTokens.getToken()?.let { NutritionApiClient(url, it).check() }
        return ingest to meals
    }
}
