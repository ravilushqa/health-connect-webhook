package com.ravil.healthconnect.nutrition

import com.ravil.healthconnect.nutrition.crypto.TokenStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The key in SharedPreferences where the token lives.
 * Must match the private KEY_TOKEN constant inside TokenStore.
 */
private const val KEY_TOKEN = "nutrition_token"

class TokenStoreTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var store: TokenStore

    @Before
    fun setUp() {
        TokenStore.resetForTesting()
        fakePrefs = FakeSharedPreferences()
        store = TokenStore(fakePrefs)
    }

    @After
    fun tearDown() {
        TokenStore.resetForTesting()
    }

    // --- basic get/set/clear ---

    @Test
    fun `getToken returns null on fresh store`() {
        assertNull(store.getToken())
    }

    @Test
    fun `setToken stores token and getToken returns it`() {
        assertTrue(store.setToken("my-secret-token"))
        assertEquals("my-secret-token", store.getToken())
    }

    @Test
    fun `clearToken removes token and getToken returns null`() {
        store.setToken("tok")
        assertTrue(store.clearToken())
        assertNull(store.getToken())
    }

    @Test
    fun `setToken with blank string stores null`() {
        store.setToken("  ")
        assertNull(store.getToken())
    }

    // --- commit failure: registry unchanged, phantom expelled ---

    @Test
    fun `setToken returns false when commit fails`() {
        fakePrefs.commitResult = false
        assertFalse(store.setToken("token"))
    }

    @Test
    fun `getToken returns null after failed setToken — registry not updated`() {
        fakePrefs.commitResult = false
        store.setToken("phantom-token")
        assertNull("Registry must not be updated on failed commit", store.getToken())
    }

    @Test
    fun `setToken failure restores prefs memory — phantom cannot be flushed by later commit`() {
        fakePrefs.commitResult = false
        store.setToken("phantom")

        // After restore commit, prefs memory must be clean (no phantom token key)
        assertNull("Phantom token must not remain in prefs memory", fakePrefs.data[KEY_TOKEN])

        // A subsequent successful commit flushes current memory to disk; phantom must not appear
        fakePrefs.commitResult = true
        fakePrefs.edit().putInt("unrelated_counter", 1).commit()

        fakePrefs.reloadFromDisk()
        assertNull("Phantom token must not reach disk via later unrelated commit",
            fakePrefs.disk[KEY_TOKEN])
    }

    @Test
    fun `clearToken returns false when commit fails — token preserved in registry`() {
        store.setToken("existing-token")
        fakePrefs.commitResult = false
        assertFalse(store.clearToken())
        assertEquals("existing-token", store.getToken())
    }

    @Test
    fun `clearToken failure restores prefs memory — existing token stays in prefs`() {
        store.setToken("existing")
        fakePrefs.commitResult = false
        store.clearToken()
        assertEquals("Restore must put token back in prefs memory", "existing", fakePrefs.data[KEY_TOKEN])
    }

    // --- process restart simulation ---

    @Test
    fun `fresh store after registry reset loads token from prefs disk`() {
        val freshFake = FakeSharedPreferences()
        freshFake.disk[KEY_TOKEN] = "persisted-token"
        freshFake.reloadFromDisk()  // data = disk

        TokenStore.resetForTesting()
        val freshStore = TokenStore(freshFake)

        assertEquals("persisted-token", freshStore.getToken())
    }

    // --- two wrappers same logical file key: registry sharing ---
    // Production: both use PREFS_NAME → always shared.
    // Tests: pass the same explicit logicalKey to simulate two wrappers for the same file.

    @Test
    fun `two wrappers share registry — second wrapper sees token written by first`() {
        // Use explicit shared key to simulate two wrappers for the same encrypted file.
        val sharedKey = "test-shared-file"
        val storeA = TokenStore(fakePrefs, sharedKey)
        val fakePrefs2 = FakeSharedPreferences()
        val storeB = TokenStore(fakePrefs2, sharedKey)

        storeA.setToken("shared-token")

        // Registry was established by storeA; storeB init must not overwrite it.
        assertEquals("Second wrapper must read token written by first wrapper",
            "shared-token", storeB.getToken())
    }

    @Test
    fun `first wrapper clear is visible to second wrapper via shared registry`() {
        val sharedKey = "test-shared-file-clear"
        val fakePrefs2 = FakeSharedPreferences()
        val storeA = TokenStore(fakePrefs, sharedKey)
        val storeB = TokenStore(fakePrefs2, sharedKey)

        storeA.setToken("tok")
        storeA.clearToken()

        assertNull("Second wrapper must see cleared state", storeB.getToken())
    }

    @Test
    fun `failed write on wrapper1 leaves wrapper2 token unchanged`() {
        val sharedKey = "test-shared-file-fail"
        val fakePrefs2 = FakeSharedPreferences()
        val storeA = TokenStore(fakePrefs, sharedKey)
        val storeB = TokenStore(fakePrefs2, sharedKey)

        storeA.setToken("original")
        fakePrefs.commitResult = false
        storeA.setToken("phantom")

        // Both wrappers share the same registry entry; it must still hold "original"
        assertEquals("original", storeA.getToken())
        assertEquals("original", storeB.getToken())
    }

    // --- distinct injected stores are independent ---

    @Test
    fun `two stores with different injected prefs are isolated — no key sharing`() {
        // Default construction (no explicit logicalKey) → keyed by prefs identity → isolated
        val fakePrefs2 = FakeSharedPreferences()
        val store2 = TokenStore(fakePrefs2)

        store.setToken("tok-store1")
        assertNull("Separate injected store must not see token from other store", store2.getToken())

        store2.setToken("tok-store2")
        assertEquals("tok-store1", store.getToken())
        assertEquals("tok-store2", store2.getToken())
    }

    // --- settings/bookmark/fingerprint phantom flushed by token commit ---
    // TokenStore uses clear() in every commit, so any external phantom keys that were in
    // SharedPreferences memory are unconditionally expelled by a successful token write.

    @Test
    fun `phantom fingerprint in prefs memory expelled by successful setToken`() {
        // Inject phantom key into prefs memory (simulates a failed NutritionPreferences commit)
        fakePrefs.data["pairing_fingerprint"] = "phantom-fp"
        fakePrefs.data["insert_bookmarks"] = """{"r1":99}"""
        fakePrefs.commitResult = true

        // setToken does clear()+putString(KEY_TOKEN)+commit() — clears all existing keys
        store.setToken("tok")

        fakePrefs.reloadFromDisk()
        assertNull("Phantom fingerprint expelled by token commit",
            fakePrefs.disk["pairing_fingerprint"])
        assertNull("Phantom bookmark expelled by token commit",
            fakePrefs.disk["insert_bookmarks"])
        assertEquals("tok", fakePrefs.disk[KEY_TOKEN])
    }

    @Test
    fun `phantom token in prefs memory from failed setToken does not reach disk after later counter-style commit`() {
        // This documents that TokenStore's restore commit prevents phantom token key
        // from persisting even if an external writer later commits.
        fakePrefs.commitResult = false
        store.setToken("phantom")          // fails; restore clears memory
        assertNull(fakePrefs.data[KEY_TOKEN])  // phantom gone from memory

        // External writer commits (e.g. NutritionPreferences counter mutation)
        fakePrefs.commitResult = true
        fakePrefs.edit().putInt("counter_pending", 3).commit()

        fakePrefs.reloadFromDisk()
        assertNull("Phantom token must not appear on disk", fakePrefs.disk[KEY_TOKEN])
    }

    // --- stores isolation ---

    @Test
    fun `reset clears registry — fresh store after reset loads from prefs again`() {
        store.setToken("old-token")

        TokenStore.resetForTesting()

        val freshFake = FakeSharedPreferences()
        freshFake.disk[KEY_TOKEN] = "new-on-disk"
        freshFake.reloadFromDisk()
        val freshStore = TokenStore(freshFake)

        assertEquals("new-on-disk", freshStore.getToken())
    }

    // --- repeated restoration failures / recovery ---

    @Test
    fun `repeated setToken failures do not corrupt registry`() {
        store.setToken("stable")
        fakePrefs.commitResult = false

        repeat(5) { store.setToken("phantom-$it") }

        assertEquals("stable", store.getToken())
        assertEquals("stable", fakePrefs.data[KEY_TOKEN])
    }

    @Test
    fun `recovery after repeated failures — first successful write updates registry`() {
        fakePrefs.commitResult = false
        repeat(3) { store.setToken("fail-$it") }
        assertNull(store.getToken())

        fakePrefs.commitResult = true
        assertTrue(store.setToken("final-token"))
        assertEquals("final-token", store.getToken())
    }

    @Test
    fun `repeated clearToken failures preserve token in registry`() {
        store.setToken("tok")
        fakePrefs.commitResult = false

        repeat(4) { store.clearToken() }

        assertEquals("tok", store.getToken())
        assertEquals("tok", fakePrefs.data[KEY_TOKEN])
    }
}
