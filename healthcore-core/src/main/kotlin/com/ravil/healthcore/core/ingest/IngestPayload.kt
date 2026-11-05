package com.ravil.healthcore.core.ingest

import com.ravil.healthcore.core.model.HealthData
import com.ravil.healthcore.core.model.HeightData
import com.ravil.healthcore.core.model.RecordMetadata
import com.ravil.healthcore.core.model.WeightData
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

// The JSON body of POST /ingest/health-connect. Ported verbatim from :app's SyncManager
// (putRecordMetadata, buildJsonPayload, computeBmiEntries); only the timestamp is injectable.
// ParityTest compares the output with :app's own code on the same inputs.

/**
 * Emit a record's Health Connect provenance and identity as a nested "metadata" object.
 *
 * Identity fields mirror the protobuf `RecordMetadata` and are emitted only when present:
 * - `id`: the Health Connect record id (absent for downsampled buckets, see [forAggregate]).
 * - `client_record_id` + `client_record_version`: the writing app's own id and version.
 * - `last_modified_time`: ISO-8601 UTC.
 *
 * With them a receiver can deduplicate by id instead of by timestamps and detect edits.
 */
internal fun JsonObjectBuilder.putRecordMetadata(meta: RecordMetadata) {
    putJsonObject("metadata") {
        put("data_origin", meta.dataOrigin)
        put("recording_method", meta.recordingMethod)
        if (meta.deviceManufacturer != null || meta.deviceModel != null || meta.deviceType != null) {
            putJsonObject("device") {
                meta.deviceManufacturer?.let { put("manufacturer", it) }
                meta.deviceModel?.let { put("model", it) }
                meta.deviceType?.let { put("type", it) }
            }
        }
        if (meta.id.isNotEmpty()) put("id", meta.id)
        meta.clientRecordId?.let {
            put("client_record_id", it)
            put("client_record_version", meta.clientRecordVersion)
        }
        meta.lastModifiedTime?.let { put("last_modified_time", it.toString()) }
    }
}

