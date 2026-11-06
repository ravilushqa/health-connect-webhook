package com.ravil.healthcore.hc

import com.ravil.healthcore.core.model.HealthDataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Health Connect silently leaves out of its dialog any permission the manifest does not
 * declare, so every permission the app requests must be declared, and nothing more.
 */
class PermissionsManifestTest {
    private val declared: Set<String> = Regex("""<uses-permission android:name="(android\.permission\.health\.[A-Z0-9_]+)"""")
        .findAll(File("src/main/AndroidManifest.xml").readText())
        .map { it.groupValues[1] }
        .toSet()

    @Test
    fun `every requested permission is declared`() {
        assertEquals(emptySet<String>(), HcPermissions.ALL - declared)
    }

    @Test
    fun `nothing is declared that is not requested`() {
        assertEquals(emptySet<String>(), declared - HcPermissions.ALL)
    }

    @Test
    fun `every type has a read permission`() {
        for (t in HealthDataType.entries) {
            assertTrue(t.name, HcPermissions.read(t).startsWith("android.permission.health.READ_"))
        }
        assertTrue(HcPermissions.WRITE_NUTRITION == "android.permission.health.WRITE_NUTRITION")
    }
}
