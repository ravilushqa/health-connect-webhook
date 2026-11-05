package com.ravil.healthcore.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * A test-only port of health-core's payload validation (ravilushqa/health-agent,
 * internal/hc/validate/validate.go and internal/hc/identity: record shapes and the RFC 3339
 * timestamp profile). It answers the question "would health-core accept this payload?"
 * without a Go toolchain. Error strings follow validate.go; keep them in sync when it changes.
 */
object HealthCoreRules {
    /** identity.go instantTypes. */
    val INSTANT_TYPES = setOf(
        "heart_rate", "heart_rate_variability", "oxygen_saturation",
        "respiratory_rate", "skin_temperature", "resting_heart_rate",
        "weight", "height", "blood_pressure", "blood_glucose",
        "body_temperature", "basal_metabolic_rate", "body_fat",
        "lean_body_mass", "body_water_mass", "vo2_max", "bone_mass",
        "menstruation_flow", "intermenstrual_bleeding", "ovulation_test",
        "cervical_mucus", "sexual_activity", "basal_body_temperature",
        "bmi",
    )

    /** identity.go intervalTypes. */
    val INTERVAL_TYPES = setOf(
        "steps", "distance", "active_calories", "total_calories",
        "hydration", "nutrition", "exercise", "menstruation_period",
    )

    val KNOWN_TYPES = INSTANT_TYPES + INTERVAL_TYPES + "sleep"

