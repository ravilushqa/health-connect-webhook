package com.ravil.healthcore.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Outcome of a side-effect-free token check against one health-core endpoint. */
enum class ConnectionCheck {
    /** health-core answered and accepted the token. */
    OK,
    /** health-core answered 401: wrong token. */
    UNAUTHORIZED,
    /** The endpoint is not enabled on the server (404), e.g. no nutrition token configured. */
    NOT_ENABLED,
    RATE_LIMITED,
    /** Something answered, but not the way health-core does (wrong URL, proxy page, …). */
    UNEXPECTED_RESPONSE,
    NETWORK_ERROR,
    ;

    companion object {
        private const val MAX_BODY = 4096L
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Sends [request], built so that an authenticated request fails validation with
         * [expectedError] before anything is stored, and maps the answer.
         */
        fun run(http: OkHttpClient, request: Request, expectedError: String): ConnectionCheck = try {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    400 -> {
                        val body = response.peekBody(MAX_BODY).string()
                        if (errorOf(body) == expectedError) OK else UNEXPECTED_RESPONSE
                    }
                    401, 403 -> UNAUTHORIZED
                    404 -> NOT_ENABLED
                    429 -> RATE_LIMITED
                    else -> UNEXPECTED_RESPONSE
                }
            }
        } catch (_: IOException) {
            NETWORK_ERROR
        }

        internal fun errorOf(body: String): String? = try {
            (json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
    }
}
