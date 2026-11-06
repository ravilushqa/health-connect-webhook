package com.ravil.healthcore.core.ingest

import com.ravil.healthcore.core.HealthCoreRules
import com.ravil.healthcore.core.Synthetic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class DailyTotalsTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val meta = DailyTotals.metadata("Google", "Pixel 10 Pro", 2)

    @Test
    fun `three local days ending today, today queried up to now`() {
        val now = Instant.parse("2026-11-06T05:30:00Z") // 06:30 in Berlin
        val days = DailyTotals.days(now, berlin)
        assertEquals(3, days.size)
        assertEquals(Instant.parse("2026-11-03T23:00:00Z"), days[0].start)
        assertEquals(Instant.parse("2026-11-05T23:00:00Z"), days[2].start)
        assertEquals(Instant.parse("2026-11-06T23:00:00Z"), days[2].end)
        assertEquals(now, days[2].queryEnd)
        assertEquals(days[1].end, days[1].queryEnd)
        for (i in 0 until 2) assertEquals("days touch, never overlap", days[i].end, days[i + 1].start)
    }

    @Test
    fun `the record of a day is the same whenever it is sent, so health-core replaces it`() {
        val morning = DailyTotals.days(Instant.parse("2026-11-06T06:00:00Z"), berlin).last()
        val evening = DailyTotals.days(Instant.parse("2026-11-06T21:00:00Z"), berlin).last()
        assertEquals(morning.start, evening.start)
        assertEquals(morning.end, evening.end)
    }

    @Test
    fun `days follow DST`() {
        // Berlin leaves summer time on 2026-10-25: that day has 25 hours.
        val days = DailyTotals.days(Instant.parse("2026-10-25T12:00:00Z"), berlin)
        assertEquals(Duration.ofHours(25), Duration.between(days[2].start, days[2].end))
        assertEquals(Duration.ofHours(24), Duration.between(days[1].start, days[1].end))
    }

    @Test
    fun `totals go out as whole-day records of their own source and health-core accepts them`() {
        val now = Synthetic.NOW
        val days = DailyTotals.days(now, ZoneId.of("UTC"))
        val totals = listOf(
            DailyTotals.Totals(days[0], steps = 9000, distanceMeters = 6500.5, activeKcal = 420.0),
            DailyTotals.Totals(days[1], steps = 8000, distanceMeters = null, activeKcal = 300.0),
            DailyTotals.Totals(days[2], steps = 1200),
        )
        assertEquals(6, DailyTotals.count(totals))
        val data = DailyTotals.appendTo(Synthetic.all(), totals, meta)
        assertEquals(Synthetic.all().steps.size + 3, data.steps.size)
        assertEquals(Synthetic.all().distance.size + 1, data.distance.size)
        assertEquals(Synthetic.all().activeCalories.size + 2, data.activeCalories.size)

        val payload = IngestPayload.build(data, "test", now)
        assertEquals(emptyList<String>(), HealthCoreRules.validate(payload))

        val steps = Json.parseToJsonElement(payload).jsonObject["steps"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["metadata"]!!.jsonObject["data_origin"]!!.jsonPrimitive.content == DailyTotals.ORIGIN }
        assertEquals(3, steps.size)
        for (s in steps) {
            val m = s["metadata"]!!.jsonObject
            assertEquals(DailyTotals.METHOD, m["recording_method"]!!.jsonPrimitive.content)
            // origin and device present: health-core does not flag the source as ambiguous
            assertEquals("Pixel 10 Pro", m["device"]!!.jsonObject["model"]!!.jsonPrimitive.content)
            assertFalse("no record id: identity is source + whole-day interval", "id" in m)
        }
        // today's record ends at next midnight, after now: allowed, and kept out of the cursors
        assertTrue(Instant.parse(steps.last()["end_time"]!!.jsonPrimitive.content).isAfter(now))
    }

    @Test
    fun `nothing is appended when Health Connect has no totals`() {
        val days = DailyTotals.days(Synthetic.NOW, berlin)
        val empty = DailyTotals.appendTo(Synthetic.empty(), days.map { DailyTotals.Totals(it) }, meta)
        assertTrue(IngestPlan.isEmpty(empty))
    }
}
