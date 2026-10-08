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

class HealthConnectWriter(private val context: Context) : HcWriterPort {

    private val writePermission = HealthPermission.getWritePermission(NutritionRecord::class)

    override fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    override suspend fun hasWritePermission(): Boolean {
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
     * clientRecordId + clientRecordVersion provide idempotent upsert semantics:
     * same id + same version → no-op; same id + higher version → update; lower → no downgrade.
     *
     * Optional fields (fiber, sugar, sodium) are mapped only when present — never guessed to zero.
     * sodium_mg from the server is converted to grams (÷1000) for the HC Mass field.
     *
     * Since NutritionRecord has no description field, the description is appended to the name
     * so AI-estimate annotations survive in the Health Connect record.
     */
    override suspend fun insertRecord(record: NutritionQueueRecord): Result<Unit> {
        if (!isAvailable()) return Result.failure(Exception("hc_not_available"))
        return try {
            val start = RecordValidator.parseOffset(record.startTime)!!
            val end = RecordValidator.parseOffset(record.endTime)!!

            val nutritionRecord = NutritionRecord(
                startTime = start.toInstant(),
                startZoneOffset = start.offset,
                endTime = end.toInstant(),
                endZoneOffset = end.offset,
                energy = Energy.kilocalories(record.energyKcal),
                protein = record.proteinG?.let { Mass.grams(it) },
                totalCarbohydrate = record.carbohydrateG?.let { Mass.grams(it) },
                totalFat = record.fatG?.let { Mass.grams(it) },
                dietaryFiber = record.fiberG?.let { Mass.grams(it) },
                sugar = record.sugarG?.let { Mass.grams(it) },
                sodium = record.sodiumMg?.let { Mass.grams(it / 1000.0) },
                name = buildRecordName(record),
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

    private fun buildRecordName(record: NutritionQueueRecord): String {
        val desc = record.description?.trim()
        return if (!desc.isNullOrBlank()) "${record.name} [$desc]" else record.name
    }
}
