package com.ravil.healthcore.core.model

/** A human-readable name for a record type (English only). */
val HealthDataType.label: String
    get() = when (this) {
        HealthDataType.VO2_MAX -> "VO2 max"
        HealthDataType.HEART_RATE_VARIABILITY -> "Heart rate variability (HRV)"
        else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

/** One choice of export granularity for a type, stored as minutes like HC Webhook does. */
data class ResolutionOption(val minutes: Int, val label: String)

object Resolutions {
    /**
     * The choices offered per type. They are a subset of what HC Webhook offers, with the
     * same stored values, so its settings can be copied one to one. Types without a
     * family are always sent in full.
     */
    fun options(type: HealthDataType): List<ResolutionOption> = when (type.resolutionFamily) {
        DataResolutionFamily.INTERVAL_WITH_DAILY, DataResolutionFamily.INTERVAL_RAW_DEFAULT -> listOf(
            ResolutionOption(RESOLUTION_DAILY, "Daily"),
            ResolutionOption(RESOLUTION_FULL, "Raw"),
            ResolutionOption(15, "15 min"),
            ResolutionOption(60, "1 h"),
        )
        DataResolutionFamily.SAMPLE_SERIES -> listOf(
            ResolutionOption(RESOLUTION_FULL, "Raw"),
            ResolutionOption(1, "1 min"),
            ResolutionOption(5, "5 min"),
            ResolutionOption(15, "15 min"),
        )
        DataResolutionFamily.SLEEP_SESSION -> listOf(
            ResolutionOption(RESOLUTION_FULL, "With stages"),
            ResolutionOption(SLEEP_SUMMARY, "Summary"),
        )
        null -> emptyList()
    }

    /** Types whose granularity can be chosen, in display order. */
    val configurable: List<HealthDataType> = HealthDataType.entries.filter { it.supportsResolution }

    /** A stored value, or the default when it is missing or no longer offered. */
    fun effective(type: HealthDataType, stored: Int?): Int =
        stored?.takeIf { v -> options(type).any { it.minutes == v } } ?: type.defaultResolutionMinutes
}
