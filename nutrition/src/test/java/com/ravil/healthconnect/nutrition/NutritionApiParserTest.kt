package com.ravil.healthconnect.nutrition

import com.ravil.healthconnect.nutrition.data.AckRequest
import com.ravil.healthconnect.nutrition.data.NutritionQueueRecord
import com.ravil.healthconnect.nutrition.data.NutritionQueueResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionApiParserTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    private val sampleJson = """
        {
          "schema_version": 1,
          "records": [
            {
              "client_record_id": "rec-abc",
              "client_record_version": 2,
              "name": "Lunch",
              "start_time": "2024-06-01T12:00:00+03:00",
              "end_time": "2024-06-01T12:30:00+03:00",
              "energy_kcal": 620.5,
              "protein_g": 35.0,
              "carbohydrate_g": 70.0,
              "fat_g": 18.5,
              "description": "Chicken and rice"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses schema_version and records`() {
        val response = json.decodeFromString<NutritionQueueResponse>(sampleJson)
        assertEquals(1, response.schemaVersion)
        assertEquals(1, response.records.size)
    }

    @Test
    fun `parses record fields correctly`() {
        val record = json.decodeFromString<NutritionQueueResponse>(sampleJson).records[0]
        assertEquals("rec-abc", record.clientRecordId)
        assertEquals(2, record.clientRecordVersion)
        assertEquals("Lunch", record.name)
        assertEquals("2024-06-01T12:00:00+03:00", record.startTime)
        assertEquals("2024-06-01T12:30:00+03:00", record.endTime)
        assertEquals(620.5, record.energyKcal, 0.001)
        assertEquals(35.0, record.proteinG!!, 0.001)
        assertEquals(70.0, record.carbohydrateG!!, 0.001)
        assertEquals(18.5, record.fatG!!, 0.001)
        assertEquals("Chicken and rice", record.description)
    }

    @Test
    fun `optional macros default to null when absent`() {
        val minimalJson = """
            {
              "schema_version": 1,
              "records": [{
                "client_record_id": "r1",
                "client_record_version": 1,
                "name": "Snack",
                "start_time": "2024-06-01T15:00:00Z",
                "end_time": "2024-06-01T15:10:00Z",
                "energy_kcal": 100.0
              }]
            }
        """.trimIndent()
        val record = json.decodeFromString<NutritionQueueResponse>(minimalJson).records[0]
        assertNull(record.proteinG)
        assertNull(record.carbohydrateG)
        assertNull(record.fatG)
        assertNull(record.description)
    }

    @Test
    fun `unknown fields ignored safely`() {
        val withExtras = """
            {
              "schema_version": 1,
              "records": [{
                "client_record_id": "r2",
                "client_record_version": 1,
                "name": "Dinner",
                "start_time": "2024-06-01T19:00:00Z",
                "end_time": "2024-06-01T19:45:00Z",
                "energy_kcal": 750.0,
                "unknown_field_future": "ignored",
                "vitamin_c_mg": 45.2
              }]
            }
        """.trimIndent()
        val record = json.decodeFromString<NutritionQueueResponse>(withExtras).records[0]
        assertEquals("Dinner", record.name)
        assertEquals(750.0, record.energyKcal, 0.001)
    }

    @Test
    fun `empty records list parsed`() {
        val emptyJson = """{"schema_version": 1, "records": []}"""
        val response = json.decodeFromString<NutritionQueueResponse>(emptyJson)
        assertTrue(response.records.isEmpty())
    }

    @Test
    fun `AckRequest serializes correctly`() {
        val ack = AckRequest("rec-abc", 2)
        val encoded = json.encodeToString(ack)
        assertTrue(encoded.contains("\"client_record_id\":\"rec-abc\""))
        assertTrue(encoded.contains("\"client_record_version\":2"))
    }

    @Test
    fun `AckRequest does not serialize extra fields`() {
        val ack = AckRequest("id-1", 5)
        val encoded = json.encodeToString(ack)
        assertEquals(2, json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(encoded).size)
    }

    @Test
    fun `records list is bounded to 50`() {
        val manyRecords = (1..60).joinToString(",") { i ->
            """{
              "client_record_id": "r$i",
              "client_record_version": 1,
              "name": "Meal $i",
              "start_time": "2024-06-01T10:00:00Z",
              "end_time": "2024-06-01T10:30:00Z",
              "energy_kcal": 300.0
            }"""
        }
        val bigJson = """{"schema_version": 1, "records": [$manyRecords]}"""
        val response = json.decodeFromString<NutritionQueueResponse>(bigJson)
        val taken = response.records.take(50)
        assertEquals(50, taken.size)
    }
}
