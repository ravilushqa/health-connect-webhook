package com.ravil.healthcore.core.ingest

import com.ravil.healthcore.core.model.HealthData
import com.ravil.healthcore.core.model.HealthDataType
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Pure parts of an ingest run, ported from :app's SyncManager and HealthConnectManager:
 * the read window, the per-type cursors that make the next read incremental, and the
 * size limits checked before anything is sent.
 */
object IngestPlan {
    /** Every automatic read looks this far back (HC Webhook's LOOKBACK_HOURS). */
    const val LOOKBACK_HOURS = 48L

    /** Soft cap before JSON encoding (HC Webhook's MAX_RECORDS_PER_PAYLOAD). */
    const val MAX_RECORDS_PER_PAYLOAD = 25_000

    /** health-core rejects ingest bodies over 8 MiB (httpapi MaxBody). */
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    data class Window(val start: Instant, val end: Instant)

    fun window(now: Instant, lookbackHours: Long = LOOKBACK_HOURS): Window =
        Window(now.minus(lookbackHours, ChronoUnit.HOURS), now)

    fun recordCount(data: HealthData): Int = data.totalRecordCount()

    fun isEmpty(data: HealthData): Boolean = recordCount(data) == 0

    /** Records per type, for the run summary (counts only, never values). */
    fun countsByType(data: HealthData): Map<HealthDataType, Int> = buildMap {
        fun add(type: HealthDataType, n: Int) { if (n > 0) put(type, n) }
        add(HealthDataType.STEPS, data.steps.size)
        add(HealthDataType.SLEEP, data.sleep.size)
        add(HealthDataType.HEART_RATE, data.heartRate.size)
        add(HealthDataType.HEART_RATE_VARIABILITY, data.heartRateVariability.size)
        add(HealthDataType.DISTANCE, data.distance.size)
        add(HealthDataType.ACTIVE_CALORIES, data.activeCalories.size)
        add(HealthDataType.TOTAL_CALORIES, data.totalCalories.size)
        add(HealthDataType.WEIGHT, data.weight.size)
        add(HealthDataType.HEIGHT, data.height.size)
        add(HealthDataType.BLOOD_PRESSURE, data.bloodPressure.size)
        add(HealthDataType.BLOOD_GLUCOSE, data.bloodGlucose.size)
        add(HealthDataType.OXYGEN_SATURATION, data.oxygenSaturation.size)
        add(HealthDataType.BODY_TEMPERATURE, data.bodyTemperature.size)
        add(HealthDataType.SKIN_TEMPERATURE, data.skinTemperature.size)
        add(HealthDataType.RESPIRATORY_RATE, data.respiratoryRate.size)
        add(HealthDataType.RESTING_HEART_RATE, data.restingHeartRate.size)
        add(HealthDataType.EXERCISE, data.exercise.size)
        add(HealthDataType.HYDRATION, data.hydration.size)
        add(HealthDataType.NUTRITION, data.nutrition.size)
        add(HealthDataType.BASAL_METABOLIC_RATE, data.basalMetabolicRate.size)
        add(HealthDataType.BODY_FAT, data.bodyFat.size)
        add(HealthDataType.LEAN_BODY_MASS, data.leanBodyMass.size)
        add(HealthDataType.BODY_WATER_MASS, data.bodyWaterMass.size)
        add(HealthDataType.VO2_MAX, data.vo2Max.size)
        add(HealthDataType.BONE_MASS, data.boneMass.size)
        add(HealthDataType.MENSTRUATION_FLOW, data.menstruationFlow.size)
        add(HealthDataType.MENSTRUATION_PERIOD, data.menstruationPeriod.size)
        add(HealthDataType.INTERMENSTRUAL_BLEEDING, data.intermenstrualBleeding.size)
        add(HealthDataType.OVULATION_TEST, data.ovulationTest.size)
        add(HealthDataType.CERVICAL_MUCUS, data.cervicalMucus.size)
        add(HealthDataType.SEXUAL_ACTIVITY, data.sexualActivity.size)
        add(HealthDataType.BASAL_BODY_TEMPERATURE, data.basalBodyTemperature.size)
    }

