package com.ravil.healthcore.core.ingest

import com.ravil.healthcore.core.HealthCoreRules
import com.ravil.healthcore.core.Synthetic
import com.ravil.healthcore.core.appref.AppReference
import com.ravil.healthcore.core.model.HealthData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IngestPayloadTest {
    private val version = "1.0.0-test"

    private fun ours(data: HealthData) = IngestPayload.build(data, version, Synthetic.NOW)

    /** :app stamps Instant.now(); everything else must be identical, byte for byte. */
    private fun app(data: HealthData): String {
        val raw = AppReference(version).buildJsonPayload(data)
        val obj = Json.parseToJsonElement(raw).jsonObject
        val fixed = JsonObject(obj.toMutableMap().apply { put("timestamp", JsonPrimitive(Synthetic.NOW.toString())) })
        return fixed.toString()
    }

    private fun variants(): List<Pair<String, HealthData>> {
        val all = Synthetic.all()
        val e = Synthetic.empty()
        return listOf(
            "all" to all,
            "empty" to e,
            "steps only" to e.copy(steps = all.steps),
            "weight without height (no bmi)" to e.copy(weight = all.weight),
            "weight and height (bmi)" to e.copy(weight = all.weight, height = all.height),
            "buckets only" to e.copy(
                heartRate = all.heartRate.filter { it.min != null },
                oxygenSaturation = all.oxygenSaturation.filter { it.min != null },
                skinTemperature = all.skinTemperature.filter { it.minDeltaCelsius != null },
            ),
            "no metadata" to e.copy(
                steps = all.steps.map { it.copy(metadata = null) },
                sleep = all.sleep.map { it.copy(metadata = null) },
                nutrition = all.nutrition.map { it.copy(metadata = null) },
            ),
        )
    }

    @Test
    fun `payload is identical to HC Webhook's serializer on the same input`() {
        for ((name, data) in variants()) {
            assertEquals(name, app(data), ours(data))
        }
    }

    @Test
    fun `payload matches the committed contract fixture`() {
        val fixture = File(javaClass.getResource("/contract/ingest-all-types.json")!!.toURI())
        val actual = pretty(ours(Synthetic.all()))
        if (System.getProperty("healthcore.updateFixtures") == "true") {
            File("src/test/resources/contract/ingest-all-types.json").writeText(actual)
        }
        assertEquals(fixture.readText(), actual)
    }

    @Test
    fun `health-core would accept the payload`() {
        for ((name, data) in variants()) {
            assertEquals(name, emptyList<String>(), HealthCoreRules.validate(ours(data)))
        }
    }

    @Test
    fun `every record type in the payload is one health-core stores`() {
        val keys = Json.parseToJsonElement(ours(Synthetic.all())).jsonObject.keys - setOf("timestamp", "app_version")
        assertEquals(emptySet<String>(), keys - HealthCoreRules.KNOWN_TYPES)
        assertEquals("every known type is covered by the fixture", HealthCoreRules.KNOWN_TYPES, keys)
    }

    @Test
    fun `record identity reaches the payload, buckets carry none`() {
        val p = Json.parseToJsonElement(ours(Synthetic.all())).jsonObject
        val steps = p["steps"].toString()
        assertTrue(steps.contains("\"id\":\"00000000-0000-4000-8000-000000000001\""))
        assertTrue(steps.contains("\"last_modified_time\":"))
        val sleep = p["sleep"].toString()
        assertTrue(sleep.contains("\"client_record_id\":\"client-3\",\"client_record_version\":3"))
        val bucket = p["heart_rate"].toString().substringAfter("\"avg\"")
        assertTrue("bucket has no id", !bucket.contains("\"id\""))
    }

    private fun pretty(json: String): String =
        Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), Json.parseToJsonElement(json).jsonObject) + "\n"
}
