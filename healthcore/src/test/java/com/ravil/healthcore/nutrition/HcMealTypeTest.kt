package com.ravil.healthcore.nutrition

import androidx.health.connect.client.records.MealType
import com.ravil.healthcore.core.nutrition.data.MealKind
import com.ravil.healthcore.core.nutrition.data.NutritionQueueRecord
import com.ravil.healthcore.core.nutrition.validation.RecordValidator
import org.junit.Assert.assertEquals
import org.junit.Test

class HcMealTypeTest {

    private fun record(mealType: String?) = NutritionQueueRecord(
        clientRecordId = "nutr_synthetic",
        clientRecordVersion = 1,
        name = "Synthetic meal",
        startTime = "2026-01-15T08:00:00+01:00",
        endTime = "2026-01-15T08:20:00+01:00",
        energyKcal = 400.0,
        proteinG = 15.0,
        carbohydrateG = 60.0,
        fatG = 9.0,
        mealType = mealType,
    )

    private fun hc(mealType: String?) = HcMealType.of(RecordValidator.mealKind(record(mealType)))

    @Test
    fun `known types map to Health Connect`() {
        assertEquals(MealType.MEAL_TYPE_BREAKFAST, hc("breakfast"))
        assertEquals(MealType.MEAL_TYPE_LUNCH, hc("lunch"))
        assertEquals(MealType.MEAL_TYPE_DINNER, hc("dinner"))
        assertEquals(MealType.MEAL_TYPE_SNACK, hc("snack"))
    }

    @Test
    fun `missing or unknown type is MEAL_TYPE_UNKNOWN`() {
        for (v in listOf(null, "", "brunch", "Lunch", " lunch")) {
            assertEquals(v.toString(), MealType.MEAL_TYPE_UNKNOWN, hc(v))
        }
    }

    @Test
    fun `every kind is mapped to a distinct type`() {
        val mapped = MealKind.values().map { HcMealType.of(it) }
        assertEquals(MealKind.values().size, mapped.toSet().size)
        assert(MealType.MEAL_TYPE_UNKNOWN !in mapped)
    }
}
