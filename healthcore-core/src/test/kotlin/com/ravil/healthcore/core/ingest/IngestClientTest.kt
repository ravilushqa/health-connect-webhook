package com.ravil.healthcore.core.ingest

import com.ravil.healthcore.core.net.ConnectionCheck
import com.ravil.healthcore.core.nutrition.network.NutritionApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class IngestClientTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private val token = "synthetic-ingest-token"

    @Before
    fun setUp() {
        val localhost = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(localhost).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(localhost.certificate).build()
        server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.start()
        http = OkHttpClient.Builder()
            .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun base() = "https://localhost:${server.port}"

    private fun client() = IngestClient(base(), token, http)

    private fun error(code: Int, msg: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody("""{"error":"$msg"}""")

    @Test
    fun `posts json with the token header only`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"ingested":{}}"""))
        val r = client().send("""{"timestamp":"2026-01-10T12:00:00Z"}""")
        assertEquals(IngestOutcome.DELIVERED, r.outcome)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/ingest/health-connect", req.path)
        assertEquals(token, req.getHeader("X-Health-Token"))
        assertNull(req.getHeader("X-Nutrition-Token"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        assertNull("no chunked body: health-core refuses Transfer-Encoding", req.getHeader("Transfer-Encoding"))
        assertEquals(req.bodySize.toString(), req.getHeader("Content-Length"))
    }

    @Test
    fun `trailing slash in base url`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        IngestClient(base() + "/", token, http).send("{}")
        assertEquals("/ingest/health-connect", server.takeRequest().path)
    }

    @Test
    fun `status codes map to outcomes`() = runBlocking {
        val cases = listOf(
            401 to IngestOutcome.UNAUTHORIZED,
            400 to IngestOutcome.REJECTED,
            413 to IngestOutcome.PAYLOAD_TOO_LARGE,
            429 to IngestOutcome.RATE_LIMITED,
            503 to IngestOutcome.SERVER_ERROR,
            302 to IngestOutcome.REJECTED,
        )
        for ((code, outcome) in cases) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", "https://example.invalid/"))
            assertEquals("$code", outcome, client().send("{}").outcome)
        }
        assertEquals("redirect not followed", cases.size, server.requestCount)
    }

    @Test
    fun `retryable outcomes`() {
        assertTrue(IngestOutcome.RATE_LIMITED.retryable)
        assertTrue(IngestOutcome.NETWORK_ERROR.retryable)
        assertTrue(!IngestOutcome.UNAUTHORIZED.retryable)
        assertTrue(!IngestOutcome.REJECTED.retryable)
    }

    @Test
    fun `network error`() = runBlocking {
        server.shutdown()
        assertEquals(IngestOutcome.NETWORK_ERROR, client().send("{}").outcome)
    }

    @Test
    fun `oversized payload is not sent`() = runBlocking {
        val big = "x".repeat(IngestPlan.MAX_PAYLOAD_BYTES + 1)
        assertEquals(IngestOutcome.PAYLOAD_TOO_LARGE, client().send(big).outcome)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refuses non https urls and empty tokens`() {
        assertThrows(IllegalArgumentException::class.java) { IngestClient("http://localhost", token, http) }
        assertThrows(IllegalArgumentException::class.java) { IngestClient("https://u:p@localhost", token, http) }
        assertThrows(IllegalArgumentException::class.java) { IngestClient("https://localhost/?t=1", token, http) }
        assertThrows(IllegalArgumentException::class.java) { IngestClient(base(), " ", http) }
    }

    @Test
    fun `ingest check sends an empty object and reads health-core's validation error`() = runBlocking {
        server.enqueue(error(400, "validation failed"))
        assertEquals(ConnectionCheck.OK, client().check())
        val req = server.takeRequest()
        assertEquals("{}", req.body.readUtf8())
        assertEquals(token, req.getHeader("X-Health-Token"))
    }

    @Test
    fun `ingest check outcomes`() = runBlocking {
        val cases = listOf(
            error(401, "unauthorized") to ConnectionCheck.UNAUTHORIZED,
            error(400, "invalid json") to ConnectionCheck.UNEXPECTED_RESPONSE,
            MockResponse().setResponseCode(400).setBody("<html>bad</html>") to ConnectionCheck.UNEXPECTED_RESPONSE,
            error(429, "rate limit exceeded") to ConnectionCheck.RATE_LIMITED,
            error(404, "not found") to ConnectionCheck.NOT_ENABLED,
            MockResponse().setResponseCode(200) to ConnectionCheck.UNEXPECTED_RESPONSE,
        )
        for ((resp, want) in cases) {
            server.enqueue(resp)
            assertEquals(want, client().check())
        }
    }

    @Test
    fun `nutrition check posts an ack health-core must refuse after auth`() = runBlocking {
        server.enqueue(error(400, "client_record_id must be a non-empty string"))
        val c = NutritionApiClient(base(), "synthetic-nutrition-token", http)
        assertEquals(ConnectionCheck.OK, c.check())
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/nutrition/ack", req.path)
        assertEquals("synthetic-nutrition-token", req.getHeader("X-Nutrition-Token"))
        assertNull(req.getHeader("X-Health-Token"))
        assertEquals("""{"client_record_id":"","client_record_version":0}""", req.body.readUtf8())

        server.enqueue(error(401, "unauthorized"))
        assertEquals(ConnectionCheck.UNAUTHORIZED, c.check())
        server.enqueue(error(404, "not found"))
        assertEquals(ConnectionCheck.NOT_ENABLED, c.check())
    }
}
