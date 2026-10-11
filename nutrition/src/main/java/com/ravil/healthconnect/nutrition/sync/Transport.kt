package com.ravil.healthconnect.nutrition.sync

import com.ravil.healthconnect.nutrition.data.NutritionQueueRecord

interface Transport {
    suspend fun fetchQueue(): Result<List<NutritionQueueRecord>>
    suspend fun ackRecord(clientRecordId: String, clientRecordVersion: Int): Result<Unit>
}
