package com.ravil.healthconnect.nutrition.network

import com.ravil.healthconnect.nutrition.data.AckRequest
import com.ravil.healthconnect.nutrition.data.NutritionQueueRecord
import com.ravil.healthconnect.nutrition.data.NutritionQueueResponse
import com.ravil.healthconnect.nutrition.sync.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

class NutritionApiClient(
    private val baseUrl: String,
    private val token: String,
) : Transport {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Fetches pending nutrition records from GET /nutrition/queue.
     * Token is sent in X-Nutrition-Token — never in URL or redirected.
     * Rejects responses larger than MAX_RESPONSE_BYTES and lists with more than MAX_RECORDS items.
     */
    override suspend fun fetchQueue(): Result<List<NutritionQueueRecord>> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/nutrition/queue"
        val request = Request.Builder()
            .url(url)
            .header("X-Nutrition-Token", token)
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val bodyResult = readBoundedBody(response)
                if (bodyResult.isFailure) {
                    return@withContext Result.failure(bodyResult.exceptionOrNull()!!)
                }
                val body = bodyResult.getOrThrow()
                val parsed = json.decodeFromString<NutritionQueueResponse>(body)
                if (parsed.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                    return@withContext Result.failure(IOException("unsupported_schema_version"))
                }
                if (parsed.records.size > MAX_RECORDS) {
                    return@withContext Result.failure(IOException("server_returned_too_many_records"))
                }
                Result.success(parsed.records)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Acknowledges a record via POST /nutrition/ack.
     * Called only AFTER a successful Health Connect insert.
     */
    override suspend fun ackRecord(clientRecordId: String, clientRecordVersion: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/nutrition/ack"
        val body = json.encodeToString(AckRequest(clientRecordId, clientRecordVersion))
        val request = Request.Builder()
            .url(url)
            .header("X-Nutrition-Token", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(IOException("ack_http_${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun readBoundedBody(response: Response): Result<String> {
        val responseBody = response.body
            ?: return Result.failure(IOException("empty_body"))
        val reader = responseBody.charStream()
        val sb = StringBuilder()
        val buf = CharArray(8192)
        var total = 0
        var n: Int
        while (reader.read(buf).also { n = it } != -1) {
            total += n
            if (total > MAX_RESPONSE_BYTES) {
                return Result.failure(IOException("response_too_large"))
            }
            sb.append(buf, 0, n)
        }
        return Result.success(sb.toString())
    }

    companion object {
        private const val SUPPORTED_SCHEMA_VERSION = 1
        const val MAX_RECORDS = 50
        const val MAX_RESPONSE_BYTES = 512 * 1024

        /**
         * Returns true only for HTTPS URLs with no userinfo, query, or fragment.
         * Rejects HTTP, credentials in URL, and any query/fragment that could indicate
         * a non-canonical endpoint.
         */
        fun isCanonicalHttpsUrl(url: String): Boolean = try {
            val uri = URI(url)
            uri.scheme == "https" &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                !uri.host.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }
}
