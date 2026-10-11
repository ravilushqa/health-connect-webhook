package com.ravil.healthconnect.nutrition.health

import com.ravil.healthconnect.nutrition.data.NutritionQueueRecord

interface HcWriterPort {
    fun isAvailable(): Boolean
    suspend fun hasWritePermission(): Boolean
    suspend fun insertRecord(record: NutritionQueueRecord): Result<Unit>
}
