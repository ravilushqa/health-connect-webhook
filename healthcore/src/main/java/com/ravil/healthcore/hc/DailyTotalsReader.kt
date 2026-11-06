package com.ravil.healthcore.hc

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.ravil.healthcore.core.ingest.DailyTotals
import com.ravil.healthcore.core.model.HealthDataType
import com.ravil.healthcore.core.model.RecordMetadata

/** Reads Health Connect's own per-day totals (see [DailyTotals]) for the readable types. */
class DailyTotalsReader(private val context: Context) {

    suspend fun read(types: Set<HealthDataType>, days: List<DailyTotals.Day>): List<DailyTotals.Totals> {
        val metrics = buildSet<AggregateMetric<*>> {
            if (HealthDataType.STEPS in types) add(StepsRecord.COUNT_TOTAL)
            if (HealthDataType.DISTANCE in types) add(DistanceRecord.DISTANCE_TOTAL)
            if (HealthDataType.ACTIVE_CALORIES in types) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
        }
        if (metrics.isEmpty()) return emptyList()
        val client = HealthConnectClient.getOrCreate(context)
        return days.filter { it.start.isBefore(it.queryEnd) }.map { day ->
            val r = client.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(day.start, day.queryEnd)))
            DailyTotals.Totals(
                day = day,
                steps = r[StepsRecord.COUNT_TOTAL],
                distanceMeters = r[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
                activeKcal = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
            )
        }
    }

    companion object {
        /** The totals' source: Health Connect's aggregate on this phone. */
        fun metadata(): RecordMetadata = DailyTotals.metadata(Build.MANUFACTURER, Build.MODEL, Device.TYPE_PHONE)
    }
}
