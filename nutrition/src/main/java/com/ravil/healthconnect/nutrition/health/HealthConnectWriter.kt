package com.ravil.healthconnect.nutrition.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import com.ravil.healthconnect.nutrition.data.NutritionQueueRecord
import com.ravil.healthconnect.nutrition.validation.RecordValidator
import java.time.ZoneOffset

class HealthConnectWriter(private val context: Context) {

    private val writePermission = HealthPermission.getWritePermission(NutritionRecord::class)

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    suspend fun hasWritePermission(): Boolean {
        if (!isAvailable()) return false
        return try {
            writePermission in client().permissionController.getGrantedPermissions()
        } catch (_: Exception) {
            false
        }
    }

    fun writePermissionSet(): Set<String> = setOf(writePermission)

    /**
     * Inserts or upserts a NutritionRecord into Health Connect.
     *
     * Uses clientRecordId + clientRecordVersion for idempotent upsert behavior:
     * - Same id + same version → no-op (HC ignores duplicate)
     * - Same id + higher version → updates the record
     * - Same id + lower version → HC retains the newer version (no downgrade)
     *
     * recordingMethod = RECORDING_METHOD_MANUALLY_ENTERED marks these as
     * user-confirmed AI estimates per the HC API contract.
     */
    suspend fun insertRecord(record: NutritionQueueRecord): Result<Unit> {
        if (!isAvailable()) return Result.failure(Exception("hc_not_available"))
        return try {
            val start = RecordValidator.parseOffset(record.startTime)!!
            val end = RecordValidator.parseOffset(record.endTime)!!

            val startInstant = start.toInstant()
            val endInstant = end.toInstant()
            val startOffset: ZoneOffset = start.offset
            val endOffset: ZoneOffset = end.offset

            val nutritionRecord = NutritionRecord(
                startTime = startInstant,
                startZoneOffset = startOffset,
                endTime = endInstant,
                endZoneOffset = endOffset,
                energy = Energy.kilocalories(record.energyKcal),
                protein = record.proteinG?.let { Mass.grams(it) },
                totalCarbohydrate = record.carbohydrateG?.let { Mass.grams(it) },
                totalFat = record.fatG?.let { Mass.grams(it) },
                name = record.name,
                metadata = Metadata(
                    clientRecordId = record.clientRecordId,
                    clientRecordVersion = record.clientRecordVersion.toLong(),
                    recordingMethod = Metadata.RECORDING_METHOD_MANUAL_ENTRY,
                ),
            )

            client().insertRecords(listOf(nutritionRecord))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
