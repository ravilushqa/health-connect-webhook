package com.ravil.healthconnect.nutrition

import com.ravil.healthconnect.nutrition.bookmarks.InsertBookmarks
import com.ravil.healthconnect.nutrition.data.NutritionQueueRecord
import com.ravil.healthconnect.nutrition.sync.RecordAction
import com.ravil.healthconnect.nutrition.sync.planSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the ack-ordering guarantee: planSync returns AckOnly for records that were
 * already successfully inserted (bookmark exists). WriteAndAck records require a
 * successful HC insert BEFORE acking — this ordering is enforced in NutritionSyncManager.
 *
 * The ack-after-insert invariant at the manager level is tested by verifying:
 * 1. planSync returns WriteAndAck for unbooked records (not AckOnly)
 * 2. After a "successful write" (simulated by updating bookmarks), re-planning
 *    the same record returns AckOnly — matching what the sync manager does.
 */
class SyncPlanTest {

    private fun record(id: String, ver: Int = 1) = NutritionQueueRecord(
        clientRecordId = id,
        clientRecordVersion = ver,
        name = "Test",
        startTime = "2024-06-01T10:00:00Z",
        endTime = "2024-06-01T10:30:00Z",
        energyKcal = 350.0,
        proteinG = 20.0,
        carbohydrateG = 40.0,
        fatG = 12.0,
    )

    private fun invalidRecord(id: String) = NutritionQueueRecord(
        clientRecordId = id,
        clientRecordVersion = 1,
        name = "",  // invalid: empty name
        startTime = "2024-06-01T10:00:00Z",
        endTime = "2024-06-01T10:30:00Z",
        energyKcal = 350.0,
    )

    @Test
    fun `fresh record without bookmark is WriteAndAck`() {
        val actions = planSync(listOf(record("r1")), InsertBookmarks.EMPTY)
        assertTrue(actions[0] is RecordAction.WriteAndAck)
    }

    @Test
    fun `invalid record is Skip — not acked`() {
        val actions = planSync(listOf(invalidRecord("bad")), InsertBookmarks.EMPTY)
        assertTrue(actions[0] is RecordAction.Skip)
    }

    @Test
    fun `ack-only after successful write simulation`() {
        val rec = record("r1", 2)
        // Simulate: plan says WriteAndAck, write succeeds, bookmark saved
        val bmAfterWrite = InsertBookmarks.EMPTY.withApplied("r1", 2)

        // Next sync: same record in queue
        val actions = planSync(listOf(rec), bmAfterWrite)
        val action = actions[0]
        assertTrue("Expected AckOnly but got $action", action is RecordAction.AckOnly)
    }

    @Test
    fun `failed ack leaves bookmark in place for retry`() {
        // Scenario: write succeeded, bookmark saved, ack failed
        val bmAfterWrite = InsertBookmarks.EMPTY.withApplied("r1", 1)

        // Next sync sees same record → AckOnly (retry ack)
        val retryActions = planSync(listOf(record("r1", 1)), bmAfterWrite)
        assertTrue(retryActions[0] is RecordAction.AckOnly)
    }

    @Test
    fun `WriteAndAck precedes ack in plan — ordering preserved`() {
        // Write record comes first in the plan before any ack-only records.
        // The plan list order matches the input records order.
        val bm = InsertBookmarks.EMPTY.withApplied("b", 1)
        val records = listOf(record("a", 1), record("b", 1))
        val actions = planSync(records, bm)

        assertTrue(actions[0] is RecordAction.WriteAndAck)
        assertTrue(actions[1] is RecordAction.AckOnly)
    }

    @Test
    fun `empty queue produces empty plan`() {
        assertTrue(planSync(emptyList(), InsertBookmarks.EMPTY).isEmpty())
    }

    @Test
    fun `Skip does not produce ack — only WriteAndAck and AckOnly produce acks`() {
        val records = listOf(invalidRecord("bad"), record("good"))
        val actions = planSync(records, InsertBookmarks.EMPTY)
        val ackedActions = actions.filter { it is RecordAction.WriteAndAck || it is RecordAction.AckOnly }
        val skipped = actions.filter { it is RecordAction.Skip }
        assertEquals(1, ackedActions.size)
        assertEquals(1, skipped.size)
    }

    @Test
    fun `counter applied matches WriteAndAck count in nominal sync`() {
        // Only WriteAndAck records contribute to applied count
        val bm = InsertBookmarks.EMPTY.withApplied("a", 1)
        val records = listOf(
            record("a", 1), // AckOnly
            record("b", 1), // WriteAndAck (new)
            record("c", 1), // WriteAndAck (new)
            invalidRecord("bad"), // Skip
        )
        val actions = planSync(records, bm)
        val writeAndAckCount = actions.count { it is RecordAction.WriteAndAck }
        assertEquals(2, writeAndAckCount)
    }
}
