package com.ravil.healthcore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionsTest {
    @Test
    fun `interval types are always raw, whatever was stored`() {
        for (t in listOf(HealthDataType.STEPS, HealthDataType.DISTANCE, HealthDataType.ACTIVE_CALORIES,
            HealthDataType.TOTAL_CALORIES, HealthDataType.HYDRATION, HealthDataType.NUTRITION)) {
            assertEquals(t.name, RESOLUTION_FULL, Resolutions.default(t))
            assertEquals(t.name, RESOLUTION_FULL, Resolutions.effective(t, null))
            assertEquals("a stored daily value is dropped", RESOLUTION_FULL, Resolutions.effective(t, RESOLUTION_DAILY))
            assertEquals(t.name, RESOLUTION_FULL, Resolutions.effective(t, 15))
        }
    }

    @Test
    fun `sample series and sleep keep HC Webhook's defaults`() {
        assertEquals(1, Resolutions.effective(HealthDataType.HEART_RATE, null))
        assertEquals(5, Resolutions.effective(HealthDataType.HEART_RATE, 5))
        assertEquals(RESOLUTION_FULL, Resolutions.effective(HealthDataType.SLEEP, null))
        assertEquals(SLEEP_SUMMARY, Resolutions.effective(HealthDataType.SLEEP, SLEEP_SUMMARY))
        for (t in Resolutions.withResolution) {
            assertTrue(t.name, Resolutions.options(t).any { it.minutes == Resolutions.default(t) })
        }
    }

    @Test
    fun `settings show only types with a choice, the reader gets all of them`() {
        assertEquals(12, Resolutions.withResolution.size)
        assertEquals(
            setOf(
                HealthDataType.HEART_RATE, HealthDataType.HEART_RATE_VARIABILITY, HealthDataType.OXYGEN_SATURATION,
                HealthDataType.RESPIRATORY_RATE, HealthDataType.SKIN_TEMPERATURE, HealthDataType.SLEEP,
            ),
            Resolutions.configurable.toSet(),
        )
        assertTrue(Resolutions.options(HealthDataType.WEIGHT).isEmpty())
    }

    @Test
    fun labels() {
        assertEquals("Heart rate", HealthDataType.HEART_RATE.label)
        assertEquals("VO2 max", HealthDataType.VO2_MAX.label)
        assertEquals("Basal body temperature", HealthDataType.BASAL_BODY_TEMPERATURE.label)
    }
}
