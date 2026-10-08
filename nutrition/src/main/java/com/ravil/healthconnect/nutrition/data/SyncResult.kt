package com.ravil.healthconnect.nutrition.data

sealed class SyncResult {
    data class Success(
        val applied: Int,
        val errors: Int,
        val ackFailed: Int,
        val pending: Int,
    ) : SyncResult()

    data class Error(val reason: String) : SyncResult()
}
