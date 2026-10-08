package com.ravil.healthconnect.nutrition.sync

import com.ravil.healthconnect.nutrition.bookmarks.InsertBookmarks
import com.ravil.healthconnect.nutrition.data.NutritionQueueRecord
import com.ravil.healthconnect.nutrition.validation.RecordValidator

sealed class RecordAction {
    /** Record needs to be written to Health Connect, then acked. */
    data class WriteAndAck(val record: NutritionQueueRecord) : RecordAction()

    /** Record was already inserted (bookmark present at same/higher version): just ack. */
    data class AckOnly(val record: NutritionQueueRecord, val reason: String) : RecordAction()

    /** Record failed validation: skip (do not write, do not ack). */
    data class Skip(val record: NutritionQueueRecord, val reason: String) : RecordAction()
}

/**
 * Pure function — no Android APIs, fully unit-testable.
 * Determines the action for each record in the server queue.
 *
 * Ack ordering guarantee: AckOnly and WriteAndAck records both result in an ack,
 * but WriteAndAck requires a successful HC insert first.
 */
fun planSync(
    records: List<NutritionQueueRecord>,
    bookmarks: InsertBookmarks,
): List<RecordAction> = records.map { record ->
    val validationError = RecordValidator.validate(record)
    when {
        validationError != null ->
            RecordAction.Skip(record, validationError)

        bookmarks.isStaleOrDuplicate(record.clientRecordId, record.clientRecordVersion) ->
            RecordAction.AckOnly(record, "already_applied")

        else ->
            RecordAction.WriteAndAck(record)
    }
}