    /**
     * Cursors (epoch ms) to store after a successful delivery, per type that had records.
     * Port of SyncManager.updateSyncTimestamps: interval types advance to the latest end
     * time that is not in the future (a projection ending at midnight must not starve
     * later records); instant types advance to the latest sample time.
     */
    fun nextCursors(data: HealthData, now: Instant): Map<HealthDataType, Long> = buildMap {
        fun interval(type: HealthDataType, ends: List<Instant>) {
            if (ends.isEmpty()) return
            ends.filter { !it.isAfter(now) }.maxOrNull()?.let { put(type, it.toEpochMilli()) }
        }
        fun instant(type: HealthDataType, times: List<Instant>) {
            if (times.isEmpty()) return
            put(type, times.max().toEpochMilli())
        }
        interval(HealthDataType.STEPS, data.steps.map { it.endTime })
        interval(HealthDataType.SLEEP, data.sleep.map { it.sessionEndTime })
        instant(HealthDataType.HEART_RATE, data.heartRate.map { it.time })
        instant(HealthDataType.HEART_RATE_VARIABILITY, data.heartRateVariability.map { it.time })
        interval(HealthDataType.DISTANCE, data.distance.map { it.endTime })
        interval(HealthDataType.ACTIVE_CALORIES, data.activeCalories.map { it.endTime })
        interval(HealthDataType.TOTAL_CALORIES, data.totalCalories.map { it.endTime })
        instant(HealthDataType.WEIGHT, data.weight.map { it.time })
        instant(HealthDataType.HEIGHT, data.height.map { it.time })
        instant(HealthDataType.BLOOD_PRESSURE, data.bloodPressure.map { it.time })
        instant(HealthDataType.BLOOD_GLUCOSE, data.bloodGlucose.map { it.time })
        instant(HealthDataType.OXYGEN_SATURATION, data.oxygenSaturation.map { it.time })
        instant(HealthDataType.BODY_TEMPERATURE, data.bodyTemperature.map { it.time })
        instant(HealthDataType.SKIN_TEMPERATURE, data.skinTemperature.map { it.time })
        instant(HealthDataType.RESPIRATORY_RATE, data.respiratoryRate.map { it.time })
        instant(HealthDataType.RESTING_HEART_RATE, data.restingHeartRate.map { it.time })
        interval(HealthDataType.EXERCISE, data.exercise.map { it.endTime })
        interval(HealthDataType.HYDRATION, data.hydration.map { it.endTime })
        interval(HealthDataType.NUTRITION, data.nutrition.map { it.endTime })
        instant(HealthDataType.BASAL_METABOLIC_RATE, data.basalMetabolicRate.map { it.time })
        instant(HealthDataType.BODY_FAT, data.bodyFat.map { it.time })
        instant(HealthDataType.LEAN_BODY_MASS, data.leanBodyMass.map { it.time })
        instant(HealthDataType.BODY_WATER_MASS, data.bodyWaterMass.map { it.time })
        instant(HealthDataType.VO2_MAX, data.vo2Max.map { it.time })
        instant(HealthDataType.BONE_MASS, data.boneMass.map { it.time })
        instant(HealthDataType.MENSTRUATION_FLOW, data.menstruationFlow.map { it.time })
        interval(HealthDataType.MENSTRUATION_PERIOD, data.menstruationPeriod.map { it.endTime })
        instant(HealthDataType.INTERMENSTRUAL_BLEEDING, data.intermenstrualBleeding.map { it.time })
        instant(HealthDataType.OVULATION_TEST, data.ovulationTest.map { it.time })
        instant(HealthDataType.CERVICAL_MUCUS, data.cervicalMucus.map { it.time })
        instant(HealthDataType.SEXUAL_ACTIVITY, data.sexualActivity.map { it.time })
        instant(HealthDataType.BASAL_BODY_TEMPERATURE, data.basalBodyTemperature.map { it.time })
    }
}
