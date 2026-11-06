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
     * The choices offered per type, stored as minutes like HC Webhook does.
     *
     * Interval types (steps, distance, calories, hydration, nutrition) are always sent raw:
     * one Health Connect record per entry, with its id. HC Webhook's daily and N-minute
     * buckets carry no identity and, for the current day or bucket, an end time that moves
     * with every sync, so health-core stores overlapping intervals and cannot total the day
     * ("Overlapping step intervals", total null). Raw records are keyed by id instead.
     */
    fun options(type: HealthDataType): List<ResolutionOption> = when (type.resolutionFamily) {
        DataResolutionFamily.INTERVAL_WITH_DAILY, DataResolutionFamily.INTERVAL_RAW_DEFAULT -> listOf(
            ResolutionOption(RESOLUTION_FULL, "Raw"),
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

    /** Health Core's default: HC Webhook's, except that interval types are raw (see [options]). */
    fun default(type: HealthDataType): Int = when (type.resolutionFamily) {
        DataResolutionFamily.INTERVAL_WITH_DAILY, DataResolutionFamily.INTERVAL_RAW_DEFAULT -> RESOLUTION_FULL
        else -> type.defaultResolutionMinutes
    }

    /** Types that have a resolution at all; every one of them is passed to the reader. */
    val withResolution: List<HealthDataType> = HealthDataType.entries.filter { it.supportsResolution }

    /** Types with more than one choice, shown in settings. */
    val configurable: List<HealthDataType> = withResolution.filter { options(it).size > 1 }

    /** A stored value, or the default when it is missing or no longer offered. */
    fun effective(type: HealthDataType, stored: Int?): Int =
        stored?.takeIf { v -> options(type).any { it.minutes == v } } ?: default(type)
}
