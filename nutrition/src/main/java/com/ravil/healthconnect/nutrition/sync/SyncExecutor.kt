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
 * Executes the write+ack cycle for an already-fetched record list.
 * Bookmark is persisted (via onBookmarkPersist) immediately after each successful write,
 * before the ack attempt. If ack fails, next sync detects AckOnly and retries.
 */
internal suspend fun executeSyncCycle(
    records: List<NutritionQueueRecord>,
    bookmarks: InsertBookmarks,
    transport: Transport,
    hcWriter: HcWriterPort,
    onBookmarkPersist: (InsertBookmarks) -> Unit,
): SyncCycleResult {
    val plan = planSync(records, bookmarks)
    var applied = 0
    var errors = 0
    var ackFailed = 0
    var updatedBookmarks = bookmarks

    for (action in plan) {
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
                    updatedBookmarks = updatedBookmarks.withApplied(
                        action.record.clientRecordId,
                        action.record.clientRecordVersion,
                    )
                    onBookmarkPersist(updatedBookmarks)
                    applied++

                    val ack = transport.ackRecord(
                        action.record.clientRecordId,
                        action.record.clientRecordVersion,
                    )
                    if (ack.isFailure) ackFailed++
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
