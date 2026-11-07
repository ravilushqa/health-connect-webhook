package com.ravil.healthcore.core.nutrition.validation

import com.ravil.healthcore.core.nutrition.data.MealKind
import com.ravil.healthcore.core.nutrition.data.NutritionQueueRecord
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

object RecordValidator {

    private const val MAX_ENERGY_KCAL = 5_000.0
    private const val MAX_PROTEIN_G = 500.0
    private const val MAX_CARBS_G = 1_000.0
    private const val MAX_FAT_G = 500.0
    private const val MAX_FIBER_G = 500.0
    private const val MAX_SUGAR_G = 500.0
    private const val MAX_SODIUM_MG = 50_000.0
    private const val MAX_DURATION_HOURS = 24L

    /** Returns null if valid, or an error key (no PHI) if invalid. */
    fun validate(record: NutritionQueueRecord): String? {
        if (record.clientRecordId.isBlank()) return "empty_client_record_id"
        if (record.clientRecordVersion < 0) return "negative_version"
        if (record.name.isBlank()) return "empty_name"

        validateDouble(record.energyKcal, 0.0, MAX_ENERGY_KCAL, "energy_kcal")?.let { return it }

        // Protocol requires all four core macros
        if (record.proteinG == null) return "protein_g_required"
        validateDouble(record.proteinG, 0.0, MAX_PROTEIN_G, "protein_g")?.let { return it }

        if (record.carbohydrateG == null) return "carbohydrate_g_required"
        validateDouble(record.carbohydrateG, 0.0, MAX_CARBS_G, "carbohydrate_g")?.let { return it }

        if (record.fatG == null) return "fat_g_required"
        validateDouble(record.fatG, 0.0, MAX_FAT_G, "fat_g")?.let { return it }

        if (record.energyKcal == 0.0 && record.proteinG == 0.0 &&
            record.carbohydrateG == 0.0 && record.fatG == 0.0
        ) return "all_zero_nutrition"

        record.fiberG?.let { validateDouble(it, 0.0, MAX_FIBER_G, "fiber_g")?.let { e -> return e } }
        record.sugarG?.let { validateDouble(it, 0.0, MAX_SUGAR_G, "sugar_g")?.let { e -> return e } }
        record.sodiumMg?.let { validateDouble(it, 0.0, MAX_SODIUM_MG, "sodium_mg")?.let { e -> return e } }

        val start = parseOffset(record.startTime) ?: return "start_time_invalid_or_no_tz"
        val end = parseOffset(record.endTime) ?: return "end_time_invalid_or_no_tz"

        if (!end.isAfter(start)) return "end_not_after_start"
        if (Duration.between(start, end).toHours() > MAX_DURATION_HOURS) return "duration_exceeds_24h"

        return null
    }

    /**
     * The record's meal type if it is one of the known values, else null (written as
     * MEAL_TYPE_UNKNOWN). Deliberately not a validation error: the meal type is a label,
     * not data, and rejecting the record would keep the meal itself (the calories) out of
     * Health Connect and leave it unacked in the queue. It also keeps this build working
     * if a newer server sends a value it does not know, as ignoreUnknownKeys does for
     * new fields.
     */
    fun mealKind(record: NutritionQueueRecord): MealKind? = MealKind.fromWire(record.mealType)

    fun parseOffset(timeStr: String): OffsetDateTime? = try {
        OffsetDateTime.parse(timeStr)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun validateDouble(v: Double, min: Double, max: Double, field: String): String? {
        if (!v.isFinite()) return "${field}_not_finite"
        if (v < min) return "${field}_negative"
        if (v > max) return "${field}_exceeds_limit"
        return null
    }
}
