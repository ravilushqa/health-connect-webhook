package com.ravil.healthcore.core.model

import java.time.Duration
import java.time.Instant

// Ported from :app (HealthConnectManager.kt, DataResolution.kt). The data classes below are
// copied verbatim so the payload built from them is the one HC Webhook sends; ParityTest
// checks this against :app's own serializer.

/** The record types Health Core reads. Names match :app's HealthDataType. */
enum class HealthDataType {
    STEPS,
    SLEEP,
    HEART_RATE,
    HEART_RATE_VARIABILITY,
    DISTANCE,
    ACTIVE_CALORIES,
    TOTAL_CALORIES,
    WEIGHT,
    HEIGHT,
    BLOOD_PRESSURE,
    BLOOD_GLUCOSE,
    OXYGEN_SATURATION,
    BODY_TEMPERATURE,
    SKIN_TEMPERATURE,
    RESPIRATORY_RATE,
    RESTING_HEART_RATE,
    EXERCISE,
    HYDRATION,
    NUTRITION,
    BASAL_METABOLIC_RATE,
    BODY_FAT,
    LEAN_BODY_MASS,
    BODY_WATER_MASS,
    VO2_MAX,
    BONE_MASS,
    MENSTRUATION_FLOW,
    MENSTRUATION_PERIOD,
    INTERMENSTRUAL_BLEEDING,
    OVULATION_TEST,
    CERVICAL_MUCUS,
    SEXUAL_ACTIVITY,
    BASAL_BODY_TEMPERATURE
}

/**
 * How export granularity can be configured for a [HealthDataType].
 *
 * Stored as minutes in preferences:
 * - [RESOLUTION_DAILY] (-1): one total per calendar day (interval types only)
 * - [RESOLUTION_FULL] (0): every raw record / sample
 * - [SLEEP_SUMMARY] (1): sleep sessions without stage breakdown
 * - positive N: bucket raw data into N-minute windows (default for sample series is 1)
 */
enum class DataResolutionFamily {
    /** Default daily aggregate; also supports full raw and N-minute buckets. */
    INTERVAL_WITH_DAILY,
    /** Default raw intervals; optional daily and N-minute buckets. */
    INTERVAL_RAW_DEFAULT,
    /** Default 1-minute buckets; optional full samples or wider N-minute buckets. */
    SAMPLE_SERIES,
    /** Default full session with stages; optional summary without stages. */
    SLEEP_SESSION,
}

const val RESOLUTION_DAILY = -1
const val RESOLUTION_FULL = 0
const val SLEEP_SUMMARY = 1

val HealthDataType.resolutionFamily: DataResolutionFamily?
    get() = when (this) {
        HealthDataType.STEPS,
        HealthDataType.DISTANCE,
        HealthDataType.ACTIVE_CALORIES -> DataResolutionFamily.INTERVAL_WITH_DAILY
        HealthDataType.TOTAL_CALORIES,
        HealthDataType.HYDRATION,
        HealthDataType.NUTRITION -> DataResolutionFamily.INTERVAL_RAW_DEFAULT
        HealthDataType.HEART_RATE,
        HealthDataType.HEART_RATE_VARIABILITY,
        HealthDataType.OXYGEN_SATURATION,
        HealthDataType.RESPIRATORY_RATE,
        HealthDataType.SKIN_TEMPERATURE -> DataResolutionFamily.SAMPLE_SERIES
        HealthDataType.SLEEP -> DataResolutionFamily.SLEEP_SESSION
        else -> null
    }

/** Preserves pre-resolution behavior for interval/sleep; sample series default to 1-minute buckets to avoid OutOfMemoryError on full raw samples. */
val HealthDataType.defaultResolutionMinutes: Int
    get() = when (resolutionFamily) {
        DataResolutionFamily.INTERVAL_WITH_DAILY -> RESOLUTION_DAILY
        DataResolutionFamily.INTERVAL_RAW_DEFAULT,
        DataResolutionFamily.SLEEP_SESSION -> RESOLUTION_FULL
        DataResolutionFamily.SAMPLE_SERIES -> 1
        null -> RESOLUTION_FULL
    }

val HealthDataType.supportsResolution: Boolean
    get() = resolutionFamily != null

