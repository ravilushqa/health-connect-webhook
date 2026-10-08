package com.ravil.healthconnect.nutrition

import com.ravil.healthconnect.nutrition.bookmarks.InsertBookmarks
import com.ravil.healthconnect.nutrition.data.NutritionQueueRecord
import com.ravil.healthconnect.nutrition.sync.RecordAction
import com.ravil.healthconnect.nutrition.sync.planSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersioningTest {

    private fun record(id: String, version: Int) = NutritionQueueRecord(
        clientRecordId = id,
        clientRecordVersion = version,
        name = "Test Meal",
        startTime = "2024-06-01T10:00:00Z",
        endTime = "2024-06-01T10:30:00Z",
        energyKcal = 400.0,
    )

    // --- InsertBookmarks unit tests ---

    @Test
    fun `empty bookmarks has no applied version`() {
        assertNull(InsertBookmarks.EMPTY.appliedVersion("any-id"))
    }

    @Test
    fun `withApplied records version`() {
        val bm = InsertBookmarks.EMPTY.withApplied("id-1", 3)
        assertEquals(3, bm.appliedVersion("id-1"))
    }

    @Test
    fun `withApplied does not decrement version`() {
        val bm = InsertBookmarks.EMPTY.withApplied("id-1", 5).withApplied("id-1", 3)
        assertEquals(5, bm.appliedVersion("id-1"))
    }

    @Test
    fun `withApplied upgrades to higher version`() {
        val bm = InsertBookmarks.EMPTY.withApplied("id-1", 2).withApplied("id-1", 7)
        assertEquals(7, bm.appliedVersion("id-1"))
    }

    @Test
    fun `isStaleOrDuplicate true when same version`() {
        val bm = InsertBookmarks.EMPTY.withApplied("id-1", 2)
        assertTrue(bm.isStaleOrDuplicate("id-1", 2))
    }

    @Test
    fun `isStaleOrDuplicate true when incoming version lower`() {
        val bm = InsertBookmarks.EMPTY.withApplied("id-1", 5)
        assertTrue(bm.isStaleOrDuplicate("id-1", 3))
    }

    @Test
    fun `isStaleOrDuplicate false when incoming version higher`() {
        val bm = InsertBookmarks.EMPTY.withApplied("id-1", 2)
        assertFalse(bm.isStaleOrDuplicate("id-1", 3))
    }

    @Test
    fun `isStaleOrDuplicate false when no bookmark exists`() {
        assertFalse(InsertBookmarks.EMPTY.isStaleOrDuplicate("id-1", 1))
    }

    @Test
    fun `fromMap round-trips`() {
        val bm = InsertBookmarks.EMPTY.withApplied("a", 1).withApplied("b", 99)
        val restored = InsertBookmarks.fromMap(bm.toMap())
        assertEquals(1, restored.appliedVersion("a"))
        assertEquals(99, restored.appliedVersion("b"))
    }

    // --- planSync versioning ---

    @Test
    fun `new record with no bookmark produces WriteAndAck`() {
        val plan = planSync(listOf(record("x", 1)), InsertBookmarks.EMPTY)
        assertEquals(1, plan.size)
        assertTrue(plan[0] is RecordAction.WriteAndAck)
    }

    @Test
    fun `same version produces AckOnly (idempotent)`() {
        val bm = InsertBookmarks.EMPTY.withApplied("x", 1)
        val plan = planSync(listOf(record("x", 1)), bm)
        val action = plan[0]
        assertTrue("expected AckOnly but got $action", action is RecordAction.AckOnly)
        assertEquals("already_applied", (action as RecordAction.AckOnly).reason)
    }

    @Test
    fun `lower version produces AckOnly (stale ignored)`() {
        val bm = InsertBookmarks.EMPTY.withApplied("x", 5)
        val plan = planSync(listOf(record("x", 3)), bm)
        assertTrue(plan[0] is RecordAction.AckOnly)
    }

    @Test
    fun `higher version produces WriteAndAck`() {
        val bm = InsertBookmarks.EMPTY.withApplied("x", 2)
        val plan = planSync(listOf(record("x", 3)), bm)
        assertTrue(plan[0] is RecordAction.WriteAndAck)
    }

    @Test
    fun `multiple records mixed versions planned correctly`() {
        val bm = InsertBookmarks.EMPTY.withApplied("a", 2).withApplied("b", 5)
        val records = listOf(
            record("a", 3),  // higher → WriteAndAck
            record("b", 4),  // stale → AckOnly
            record("c", 1),  // new → WriteAndAck
            record("b", 5),  // same → AckOnly
        )
        val plan = planSync(records, bm)
        assertTrue(plan[0] is RecordAction.WriteAndAck)
        assertTrue(plan[1] is RecordAction.AckOnly)
        assertTrue(plan[2] is RecordAction.WriteAndAck)
        assertTrue(plan[3] is RecordAction.AckOnly)
    }
}
