package com.ravil.healthcore.core.ingest

import com.ravil.healthcore.core.net.ConnectionCheck
import com.ravil.healthcore.core.net.HealthCoreHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Result of one POST /ingest/health-connect. Carries a status, never a body or a value. */
enum class IngestOutcome(val retryable: Boolean) {
    DELIVERED(false),
    UNAUTHORIZED(false),
    REJECTED(false),
    PAYLOAD_TOO_LARGE(false),
    RATE_LIMITED(true),
    SERVER_ERROR(true),
    NETWORK_ERROR(true),
}

data class IngestResponse(val outcome: IngestOutcome, val httpCode: Int? = null)

/**
 * Sends ingest payloads to health-core. The token goes only in [HealthCoreHttp.HEADER_INGEST_TOKEN];
 * redirects are not followed.
 */
class IngestClient(
    baseUrl: String,
    private val token: String,
    private val http: OkHttpClient = HealthCoreHttp.newClient(),
) {
    private val url: String

    init {
        require(HealthCoreHttp.isCanonicalHttpsUrl(baseUrl.trim())) { "base URL must be a canonical https URL" }
        require(token.isNotBlank()) { "ingest token is empty" }
        url = HealthCoreHttp.endpoint(baseUrl, HealthCoreHttp.PATH_INGEST)
    }

    suspend fun send(payload: String): IngestResponse = withContext(Dispatchers.IO) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        if (bytes.size > IngestPlan.MAX_PAYLOAD_BYTES) {
            return@withContext IngestResponse(IngestOutcome.PAYLOAD_TOO_LARGE)
        }
        val request = Request.Builder()
            .url(url)
            .header(HealthCoreHttp.HEADER_INGEST_TOKEN, token)
            .post(bytes.toRequestBody(JSON))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                IngestResponse(classify(response.code), response.code)
            }
        } catch (_: IOException) {
            IngestResponse(IngestOutcome.NETWORK_ERROR)
        }
    }

    /**
     * Checks the URL and token without storing anything: health-core authenticates before
     * it validates, so an empty object is answered 401 for a wrong token and
     * 400 "validation failed" for a right one, and nothing reaches the journal.
     */
    suspend fun check(): ConnectionCheck = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header(HealthCoreHttp.HEADER_INGEST_TOKEN, token)
            .post("{}".toRequestBody(JSON))
            .build()
        ConnectionCheck.run(http, request, expectedError = "validation failed")
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun classify(code: Int): IngestOutcome = when {
            code in 200..299 -> IngestOutcome.DELIVERED
            code == 401 || code == 403 -> IngestOutcome.UNAUTHORIZED
            code == 413 -> IngestOutcome.PAYLOAD_TOO_LARGE
            code == 429 -> IngestOutcome.RATE_LIMITED
            code >= 500 -> IngestOutcome.SERVER_ERROR
            else -> IngestOutcome.REJECTED
        }
    }
}
