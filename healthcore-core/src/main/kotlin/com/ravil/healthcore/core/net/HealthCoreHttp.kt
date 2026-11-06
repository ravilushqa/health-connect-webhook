package com.ravil.healthcore.core.net

import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit

/** Shared HTTP rules for every call to health-core. */
object HealthCoreHttp {
    const val DEFAULT_BASE_URL = "https://health-core.ravil.space"

    const val PATH_INGEST = "/ingest/health-connect"
    const val PATH_NUTRITION_QUEUE = "/nutrition/queue"
    const val PATH_NUTRITION_ACK = "/nutrition/ack"

    const val HEADER_INGEST_TOKEN = "X-Health-Token"
    const val HEADER_NUTRITION_TOKEN = "X-Nutrition-Token"

    /**
     * True only for https URLs with a host and no userinfo, query or fragment, so a token
     * can never be sent in clear text or to a URL that embeds credentials.
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

    fun endpoint(baseUrl: String, path: String): String = baseUrl.trim().trimEnd('/') + path

    /**
     * Redirects are disabled: OkHttp would otherwise replay the token header to wherever a
     * redirect points.
     */
    fun newClient(readTimeoutSeconds: Long = 60): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}
