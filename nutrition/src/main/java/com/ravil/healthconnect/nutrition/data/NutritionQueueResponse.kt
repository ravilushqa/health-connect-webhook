package com.ravil.healthconnect.nutrition.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NutritionQueueResponse(
    @SerialName("schema_version") val schemaVersion: Int,
    val records: List<NutritionQueueRecord> = emptyList(),
)
