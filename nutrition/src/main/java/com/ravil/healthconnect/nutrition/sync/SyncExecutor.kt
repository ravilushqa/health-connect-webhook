package com.ravil.healthconnect.nutrition.sync

import com.ravil.healthconnect.nutrition.bookmarks.InsertBookmarks
import com.ravil.healthconnect.nutrition.data.NutritionQueueRecord
import com.ravil.healthconnect.nutrition.health.HcWriterPort

data class SyncCycleResult(
    val applied: Int,
    val errors: Int,
    val ackFailed: Int,
    val updatedBookmarks: InsertBookmarks,
)

/**
 * Pure suspend function — no Android context, fully unit-testable.
 *
 * Ordering and durability guarantees:
 *  1. HC write must succeed before bookmark is attempted.
 *  2. onBookmarkPersist must return true (durable commit to disk) before ACK is sent.
 *     If it returns false the record is counted as an error, ACK is not sent, and
 *     updatedBookmarks is NOT advanced — safe WriteAndAck retry on next sync.
 *  3. shouldContinue is checked at THREE points per WriteAndAck record:
 *       a. at the top of each loop iteration (before write)
 *       b. immediately after persist succeeds, before ACK  ← post-write/pre-ACK opt-out
 *       c. at the top of the next iteration
 *     Breaking at point (b) leaves bookmark on disk and HC write done; next sync
 *     detects the bookmark and retries only the ACK (AckOnly path). Safe.
 */
internal suspend fun executeSyncCycle(
    records: List<NutritionQueueRecord>,
    bookmarks: InsertBookmarks,
    transport: Transport,
    hcWriter: HcWriterPort,
    onBookmarkPersist: (InsertBookmarks) -> Boolean,
    shouldContinue: () -> Boolean = { true },
): SyncCycleResult {
    val plan = planSync(records, bookmarks)
    var applied = 0
    var errors = 0
    var ackFailed = 0
    var updatedBookmarks = bookmarks

    planLoop@ for (action in plan) {
        if (!shouldContinue()) break@planLoop

        when (action) {
            is RecordAction.Skip -> errors++

            is RecordAction.AckOnly -> {
                val ack = transport.ackRecord(
                    action.record.clientRecordId,
                    action.record.clientRecordVersion,
                )
                if (ack.isFailure) ackFailed++
            }

            is RecordAction.WriteAndAck -> {
                val write = hcWriter.insertRecord(action.record)
                if (write.isSuccess) {
                    val candidateBookmarks = updatedBookmarks.withApplied(
                        action.record.clientRecordId,
                        action.record.clientRecordVersion,
                    )
                    val persisted = onBookmarkPersist(candidateBookmarks)
                    if (persisted) {
                        updatedBookmarks = candidateBookmarks
                        applied++
                        // Re-check consent after the suspend calls above and before
                        // the network ACK. If opted out between persist and ACK, stop:
                        // bookmark is durable, HC write is done, next sync AckOnly retries.
                        if (!shouldContinue()) break@planLoop
                        val ack = transport.ackRecord(
                            action.record.clientRecordId,
                            action.record.clientRecordVersion,
                        )
                        if (ack.isFailure) ackFailed++
                    } else {
                        // Persist failed — no ACK, bookmark not advanced, safe retry
                        errors++
                    }
                } else {
                    errors++
                }
            }
        }
    }

    return SyncCycleResult(
        applied = applied,
        errors = errors,
        ackFailed = ackFailed,
        updatedBookmarks = updatedBookmarks,
    )
}
