package com.ravil.healthcore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionsTest {
    @Test
    fun `defaults are HC Webhook's and are always offered`() {
        assertEquals(RESOLUTION_DAILY, HealthDataType.STEPS.defaultResolutionMinutes)
        assertEquals(RESOLUTION_FULL, HealthDataType.NUTRITION.defaultResolutionMinutes)
        assertEquals(1, HealthDataType.HEART_RATE.defaultResolutionMinutes)
        assertEquals(RESOLUTION_FULL, HealthDataType.SLEEP.defaultResolutionMinutes)
        for (t in Resolutions.configurable) {
            assertTrue(t.name, Resolutions.options(t).any { it.minutes == t.defaultResolutionMinutes })
        }
    }

    @Test
    fun `effective falls back to the default`() {
        assertEquals(RESOLUTION_DAILY, Resolutions.effective(HealthDataType.STEPS, null))
        assertEquals(RESOLUTION_FULL, Resolutions.effective(HealthDataType.STEPS, RESOLUTION_FULL))
        assertEquals(RESOLUTION_DAILY, Resolutions.effective(HealthDataType.STEPS, 7))
        assertEquals(RESOLUTION_FULL, Resolutions.effective(HealthDataType.WEIGHT, 15))
    }

    @Test
    fun `configurable types`() {
        assertEquals(12, Resolutions.configurable.size)
        assertTrue(Resolutions.options(HealthDataType.WEIGHT).isEmpty())
    }

    @Test
    fun labels() {
        assertEquals("Heart rate", HealthDataType.HEART_RATE.label)
        assertEquals("VO2 max", HealthDataType.VO2_MAX.label)
        assertEquals("Basal body temperature", HealthDataType.BASAL_BODY_TEMPERATURE.label)
    }
}
