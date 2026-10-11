package com.ravil.healthconnect.nutrition

import android.content.SharedPreferences
import com.ravil.healthconnect.nutrition.crypto.TokenStorePort
import com.ravil.healthconnect.nutrition.prefs.NutritionPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// ---------------------------------------------------------------------------
// Shared test doubles (visible to NutritionSyncManagerTest in same package)
// ---------------------------------------------------------------------------

/**
 * In-memory SharedPreferences double with separate disk and memory maps.
 *
 * Durability model (matches Android behaviour):
 *  - commit(false) applies pending edits to in-process MEMORY but leaves DISK unchanged.
 *  - commit(true)  applies pending edits to MEMORY and then writes the ENTIRE memory map
 *    to DISK — so any phantom values already in memory are also flushed.
 *  - apply()       applies pending edits to MEMORY only (no disk write, simulates async).
 *
 * [reloadFromDisk] resets the memory map to the disk snapshot, simulating a process restart
 * or a secondary SharedPreferences instance reading a fresh file.
 *
 * [commitResult] controls whether commit() simulates a successful disk write.
 * [applyWritesToDisk] controls whether apply() also writes to disk (default false = realistic async).
 */
class FakeSharedPreferences(
    var commitResult: Boolean = true,
    var applyWritesToDisk: Boolean = false,
) : SharedPreferences {

    /** In-process memory — updated by both commit() and apply(). */
    val data = mutableMapOf<String, Any?>()

    /** Simulated on-disk snapshot — updated only by a successful commit() writing the full memory map. */
    val disk = mutableMapOf<String, Any?>()

    /** Resets in-process memory to the last durably committed disk snapshot. */
    fun reloadFromDisk() {
        data.clear()
        data.putAll(disk)
    }

    inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value; return this
        }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            pending[key] = values; return this
        }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value; return this
        }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value; return this
        }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value; return this
        }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value; return this
        }
        override fun remove(key: String): SharedPreferences.Editor {
            removals.add(key); pending.remove(key); return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearAll = true; return this
        }

        override fun commit(): Boolean {
            // Always apply to memory (Android commit updates memory before disk)
            flushToMemory()
            return if (commitResult) {
                // Successful disk write: write ENTIRE current memory to disk
                disk.clear()
                disk.putAll(data)
                true
            } else {
                false
            }
        }

        override fun apply() {
            flushToMemory()
            if (applyWritesToDisk) {
                disk.clear()
                disk.putAll(data)
            }
        }

        private fun flushToMemory() {
            if (clearAll) data.clear()
            data.putAll(pending)
            removals.forEach { data.remove(it) }
        }
    }

    override fun getAll(): MutableMap<String, *> = data
    override fun getString(key: String, defValue: String?): String? =
        data[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        data[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int =
        data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long =
        data[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float =
        data[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        data[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {}
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {}
}

/**
 * Fake TokenStorePort with scriptable setToken/clearToken success.
 * Both mutating operations return Boolean per the real interface contract.
 */
class FakeTokenStorePort(
    private var token: String? = null,
    var setResult: Boolean = true,
    var clearResult: Boolean = true,
) : TokenStorePort {
    override fun getToken(): String? = token
    override fun setToken(token: String): Boolean {
        if (setResult) this.token = token
        return setResult
    }
    override fun clearToken(): Boolean {
        if (clearResult) token = null
        return clearResult
    }
}

// ---------------------------------------------------------------------------
// NutritionPreferences tests
// ---------------------------------------------------------------------------

class NutritionPreferencesTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var prefs: NutritionPreferences

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        prefs = NutritionPreferences(fakePrefs)
        // Companion shims reset for tests that still reference them (SyncOrchestrator, etc.)
        NutritionPreferences.bookmarksDurable = true
        NutritionPreferences.fingerprintDurable = true
        NutritionPreferences.settingsDurable = true
        NutritionPreferences.stableSettings = null
    }

    @After
    fun tearDown() {
        NutritionPreferences.bookmarksDurable = true
        NutritionPreferences.fingerprintDurable = true
        NutritionPreferences.settingsDurable = true
        NutritionPreferences.stableSettings = null
    }

    // --- FakeSharedPreferences disk/memory model ---

    @Test
    fun `commit true writes to both memory and disk`() {
        fakePrefs.edit().putString("key", "value").commit()
        assertEquals("value", fakePrefs.data["key"])
        assertEquals("value", fakePrefs.disk["key"])
    }

    @Test
    fun `commit false updates memory but leaves disk unchanged`() {
        fakePrefs.disk["key"] = "old"
        fakePrefs.data["key"] = "old"
        fakePrefs.commitResult = false

        fakePrefs.edit().putString("key", "new").commit()

        assertEquals("Memory updated with phantom", "new", fakePrefs.data["key"])
        assertEquals("Disk unchanged", "old", fakePrefs.disk["key"])
    }

    @Test
    fun `apply updates memory but not disk by default`() {
        fakePrefs.disk["key"] = "old"
        fakePrefs.data["key"] = "old"

        fakePrefs.edit().putString("key", "new").apply()

        assertEquals("Memory updated", "new", fakePrefs.data["key"])
        assertEquals("Disk unchanged", "old", fakePrefs.disk["key"])
    }

    @Test
    fun `reloadFromDisk resets memory to disk snapshot`() {
        fakePrefs.disk["key"] = "disk-value"
        fakePrefs.data["key"] = "memory-phantom"

        fakePrefs.reloadFromDisk()

        assertEquals("disk-value", fakePrefs.data["key"])
    }

    @Test
    fun `successful later commit flushes whole memory including phantom from prior failed commit`() {
        // Disk starts with stable old URL
        fakePrefs.commitResult = true
        fakePrefs.edit().putString("base_url", "https://old.example.com").commit()
        assertEquals("https://old.example.com", fakePrefs.disk["base_url"])

        // Failed commit: new phantom URL written to memory, disk unchanged
        fakePrefs.commitResult = false
        fakePrefs.edit().putString("base_url", "https://phantom.example.com").commit()
        assertEquals("Phantom in memory", "https://phantom.example.com", fakePrefs.data["base_url"])
        assertEquals("Disk still old", "https://old.example.com", fakePrefs.disk["base_url"])

        // Unrelated successful counter commit flushes ENTIRE memory (including phantom) to disk
        fakePrefs.commitResult = true
        fakePrefs.edit().putInt("counter", 5).commit()
        assertEquals(
            "Phantom URL flushed to disk by unrelated successful commit",
            "https://phantom.example.com",
            fakePrefs.disk["base_url"],
        )
    }

    // --- Durability flags default state ---

    @Test
    fun `durability flags start true on fresh instance`() {
        assertTrue(prefs.bookmarksDurable)
        assertTrue(prefs.fingerprintDurable)
    }

    // --- saveBookmarks: commit false quarantines in-memory state ---

    @Test
    fun `saveBookmarks returns false and sets bookmarksDurable=false when commit fails`() {
        fakePrefs.commitResult = false
        val ok = prefs.saveBookmarks(mapOf("r1" to 1))
        assertFalse(ok)
        assertFalse(prefs.bookmarksDurable)
    }

    @Test
    fun `getBookmarks returns empty when bookmarksDurable is false — phantom quarantine`() {
        fakePrefs.commitResult = true
        prefs.saveBookmarks(mapOf("r1" to 1))
        assertEquals(1, prefs.getBookmarks()["r1"])

        fakePrefs.commitResult = false
        prefs.saveBookmarks(mapOf("r1" to 2))
        assertFalse(prefs.bookmarksDurable)

        assertEquals(
            "Quarantined: getBookmarks must return empty to force WriteAndAck retry",
            emptyMap<String, Int>(),
            prefs.getBookmarks(),
        )
    }

    @Test
    fun `same-process recovery — second successful saveBookmarks restores getBookmarks`() {
        fakePrefs.commitResult = false
        prefs.saveBookmarks(mapOf("r1" to 1))
        assertFalse(prefs.bookmarksDurable)
        assertEquals(emptyMap<String, Int>(), prefs.getBookmarks())

        fakePrefs.commitResult = true
        val ok = prefs.saveBookmarks(mapOf("r1" to 1))
        assertTrue(ok)
        assertTrue(prefs.bookmarksDurable)
        assertEquals(1, prefs.getBookmarks()["r1"])
    }

    @Test
    fun `saveBookmarks commit success sets bookmarksDurable=true`() {
        fakePrefs.commitResult = true
        val ok = prefs.saveBookmarks(mapOf("a" to 5, "b" to 3))
        assertTrue(ok)
        assertTrue(prefs.bookmarksDurable)
        assertEquals(5, prefs.getBookmarks()["a"])
        assertEquals(3, prefs.getBookmarks()["b"])
    }

    // --- savePairingReset: coupled fingerprint+bookmarks commit ---

    @Test
    fun `savePairingReset saves fingerprint and empty bookmarks in one commit`() {
        fakePrefs.commitResult = true
        val ok = prefs.savePairingReset("fp-v1", emptyMap())
        assertTrue(ok)
        assertTrue(prefs.fingerprintDurable)
        assertTrue(prefs.bookmarksDurable)
        assertEquals("fp-v1", prefs.getPairingFingerprint())
        assertEquals(emptyMap<String, Int>(), prefs.getBookmarks())
    }

    @Test
    fun `savePairingReset failure sets BOTH durability flags to false`() {
        fakePrefs.commitResult = false
        val ok = prefs.savePairingReset("fp-fail", emptyMap())
        assertFalse(ok)
        assertFalse(prefs.fingerprintDurable)
        assertFalse(prefs.bookmarksDurable)
        assertNull(prefs.getPairingFingerprint())
        assertEquals(emptyMap<String, Int>(), prefs.getBookmarks())
    }

    @Test
    fun `savePairingReset with non-empty bookmarks persists both atomically`() {
        fakePrefs.commitResult = true
        val ok = prefs.savePairingReset("fp-bm", mapOf("r1" to 2, "r2" to 1))
        assertTrue(ok)
        assertEquals("fp-bm", prefs.getPairingFingerprint())
        assertEquals(2, prefs.getBookmarks()["r1"])
        assertEquals(1, prefs.getBookmarks()["r2"])
    }

    @Test
    fun `savePairingReset recovery after failure restores both flags`() {
        fakePrefs.commitResult = false
        prefs.savePairingReset("bad-fp")
        assertFalse(prefs.fingerprintDurable)
        assertFalse(prefs.bookmarksDurable)

        fakePrefs.commitResult = true
        val ok = prefs.savePairingReset("good-fp")
        assertTrue(ok)
        assertTrue(prefs.fingerprintDurable)
        assertTrue(prefs.bookmarksDurable)
        assertEquals("good-fp", prefs.getPairingFingerprint())
    }

    @Test
    fun `both flags false after savePairingReset fails — both getters return safe defaults`() {
        fakePrefs.commitResult = false
        prefs.savePairingReset("fp")
        assertFalse(prefs.bookmarksDurable)
        assertFalse(prefs.fingerprintDurable)
        assertEquals(emptyMap<String, Int>(), prefs.getBookmarks())
        assertNull(prefs.getPairingFingerprint())
    }

    @Test
    fun `saveBookmarks durability is independent of fingerprint — bookmark flag unaffected by savePairingReset success`() {
        // First establish bookmarks separately
        fakePrefs.commitResult = true
        prefs.saveBookmarks(mapOf("r1" to 1))
        assertTrue(prefs.bookmarksDurable)

        // savePairingReset success also sets bookmarksDurable true (both are coupled)
        prefs.savePairingReset("fp")
        assertTrue(prefs.fingerprintDurable)
        assertTrue(prefs.bookmarksDurable)
    }

    // --- saveSettings: stable bundle and memory restore on failure ---

    @Test
    fun `saveSettings persists all three fields atomically and returns true`() {
        val ok = prefs.saveSettings("https://server.example.com", enabled = true, intervalMinutes = 30)
        assertTrue(ok)
        assertEquals("https://server.example.com", prefs.getBaseUrl())
        assertTrue(prefs.isSyncEnabled())
        assertEquals(30, prefs.getSyncIntervalMinutes())
    }

    @Test
    fun `saveSettings success updates settingsBundle`() {
        prefs.saveSettings("https://s.example.com", enabled = true, intervalMinutes = 45)
        val bundle = prefs.settingsBundle!!
        assertEquals("https://s.example.com", bundle.url)
        assertTrue(bundle.enabled)
        assertEquals(45, bundle.intervalMinutes)
    }

    @Test
    fun `saveSettings returns false and url stays old but isSyncEnabled is false when commit fails`() {
        // Pre-populate with old values BEFORE constructing NutritionPreferences so the
        // registry snapshot is initialised from those values.
        val customFake = FakeSharedPreferences()
        customFake.data["base_url"] = "https://old.example.com"
        customFake.data["sync_enabled"] = true
        val customPrefs = NutritionPreferences(customFake)

        customFake.commitResult = false
        val ok = customPrefs.saveSettings("https://new.example.com", enabled = false, intervalMinutes = 15)
        assertFalse(ok)
        // Old URL is preserved in snapshot
        assertEquals("https://old.example.com", customPrefs.getBaseUrl())
        // Fail-closed: settingsDurable=false → isSyncEnabled() returns false regardless of snapshot
        assertFalse("settingsDurable=false must make isSyncEnabled return false", customPrefs.isSyncEnabled())
        assertFalse(customPrefs.settingsDurable)
        // settingsBundle is null — no saveSettings call has succeeded via the API yet
        assertNull(customPrefs.settingsBundle)
    }

    @Test
    fun `isSyncEnabled returns false when settingsDurable is false — lifted by successful saveSettings`() {
        fakePrefs.commitResult = true
        prefs.saveSettings("https://example.com", enabled = true, intervalMinutes = 60)
        assertTrue(prefs.isSyncEnabled())
        assertTrue(prefs.settingsDurable)

        fakePrefs.commitResult = false
        prefs.saveSettings("https://new.example.com", enabled = true, intervalMinutes = 60)
        assertFalse("settingsDurable=false blocks isSyncEnabled", prefs.isSyncEnabled())

        fakePrefs.commitResult = true
        prefs.saveSettings("https://example.com", enabled = true, intervalMinutes = 60)
        assertTrue("Successful saveSettings lifts settingsDurable", prefs.settingsDurable)
        assertTrue(prefs.isSyncEnabled())
    }

    @Test
    fun `getSettingsTokenFingerprint returns null when settingsDurable is false`() {
        fakePrefs.commitResult = true
        prefs.saveSettings("https://example.com", true, 60, "fp-abc")
        assertEquals("fp-abc", prefs.getSettingsTokenFingerprint())

        fakePrefs.commitResult = false
        prefs.saveSettings("https://example.com", true, 60, "fp-new")
        assertNull("Fingerprint blocked when settingsDurable=false", prefs.getSettingsTokenFingerprint())
    }

    @Test
    fun `saveSettings stores settingsTokenFingerprint when provided`() {
        prefs.saveSettings("https://example.com", true, 60, "fp-xyz")
        assertEquals("fp-xyz", prefs.getSettingsTokenFingerprint())
        assertEquals("fp-xyz", prefs.settingsBundle?.settingsTokenFingerprint)
    }

    @Test
    fun `saveSettings clears settingsTokenFingerprint when null provided`() {
        prefs.saveSettings("https://example.com", true, 60, "fp-xyz")
        assertEquals("fp-xyz", prefs.getSettingsTokenFingerprint())

        prefs.saveSettings("https://example.com", true, 60, null)
        assertNull("Null fingerprint argument must clear the stored fingerprint",
            prefs.getSettingsTokenFingerprint())
    }

    @Test
    fun `counter mutations do NOT restore settingsDurable after failed saveSettings`() {
        fakePrefs.commitResult = true
        prefs.saveSettings("https://example.com", true, 60)
        assertTrue(prefs.settingsDurable)

        fakePrefs.commitResult = false
        prefs.saveSettings("https://example.com", true, 60)
        assertFalse(prefs.settingsDurable)

        // Counter mutations must not lift the flag
        fakePrefs.commitResult = true
        prefs.setPendingCount(5)
        prefs.addApplied(3)
        prefs.setLastSyncErrorCount(1)
        assertFalse("Counter writes must not restore settingsDurable", prefs.settingsDurable)
    }

    @Test
    fun `saveSettings failure restores memory — phantom cannot be flushed by later counter commit`() {
        // Establish old values through the API so the registry snapshot is up-to-date
        fakePrefs.commitResult = true
        prefs.saveSettings("https://old.example.com", enabled = true, intervalMinutes = 60)

        // Failed settings save: full-snapshot commit writes phantom to memory, returns false,
        // NutritionPreferences then issues a restore commit to expel the phantom.
        fakePrefs.commitResult = false
        prefs.saveSettings("https://new.example.com", enabled = false, intervalMinutes = 15)

        // After restore commit, memory must hold the old (confirmed) snapshot values
        assertEquals("Memory restored to old after failed commit",
            "https://old.example.com", fakePrefs.data["base_url"])
        assertTrue(fakePrefs.data["sync_enabled"] as Boolean)

        // A subsequent counter mutation (full-snapshot commit) should land old values on disk
        fakePrefs.commitResult = true
        prefs.setPendingCount(3)

        fakePrefs.reloadFromDisk()
        assertEquals("Old URL on disk after counter commit (no phantom flush)",
            "https://old.example.com", fakePrefs.disk["base_url"])
        assertEquals("https://old.example.com", fakePrefs.data["base_url"])
    }

    @Test
    fun `saveSettings cross-instance — settingsBundle visible to second wrapper on same prefs`() {
        prefs.saveSettings("https://shared.example.com", enabled = true, intervalMinutes = 60)

        // Second NutritionPreferences wrapping the SAME SharedPreferences instance
        val prefs2 = NutritionPreferences(fakePrefs)
        // Registry is keyed by SharedPreferences identity — same instance → same state
        assertEquals("https://shared.example.com", prefs2.getBaseUrl())
        assertTrue(prefs2.isSyncEnabled())
    }

    @Test
    fun `saveSettings enforces minimum interval of 15 minutes`() {
        prefs.saveSettings("https://example.com", enabled = true, intervalMinutes = 5)
        assertEquals(15, prefs.getSyncIntervalMinutes())
    }

    @Test
    fun `saveSettings trims URL whitespace`() {
        prefs.saveSettings("  https://example.com  ", enabled = true, intervalMinutes = 60)
        assertEquals("https://example.com", prefs.getBaseUrl())
    }

    @Test
    fun `settingsBundle null after failed save — second attempt success updates settingsBundle`() {
        fakePrefs.commitResult = false
        prefs.saveSettings("https://fail.example.com", enabled = true, intervalMinutes = 30)
        assertNull(prefs.settingsBundle)

        fakePrefs.commitResult = true
        prefs.saveSettings("https://success.example.com", enabled = false, intervalMinutes = 60)
        val bundle = prefs.settingsBundle!!
        assertEquals("https://success.example.com", bundle.url)
        assertFalse(bundle.enabled)
        assertEquals(60, bundle.intervalMinutes)
    }

    // --- counter mutations use full-snapshot commit, not apply() ---

    @Test
    fun `setPendingCount uses full snapshot commit — phantom settings cannot be flushed`() {
        // Establish confirmed settings
        fakePrefs.commitResult = true
        prefs.saveSettings("https://stable.example.com", enabled = true, intervalMinutes = 60)

        // Failed settings update leaves phantom in memory; NutritionPreferences restores old
        fakePrefs.commitResult = false
        prefs.saveSettings("https://phantom.example.com", enabled = false, intervalMinutes = 15)

        // Counter commit succeeds — must flush OLD (stable) URL, not phantom
        fakePrefs.commitResult = true
        prefs.setPendingCount(7)

        fakePrefs.reloadFromDisk()
        assertEquals(
            "Full snapshot commit must not flush phantom URL to disk",
            "https://stable.example.com",
            fakePrefs.disk["base_url"],
        )
        assertEquals(7, (fakePrefs.disk["counter_pending"] as? Int))
    }

    @Test
    fun `two independent stores have isolated durability flags`() {
        val fakePrefs2 = FakeSharedPreferences()
        val prefs2 = NutritionPreferences(fakePrefs2)

        // Fail bookmarks on store 1 only
        fakePrefs.commitResult = false
        prefs.saveBookmarks(mapOf("a" to 1))
        fakePrefs2.commitResult = true
        prefs2.saveBookmarks(mapOf("b" to 2))

        assertFalse("Store 1 bookmark quarantined", prefs.bookmarksDurable)
        assertTrue("Store 2 bookmark unaffected", prefs2.bookmarksDurable)
    }

    @Test
    fun `saveBookmarks phantom from failed commit cannot be flushed by later counter commit`() {
        // Establish confirmed bookmark
        fakePrefs.commitResult = true
        prefs.saveBookmarks(mapOf("r1" to 1))

        // Failed bookmark save — phantom in memory, NutritionPreferences issues restore
        fakePrefs.commitResult = false
        prefs.saveBookmarks(mapOf("r1" to 99))
        assertFalse(prefs.bookmarksDurable)

        // Successful counter commit must land confirmed snapshot on disk (no phantom bookmark)
        fakePrefs.commitResult = true
        prefs.setPendingCount(5)

        fakePrefs.reloadFromDisk()
        // Bookmarks on disk should reflect the last confirmed save (r1=1), not the phantom (r1=99)
        val raw = fakePrefs.disk["insert_bookmarks"] as? String
        assertFalse("Phantom bookmark must not be on disk", raw?.contains("99") == true)
    }
}
