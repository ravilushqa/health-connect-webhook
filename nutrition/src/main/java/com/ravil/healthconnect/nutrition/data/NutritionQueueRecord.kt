package com.ravil.healthconnect.nutrition.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NutritionQueueRecord(
    @SerialName("client_record_id") val clientRecordId: String,
    @SerialName("client_record_version") val clientRecordVersion: Int,
    val name: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("energy_kcal") val energyKcal: Double,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbohydrate_g") val carbohydrateG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    val description: String? = null,
)
