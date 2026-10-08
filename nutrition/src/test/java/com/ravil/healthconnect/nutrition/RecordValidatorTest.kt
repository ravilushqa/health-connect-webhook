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
        assertNotNull(RecordValidator.validate(valid().copy(name = "   ")))
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
    fun `energy exceeding 5000 kcal rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(energyKcal = 5_001.0)))
    }

    @Test
    fun `energy at 5000 kcal accepted`() {
        assertNull(RecordValidator.validate(valid().copy(energyKcal = 5_000.0)))
    }

    @Test
    fun `zero energy with nonzero macros is valid`() {
        assertNull(RecordValidator.validate(valid().copy(energyKcal = 0.0)))
    }

    // --- Required core macros ---

    @Test
    fun `null protein rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(proteinG = null)))
    }

    @Test
    fun `null carbohydrate rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(carbohydrateG = null)))
    }

    @Test
    fun `null fat rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(fatG = null)))
    }

    @Test
    fun `negative macro rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(proteinG = -0.1)))
    }

    @Test
    fun `protein exceeding 500g rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(proteinG = 501.0)))
    }

    @Test
    fun `carbs exceeding 1000g rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(carbohydrateG = 1_001.0)))
    }

    @Test
    fun `fat exceeding 500g rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(fatG = 501.0)))
    }

    // --- All-zero rejection ---

    @Test
    fun `all four core macros zero is rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(
            energyKcal = 0.0,
            proteinG = 0.0,
            carbohydrateG = 0.0,
            fatG = 0.0,
        )))
    }

    @Test
    fun `zero energy with nonzero protein is valid`() {
        assertNull(RecordValidator.validate(valid().copy(energyKcal = 0.0, proteinG = 1.0)))
    }

    // --- Optional fields: fiber, sugar, sodium ---

    @Test
    fun `optional fiber null passes`() {
        assertNull(RecordValidator.validate(valid().copy(fiberG = null)))
    }

    @Test
    fun `optional fiber valid`() {
        assertNull(RecordValidator.validate(valid().copy(fiberG = 10.0)))
    }

    @Test
    fun `fiber exceeding 500g rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(fiberG = 501.0)))
    }

    @Test
    fun `optional sugar valid`() {
        assertNull(RecordValidator.validate(valid().copy(sugarG = 50.0)))
    }

    @Test
    fun `sugar exceeding 500g rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(sugarG = 501.0)))
    }

    @Test
    fun `optional sodium_mg valid`() {
        assertNull(RecordValidator.validate(valid().copy(sodiumMg = 2_300.0)))
    }

    @Test
    fun `sodium_mg exceeding 50000 mg rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(sodiumMg = 50_001.0)))
    }

    @Test
    fun `negative sodium_mg rejected`() {
        assertNotNull(RecordValidator.validate(valid().copy(sodiumMg = -1.0)))
    }

    // --- Time validation ---

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
