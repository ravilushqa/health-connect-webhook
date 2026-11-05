import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Plain Kotlin/JVM: everything here is testable without the Android SDK.
//   ./gradlew :healthcore-core:test          (inside the full build)
//   ./gradlew -p healthcore-core test        (standalone, no Google Maven needed)
plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "1.8"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}

// ParityTest runs HC Webhook's own JSON serializer next to ours. That code lives in :app
// (an Android module this one cannot depend on), so it is cut out of :app's sources at
// build time and compiled into the tests against this module's model, which is a copy of
// :app's. If :app changes its serializer or its model, this task or ParityTest fails.
val appReferenceDir = layout.buildDirectory.dir("generated/appReference/kotlin")
val appSyncManager = projectDir.parentFile.resolve("app/src/main/java/com/hcwebhook/app/SyncManager.kt")

val generateAppReference by tasks.registering {
    inputs.file(appSyncManager)
    outputs.dir(appReferenceDir)
    doLast {
        val src = appSyncManager.readText()
        fun cut(from: String, to: String): String {
            val a = src.indexOf(from)
            val b = src.indexOf(to, a + 1)
            check(a >= 0 && b > a) { "AppReference: marker not found in SyncManager.kt: $from" }
            return src.substring(a, b)
        }
        val meta = cut("internal fun JsonObjectBuilder.putRecordMetadata", "\nclass SyncManager(")
        val build = cut("    private fun buildJsonPayload(healthData: HealthData): String {", "    companion object {")
            .replace("    private fun buildJsonPayload(", "    fun buildJsonPayload(")
        val bmi = cut("    private data class BmiEntry(", "\nsealed class SyncResult").trimEnd().removeSuffix("}")
        val out = buildString {
            appendLine("// GENERATED from app/src/main/java/com/hcwebhook/app/SyncManager.kt. Do not edit.")
            appendLine("package com.ravil.healthcore.core.appref")
            appendLine()
            appendLine("import com.ravil.healthcore.core.model.*")
            appendLine("import kotlinx.serialization.json.JsonObjectBuilder")
            appendLine("import kotlinx.serialization.json.buildJsonObject")
            appendLine("import kotlinx.serialization.json.put")
            appendLine("import kotlinx.serialization.json.putJsonArray")
            appendLine("import kotlinx.serialization.json.putJsonObject")
            appendLine("import java.time.Instant")
            appendLine()
            appendLine(meta)
            appendLine("class AppReference(private val appVersionName: String) {")
            appendLine(build)
            appendLine(bmi)
            appendLine("}")
        }
        val dir = appReferenceDir.get().asFile.resolve("com/ravil/healthcore/core/appref")
        dir.mkdirs()
        dir.resolve("AppReference.kt").writeText(out)
    }
}

kotlin.sourceSets["test"].kotlin.srcDir(appReferenceDir)
tasks.named("compileTestKotlin") { dependsOn(generateAppReference) }

tasks.test {
    // ./gradlew -p healthcore-core test -Dhealthcore.updateFixtures=true rewrites
    // src/test/resources/contract/*.json from the synthetic data; review the diff.
    System.getProperty("healthcore.updateFixtures")?.let { systemProperty("healthcore.updateFixtures", it) }
}
