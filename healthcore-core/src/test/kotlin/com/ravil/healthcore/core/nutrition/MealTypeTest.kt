package com.ravil.healthcore.core.nutrition

import com.ravil.healthcore.core.nutrition.data.MealKind
import com.ravil.healthcore.core.nutrition.data.NutritionQueueRecord
import com.ravil.healthcore.core.nutrition.data.NutritionQueueResponse
import com.ravil.healthcore.core.nutrition.validation.RecordValidator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MealTypeTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    private fun queue(mealType: String?): String {
        val field = if (mealType == null) "" else """, "meal_type": $mealType"""
        return """
            {"schema_version": 1, "records": [{
              "client_record_id": "nutr_synthetic", "client_record_version": 3,
              "name": "Synthetic oats",
              "start_time": "2026-01-15T08:00:00+01:00", "end_time": "2026-01-15T08:20:00+01:00",
              "energy_kcal": 400.0, "protein_g": 15.0, "carbohydrate_g": 60.0, "fat_g": 9.0,
              "description": "[AI estimate]"$field
            }]}
        """.trimIndent()
    }

    private fun parse(mealType: String?): NutritionQueueRecord =
        json.decodeFromString(NutritionQueueResponse.serializer(), queue(mealType)).records.single()

    @Test
    fun `meal_type is parsed`() {
        assertEquals("lunch", parse("\"lunch\"").mealType)
        assertEquals(MealKind.LUNCH, RecordValidator.mealKind(parse("\"lunch\"")))
    }

    @Test
    fun `absent or null meal_type is null`() {
        assertNull(parse(null).mealType)
        assertNull(parse("null").mealType)
        assertNull(RecordValidator.mealKind(parse(null)))
    }

    @Test
    fun `every wire value maps to its kind`() {
        for (kind in MealKind.values()) {
            assertEquals(kind, RecordValidator.mealKind(parse("\"${kind.wire}\"")))
        }
        assertEquals(listOf("breakfast", "lunch", "dinner", "snack"), MealKind.values().map { it.wire })
    }

    @Test
    fun `unknown meal_type is null but the record stays valid`() {
        for (v in listOf("\"brunch\"", "\"Lunch\"", "\"\"", "\" snack\"")) {
            val r = parse(v)
            assertNull(v, RecordValidator.mealKind(r))
            assertNull(v, RecordValidator.validate(r))
        }
    }

    @Test
    fun `known meal_type does not affect validation`() {
        for (kind in MealKind.values()) {
            assertNull(RecordValidator.validate(parse("\"${kind.wire}\"")))
        }
    }
}
