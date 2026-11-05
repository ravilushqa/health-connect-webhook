package com.ravil.healthcore.core.ingest

import com.ravil.healthcore.core.Synthetic
import com.ravil.healthcore.core.model.HealthDataType
import com.ravil.healthcore.core.model.HeartRateData
import com.ravil.healthcore.core.model.TotalCaloriesData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class IngestPlanTest {
    private val now = Synthetic.NOW

    @Test
    fun `window looks back 48 hours`() {
        val w = IngestPlan.window(now)
        assertEquals(Instant.parse("2026-01-08T12:00:00Z"), w.start)
        assertEquals(now, w.end)
    }

    @Test
    fun `interval cursor ignores future end times`() {
        val data = Synthetic.empty().copy(
            totalCalories = listOf(
                TotalCaloriesData(100.0, Synthetic.at(0), Synthetic.at(60)),
                // A daily projection ending at midnight, in the future.
                TotalCaloriesData(2000.0, Synthetic.at(0), Instant.parse("2026-01-11T00:00:00Z")),
            ),
        )
        assertEquals(mapOf(HealthDataType.TOTAL_CALORIES to Synthetic.at(60).toEpochMilli()), IngestPlan.nextCursors(data, now))
    }

    @Test
    fun `interval cursor stays put when every end time is in the future`() {
        val data = Synthetic.empty().copy(
            totalCalories = listOf(TotalCaloriesData(2000.0, Synthetic.at(0), Instant.parse("2026-01-11T00:00:00Z"))),
        )
        assertNull(IngestPlan.nextCursors(data, now)[HealthDataType.TOTAL_CALORIES])
        assertEquals(1, IngestPlan.countsByType(data)[HealthDataType.TOTAL_CALORIES])
    }

    @Test
    fun `instant cursor is the latest sample, as in HC Webhook`() {
        val data = Synthetic.empty().copy(
            heartRate = listOf(HeartRateData(60, Synthetic.at(5)), HeartRateData(61, Synthetic.at(9)), HeartRateData(62, Synthetic.at(7))),
        )
        assertEquals(Synthetic.at(9).toEpochMilli(), IngestPlan.nextCursors(data, now)[HealthDataType.HEART_RATE])
    }

    @Test
    fun `every type with records gets a count and moves its cursor`() {
        val all = Synthetic.all()
        val counts = IngestPlan.countsByType(all)
        assertEquals(emptySet<HealthDataType>(), HealthDataType.entries.toSet() - counts.keys)
        assertEquals(all.totalRecordCount(), counts.values.sum())
        // The synthetic total-calories record ends after NOW (a projection), so its cursor stays.
        assertEquals(setOf(HealthDataType.TOTAL_CALORIES), HealthDataType.entries.toSet() - IngestPlan.nextCursors(all, now).keys)
    }

    @Test
    fun `empty data`() {
        assertTrue(IngestPlan.isEmpty(Synthetic.empty()))
        assertFalse(IngestPlan.isEmpty(Synthetic.all()))
        assertEquals(emptyMap<HealthDataType, Long>(), IngestPlan.nextCursors(Synthetic.empty(), now))
    }
}
