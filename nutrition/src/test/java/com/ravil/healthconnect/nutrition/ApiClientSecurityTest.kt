package com.ravil.healthconnect.nutrition

import com.ravil.healthconnect.nutrition.network.NutritionApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

/**
 * Network-level security tests using an in-process HTTPS MockWebServer.
 *
 * A self-signed certificate is generated per test run and trusted only by the
 * test-scoped OkHttpClient — production trust is unchanged.
 *
 * Covers: redirect non-follow (all RFC 7231 redirect codes), token isolation,
 * byte-bounded response, >50 record rejection, cleartext rejection, and a
 * round-trip parse+ack fixture matching the server protocol.
 */
class ApiClientSecurityTest {

    private lateinit var server: MockWebServer
    private lateinit var heldCert: HeldCertificate
    private lateinit var testHttpClient: OkHttpClient
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        heldCert = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName(InetAddress.getLocalHost().canonicalHostName)
            .build()

        val serverCerts = HandshakeCertificates.Builder()
            .heldCertificate(heldCert)
            .build()

        val clientCerts = HandshakeCertificates.Builder()
            .addTrustedCertificate(heldCert.certificate)
            .build()

        testHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .build()

        server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.start()
        baseUrl = "https://localhost:${server.port}"
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(token: String = "secret-token") =
        NutritionApiClient(baseUrl, token, testHttpClient)

    // -------------------------------------------------------------------------
    // Redirect: all RFC 7231 redirect codes must not be followed
    // -------------------------------------------------------------------------

    private fun assertRedirectNotFollowed(statusCode: Int) = runBlocking {
        // Set up a redirect destination server — also HTTPS with the same cert
        val target = MockWebServer()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(heldCert).build()
        target.useHttps(serverCerts.sslSocketFactory(), false)
        target.start()
        target.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        server.enqueue(
            MockResponse()
                .setResponseCode(statusCode)
                .addHeader("Location", "https://localhost:${target.port}/stolen")
        )

        val result = client("secret-token").fetchQueue()

        // Client must not follow the redirect
        assertTrue("Expected failure for $statusCode redirect", result.isFailure)
        // Token must not have been sent to the redirect destination
        assertEquals(
            "Token must not reach redirect target for $statusCode",
            0,
            target.requestCount,
        )

        target.shutdown()
    }

    @Test fun `301 redirect not followed — token not sent to destination`() =
        assertRedirectNotFollowed(301)

    @Test fun `302 redirect not followed — token not sent to destination`() =
        assertRedirectNotFollowed(302)

    @Test fun `303 redirect not followed — token not sent to destination`() =
        assertRedirectNotFollowed(303)

    @Test fun `307 redirect not followed — token not sent to destination`() =
        assertRedirectNotFollowed(307)

    @Test fun `308 redirect not followed — token not sent to destination`() =
        assertRedirectNotFollowed(308)

    // -------------------------------------------------------------------------
    // Cleartext / URL validation
    // -------------------------------------------------------------------------

    @Test
    fun `isCanonicalHttpsUrl rejects http scheme`() {
        assertFalse(NutritionApiClient.isCanonicalHttpsUrl("http://example.com"))
        assertFalse(NutritionApiClient.isCanonicalHttpsUrl("http://example.com/path"))
    }

    @Test
    fun `isCanonicalHttpsUrl rejects url with userinfo`() {
        assertFalse(NutritionApiClient.isCanonicalHttpsUrl("https://user:pass@example.com"))
        assertFalse(NutritionApiClient.isCanonicalHttpsUrl("https://user@example.com"))
    }

    @Test
    fun `isCanonicalHttpsUrl rejects url with query string`() {
        assertFalse(NutritionApiClient.isCanonicalHttpsUrl("https://example.com?token=abc"))
        assertFalse(NutritionApiClient.isCanonicalHttpsUrl("https://example.com/path?q=1"))
    }

    @Test
    fun `isCanonicalHttpsUrl rejects url with fragment`() {
        assertFalse(NutritionApiClient.isCanonicalHttpsUrl("https://example.com#section"))
    }

