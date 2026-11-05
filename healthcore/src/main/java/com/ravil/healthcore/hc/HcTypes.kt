package com.ravil.healthcore.hc

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import com.ravil.healthcore.core.model.HealthDataType
import kotlin.reflect.KClass

/** The Health Connect record class behind each type (the part of :app's enum that needs Android). */
val HealthDataType.recordClass: KClass<out Record>
    get() = when (this) {
        HealthDataType.STEPS -> StepsRecord::class
        HealthDataType.SLEEP -> SleepSessionRecord::class
        HealthDataType.HEART_RATE -> HeartRateRecord::class
        HealthDataType.HEART_RATE_VARIABILITY -> HeartRateVariabilityRmssdRecord::class
        HealthDataType.DISTANCE -> DistanceRecord::class
        HealthDataType.ACTIVE_CALORIES -> ActiveCaloriesBurnedRecord::class
        HealthDataType.TOTAL_CALORIES -> TotalCaloriesBurnedRecord::class
        HealthDataType.WEIGHT -> WeightRecord::class
        HealthDataType.HEIGHT -> HeightRecord::class
        HealthDataType.BLOOD_PRESSURE -> BloodPressureRecord::class
        HealthDataType.BLOOD_GLUCOSE -> BloodGlucoseRecord::class
        HealthDataType.OXYGEN_SATURATION -> OxygenSaturationRecord::class
        HealthDataType.BODY_TEMPERATURE -> BodyTemperatureRecord::class
        HealthDataType.SKIN_TEMPERATURE -> SkinTemperatureRecord::class
        HealthDataType.RESPIRATORY_RATE -> RespiratoryRateRecord::class
        HealthDataType.RESTING_HEART_RATE -> RestingHeartRateRecord::class
        HealthDataType.EXERCISE -> ExerciseSessionRecord::class
        HealthDataType.HYDRATION -> HydrationRecord::class
        HealthDataType.NUTRITION -> NutritionRecord::class
        HealthDataType.BASAL_METABOLIC_RATE -> BasalMetabolicRateRecord::class
        HealthDataType.BODY_FAT -> BodyFatRecord::class
        HealthDataType.LEAN_BODY_MASS -> LeanBodyMassRecord::class
        HealthDataType.BODY_WATER_MASS -> BodyWaterMassRecord::class
        HealthDataType.VO2_MAX -> Vo2MaxRecord::class
        HealthDataType.BONE_MASS -> BoneMassRecord::class
        HealthDataType.MENSTRUATION_FLOW -> MenstruationFlowRecord::class
        HealthDataType.MENSTRUATION_PERIOD -> MenstruationPeriodRecord::class
        HealthDataType.INTERMENSTRUAL_BLEEDING -> IntermenstrualBleedingRecord::class
        HealthDataType.OVULATION_TEST -> OvulationTestRecord::class
        HealthDataType.CERVICAL_MUCUS -> CervicalMucusRecord::class
        HealthDataType.SEXUAL_ACTIVITY -> SexualActivityRecord::class
        HealthDataType.BASAL_BODY_TEMPERATURE -> BasalBodyTemperatureRecord::class
    }

/** Health Connect permissions Health Core asks for. */
object HcPermissions {
    const val READ_IN_BACKGROUND = HealthConnectReader.BACKGROUND_PERMISSION_STR
    val WRITE_NUTRITION: String = HealthPermission.getWritePermission(NutritionRecord::class)
    // Cadence (read for exercise sessions) is covered by READ_STEPS in androidx.
    private val READ_STEPS_CADENCE: String = HealthPermission.getReadPermission(StepsCadenceRecord::class)

    fun read(type: HealthDataType): String = HealthPermission.getReadPermission(type.recordClass)

    /** Every read permission plus background reads and nutrition write-back. */
    val ALL: Set<String> by lazy {
        HealthDataType.entries.map(::read).toSet() + READ_STEPS_CADENCE + READ_IN_BACKGROUND + WRITE_NUTRITION
    }

    /** The types whose read permission is granted: what an ingest run reads. */
    fun grantedTypes(granted: Set<String>): Set<HealthDataType> =
        HealthDataType.entries.filter { read(it) in granted }.toSet()
}