data class HealthData(
    val steps: List<StepsData>,
    val sleep: List<SleepData>,
    val heartRate: List<HeartRateData>,
    val heartRateVariability: List<HeartRateVariabilityData>,
    val distance: List<DistanceData>,
    val activeCalories: List<ActiveCaloriesData>,
    val totalCalories: List<TotalCaloriesData>,
    val weight: List<WeightData>,
    val height: List<HeightData>,
    val bloodPressure: List<BloodPressureData>,
    val bloodGlucose: List<BloodGlucoseData>,
    val oxygenSaturation: List<OxygenSaturationData>,
    val bodyTemperature: List<BodyTemperatureData>,
    val skinTemperature: List<SkinTemperatureData>,
    val respiratoryRate: List<RespiratoryRateData>,
    val restingHeartRate: List<RestingHeartRateData>,
    val exercise: List<ExerciseData>,
    val hydration: List<HydrationData>,
    val nutrition: List<NutritionData>,
    val basalMetabolicRate: List<BasalMetabolicRateData>,
    val bodyFat: List<BodyFatData>,
    val leanBodyMass: List<LeanBodyMassData>,
    val bodyWaterMass: List<BodyWaterMassData>,
    val vo2Max: List<Vo2MaxData>,
    val boneMass: List<BoneMassData>,
    val menstruationFlow: List<MenstruationFlowData>,
    val menstruationPeriod: List<MenstruationPeriodData>,
    val intermenstrualBleeding: List<IntermenstrualBleedingData>,
    val ovulationTest: List<OvulationTestData>,
    val cervicalMucus: List<CervicalMucusData>,
    val sexualActivity: List<SexualActivityData>,
    val basalBodyTemperature: List<BasalBodyTemperatureData>
) {
    fun totalRecordCount(): Int =
        steps.size + sleep.size + heartRate.size + heartRateVariability.size +
            distance.size + activeCalories.size + totalCalories.size + weight.size +
            height.size + bloodPressure.size + bloodGlucose.size + oxygenSaturation.size +
            bodyTemperature.size + skinTemperature.size + respiratoryRate.size +
            restingHeartRate.size + exercise.size + hydration.size + nutrition.size +
            basalMetabolicRate.size + bodyFat.size + leanBodyMass.size + bodyWaterMass.size +
            vo2Max.size + boneMass.size + menstruationFlow.size + menstruationPeriod.size +
            intermenstrualBleeding.size + ovulationTest.size + cervicalMucus.size +
            sexualActivity.size + basalBodyTemperature.size
}

/**
 * Provenance of a Health Connect record: which app wrote it, how it was recorded, and on what
 * device. Lets downstream consumers tell e.g. an automatically-detected session apart from one
 * recorded by gym equipment, and deduplicate records that arrive from multiple source apps.
 *
 * [id] / [clientRecordId] / [clientRecordVersion] / [lastModifiedTime] carry record identity so a
 * consumer can deduplicate, correlate with the source app's own records, and detect updates.
 * [zoneOffsetSeconds] / [startZoneOffsetSeconds] / [endZoneOffsetSeconds] are the UTC offsets
 * Health Connect stores, for local-day aggregation on the server. Only the pair that applies to
 * the record's shape (instant vs interval) is set; the other is null.
 */
data class RecordMetadata(
    val dataOrigin: String,
    val recordingMethod: String,
    val deviceManufacturer: String? = null,
    val deviceModel: String? = null,
    val deviceType: Int? = null,
    val id: String = "",
    val clientRecordId: String? = null,
    val clientRecordVersion: Long = 0L,
    val lastModifiedTime: Instant? = null,
    val zoneOffsetSeconds: Int? = null,
    val startZoneOffsetSeconds: Int? = null,
    val endZoneOffsetSeconds: Int? = null,
)


/**
 * Metadata for a value aggregated from several samples (a downsampled bucket).
 *
 * Provenance (origin, recording method, device, zone offsets) is kept, but per-record identity
 * is dropped: a bucket is not a Health Connect record, so it must not carry the id, client id,
 * version or modification time of whichever sample happened to be first in it. Consumers then
 * identify buckets by provenance and time, as they must.
 */
fun RecordMetadata.forAggregate(): RecordMetadata = copy(
    id = "",
    clientRecordId = null,
    clientRecordVersion = 0L,
    lastModifiedTime = null,
)

