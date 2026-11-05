package com.ravil.healthcore.core

import com.ravil.healthcore.core.model.*
import java.time.Duration
import java.time.Instant

/**
 * Synthetic Health Connect data covering every record type and every optional branch of the
 * serializer. All values are made up; none come from a real device.
 */
object Synthetic {
    val T0: Instant = Instant.parse("2026-01-10T06:00:00Z")
    val NOW: Instant = Instant.parse("2026-01-10T12:00:00Z")

    fun at(minutes: Long): Instant = T0.plus(Duration.ofMinutes(minutes))

    /** Metadata of a record written by another app, with identity. */
    fun meta(n: Int, client: Boolean = false) = RecordMetadata(
        dataOrigin = "com.example.tracker",
        recordingMethod = "automatically_recorded",
        deviceManufacturer = "ExampleCo",
        deviceModel = "Band ${n % 3}",
        deviceType = 6,
        id = "00000000-0000-4000-8000-%012d".format(n),
        clientRecordId = if (client) "client-$n" else null,
        clientRecordVersion = if (client) n.toLong() else 0L,
        lastModifiedTime = at(n.toLong()).plusSeconds(30),
        zoneOffsetSeconds = 3600,
    )

    /** Metadata of a downsampled bucket: provenance only. */
    fun bucketMeta() = meta(999).forAggregate()

    /** Provenance without a device, written by hand. */
    fun manualMeta(n: Int) = RecordMetadata(
        dataOrigin = "com.example.scale",
        recordingMethod = "manual_entry",
        id = "00000000-0000-4000-9000-%012d".format(n),
        lastModifiedTime = at(n.toLong()),
    )

    fun all(): HealthData = HealthData(
        steps = listOf(
            StepsData(1234, at(0), at(15), meta(1)),
            StepsData(4321, at(0), at(24 * 60), bucketMeta()),
        ),
        sleep = listOf(
            SleepData(
                sessionEndTime = at(-30),
                duration = Duration.ofHours(7),
                stages = listOf(
                    SleepStage("light", at(-450), at(-300), Duration.ofMinutes(150)),
                    SleepStage("deep", at(-300), at(-30), Duration.ofMinutes(270)),
                ),
                metadata = meta(2),
            ),
            SleepData(at(-1500), Duration.ofHours(6), emptyList(), meta(3, client = true)),
        ),
        heartRate = listOf(
            HeartRateData(62, at(1), metadata = meta(4)),
            HeartRateData(70, at(2), min = 65, max = 80, metadata = bucketMeta()),
        ),
        heartRateVariability = listOf(
            HeartRateVariabilityData(41.5, at(3), metadata = meta(5)),
            HeartRateVariabilityData(40.25, at(4), min = 30.0, max = 55.5, metadata = bucketMeta()),
        ),
        distance = listOf(DistanceData(812.5, at(0), at(15), meta(6))),
        activeCalories = listOf(ActiveCaloriesData(55.25, at(0), at(15), meta(7))),
        totalCalories = listOf(TotalCaloriesData(1800.0, at(0), at(600), meta(8))),
        weight = listOf(WeightData(72.4, at(5), manualMeta(9))),
        height = listOf(HeightData(1.78, at(-10_000), manualMeta(10))),
        bloodPressure = listOf(BloodPressureData(118.0, 76.0, at(6), manualMeta(11))),
        bloodGlucose = listOf(BloodGlucoseData(5.4, at(7), manualMeta(12))),
        oxygenSaturation = listOf(
            OxygenSaturationData(97.0, at(8), metadata = meta(13)),
            OxygenSaturationData(96.5, at(9), min = 95.0, max = 98.0, metadata = bucketMeta()),
        ),
        bodyTemperature = listOf(BodyTemperatureData(36.6, at(10), manualMeta(14))),
        skinTemperature = listOf(
            SkinTemperatureData(at(11), -0.25, 33.5, 1, metadata = meta(15)),
            SkinTemperatureData(at(12), 0.1, null, 0, minDeltaCelsius = -0.2, maxDeltaCelsius = 0.3, metadata = bucketMeta()),
        ),
        respiratoryRate = listOf(
            RespiratoryRateData(14.5, at(13), metadata = meta(16)),
            RespiratoryRateData(15.0, at(14), min = 13.0, max = 17.0, metadata = bucketMeta()),
        ),
        restingHeartRate = listOf(RestingHeartRateData(54, at(15), meta(17))),
        exercise = listOf(
            ExerciseData(
                type = "running", title = "Morning run", startTime = at(30), endTime = at(75),
                duration = Duration.ofMinutes(45), distanceMeters = 8000.0, steps = 7600,
                avgCadenceSpm = 168.5, maxCadenceSpm = 182.0, strideLengthMeters = 1.05,
                metadata = meta(18),
            ),
            ExerciseData(type = "yoga", startTime = at(90), endTime = at(120), duration = Duration.ofMinutes(30), metadata = meta(19)),
        ),
        hydration = listOf(HydrationData(0.25, at(20), at(21), manualMeta(20))),
        nutrition = listOf(
            NutritionData(520.0, 31.0, 48.5, 18.0, 6.0, 0.9, 7.5, "[AI estimate] Lunch", at(240), at(260), meta(21, client = true)),
            NutritionData(90.0, null, 21.0, null, null, null, null, null, at(300), at(301), manualMeta(22)),
        ),
        basalMetabolicRate = listOf(BasalMetabolicRateData(78.5, at(16), meta(23))),
        bodyFat = listOf(BodyFatData(18.2, at(5), manualMeta(24))),
        leanBodyMass = listOf(LeanBodyMassData(59.2, at(5), manualMeta(25))),
        bodyWaterMass = listOf(BodyWaterMassData(41.0, at(5), manualMeta(26))),
        vo2Max = listOf(Vo2MaxData(48.3, at(17), meta(27))),
        boneMass = listOf(BoneMassData(3.1, at(5), manualMeta(28))),
        menstruationFlow = listOf(MenstruationFlowData(2, at(18), manualMeta(29))),
        menstruationPeriod = listOf(MenstruationPeriodData(at(-4000), at(-100), manualMeta(30))),
        intermenstrualBleeding = listOf(IntermenstrualBleedingData(at(19), manualMeta(31))),
        ovulationTest = listOf(OvulationTestData(1, at(20), manualMeta(32))),
        cervicalMucus = listOf(CervicalMucusData(3, at(21), manualMeta(33))),
        sexualActivity = listOf(SexualActivityData(1, at(22), manualMeta(34))),
        basalBodyTemperature = listOf(BasalBodyTemperatureData(36.4, 2, at(23), manualMeta(35))),
    )

    fun empty(): HealthData = HealthData(
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(),
    )
}