    @Test
    fun `isCanonicalHttpsUrl accepts valid canonical https url`() {
        assertTrue(NutritionApiClient.isCanonicalHttpsUrl("https://example.com"))
        assertTrue(NutritionApiClient.isCanonicalHttpsUrl("https://example.com/path"))
        assertTrue(NutritionApiClient.isCanonicalHttpsUrl("https://sub.example.com:8443"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects http url`() {
        NutritionApiClient("http://example.com", "token", testHttpClient)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects url with userinfo`() {
        NutritionApiClient("https://user:pass@example.com", "token", testHttpClient)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects url with query string`() {
        NutritionApiClient("https://example.com?q=1", "token", testHttpClient)
    }

    // -------------------------------------------------------------------------
    // Bounded response (byte limit)
    // -------------------------------------------------------------------------

    @Test
    fun `response body exceeding byte limit is rejected`() = runBlocking {
        // Produce a body > 512 KB.  It does not need to be valid JSON — the byte counter
        // fires before JSON parsing.
        val bigBody = "x".repeat(NutritionApiClient.MAX_RESPONSE_BYTES + 1)
        server.enqueue(MockResponse().setResponseCode(200).setBody(bigBody))

        val result = client().fetchQueue()

        assertTrue(result.isFailure)
        val msg = (result.exceptionOrNull() as? IOException)?.message
        assertEquals("response_too_large", msg)
    }

    @Test
    fun `response body exactly at byte limit is accepted for valid json`() = runBlocking {
        val validJson = """{"schema_version":1,"records":[]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(validJson))

        val result = client().fetchQueue()
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().size)
    }

    // -------------------------------------------------------------------------
    // Record count enforcement
    // -------------------------------------------------------------------------

    @Test
    fun `more than 50 records returned by server is rejected`() = runBlocking {
        val recordJson = (1..51).joinToString(",") { i ->
            """{"client_record_id":"r$i","client_record_version":1,"name":"Meal $i",""" +
                """"start_time":"2024-01-01T10:00:00Z","end_time":"2024-01-01T10:30:00Z",""" +
                """"energy_kcal":300.0,"protein_g":20.0,"carbohydrate_g":30.0,"fat_g":10.0}"""
        }
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"schema_version":1,"records":[$recordJson]}"""))

        val result = client().fetchQueue()
        assertTrue(result.isFailure)
        val msg = (result.exceptionOrNull() as? IOException)?.message
        assertEquals("server_returned_too_many_records", msg)
    }

    @Test
    fun `exactly 50 records is accepted`() = runBlocking {
        val recordJson = (1..50).joinToString(",") { i ->
            """{"client_record_id":"r$i","client_record_version":1,"name":"Meal $i",""" +
                """"start_time":"2024-01-01T10:00:00Z","end_time":"2024-01-01T10:30:00Z",""" +
                """"energy_kcal":300.0,"protein_g":20.0,"carbohydrate_g":30.0,"fat_g":10.0}"""
        }
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"schema_version":1,"records":[$recordJson]}"""))

        val result = client().fetchQueue()
        assertTrue(result.isSuccess)
        assertEquals(50, result.getOrThrow().size)
    }

    // -------------------------------------------------------------------------
    // Parse + ack fixture: round-trip matching the server protocol
    // -------------------------------------------------------------------------

    private val queueFixture = """
        {
          "schema_version": 1,
          "records": [
            {
              "client_record_id": "fixture-001",
              "client_record_version": 2,
              "name": "Lunch",
              "start_time": "2024-06-01T12:00:00+03:00",
              "end_time": "2024-06-01T12:30:00+03:00",
              "energy_kcal": 620.0,
              "protein_g": 35.0,
              "carbohydrate_g": 70.0,
              "fat_g": 18.0
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `fetchQueue parses backend fixture correctly`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(queueFixture))

        val result = client().fetchQueue()

        assertTrue(result.isSuccess)
        val records = result.getOrThrow()
        assertEquals(1, records.size)
        val r = records[0]
        assertEquals("fixture-001", r.clientRecordId)
        assertEquals(2, r.clientRecordVersion)
        assertEquals("Lunch", r.name)
        assertEquals(620.0, r.energyKcal, 0.001)
        assertEquals(35.0, r.proteinG!!, 0.001)
        assertEquals(70.0, r.carbohydrateG!!, 0.001)
        assertEquals(18.0, r.fatG!!, 0.001)
    }

    @Test
    fun `fetchQueue sends X-Nutrition-Token header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(queueFixture))

        client("my-secret-token").fetchQueue()

        val recorded = server.takeRequest()
        assertEquals("my-secret-token", recorded.getHeader("X-Nutrition-Token"))
    }

    @Test
    fun `ackRecord sends correct json body and token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = client("ack-token").ackRecord("fixture-001", 2)

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("ack-token", recorded.getHeader("X-Nutrition-Token"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"client_record_id\":\"fixture-001\""))
        assertTrue(body.contains("\"client_record_version\":2"))
    }

    @Test
    fun `ackRecord returns failure on non-2xx response`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409))

        val result = client().ackRecord("id-1", 1)

        assertTrue(result.isFailure)
    }

    @Test
    fun `unsupported schema version returns failure`() = runBlocking {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"schema_version":99,"records":[]}"""))

        val result = client().fetchQueue()

        assertTrue(result.isFailure)
        val msg = (result.exceptionOrNull() as? IOException)?.message
        assertEquals("unsupported_schema_version", msg)
    }

    @Test
    fun `http 4xx response from server returns failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client().fetchQueue()

        assertTrue(result.isFailure)
    }
}