data class StepsData(
    val count: Long,
    val startTime: Instant,
    val endTime: Instant,
    val metadata: RecordMetadata? = null
)

data class SleepData(
    val sessionEndTime: Instant,
    val duration: Duration,
    val stages: List<SleepStage>,
    val metadata: RecordMetadata? = null
)

data class SleepStage(
    val stage: String,
    val startTime: Instant,
    val endTime: Instant,
    val duration: Duration
)

data class HeartRateData(
    val bpm: Long,
    val time: Instant,
    val min: Long? = null,
    val max: Long? = null,
    val metadata: RecordMetadata? = null,
)

data class HeartRateVariabilityData(
    val rmssdMillis: Double,
    val time: Instant,
    val min: Double? = null,
    val max: Double? = null,
    val metadata: RecordMetadata? = null,
)

data class DistanceData(
    val meters: Double,
    val startTime: Instant,
    val endTime: Instant,
    val metadata: RecordMetadata? = null
)

data class ActiveCaloriesData(
    val calories: Double,
    val startTime: Instant,
    val endTime: Instant,
    val metadata: RecordMetadata? = null
)

data class TotalCaloriesData(
    val calories: Double,
    val startTime: Instant,
    val endTime: Instant,
    val metadata: RecordMetadata? = null
)

data class WeightData(
    val kilograms: Double,
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class HeightData(
    val meters: Double,
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class BloodPressureData(
    val systolic: Double,
    val diastolic: Double,
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class BloodGlucoseData(
    val mmolPerLiter: Double,
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class OxygenSaturationData(
    val percentage: Double,
    val time: Instant,
    val min: Double? = null,
    val max: Double? = null,
    val metadata: RecordMetadata? = null,
)

data class BodyTemperatureData(
    val celsius: Double,
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class SkinTemperatureData(
    val time: Instant,
    val deltaCelsius: Double,
    val baselineCelsius: Double?,
    val measurementLocation: Int,
    val minDeltaCelsius: Double? = null,
    val maxDeltaCelsius: Double? = null,
    val metadata: RecordMetadata? = null,
)

data class RespiratoryRateData(
    val rate: Double,
    val time: Instant,
    val min: Double? = null,
    val max: Double? = null,
    val metadata: RecordMetadata? = null,
)

data class RestingHeartRateData(
    val bpm: Long,
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class ExerciseData(
    val type: String,
    val title: String? = null,
    val startTime: Instant,
    val endTime: Instant,
    val duration: Duration,
    val distanceMeters: Double? = null,
    val steps: Long? = null,
    val avgCadenceSpm: Double? = null,
    val maxCadenceSpm: Double? = null,
    val strideLengthMeters: Double? = null,
    val metadata: RecordMetadata? = null
)

data class HydrationData(
    val liters: Double,
    val startTime: Instant,
    val endTime: Instant,
    val metadata: RecordMetadata? = null
)

data class NutritionData(
    val calories: Double?,
    val protein: Double?,
    val carbs: Double?,
    val fat: Double?,
    val sugar: Double?,
    val sodium: Double?,
    val dietaryFiber: Double?,
    val name: String?,
    val startTime: Instant,
    val endTime: Instant,
    val metadata: RecordMetadata? = null
)

data class BasalMetabolicRateData(
    val watts: Double,
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class BodyFatData(
    val percentage: Double,
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class LeanBodyMassData(
    val kilograms: Double,
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class BodyWaterMassData(
    val kilograms: Double,
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class Vo2MaxData(
    val mlPerKgPerMin: Double,
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class BoneMassData(
    val kilograms: Double,
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class MenstruationFlowData(
    val flow: Int,
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class MenstruationPeriodData(
    val startTime: Instant,
    val endTime: Instant,
    val metadata: RecordMetadata? = null
)

data class IntermenstrualBleedingData(
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class OvulationTestData(
    val result: Int,
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class CervicalMucusData(
    val appearance: Int,
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class SexualActivityData(
    val protectionUsed: Int,
    val time: Instant,
    val metadata: RecordMetadata? = null
)

data class BasalBodyTemperatureData(
    val celsius: Double,
    val measurementLocation: Int,
    val time: Instant,
    val metadata: RecordMetadata? = null
)