/** Builds the ingest payload exactly as HC Webhook's JSON delivery does. */
object IngestPayload {
    fun build(healthData: HealthData, appVersionName: String, timestamp: Instant = Instant.now()): String {
        val json = buildJsonObject {
            put("timestamp", timestamp.toString())
            put("app_version", appVersionName)

            if (healthData.steps.isNotEmpty()) {
                putJsonArray("steps") {
                    healthData.steps.forEach { step ->
                        add(buildJsonObject {
                            put("count", step.count)
                            put("start_time", step.startTime.toString())
                            put("end_time", step.endTime.toString())
                            step.metadata?.let { meta -> putRecordMetadata(meta) }
                        })
                    }
                }
            }

            if (healthData.sleep.isNotEmpty()) {
                putJsonArray("sleep") {
                    healthData.sleep.forEach { sleep ->
                        add(buildJsonObject {
                            put("session_end_time", sleep.sessionEndTime.toString())
                            put("duration_seconds", sleep.duration.seconds)
                            putJsonArray("stages") {
                                sleep.stages.forEach { stage ->
                                    add(buildJsonObject {
                                        put("stage", stage.stage)
                                        put("start_time", stage.startTime.toString())
                                        put("end_time", stage.endTime.toString())
                                        put("duration_seconds", stage.duration.seconds)
                                    })
                                }
                            }
                            sleep.metadata?.let { meta -> putRecordMetadata(meta) }
                        })
                    }
                }
            }

            if (healthData.heartRate.isNotEmpty()) {
                putJsonArray("heart_rate") {
                    healthData.heartRate.forEach { hr -> add(buildJsonObject {
                        if (hr.min != null) {
                            put("time", hr.time.toString())
                            put("avg", hr.bpm)
                            put("min", hr.min)
                            put("max", hr.max)
                            put("bpm", hr.bpm)
                        } else {
                            put("bpm", hr.bpm)
                            put("time", hr.time.toString())
                        }
                        hr.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.heartRateVariability.isNotEmpty()) {
                putJsonArray("heart_rate_variability") {
                    healthData.heartRateVariability.forEach { hrv -> add(buildJsonObject {
                        if (hrv.min != null) {
                            put("time", hrv.time.toString())
                            put("avg", hrv.rmssdMillis)
                            put("min", hrv.min)
                            put("max", hrv.max)
                            put("rmssd_millis", hrv.rmssdMillis)
                        } else {
                            put("rmssd_millis", hrv.rmssdMillis)
                            put("time", hrv.time.toString())
                        }
                        hrv.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.distance.isNotEmpty()) {
                putJsonArray("distance") {
                    healthData.distance.forEach { add(buildJsonObject {
                        put("meters", it.meters)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.activeCalories.isNotEmpty()) {
                putJsonArray("active_calories") {
                    healthData.activeCalories.forEach { add(buildJsonObject {
                        put("calories", it.calories)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.totalCalories.isNotEmpty()) {
                putJsonArray("total_calories") {
                    healthData.totalCalories.forEach { add(buildJsonObject {
                        put("calories", it.calories)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.weight.isNotEmpty()) {
                putJsonArray("weight") {
                    healthData.weight.forEach { add(buildJsonObject {
                        put("kilograms", it.kilograms)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.height.isNotEmpty()) {
                putJsonArray("height") {
                    healthData.height.forEach { add(buildJsonObject {
                        put("meters", it.meters)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.bloodPressure.isNotEmpty()) {
                putJsonArray("blood_pressure") {
                    healthData.bloodPressure.forEach { add(buildJsonObject {
                        put("systolic", it.systolic)
                        put("diastolic", it.diastolic)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.bloodGlucose.isNotEmpty()) {
                putJsonArray("blood_glucose") {
                    healthData.bloodGlucose.forEach { add(buildJsonObject {
                        put("mmol_per_liter", it.mmolPerLiter)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.oxygenSaturation.isNotEmpty()) {
                putJsonArray("oxygen_saturation") {
                    healthData.oxygenSaturation.forEach { o2 -> add(buildJsonObject {
                        if (o2.min != null) {
                            put("time", o2.time.toString())
                            put("avg", o2.percentage)
                            put("min", o2.min)
                            put("max", o2.max)
                            put("percentage", o2.percentage)
                        } else {
                            put("percentage", o2.percentage)
                            put("time", o2.time.toString())
                        }
                        o2.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.bodyTemperature.isNotEmpty()) {
                putJsonArray("body_temperature") {
                    healthData.bodyTemperature.forEach { add(buildJsonObject {
                        put("celsius", it.celsius)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.skinTemperature.isNotEmpty()) {
                putJsonArray("skin_temperature") {
                    healthData.skinTemperature.forEach { skin -> add(buildJsonObject {
                        put("time", skin.time.toString())
                        if (skin.minDeltaCelsius != null) {
                            put("avg_delta_celsius", skin.deltaCelsius)
                            put("min_delta_celsius", skin.minDeltaCelsius)
                            put("max_delta_celsius", skin.maxDeltaCelsius)
                            put("delta_celsius", skin.deltaCelsius)
                        } else {
                            put("delta_celsius", skin.deltaCelsius)
                        }
                        skin.baselineCelsius?.let { baseline -> put("baseline_celsius", baseline) }
                        put("measurement_location", skin.measurementLocation)
                        skin.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.respiratoryRate.isNotEmpty()) {
                putJsonArray("respiratory_rate") {
                    healthData.respiratoryRate.forEach { resp -> add(buildJsonObject {
                        if (resp.min != null) {
                            put("time", resp.time.toString())
                            put("avg", resp.rate)
                            put("min", resp.min)
                            put("max", resp.max)
                            put("rate", resp.rate)
                        } else {
                            put("rate", resp.rate)
                            put("time", resp.time.toString())
                        }
                        resp.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.restingHeartRate.isNotEmpty()) {
                putJsonArray("resting_heart_rate") {
                    healthData.restingHeartRate.forEach { add(buildJsonObject {
                        put("bpm", it.bpm)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.exercise.isNotEmpty()) {
                putJsonArray("exercise") {
                    healthData.exercise.forEach { add(buildJsonObject {
                        put("type", it.type)
                        it.title?.let { title -> put("title", title) }
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                        put("duration_seconds", it.duration.seconds)
                        it.distanceMeters?.let { distanceMeters ->
                            put("distance_meters", distanceMeters)
                        }
                        it.steps?.let { steps ->
                            put("steps", steps)
                        }
                        it.avgCadenceSpm?.let { avgCadenceSpm ->
                            put("avg_cadence_spm", avgCadenceSpm)
                        }
                        it.maxCadenceSpm?.let { maxCadenceSpm ->
                            put("max_cadence_spm", maxCadenceSpm)
                        }
                        it.strideLengthMeters?.let { strideLengthMeters ->
                            put("stride_length_m", strideLengthMeters)
                        }
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.hydration.isNotEmpty()) {
                putJsonArray("hydration") {
                    healthData.hydration.forEach { add(buildJsonObject {
                        put("liters", it.liters)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.nutrition.isNotEmpty()) {
                putJsonArray("nutrition") {
                    healthData.nutrition.forEach { add(buildJsonObject {
                        it.calories?.let { cal -> put("calories", cal) }
                        it.protein?.let { prot -> put("protein_grams", prot) }
                        it.carbs?.let { carb -> put("carbs_grams", carb) }
                        it.fat?.let { f -> put("fat_grams", f) }
                        it.sugar?.let { sugar -> put("sugar_grams", sugar) }
                        it.sodium?.let { sodium -> put("sodium_grams", sodium) }
                        it.dietaryFiber?.let { fiber -> put("dietary_fiber_grams", fiber) }
                        it.name?.let { name -> put("name", name) }
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.basalMetabolicRate.isNotEmpty()) {
                putJsonArray("basal_metabolic_rate") {
                    healthData.basalMetabolicRate.forEach { add(buildJsonObject {
                        put("watts", it.watts)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.bodyFat.isNotEmpty()) {
                putJsonArray("body_fat") {
                    healthData.bodyFat.forEach { add(buildJsonObject {
                        put("percentage", it.percentage)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.leanBodyMass.isNotEmpty()) {
                putJsonArray("lean_body_mass") {
                    healthData.leanBodyMass.forEach { add(buildJsonObject {
                        put("kilograms", it.kilograms)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.bodyWaterMass.isNotEmpty()) {
                putJsonArray("body_water_mass") {
                    healthData.bodyWaterMass.forEach { add(buildJsonObject {
                        put("kilograms", it.kilograms)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            // Health Connect has no BMI record type. Compute BMI = kg / m² when both
            // weight and height lists are present (enabled + non-empty after filter).
            // Pair each weight with the height closest in time.
            if (healthData.weight.isNotEmpty() && healthData.height.isNotEmpty()) {
                putJsonArray("bmi") {
                    computeBmiEntries(healthData.weight, healthData.height).forEach { entry ->
                        add(buildJsonObject {
                            put("value", entry.value)
                            put("time", entry.time.toString())
                            put("weight_kg", entry.weightKg)
                            put("height_meters", entry.heightMeters)
                        })
                    }
                }
            }

            if (healthData.vo2Max.isNotEmpty()) {
                putJsonArray("vo2_max") {
                    healthData.vo2Max.forEach { add(buildJsonObject {
                        put("ml_per_kg_per_min", it.mlPerKgPerMin)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.boneMass.isNotEmpty()) {
                putJsonArray("bone_mass") {
                    healthData.boneMass.forEach { add(buildJsonObject {
                        put("kilograms", it.kilograms)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.menstruationFlow.isNotEmpty()) {
                putJsonArray("menstruation_flow") {
                    healthData.menstruationFlow.forEach { add(buildJsonObject {
                        put("flow", it.flow)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.menstruationPeriod.isNotEmpty()) {
                putJsonArray("menstruation_period") {
                    healthData.menstruationPeriod.forEach { add(buildJsonObject {
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.intermenstrualBleeding.isNotEmpty()) {
                putJsonArray("intermenstrual_bleeding") {
                    healthData.intermenstrualBleeding.forEach { add(buildJsonObject {
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.ovulationTest.isNotEmpty()) {
                putJsonArray("ovulation_test") {
                    healthData.ovulationTest.forEach { add(buildJsonObject {
                        put("result", it.result)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.cervicalMucus.isNotEmpty()) {
                putJsonArray("cervical_mucus") {
                    healthData.cervicalMucus.forEach { add(buildJsonObject {
                        put("appearance", it.appearance)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.sexualActivity.isNotEmpty()) {
                putJsonArray("sexual_activity") {
                    healthData.sexualActivity.forEach { add(buildJsonObject {
                        put("protection_used", it.protectionUsed)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }

            if (healthData.basalBodyTemperature.isNotEmpty()) {
                putJsonArray("basal_body_temperature") {
                    healthData.basalBodyTemperature.forEach { add(buildJsonObject {
                        put("celsius", it.celsius)
                        put("measurement_location", it.measurementLocation)
                        put("time", it.time.toString())
                        it.metadata?.let { meta -> putRecordMetadata(meta) }
                    }) }
                }
            }
        } // End of buildJsonObject block

        return json.toString()
    }

    private data class BmiEntry(
        val value: Double,
        val time: Instant,
        val weightKg: Double,
        val heightMeters: Double
    )

    /** BMI = kg / m². Pair each weight with the height record closest in time. */
    private fun computeBmiEntries(
        weights: List<WeightData>,
        heights: List<HeightData>
    ): List<BmiEntry> {
        if (weights.isEmpty() || heights.isEmpty()) return emptyList()
        return weights.mapNotNull { weight ->
            val height = heights.minByOrNull { h ->
                kotlin.math.abs(h.time.toEpochMilli() - weight.time.toEpochMilli())
            } ?: return@mapNotNull null
            if (height.meters <= 0.0) return@mapNotNull null
            val bmi = weight.kilograms / (height.meters * height.meters)
            BmiEntry(
                value = bmi,
                time = weight.time,
                weightKg = weight.kilograms,
                heightMeters = height.meters
            )
        }
    }
}
