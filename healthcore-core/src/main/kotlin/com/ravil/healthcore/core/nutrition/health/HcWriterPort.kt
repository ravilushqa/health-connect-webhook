package com.ravil.healthcore.core.nutrition.health

import com.ravil.healthcore.core.nutrition.data.NutritionQueueRecord

interface HcWriterPort {
    fun isAvailable(): Boolean
    suspend fun hasWritePermission(): Boolean
    suspend fun insertRecord(record: NutritionQueueRecord): Result<Unit>
}
