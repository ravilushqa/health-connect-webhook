package com.ravil.healthcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The port of health-core's rules must reject what health-core rejects, or the contract test proves nothing. */
class HealthCoreRulesTest {
    private val ts = "\"timestamp\":\"2026-01-10T12:00:00Z\""

    @Test
    fun `timestamp profile`() {
        assertTrue(HealthCoreRules.isTimestamp("2026-01-10T12:00:00Z"))
        assertTrue(HealthCoreRules.isTimestamp("2026-01-10T12:00:00.123456789+01:00"))
        assertFalse(HealthCoreRules.isTimestamp("2026-01-10T12:00Z"))
        assertFalse(HealthCoreRules.isTimestamp("2026-01-10 12:00:00Z"))
        assertFalse(HealthCoreRules.isTimestamp("2026-01-10T12:00:00"))
        assertFalse(HealthCoreRules.isTimestamp("2026-02-30T12:00:00Z"))
        assertFalse(HealthCoreRules.isTimestamp("2026-01-10T12:00:00+0100"))
    }

    @Test
    fun `missing timestamp`() {
        assertEquals(listOf("missing required field: timestamp"), HealthCoreRules.validate("{}"))
    }

    @Test
    fun `steps rules`() {
        val errs = HealthCoreRules.validate(
            """{$ts,"steps":[{"count":-1,"start_time":"2026-01-10T10:00:00Z","end_time":"2026-01-10T11:00:00Z"},
               {"count":5,"start_time":"2026-01-10T11:00:00Z","end_time":"2026-01-10T11:00:00Z"},
               {"count":1.5,"start_time":"2026-01-10T10:00:00","end_time":"2026-01-10T11:00:00Z"}]}"""
        )
        assertEquals(
            listOf(
                "steps[0]: count: must be non-negative integer (not bool)",
                "steps[1]: start_time must be strictly before end_time",
                "steps[2]: count: must be non-negative integer (not bool)",
                "steps[2]: start_time: invalid or naive timestamp",
            ),
            errs,
        )
    }

    @Test
    fun `heart rate bucket needs ordered min avg max`() {
        val errs = HealthCoreRules.validate("""{$ts,"heart_rate":[{"time":"2026-01-10T10:00:00Z","avg":70,"min":75,"max":80}]}""")
        assertEquals(listOf("heart_rate[0]: min <= avg <= max required"), errs)
        val nullMax = HealthCoreRules.validate("""{$ts,"heart_rate":[{"time":"2026-01-10T10:00:00Z","avg":70,"min":65,"max":null}]}""")
        assertEquals(listOf("heart_rate[0]: max: must be non-negative finite number"), nullMax)
    }

    @Test
    fun `metadata must be an object and unknown types are ignored`() {
        val errs = HealthCoreRules.validate("""{$ts,"weight":[{"kilograms":0,"time":"2026-01-10T10:00:00Z","metadata":"x"}],"whatever":5}""")
        assertEquals(
            listOf("weight[0].metadata: must be dict", "weight[0]: kilograms: must be positive finite number"),
            errs,
        )
    }
}
