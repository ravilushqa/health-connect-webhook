package com.ravil.healthconnect.nutrition.network

import com.ravil.healthconnect.nutrition.data.AckRequest
import com.ravil.healthconnect.nutrition.data.NutritionQueueRecord
import com.ravil.healthconnect.nutrition.data.NutritionQueueResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val SUPPORTED_SCHEMA_VERSION = 1
private const val MAX_RECORDS = 50

class NutritionApiClient(
    private val baseUrl: String,
    private val token: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        // OkHttp will not follow HTTPS → HTTP redirects by default; HTTPS → HTTPS is OK
        .followSslRedirects(true)
        .followRedirects(true)
        .build()

    /**
     * Fetches pending nutrition records from GET /nutrition/queue.
     * Token is sent in the X-Nutrition-Token header — never in URL or logged.
     * Returns a bounded list (max 50 items, validated schema_version==1).
     */
    fun fetchQueue(): Result<List<NutritionQueueRecord>> {
        val url = "${baseUrl.trimEnd('/')}/nutrition/queue"
        val request = Request.Builder()
            .url(url)
            .header("X-Nutrition-Token", token)
            .get()
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("HTTP ${response.code}"))
                }
                val body = response.body?.string()
                    ?: return Result.failure(IOException("empty_body"))
                val parsed = json.decodeFromString<NutritionQueueResponse>(body)
                if (parsed.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                    return Result.failure(IOException("unsupported_schema_version"))
                }
                Result.success(parsed.records.take(MAX_RECORDS))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Acknowledges a record via POST /nutrition/ack.
     * Only called AFTER a successful Health Connect insert.
     * Returns success on any 2xx response.
     */
    fun ackRecord(clientRecordId: String, clientRecordVersion: Int): Result<Unit> {
        val url = "${baseUrl.trimEnd('/')}/nutrition/ack"
        val body = json.encodeToString(AckRequest(clientRecordId, clientRecordVersion))
        val request = Request.Builder()
            .url(url)
            .header("X-Nutrition-Token", token)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(IOException("ack_http_${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