    private val TS = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})$""")

    fun validate(payloadJson: String): List<String> =
        validate(Json.parseToJsonElement(payloadJson) as JsonObject)

    fun validate(p: JsonObject): List<String> {
        val ts = p["timestamp"]
        if (ts == null || ts is JsonNull) return listOf("missing required field: timestamp")
        if (!ts.isString()) return listOf("timestamp: must be string")
        if (!isTimestamp((ts as JsonPrimitive).content)) return listOf("timestamp: invalid or naive datetime")
        val errs = mutableListOf<String>()
        for ((key, value) in p) {
            if (key == "timestamp" || key == "app_version" || key !in KNOWN_TYPES) continue
            if (value !is JsonArray) { errs += "$key: must be an array"; continue }
            value.forEachIndexed { i, r ->
                if (r !is JsonObject) errs += "$key[$i]: record must be an object"
                else errs += record(key, r, i)
            }
        }
        return errs
    }

    fun isTimestamp(s: String): Boolean {
        val m = TS.matchEntire(s) ?: return false
        val (y, mo, d, h, mi, se) = m.destructured
        val off = m.groupValues[8]
        if (off != "Z" && (off.substring(1, 3).toInt() > 23 || off.substring(4, 6).toInt() > 59)) return false
        if (y.toInt() < 1) return false
        return try {
            LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), se.toInt()); true
        } catch (_: Exception) {
            false
        }
    }

    private fun record(type: String, rec: JsonObject, index: Int): List<String> {
        val prefix = "$type[$index]"
        val errs = metadata(rec["metadata"]).map { "$prefix.$it" }.toMutableList()
        val typeErrs = when (type) {
            "steps" -> steps(rec)
            "sleep" -> sleep(rec)
            "heart_rate" -> heartRate(rec)
            "weight" -> weight(rec)
            "exercise" -> exercise(rec)
            in INTERVAL_TYPES -> interval(rec)
            else -> awareTs(rec.getOrEmpty("time"), "time")
        }
        return errs + typeErrs.map { "$prefix: $it" }
    }

    private fun metadata(meta: JsonElement?): List<String> {
        if (meta == null || meta is JsonNull) return emptyList()
        if (meta !is JsonObject) return listOf("metadata: must be dict")
        val errs = mutableListOf<String>()
        meta["data_origin"]?.let { if (it !is JsonNull && !it.isString()) errs += "metadata.data_origin: must be string if present" }
        meta["device"]?.let { if (it !is JsonNull && it !is JsonObject) errs += "metadata.device: must be dict if present" }
        return errs
    }

    private fun steps(rec: JsonObject): List<String> {
        val errs = mutableListOf<String>()
        val count = rec["count"]
        if (!count.isInt() || count.num()!!.signum() < 0) errs += "count: must be non-negative integer (not bool)"
        errs += startEnd(rec)
        return errs
    }

    private fun sleep(rec: JsonObject): List<String> {
        val errs = awareTs(rec.getOrEmpty("session_end_time"), "session_end_time").toMutableList()
        if (!rec["duration_seconds"].finiteNonNeg()) errs += "duration_seconds: must be finite non-negative number"
        when (val stages = rec["stages"]) {
            null, is JsonNull -> {}
            is JsonArray -> stages.forEachIndexed { i, s ->
                if (s !is JsonObject) { errs += "stages[$i]: must be dict"; return@forEachIndexed }
                val p = "stages[$i]"
                val se = mutableListOf<String>()
                if (!s["stage"].isString()) se += "$p.stage: must be string"
                se += awareTs(s.getOrEmpty("start_time"), "$p.start_time")
                se += awareTs(s.getOrEmpty("end_time"), "$p.end_time")
                if (se.isEmpty() && !before(s["start_time"]!!, s["end_time"]!!)) se += "$p: start_time must be before end_time"
                s["duration_seconds"]?.let { if (it !is JsonNull && !it.finiteNonNeg()) se += "$p.duration_seconds: must be finite non-negative" }
                errs += se
            }
            else -> errs += "stages: must be list if present"
        }
        return errs
    }

    private fun heartRate(rec: JsonObject): List<String> {
        val errs = awareTs(rec.getOrEmpty("time"), "time").toMutableList()
        if ("avg" in rec) {
            for (f in listOf("avg", "min", "max")) if (!rec[f].finiteNonNeg()) errs += "$f: must be non-negative finite number"
            if (errs.isEmpty()) {
                val (avg, mn, mx) = listOf("avg", "min", "max").map { rec[it].num()!! }
                if (mn > avg || avg > mx) errs += "min <= avg <= max required"
            }
            return errs
        }
        if (!rec["bpm"].finitePositive()) errs += "bpm: must be positive finite number"
        return errs
    }

    private fun weight(rec: JsonObject): List<String> {
        val errs = mutableListOf<String>()
        if (!rec["kilograms"].finitePositive()) errs += "kilograms: must be positive finite number"
        return errs + awareTs(rec.getOrEmpty("time"), "time")
    }

    private fun exercise(rec: JsonObject): List<String> {
        val errs = mutableListOf<String>()
        val t = rec["type"]
        if (!t.isString() || (t as JsonPrimitive).content.isEmpty()) errs += "type: must be non-empty string"
        errs += startEnd(rec)
        if (!rec["duration_seconds"].finiteNonNeg()) errs += "duration_seconds: must be finite non-negative number"
        rec["steps"]?.let { if (it !is JsonNull && (!it.isInt() || it.num()!!.signum() < 0)) errs += "steps: must be non-negative integer if present" }
        rec["distance_meters"]?.let { if (it !is JsonNull && !it.finiteNonNeg()) errs += "distance_meters: must be non-negative finite if present" }
        return errs
    }

    private fun interval(rec: JsonObject): List<String> = startEnd(rec)

    private fun startEnd(rec: JsonObject): List<String> {
        val errs = awareTs(rec.getOrEmpty("start_time"), "start_time") + awareTs(rec.getOrEmpty("end_time"), "end_time")
        if (errs.isEmpty() && !before(rec["start_time"]!!, rec["end_time"]!!)) return listOf("start_time must be strictly before end_time")
        return errs
    }

    private fun awareTs(v: JsonElement, field: String): List<String> = when {
        !v.isString() -> listOf("$field: must be a string")
        !isTimestamp((v as JsonPrimitive).content) -> listOf("$field: invalid or naive timestamp")
        else -> emptyList()
    }

    private fun before(a: JsonElement, b: JsonElement): Boolean =
        java.time.OffsetDateTime.parse((a as JsonPrimitive).content).toInstant() <
            java.time.OffsetDateTime.parse((b as JsonPrimitive).content).toInstant()

    private fun JsonObject.getOrEmpty(f: String): JsonElement = this[f] ?: JsonPrimitive("")

    private fun JsonElement?.isString() = this is JsonPrimitive && this !is JsonNull && isString

    private fun JsonElement?.num(): BigDecimal? =
        if (this is JsonPrimitive && this !is JsonNull && !isString && content != "true" && content != "false") content.toBigDecimalOrNull() else null

    private fun JsonElement?.isInt(): Boolean {
        val n = num() ?: return false
        val c = (this as JsonPrimitive).content
        return '.' !in c && 'e' !in c && 'E' !in c && n.scale() <= 0
    }

    private fun JsonElement?.finiteNonNeg(): Boolean = num()?.let { it.signum() >= 0 } ?: false

    private fun JsonElement?.finitePositive(): Boolean = num()?.let { it.signum() > 0 } ?: false
}
