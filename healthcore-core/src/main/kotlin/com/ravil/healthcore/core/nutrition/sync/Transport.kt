package com.ravil.healthcore.core.nutrition.sync

import com.ravil.healthcore.core.nutrition.data.NutritionQueueRecord

interface Transport {
    suspend fun fetchQueue(): Result<List<NutritionQueueRecord>>
    suspend fun ackRecord(clientRecordId: String, clientRecordVersion: Int): Result<Unit>
}
