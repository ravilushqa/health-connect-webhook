package com.ravil.healthconnect.nutrition.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AckRequest(
    @SerialName("client_record_id") val clientRecordId: String,
    @SerialName("client_record_version") val clientRecordVersion: Int,
)
