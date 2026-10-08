package com.ravil.healthconnect.nutrition

import com.ravil.healthconnect.nutrition.data.NutritionQueueRecord
import com.ravil.healthconnect.nutrition.validation.RecordValidator
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecordValidatorTest {

    private fun valid() = NutritionQueueRecord(
        clientRecordId = "rec-001",
        clientRecordVersion = 1,
        name = "Breakfast",
        startTime = "2024-06-01T08:00:00+05:30",
        endTime = "2024-06-01T08:30:00+05:30",
        energyKcal = 350.0,
        proteinG = 20.0,
        carbohydrateG = 45.0,
        fatG = 12.0,
    )

    @Test
    fun `valid record passes`() {
        assertNull(RecordValidator.validate(valid()))
    }

    @Test
    fun `blank clientRecordId rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(clientRecordId = "")))
        assertNotNull(RecordValidator.validate(valid().copy(clientRecordId = "  ")))
    }

    @Test
    fun `negative version rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(clientRecordVersion = -1)))
    }

    @Test
    fun `blank name rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(name = "")))
    }

    @Test
    fun `negative energy rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(energyKcal = -1.0)))
    }

    @Test
    fun `infinite energy rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(energyKcal = Double.POSITIVE_INFINITY)))
        assertNotNull(RecordValidator.validate(valid().copy(energyKcal = Double.NaN)))
    }

    @Test
    fun `energy exceeding limit rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(energyKcal = 20_000.0)))
    }

    @Test
    fun `zero energy is valid`() {
        assertNull(RecordValidator.validate(valid().copy(energyKcal = 0.0)))
    }

    @Test
    fun `optional macros null passes`() {
        assertNull(RecordValidator.validate(valid().copy(proteinG = null, carbohydrateG = null, fatG = null)))
    }

    @Test
    fun `negative macro rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(proteinG = -0.1)))
    }

    @Test
    fun `macro exceeding limit rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(fatG = 3_000.0)))
    }

    @Test
    fun `time without timezone rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(startTime = "2024-06-01T08:00:00")))
        assertNotNull(RecordValidator.validate(valid().copy(endTime = "2024-06-01T08:30:00")))
    }

    @Test
    fun `UTC timezone accepted`() {
        assertNull(RecordValidator.validate(valid().copy(
            startTime = "2024-06-01T08:00:00Z",
            endTime = "2024-06-01T08:30:00Z",
        )))
    }

    @Test
    fun `end before start rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(
            startTime = "2024-06-01T09:00:00Z",
            endTime = "2024-06-01T08:00:00Z",
        )))
    }

    @Test
    fun `start equals end rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(
            startTime = "2024-06-01T08:00:00Z",
            endTime = "2024-06-01T08:00:00Z",
        )))
    }

    @Test
    fun `duration over 24h rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(
            startTime = "2024-06-01T08:00:00Z",
            endTime = "2024-06-03T08:00:01Z",
        )))
    }

    @Test
    fun `exactly 24h duration accepted`() {
        assertNull(RecordValidator.validate(valid().copy(
            startTime = "2024-06-01T08:00:00Z",
            endTime = "2024-06-02T08:00:00Z",
        )))
    }

    @Test
    fun `parseOffset returns null for missing timezone`() {
        assert(RecordValidator.parseOffset("2024-06-01T08:00:00") == null)
    }

    @Test
    fun `parseOffset accepts positive offset`() {
        assert(RecordValidator.parseOffset("2024-06-01T08:00:00+05:30") != null)
    }

    @Test
    fun `parseOffset accepts negative offset`() {
        assert(RecordValidator.parseOffset("2024-06-01T08:00:00-08:00") != null)
    }

    @Test
    fun `parseOffset accepts Z`() {
        assert(RecordValidator.parseOffset("2024-06-01T08:00:00Z") != null)
    }
}
