package com.ravil.healthcore.core.nutrition.data

/**
 * Meal type as health-core sends it in `meal_type`. The Android module maps it to
 * Health Connect's `MealType.MEAL_TYPE_*`; a missing or unknown value is written as
 * `MEAL_TYPE_UNKNOWN`.
 */
enum class MealKind(val wire: String) {
    BREAKFAST("breakfast"),
    LUNCH("lunch"),
    DINNER("dinner"),
    SNACK("snack");

    companion object {
        /** The kind for an exact wire value, or null for null and anything unknown. */
        fun fromWire(value: String?): MealKind? = values().firstOrNull { it.wire == value }
    }
}
