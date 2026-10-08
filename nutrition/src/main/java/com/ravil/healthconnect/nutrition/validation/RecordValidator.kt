package com.ravil.healthconnect.nutrition.validation

import com.ravil.healthconnect.nutrition.data.NutritionQueueRecord
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

object RecordValidator {

    private const val MAX_ENERGY_KCAL = 15_000.0
    private const val MAX_MACRO_G = 2_000.0
    private const val MAX_DURATION_HOURS = 24L

    /** Returns null if the record is valid, or a generic error reason (no PHI) if invalid. */
    fun validate(record: NutritionQueueRecord): String? {
        if (record.clientRecordId.isBlank()) return "empty_client_record_id"
        if (record.clientRecordVersion < 0) return "negative_version"
        if (record.name.isBlank()) return "empty_name"

        val energyError = validateDouble(record.energyKcal, 0.0, MAX_ENERGY_KCAL, "energy_kcal")
        if (energyError != null) return energyError

        record.proteinG?.let { v ->
            validateDouble(v, 0.0, MAX_MACRO_G, "protein_g")?.let { return it }
        }
        record.carbohydrateG?.let { v ->
            validateDouble(v, 0.0, MAX_MACRO_G, "carbohydrate_g")?.let { return it }
        }
        record.fatG?.let { v ->
            validateDouble(v, 0.0, MAX_MACRO_G, "fat_g")?.let { return it }
        }

        val start = parseOffset(record.startTime) ?: return "start_time_invalid_or_no_tz"
        val end = parseOffset(record.endTime) ?: return "end_time_invalid_or_no_tz"

        if (!end.isAfter(start)) return "end_not_after_start"
        val durationHours = Duration.between(start, end).toHours()
        if (durationHours > MAX_DURATION_HOURS) return "duration_exceeds_24h"

        return null
    }

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
