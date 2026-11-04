package com.hcwebhook.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

class RecordMetadataJsonTest {

    private val modified = Instant.parse("2026-05-09T12:00:00.250Z")

    private val full = RecordMetadata(
        dataOrigin = "com.example",
        recordingMethod = "automatically_recorded",
        deviceManufacturer = "Acme",
        deviceModel = "Watch",
        deviceType = 1,
        id = "hc-record-1",
        clientRecordId = "app-record-1",
        clientRecordVersion = 3L,
        lastModifiedTime = modified,
    )

    private fun metadataJson(meta: RecordMetadata): JsonObject =
        buildJsonObject { putRecordMetadata(meta) }["metadata"]!!.jsonObject

    @Test
    fun emitsRecordIdentity() {
        val json = metadataJson(full)

        assertEquals("com.example", json["data_origin"]!!.jsonPrimitive.content)
        assertEquals("automatically_recorded", json["recording_method"]!!.jsonPrimitive.content)
        assertEquals("Acme", json["device"]!!.jsonObject["manufacturer"]!!.jsonPrimitive.content)
        assertEquals("hc-record-1", json["id"]!!.jsonPrimitive.content)
        assertEquals("app-record-1", json["client_record_id"]!!.jsonPrimitive.content)
        assertEquals(3L, json["client_record_version"]!!.jsonPrimitive.long)
        assertEquals("2026-05-09T12:00:00.250Z", json["last_modified_time"]!!.jsonPrimitive.content)
    }

    @Test
    fun omitsAbsentIdentity() {
        val json = metadataJson(
            RecordMetadata(dataOrigin = "com.example", recordingMethod = "manual_entry")
        )

        assertEquals(setOf("data_origin", "recording_method"), json.keys)
    }

    @Test
    fun clientRecordVersionOnlyWithClientRecordId() {
        val json = metadataJson(full.copy(clientRecordId = null))

        assertFalse(json.containsKey("client_record_id"))
        assertFalse(json.containsKey("client_record_version"))
        assertEquals("hc-record-1", json["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun aggregateKeepsProvenanceAndDropsIdentity() {
        val bucket = full.copy(zoneOffsetSeconds = 3600).forAggregate()

        assertEquals("com.example", bucket.dataOrigin)
        assertEquals("Watch", bucket.deviceModel)
        assertEquals(3600, bucket.zoneOffsetSeconds)
        assertEquals("", bucket.id)
        assertEquals(null, bucket.clientRecordId)
        assertEquals(0L, bucket.clientRecordVersion)
        assertEquals(null, bucket.lastModifiedTime)

        val json = metadataJson(bucket)
        assertEquals(setOf("data_origin", "recording_method", "device"), json.keys)
    }
}
