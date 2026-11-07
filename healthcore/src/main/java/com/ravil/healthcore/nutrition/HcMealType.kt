package com.ravil.healthcore.nutrition

import androidx.health.connect.client.records.MealType
import com.ravil.healthcore.core.nutrition.data.MealKind

/** Maps health-core's meal type to Health Connect's; no type becomes MEAL_TYPE_UNKNOWN. */
internal object HcMealType {
    fun of(kind: MealKind?): Int = when (kind) {
        MealKind.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
        MealKind.LUNCH -> MealType.MEAL_TYPE_LUNCH
        MealKind.DINNER -> MealType.MEAL_TYPE_DINNER
        MealKind.SNACK -> MealType.MEAL_TYPE_SNACK
        null -> MealType.MEAL_TYPE_UNKNOWN
    }
}
