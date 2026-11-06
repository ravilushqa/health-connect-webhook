package com.ravil.healthcore.core.ingest

import com.ravil.healthcore.core.model.ActiveCaloriesData
import com.ravil.healthcore.core.model.DistanceData
import com.ravil.healthcore.core.model.HealthData
import com.ravil.healthcore.core.model.RecordMetadata
import com.ravil.healthcore.core.model.StepsData
import java.time.Instant
import java.time.ZoneId

/**
 * Health Connect's own day totals for steps, distance and active calories, sent next to the
 * raw records as one extra record per local day.
 *
 * Why: raw records of one source can overlap (Android's built-in step counter writes partly
 * overlapping intervals), and health-core then refuses to total the day, rightly, since it
 * cannot tell how the overlap splits. Health Connect's aggregate resolves overlaps and
 * duplicates across apps; it is the number the phone shows.
 *
 * How it stays clean in health-core: each total is an interval record covering the whole
 * local day [midnight, next midnight), so its identity (source + start + end) never changes
 * and every sync replaces the previous value instead of adding an overlapping interval. Its
 * metadata names a source of its own ([ORIGIN], [METHOD]) and the phone as the device, so it
 * is never mixed with the raw records' sources and its provenance is not "ambiguous".
 */
object DailyTotals {
    const val ORIGIN = "health-connect.aggregate"
    const val METHOD = "aggregate"

    /** Local days covered: today and the two before, i.e. every day the 48-hour window touches. */
    const val DAYS = 3

    /** One local day: [start, end) is midnight to midnight; [queryEnd] stops at now for today. */
    data class Day(val start: Instant, val end: Instant, val queryEnd: Instant)

    /** Totals of one day; null where the type is not readable or Health Connect has nothing. */
    data class Totals(
        val day: Day,
        val steps: Long? = null,
        val distanceMeters: Double? = null,
        val activeKcal: Double? = null,
    )

    fun days(now: Instant, zone: ZoneId, count: Int = DAYS): List<Day> {
        val today = now.atZone(zone).toLocalDate() // not LocalDate.ofInstant: Java 9+, missing before API 34
        return (count - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            val start = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant()
            Day(start, end, if (end.isAfter(now)) now else end)
        }
    }

    fun metadata(manufacturer: String?, model: String?, deviceType: Int?) = RecordMetadata(
        dataOrigin = ORIGIN,
        recordingMethod = METHOD,
        deviceManufacturer = manufacturer,
        deviceModel = model,
        deviceType = deviceType,
    )

    /** [data] with one record per available total appended to steps, distance and active calories. */
    fun appendTo(data: HealthData, totals: List<Totals>, meta: RecordMetadata): HealthData = data.copy(
        steps = data.steps + totals.mapNotNull { t -> t.steps?.let { StepsData(it, t.day.start, t.day.end, meta) } },
        distance = data.distance + totals.mapNotNull { t ->
            t.distanceMeters?.let { DistanceData(it, t.day.start, t.day.end, meta) }
        },
        activeCalories = data.activeCalories + totals.mapNotNull { t ->
            t.activeKcal?.let { ActiveCaloriesData(it, t.day.start, t.day.end, meta) }
        },
    )

    fun count(totals: List<Totals>): Int =
        totals.sumOf { t -> listOfNotNull(t.steps, t.distanceMeters, t.activeKcal).size }
}
