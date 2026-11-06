package com.ravil.healthcore.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import com.ravil.healthcore.BuildConfig
import com.ravil.healthcore.core.ingest.DailyTotals
import com.ravil.healthcore.core.ingest.IngestClient
import com.ravil.healthcore.core.ingest.IngestOutcome
import com.ravil.healthcore.core.ingest.IngestPayload
import com.ravil.healthcore.core.ingest.IngestPlan
import com.ravil.healthcore.core.net.HealthCoreHttp
import com.ravil.healthcore.core.nutrition.crypto.TokenStorePort
import com.ravil.healthcore.core.status.RunStatus
import com.ravil.healthcore.data.AppPreferences
import com.ravil.healthcore.data.TokenStore
import com.ravil.healthcore.hc.DailyTotalsReader
import com.ravil.healthcore.hc.HcPermissions
import com.ravil.healthcore.hc.HealthConnectReader
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId

/** The outcome of one ingest run: what the main screen shows, and whether to retry. */
data class RunResult(val status: RunStatus, val retryable: Boolean)

/**
 * One ingest run: read what is new in Health Connect since the per-type cursors (within the
 * last 48 hours), send it to health-core, and advance the cursors only after health-core
 * accepted it. Mirrors HC Webhook's automatic sync for a single JSON webhook.
 */
class IngestRunner(
    private val context: Context,
    private val prefs: AppPreferences = AppPreferences(context),
    private val tokens: TokenStorePort = TokenStore(context, TokenStore.PREFS_INGEST),
) {
    suspend fun run(): RunResult {
        val now = Instant.now()
        fun done(code: String, ok: Boolean = false, records: Int = 0, retryable: Boolean = false) =
            RunResult(RunStatus(now, code, ok, records), retryable).also { prefs.setLastRun(AppPreferences.Job.INGEST, it.status) }

        val token = tokens.getToken() ?: return done("no_token")
        val baseUrl = prefs.baseUrl
        if (!HealthCoreHttp.isCanonicalHttpsUrl(baseUrl)) return done("invalid_url")
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return done("hc_not_available")

        val reader = HealthConnectReader(context)
        val types = try {
            HcPermissions.grantedTypes(reader.getGrantedPermissions())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return done("hc_not_available")
        }
        if (types.isEmpty()) return done("no_permissions")

        val read = try {
            reader.readHealthData(
                enabledTypes = types,
                lastSyncTimestamps = types.associateWith(prefs::cursor),
                dataTypeResolutions = prefs.resolutions(),
            )
        } catch (_: OutOfMemoryError) {
            return done("out_of_memory")
        }
        val data = read.getOrElse { e ->
            if (e is CancellationException) throw e
            // Health Connect rate limits and transient IPC errors clear up; let WorkManager retry.
            return done("read_failed", retryable = true)
        }

        // Health Connect's own day totals, sent along every run (see DailyTotals). They are
        // best effort: without them the raw records still go out.
        val totals = try {
            DailyTotalsReader(context).read(types, DailyTotals.days(now, ZoneId.systemDefault()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val toSend = DailyTotals.appendTo(data, totals, DailyTotalsReader.metadata())

        if (IngestPlan.isEmpty(toSend)) return done("no_data", ok = true)
        val count = IngestPlan.recordCount(toSend)
        if (count > IngestPlan.MAX_RECORDS_PER_PAYLOAD) return done("too_many_records", records = count)

        val payload = try {
            IngestPayload.build(toSend, BuildConfig.VERSION_NAME, now)
        } catch (_: OutOfMemoryError) {
            return done("out_of_memory", records = count)
        }
        val response = IngestClient(baseUrl, token).send(payload)
        return when (response.outcome) {
            IngestOutcome.DELIVERED -> {
                // Cursors follow the raw records only: a day total is not a Health Connect record.
                prefs.saveCursors(IngestPlan.nextCursors(data, now))
                done("delivered", ok = true, records = count)
            }
            else -> done(response.outcome.name.lowercase(), records = count, retryable = response.outcome.retryable)
        }
    }
}
