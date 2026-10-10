package com.ravil.healthconnect.nutrition

import android.content.SharedPreferences
import com.ravil.healthconnect.nutrition.crypto.TokenStore
import com.ravil.healthconnect.nutrition.crypto.TokenStorePort
import com.ravil.healthconnect.nutrition.data.NutritionQueueRecord
import com.ravil.healthconnect.nutrition.data.SyncResult
import com.ravil.healthconnect.nutrition.health.HcWriterPort
import com.ravil.healthconnect.nutrition.prefs.NutritionPreferences
import com.ravil.healthconnect.nutrition.sync.NutritionSyncManager
import com.ravil.healthconnect.nutrition.sync.SettingsRequest
import com.ravil.healthconnect.nutrition.sync.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** HcWriter that is available, has permission, and always inserts successfully. */
private class AlwaysOkWriter : HcWriterPort {
    override fun isAvailable() = true
    override suspend fun hasWritePermission() = true
    override suspend fun insertRecord(record: NutritionQueueRecord) = Result.success(Unit)
}

/**
 * HcWriter whose hasWritePermission() increments the consent generation before returning
 * true — simulates a beginSettingsUpdate pre-mutex increment arriving during that suspension.
 */
private class ConsentBumpingWriter : HcWriterPort {
    override fun isAvailable() = true
    override suspend fun hasWritePermission(): Boolean {
        NutritionSyncManager.consentGeneration.incrementAndGet()
        return true
    }
    override suspend fun insertRecord(record: NutritionQueueRecord) = Result.success(Unit)
}

/** HcWriter that is not available. */
private class UnavailableWriter : HcWriterPort {
    override fun isAvailable() = false
    override suspend fun hasWritePermission() = false
    override suspend fun insertRecord(record: NutritionQueueRecord): Result<Unit> =
        Result.failure(Exception("unavailable"))
}

/** Transport that always returns an empty queue successfully. */
private class EmptyQueueTransport : Transport {
    override suspend fun fetchQueue(): Result<List<NutritionQueueRecord>> =
        Result.success(emptyList())
    override suspend fun ackRecord(clientRecordId: String, clientRecordVersion: Int) =
        Result.success(Unit)
}

private fun queueRecord(id: String, ver: Int = 1) = NutritionQueueRecord(
    clientRecordId = id, clientRecordVersion = ver,
    name = "Meal", startTime = "2024-06-01T10:00:00Z", endTime = "2024-06-01T10:30:00Z",
    energyKcal = 400.0, proteinG = 25.0, carbohydrateG = 45.0, fatG = 15.0,
)

/**
 * Creates a NutritionSyncManager seeded with settings via updateSettings so the
 * settings-token binding fingerprint is durably established before performSync is called.
 */
private suspend fun seedManager(
    url: String = "https://test.example.com",
    token: String = "tok",
    enabled: Boolean = true,
    fakePrefs: FakeSharedPreferences = FakeSharedPreferences(),
    tokenStore: FakeTokenStorePort = FakeTokenStorePort(null),
    writer: HcWriterPort = AlwaysOkWriter(),
    transportFactory: (String, String) -> Transport = { _, _ -> EmptyQueueTransport() },
): NutritionSyncManager {
    val prefs = NutritionPreferences(fakePrefs)
    val manager = NutritionSyncManager(prefs, tokenStore, writer, transportFactory)
    manager.updateSettings(url, token, enabled, 60)
    return manager
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

class NutritionSyncManagerTest {

    @Before
    fun setUp() {
        NutritionSyncManager.resetForTesting()
        TokenStore.resetForTesting()
        NutritionPreferences.bookmarksDurable = true
        NutritionPreferences.fingerprintDurable = true
        NutritionPreferences.settingsDurable = true
        NutritionPreferences.stableSettings = null
    }

    @After
    fun tearDown() {
        NutritionSyncManager.resetForTesting()
        TokenStore.resetForTesting()
        NutritionPreferences.bookmarksDurable = true
        NutritionPreferences.fingerprintDurable = true
        NutritionPreferences.settingsDurable = true
        NutritionPreferences.stableSettings = null
    }

    // -------------------------------------------------------------------------
    // Generation snapshot BEFORE mutex: stale-ticket rejection
    // -------------------------------------------------------------------------

    @Test
    fun `performSync queued behind updateSettings rejects stale ticket on entry`() = runBlocking {
        val fakeSharedPrefs = FakeSharedPreferences()
        val tokenStore = FakeTokenStorePort(null)
        val prefs = NutritionPreferences(fakeSharedPrefs)
        val manager = NutritionSyncManager(
            prefs, tokenStore, AlwaysOkWriter(),
            transportFactory = { _, _ -> EmptyQueueTransport() },
        )
        // Seed settings so binding is established (updateSettings clears pendingUpdate).
        manager.updateSettings("https://test.example.com", "tok", true, 60)

        var syncResult: SyncResult? = null
        val holder = launch(Dispatchers.IO) {
            NutritionSyncManager.syncMutex.withLock {
                delay(100)
                // Simulate beginSettingsUpdate firing while performSync is queued
                NutritionSyncManager.consentGeneration.incrementAndGet()
            }
        }
        delay(20) // let holder acquire lock first
        val syncJob = launch(Dispatchers.IO) {
            syncResult = manager.performSync()
        }
        holder.join(); syncJob.join()

        assertTrue("Expected SyncResult.Error", syncResult is SyncResult.Error)
        assertEquals("consent_revoked", (syncResult as SyncResult.Error).reason)
    }

    @Test
    fun `generation incremented during hasWritePermission causes consent_revoked`() = runBlocking {
        val manager = seedManager(writer = ConsentBumpingWriter())

        val result = manager.performSync()

        assertTrue("Expected SyncResult.Error", result is SyncResult.Error)
        assertEquals("consent_revoked", (result as SyncResult.Error).reason)
    }

    @Test
    fun `generation stable throughout hasWritePermission — consent check passes cycle continues`() = runBlocking {
        val manager = seedManager()

        val result = manager.performSync()

        val reason = (result as? SyncResult.Error)?.reason
        assertFalse(
            "Generation was stable — consent_revoked must not be the reason (got: $reason)",
            reason == "consent_revoked",
        )
    }

    // -------------------------------------------------------------------------
    // updateSettings returns false on commit failure — no false saved success
    // -------------------------------------------------------------------------

    @Test
    fun `updateSettings returns false when prefs commit fails`() = runBlocking {
        val fakePrefs = FakeSharedPreferences(commitResult = false)
        val prefs = NutritionPreferences(fakePrefs)
        val manager = NutritionSyncManager(prefs, FakeTokenStorePort("existing"), AlwaysOkWriter())

        // With commitResult=false, saveSettings fails → updateSettings returns false
        val ok = manager.updateSettings(
            baseUrl = "https://new.example.com",
            tokenInput = null,
            syncEnabled = true,
            intervalMinutes = 60,
        )

        assertFalse("updateSettings must return false when disk commit fails", ok)
    }

    @Test
    fun `updateSettings returns false when token store commit fails`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val manager = NutritionSyncManager(
            prefs,
            FakeTokenStorePort("old-token", setResult = false),
            AlwaysOkWriter(),
        )

        val ok = manager.updateSettings(
            baseUrl = "https://test.example.com",
            tokenInput = "new-token",
            syncEnabled = true,
            intervalMinutes = 60,
        )

        assertFalse("updateSettings must return false when token commit fails", ok)
    }

    @Test
    fun `updateSettings returns true when prefs and token commit succeed`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val tokenStore = FakeTokenStorePort(null, setResult = true)
        val manager = NutritionSyncManager(prefs, tokenStore, AlwaysOkWriter())

        val ok = manager.updateSettings(
            baseUrl = "https://test.example.com",
            tokenInput = "new-token",
            syncEnabled = true,
            intervalMinutes = 60,
        )

        assertTrue(ok)
        assertEquals("new-token", tokenStore.getToken())
    }

    @Test
    fun `updateSettings with no token input does not call setToken`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val tokenStore = FakeTokenStorePort("existing-token", setResult = false)
        val manager = NutritionSyncManager(prefs, tokenStore, AlwaysOkWriter())

        val ok = manager.updateSettings(
            baseUrl = "https://test.example.com",
            tokenInput = null,
            syncEnabled = true,
            intervalMinutes = 60,
        )

        assertTrue("Should succeed — no token write attempted", ok)
        assertEquals("existing-token", tokenStore.getToken())
    }

    // -------------------------------------------------------------------------
    // Token-first write order: token persisted before settings
    // -------------------------------------------------------------------------

    @Test
    fun `token is persisted before settings — token failure prevents settings write`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val tokenStore = FakeTokenStorePort("old-token", setResult = false)
        val manager = NutritionSyncManager(prefs, tokenStore, AlwaysOkWriter())

        val ok = manager.updateSettings("https://test.example.com", "new-token", true, 60)

        assertFalse(ok)
        // Token not changed
        assertEquals("old-token", tokenStore.getToken())
        // Settings not written (prefs has no base_url from registry — fresh prefs)
        assertNull(prefs.getBaseUrl())
    }

    @Test
    fun `settings failure best-effort restores old token`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val tokenStore = FakeTokenStorePort("old-token", setResult = true)
        val manager = NutritionSyncManager(prefs, tokenStore, AlwaysOkWriter())

        // Token write will succeed but settings write will fail
        fakePrefs.commitResult = false
        val ok = manager.updateSettings("https://test.example.com", "new-token", true, 60)

        assertFalse(ok)
        // Best-effort restore: old token should be back
        assertEquals("old-token", tokenStore.getToken())
    }

    // -------------------------------------------------------------------------
    // Settings-token binding fingerprint: mismatch and missing block sync
    // -------------------------------------------------------------------------

    @Test
    fun `performSync blocked with no_settings_binding when no saveSettings was called`() = runBlocking {
        var transportCreated = 0
        var hcAvailabilityCalls = 0
        var hcPermissionCalls = 0
        var hcInsertCalls = 0

        val trackingWriter = object : HcWriterPort {
            override fun isAvailable(): Boolean { hcAvailabilityCalls++; return true }
            override suspend fun hasWritePermission(): Boolean { hcPermissionCalls++; return true }
            override suspend fun insertRecord(record: NutritionQueueRecord): Result<Unit> {
                hcInsertCalls++; return Result.success(Unit)
            }
        }

        val fakePrefs = FakeSharedPreferences().also {
            it.data["base_url"] = "https://test.example.com"
            it.data["sync_enabled"] = true
        }
        val prefs = NutritionPreferences(fakePrefs)
        // Do NOT call updateSettings — no fingerprint stored
        val manager = NutritionSyncManager(prefs, FakeTokenStorePort("tok"), trackingWriter,
            transportFactory = { _, _ -> transportCreated++; EmptyQueueTransport() })

        val result = manager.performSync()

        assertTrue(result is SyncResult.Error)
        assertEquals("no_settings_binding", (result as SyncResult.Error).reason)
        assertEquals("No transport created on no_settings_binding", 0, transportCreated)
        assertEquals("No HC availability check on no_settings_binding", 0, hcAvailabilityCalls)
        assertEquals("No HC permission check on no_settings_binding", 0, hcPermissionCalls)
        assertEquals("No HC insert on no_settings_binding", 0, hcInsertCalls)
    }

    @Test
    fun `performSync blocked with settings_binding_mismatch when URL changed without re-saving`() = runBlocking {
        var transportCreated = 0
        var hcAvailabilityCalls = 0
        var hcInsertCalls = 0

        val trackingWriter = object : HcWriterPort {
            override fun isAvailable(): Boolean { hcAvailabilityCalls++; return true }
            override suspend fun hasWritePermission() = true
            override suspend fun insertRecord(record: NutritionQueueRecord): Result<Unit> {
                hcInsertCalls++; return Result.success(Unit)
            }
        }

        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val tokenStore = FakeTokenStorePort(null)
        val manager = NutritionSyncManager(prefs, tokenStore, AlwaysOkWriter(),
            transportFactory = { _, _ -> EmptyQueueTransport() })

        // Establish binding with URL A
        manager.updateSettings("https://server-a.example.com", "tok", true, 60)

        // Manually change URL in prefs snapshot without going through updateSettings
        // (simulates direct storage tampering or process restart with different disk state)
        fakePrefs.edit().putString("base_url", "https://server-b.example.com").commit()
        // New FakeSharedPreferences instance populated from fakePrefs disk — distinct registry
        // entry, so NutritionPreferences loads fresh state instead of the shared snapshot.
        val freshFakePrefs = FakeSharedPreferences().also { it.data.putAll(fakePrefs.disk) }
        val prefs2 = NutritionPreferences(freshFakePrefs)

        val manager2 = NutritionSyncManager(prefs2, tokenStore, trackingWriter,
            transportFactory = { _, _ -> transportCreated++; EmptyQueueTransport() })

        // performSync uses prefs2 which reads the tampered URL from disk but the stored
        // fingerprint was computed with the old URL → mismatch
        val result = manager2.performSync()

        assertTrue(result is SyncResult.Error)
        val reason = (result as SyncResult.Error).reason
        assertTrue("Expected binding mismatch or no_settings_binding, got: $reason",
            reason == "settings_binding_mismatch" || reason == "no_settings_binding")
        assertEquals("No transport created on binding check failure", 0, transportCreated)
        assertEquals("No HC availability check on binding check failure", 0, hcAvailabilityCalls)
        assertEquals("No HC insert on binding check failure", 0, hcInsertCalls)
    }

    @Test
    fun `performSync succeeds after updateSettings establishes correct binding`() = runBlocking {
        val manager = seedManager(transportFactory = { _, _ -> EmptyQueueTransport() })

        val result = manager.performSync()

        assertTrue("Should succeed with correct binding", result is SyncResult.Success)
    }

    // -------------------------------------------------------------------------
    // SettingsRequest ticket: beginSettingsUpdate + applySettings
    // -------------------------------------------------------------------------

    @Test
    fun `beginSettingsUpdate creates ticket with current generation and marks pending`() {
        val genBefore = NutritionSyncManager.consentGeneration.get()
        val request = NutritionSyncManager.beginSettingsUpdate(
            "https://t.com", "tok", true, 60)

        assertEquals(genBefore + 1, request.generation)
        assertEquals(genBefore + 1, NutritionSyncManager.consentGeneration.get())
        assertTrue(NutritionSyncManager.pendingUpdate.get())
    }

    @Test
    fun `applySettings with matching generation clears pending on success`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val manager = NutritionSyncManager(prefs, FakeTokenStorePort("tok"), AlwaysOkWriter())

        val request = NutritionSyncManager.beginSettingsUpdate(
            "https://t.example.com", null, true, 60)
        assertTrue(NutritionSyncManager.pendingUpdate.get())

        val ok = manager.applySettings(request)

        assertTrue(ok)
        assertFalse("Successful applySettings must clear pending", NutritionSyncManager.pendingUpdate.get())
    }

    @Test
    fun `applySettings with stale generation rejects ticket — pending stays true`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val manager = NutritionSyncManager(prefs, FakeTokenStorePort("tok"), AlwaysOkWriter())

        val stale = NutritionSyncManager.beginSettingsUpdate(
            "https://t.example.com", null, true, 60)
        // Newer request issued — stale's generation no longer matches current
        NutritionSyncManager.beginSettingsUpdate("https://t.example.com", null, true, 60)

        val ok = manager.applySettings(stale)

        assertFalse("Stale ticket must be rejected", ok)
        assertTrue("Pending must stay true after stale rejection", NutritionSyncManager.pendingUpdate.get())
    }

    @Test
    fun `performSync returns settings_pending when pendingUpdate is true`() = runBlocking {
        val manager = seedManager()
        // Mark pending without clearing it
        NutritionSyncManager.pendingUpdate.set(true)

        val result = manager.performSync()

        assertTrue(result is SyncResult.Error)
        assertEquals("settings_pending", (result as SyncResult.Error).reason)
    }

    @Test
    fun `updateSettings wrapper creates ticket then applies — same as manual beginSettingsUpdate+applySettings`() = runBlocking {
        val fakePrefs1 = FakeSharedPreferences()
        val prefs1 = NutritionPreferences(fakePrefs1)
        val manager1 = NutritionSyncManager(prefs1, FakeTokenStorePort(null), AlwaysOkWriter())

        // Via updateSettings convenience wrapper
        val ok = manager1.updateSettings("https://t.example.com", "tok", true, 60)

        assertTrue(ok)
        assertFalse("updateSettings must clear pending on success", NutritionSyncManager.pendingUpdate.get())
        assertEquals("https://t.example.com", prefs1.getBaseUrl())
        assertTrue(prefs1.isSyncEnabled())
        assertNotNull(prefs1.getSettingsTokenFingerprint())
    }

    // -------------------------------------------------------------------------
    // Overlap: older enabled ticket processed AFTER newer disable ticket
    // -------------------------------------------------------------------------

    @Test
    fun `older enabled ticket processed after newer disable ticket is rejected as stale`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val manager = NutritionSyncManager(prefs, FakeTokenStorePort(null), AlwaysOkWriter())

        // Simulate UI: user saves enable, then immediately disables before IO coroutine runs
        val enableRequest = NutritionSyncManager.beginSettingsUpdate(
            "https://t.example.com", "tok", true, 60)
        // User toggles off — newer disable request issued
        val disableRequest = NutritionSyncManager.beginSettingsUpdate(
            "https://t.example.com", null, false, 60)

        // Enable coroutine arrives at mutex first but its generation is now stale
        val enableOk = manager.applySettings(enableRequest)
        assertFalse("Stale enable ticket must be rejected", enableOk)
        assertTrue("Pending must stay true", NutritionSyncManager.pendingUpdate.get())

        // Disable coroutine runs with the latest generation — succeeds
        val disableOk = manager.applySettings(disableRequest)
        assertTrue("Latest disable ticket must succeed", disableOk)
        assertFalse("Pending cleared after disable commit", NutritionSyncManager.pendingUpdate.get())
        assertFalse("Sync must be disabled", prefs.isSyncEnabled())
    }

    // -------------------------------------------------------------------------
    // Disable-reenable: existing cycle resurrection prevention
    // -------------------------------------------------------------------------

    @Test
    fun `disable-reenable does not restore consent for in-flight cycle`() {
        val snapshot = NutritionSyncManager.consentGeneration.get()
        val shouldContinue = { NutritionSyncManager.consentGeneration.get() == snapshot }

        NutritionSyncManager.consentGeneration.incrementAndGet()  // disable
        assertFalse(shouldContinue())

        NutritionSyncManager.consentGeneration.incrementAndGet()  // re-enable
        assertFalse("Re-enable must not restore in-flight cycle consent", shouldContinue())
    }

    /**
     * Disable fires right after the first HC insert (before bookmark persist and ACK).
     * The generation bump happens inside insertRecord so the shouldContinue check at the
     * post-persist pre-ACK point detects the mismatch — ACK is withheld, records b and c
     * are never started.
     */
    @Test
    fun `disable during cycle stops processing — ACK withheld and remaining records skipped`() = runBlocking {
        val insertCalls = mutableListOf<String>()
        val ackCalls = mutableListOf<String>()

        // Writer bumps generation after first insert — simulates disable arriving mid-cycle
        val writer = object : HcWriterPort {
            var count = 0
            override fun isAvailable() = true
            override suspend fun hasWritePermission() = true
            override suspend fun insertRecord(record: NutritionQueueRecord): Result<Unit> {
                insertCalls.add(record.clientRecordId)
                count++
                if (count == 1) NutritionSyncManager.consentGeneration.incrementAndGet()
                return Result.success(Unit)
            }
        }
        val transport = object : Transport {
            override suspend fun fetchQueue() =
                Result.success(listOf(queueRecord("a"), queueRecord("b"), queueRecord("c")))
            override suspend fun ackRecord(id: String, ver: Int): Result<Unit> {
                ackCalls.add(id); return Result.success(Unit)
            }
        }

        val manager = seedManager(
            writer = writer,
            transportFactory = { _, _ -> transport },
        )

        manager.performSync()

        // "a" was inserted; generation bumped after insert
        assertTrue("a must have been inserted", insertCalls.contains("a"))
        // ACK was withheld — shouldContinue fired false at post-persist pre-ACK check
        assertFalse("ACK for a must be withheld after disable", ackCalls.contains("a"))
        // "b" and "c" were never started
        assertFalse("b must not be inserted after disable", insertCalls.contains("b"))
        assertFalse("c must not be inserted after disable", insertCalls.contains("c"))
    }

    // -------------------------------------------------------------------------
    // Transport factory injection: performSync uses injected transport
    // -------------------------------------------------------------------------

    @Test
    fun `performSync uses injected transport factory — empty queue returns Success`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val tokenStore = FakeTokenStorePort(null)
        var factoryCalled = false
        val manager = NutritionSyncManager(
            prefs, tokenStore, AlwaysOkWriter(),
            transportFactory = { url, token ->
                factoryCalled = true
                assertEquals("https://test.example.com", url)
                assertEquals("tok", token)
                EmptyQueueTransport()
            },
        )
        manager.updateSettings("https://test.example.com", "tok", true, 60)

        val result = manager.performSync()

        assertTrue("Transport factory should have been called", factoryCalled)
        assertTrue("Empty queue should yield Success", result is SyncResult.Success)
        assertEquals(0, (result as SyncResult.Success).applied)
    }

    @Test
    fun `performSync completes full fetch-insert-bookmark-ack cycle`() = runBlocking {
        val record = queueRecord("r1")
        val fakeTransport = FakeTransport(
            queueResult = Result.success(listOf(record)),
            ackResult = Result.success(Unit),
        )
        val writer = FakeHcWriter()

        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val tokenStore = FakeTokenStorePort(null)
        val manager = NutritionSyncManager(prefs, tokenStore, writer,
            transportFactory = { _, _ -> fakeTransport })
        manager.updateSettings("https://test.example.com", "tok", true, 60)

        val result = manager.performSync()

        assertTrue("Should be Success", result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(1, success.applied)
        assertEquals(0, success.errors)
        assertEquals(0, success.ackFailed)
        assertEquals(1, writer.insertCalls.size)
        assertEquals("r1", writer.insertCalls[0].clientRecordId)
        assertEquals(1, fakeTransport.ackCalls.size)
        assertEquals("r1", fakeTransport.ackCalls[0].first)
    }

    @Test
    fun `insert happens and bookmark is durable before ACK is sent through manager`() = runBlocking {
        val record = queueRecord("r1")
        val insertCalls = mutableListOf<String>()
        val ackCalls = mutableListOf<String>()

        val transport = object : Transport {
            override suspend fun fetchQueue() = Result.success(listOf(record))
            override suspend fun ackRecord(id: String, ver: Int): Result<Unit> {
                ackCalls.add(id); return Result.success(Unit)
            }
        }
        val writer = object : HcWriterPort {
            override fun isAvailable() = true
            override suspend fun hasWritePermission() = true
            override suspend fun insertRecord(r: NutritionQueueRecord): Result<Unit> {
                insertCalls.add(r.clientRecordId); return Result.success(Unit)
            }
        }

        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val tokenStore = FakeTokenStorePort(null)
        val manager = NutritionSyncManager(prefs, tokenStore, writer,
            transportFactory = { _, _ -> transport })
        manager.updateSettings("https://test.example.com", "tok", true, 60)

        val result = manager.performSync()

        assertTrue("Should succeed", result is SyncResult.Success)
        assertEquals(1, insertCalls.size)
        assertEquals("r1", insertCalls[0])
        assertEquals(1, ackCalls.size)
        assertEquals("r1", ackCalls[0])
        // Bookmark must be durably persisted (written to disk) after successful sync
        val bookmarksOnDisk = fakePrefs.disk["insert_bookmarks"] as? String
        assertNotNull("Bookmark must be on disk after sync", bookmarksOnDisk)
        assertTrue("Bookmark must contain r1", bookmarksOnDisk!!.contains("r1"))
    }

    @Test
    fun `performSync fetch failure returns fetch_error`() = runBlocking {
        val manager = seedManager(transportFactory = { _, _ ->
            FakeTransport(queueResult = Result.failure(Exception("network_error")))
        })

        val result = manager.performSync()
        assertTrue(result is SyncResult.Error)
        assertEquals("fetch_error", (result as SyncResult.Error).reason)
    }

    // -------------------------------------------------------------------------
    // Settings coherence: saveSettings atomic under mutex
    // -------------------------------------------------------------------------

    @Test
    fun `updateSettings persists url, enabled, token, and fingerprint atomically`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val tokenStore = FakeTokenStorePort(null)
        val manager = NutritionSyncManager(prefs, tokenStore, AlwaysOkWriter())

        val ok = manager.updateSettings(
            baseUrl = "https://saved.example.com",
            tokenInput = "my-token",
            syncEnabled = true,
            intervalMinutes = 60,
        )

        assertTrue(ok)
        assertEquals("https://saved.example.com", prefs.getBaseUrl())
        assertTrue(prefs.isSyncEnabled())
        assertEquals(60, prefs.getSyncIntervalMinutes())
        assertEquals("my-token", tokenStore.getToken())
        assertNotNull("Fingerprint must be set after successful updateSettings",
            prefs.getSettingsTokenFingerprint())
    }

    @Test
    fun `updateSettings with syncEnabled=false disables sync — next performSync returns sync_disabled`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val manager = NutritionSyncManager(prefs, FakeTokenStorePort("tok"), AlwaysOkWriter())
        manager.updateSettings("https://test.example.com", null, true, 60)

        manager.updateSettings("https://test.example.com", null, false, 60)

        val result = manager.performSync()
        assertTrue(result is SyncResult.Error)
        assertEquals("sync_disabled", (result as SyncResult.Error).reason)
    }

    @Test
    fun `enabling without canonical HTTPS URL is rejected`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val manager = NutritionSyncManager(prefs, FakeTokenStorePort("tok"), AlwaysOkWriter())

        val ok = manager.updateSettings("http://not-https.example.com", null, true, 60)

        assertFalse("Non-HTTPS URL must be rejected when enabling", ok)
    }

    @Test
    fun `enabling without any token is rejected`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val manager = NutritionSyncManager(prefs, FakeTokenStorePort(null), AlwaysOkWriter())

        val ok = manager.updateSettings("https://test.example.com", null, true, 60)

        assertFalse("Enabling with no token (existing or new) must be rejected", ok)
    }

    // -------------------------------------------------------------------------
    // Durable token + settings failure / reload / mismatch coupling
    // -------------------------------------------------------------------------

    @Test
    fun `durable token failure leaves settings un-written — sync blocked after failed update`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val tokenStore = FakeTokenStorePort(null, setResult = false)
        val manager = NutritionSyncManager(prefs, tokenStore, AlwaysOkWriter())

        val ok = manager.updateSettings("https://test.example.com", "my-token", true, 60)
        assertFalse(ok)
        assertNull("Token must not be stored", tokenStore.getToken())
        assertNull("Settings must not be persisted when token fails", prefs.getBaseUrl())

        // pendingUpdate should be cleared (beginSettingsUpdate set it, but applySettings
        // failed so it stays true — subsequent performSync returns settings_pending)
        val syncResult = manager.performSync()
        assertTrue(syncResult is SyncResult.Error)
        assertEquals("settings_pending", (syncResult as SyncResult.Error).reason)
    }

    @Test
    fun `settings failure after successful token write restores token — fail-closed`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val tokenStore = FakeTokenStorePort("old-token", setResult = true)
        val manager = NutritionSyncManager(prefs, tokenStore, AlwaysOkWriter())

        // Make prefs commit fail after token is written
        fakePrefs.commitResult = false
        val ok = manager.updateSettings("https://test.example.com", "new-token", true, 60)

        assertFalse(ok)
        // Token was restored to old value
        assertEquals("old-token", tokenStore.getToken())
        // Settings durability is false — sync blocked
        assertFalse(prefs.settingsDurable)
    }

    @Test
    fun `process restart with correct binding on disk allows performSync`() = runBlocking {
        val settingsFakePrefs = FakeSharedPreferences()
        val tokenFakePrefs = FakeSharedPreferences()
        TokenStore.resetForTesting()
        val tokenStore = TokenStore(tokenFakePrefs)

        // First process: establish settings + binding on disk
        run {
            val prefs = NutritionPreferences(settingsFakePrefs)
            val manager = NutritionSyncManager(prefs, tokenStore, AlwaysOkWriter(),
                transportFactory = { _, _ -> EmptyQueueTransport() })
            manager.updateSettings("https://test.example.com", "tok", true, 60)
        }

        // Simulate process restart: reset all in-process registry state
        NutritionSyncManager.resetForTesting()
        TokenStore.resetForTesting()

        // Second process: DISTINCT fresh FakeSharedPreferences each populated from their respective disk snapshots
        val freshSettingsFakePrefs = FakeSharedPreferences().also { it.data.putAll(settingsFakePrefs.disk) }
        val freshTokenFakePrefs = FakeSharedPreferences().also { it.data.putAll(tokenFakePrefs.disk) }
        val freshTokenStore = TokenStore(freshTokenFakePrefs)
        val prefs2 = NutritionPreferences(freshSettingsFakePrefs)
        val manager2 = NutritionSyncManager(prefs2, freshTokenStore, AlwaysOkWriter(),
            transportFactory = { _, _ -> EmptyQueueTransport() })

        val result = manager2.performSync()

        assertTrue(
            "After restart with correct binding, sync must return Success. Got: ${(result as? SyncResult.Error)?.reason}",
            result is SyncResult.Success,
        )
    }

    @Test
    fun `partial token-commit then settings-commit failure — disk mismatch detected on restart — accepted save unblocks`() = runBlocking {
        // Phase 1: initial save establishes old binding on disk
        val settingsFakePrefs = FakeSharedPreferences()
        val tokenFakePrefs = FakeSharedPreferences()
        TokenStore.resetForTesting()
        val tokenStore = TokenStore(tokenFakePrefs)
        val prefs = NutritionPreferences(settingsFakePrefs)
        val manager = NutritionSyncManager(prefs, tokenStore, AlwaysOkWriter(),
            transportFactory = { _, _ -> EmptyQueueTransport() })
        assertTrue(manager.updateSettings("https://server.example.com", "old-token", true, 60))
        val baselineUrl = settingsFakePrefs.disk["base_url"] as String
        val baselineFingerprint = settingsFakePrefs.disk["settings_token_fingerprint"] as String

        // Phase 2: new token commit succeeds; settings commit arms token-restore failure then returns false
        // The settings wrapper fires AFTER tokenStore.setToken("new-token") already committed to disk.
        val failingSettingsPrefs = object : SharedPreferences by settingsFakePrefs {
            override fun edit(): SharedPreferences.Editor {
                val ed = settingsFakePrefs.edit()
                return object : SharedPreferences.Editor by ed {
                    override fun putString(k: String, v: String?) = apply { ed.putString(k, v) }
                    override fun putStringSet(k: String, v: MutableSet<String>?) = apply { ed.putStringSet(k, v) }
                    override fun putInt(k: String, v: Int) = apply { ed.putInt(k, v) }
                    override fun putLong(k: String, v: Long) = apply { ed.putLong(k, v) }
                    override fun putFloat(k: String, v: Float) = apply { ed.putFloat(k, v) }
                    override fun putBoolean(k: String, v: Boolean) = apply { ed.putBoolean(k, v) }
                    override fun remove(k: String) = apply { ed.remove(k) }
                    override fun clear() = apply { ed.clear() }
                    override fun commit(): Boolean {
                        // Arm: all subsequent token commits (including best-effort restore) will fail
                        tokenFakePrefs.commitResult = false
                        return false  // settings commit fails; edits in ed are never flushed
                    }
                }
            }
        }
        NutritionSyncManager.resetForTesting()
        TokenStore.resetForTesting()
        val tokenStore2 = TokenStore(tokenFakePrefs)   // reads "old-token" from tokenFakePrefs.disk
        val prefs2 = NutritionPreferences(failingSettingsPrefs)
        val manager2 = NutritionSyncManager(prefs2, tokenStore2, AlwaysOkWriter(),
            transportFactory = { _, _ -> EmptyQueueTransport() })
        val failOk = manager2.updateSettings("https://server.example.com", "new-token", true, 60)
        assertFalse("Settings commit failure must cause updateSettings to return false", failOk)

        // Verify durable disk state: token disk has new-token (setToken committed before settings failed);
        // settings disk retains old fingerprint (settings commit failed, disk unchanged).
        assertEquals("new-token must be durable on token disk", "new-token", tokenFakePrefs.disk["nutrition_token"])
        assertEquals("Settings disk fingerprint must remain the old binding",
            baselineFingerprint, settingsFakePrefs.disk["settings_token_fingerprint"] as? String)

        // Phase 3: same-process performSync is blocked by pendingUpdate=true (zero transport/HC/ACK)
        val blockedResult = manager2.performSync()
        assertEquals("settings_pending", (blockedResult as SyncResult.Error).reason)

        // Phase 4: process restart — fresh DISTINCT fakes from each disk snapshot
        NutritionSyncManager.resetForTesting()
        TokenStore.resetForTesting()
        var transportCalls = 0; var hcAvailCalls = 0; var hcPermCalls = 0; var hcInsertCalls = 0
        val trackingWriter = object : HcWriterPort {
            override fun isAvailable(): Boolean { hcAvailCalls++; return true }
            override suspend fun hasWritePermission(): Boolean { hcPermCalls++; return true }
            override suspend fun insertRecord(r: com.ravil.healthconnect.nutrition.data.NutritionQueueRecord): Result<Unit> {
                hcInsertCalls++; return Result.success(Unit)
            }
        }
        val freshSettingsFake = FakeSharedPreferences().also { it.data.putAll(settingsFakePrefs.disk) }
        val freshTokenFake = FakeSharedPreferences().also {
            it.data.putAll(tokenFakePrefs.disk)
            it.commitResult = true  // re-enable commits for the restart scenario
        }
        val freshTokenStore = TokenStore(freshTokenFake)     // reads "new-token" from disk
        val freshPrefs = NutritionPreferences(freshSettingsFake)
        val freshManager = NutritionSyncManager(freshPrefs, freshTokenStore, trackingWriter,
            transportFactory = { _, _ -> transportCalls++; EmptyQueueTransport() })

        val mismatchResult = freshManager.performSync()
        assertTrue(mismatchResult is SyncResult.Error)
        assertEquals("settings_binding_mismatch", (mismatchResult as SyncResult.Error).reason)
        assertEquals("No transport created on mismatch", 0, transportCalls)
        assertEquals("No HC availability check on mismatch", 0, hcAvailCalls)
        assertEquals("No HC permission check on mismatch", 0, hcPermCalls)
        assertEquals("No HC insert on mismatch", 0, hcInsertCalls)

        // Prove mismatch is genuine: computed from the actual durable disk values, not invented
        val diskUrl = freshSettingsFake.data["base_url"] as String
        val diskFingerprint = freshSettingsFake.data["settings_token_fingerprint"] as String
        val diskToken = freshTokenFake.data["nutrition_token"] as String
        assertEquals("Disk URL must be the baseline URL", baselineUrl, diskUrl)
        assertEquals("Disk token must be the new token written before settings failed",
            "new-token", diskToken)
        assertFalse(
            "Disk fingerprint (old-token binding) must differ from computed fingerprint (new-token)",
            diskFingerprint == NutritionSyncManager.computePairingFingerprint(diskUrl, diskToken),
        )

        // Phase 5: explicit accepted save aligns fingerprint with current token; fresh sync succeeds
        val acceptOk = freshManager.updateSettings(diskUrl, null, true, 60)
        assertTrue("Accepted save using current URL and new-token from store must succeed", acceptOk)
        val successResult = freshManager.performSync()
        assertTrue("Sync must succeed after accepted save resolves the mismatch",
            successResult is SyncResult.Success)
    }

    // -------------------------------------------------------------------------
    // Real TokenStore + FakeSharedPreferences coupling
    // -------------------------------------------------------------------------

    @Test
    fun `real TokenStore with FakeSharedPreferences — token persisted and read back`() {
        TokenStore.resetForTesting()
        val fakePrefs = FakeSharedPreferences()
        val tokenStore = TokenStore(fakePrefs)

        assertTrue(tokenStore.setToken("real-token"))
        assertEquals("real-token", tokenStore.getToken())
        assertEquals("real-token", fakePrefs.disk["nutrition_token"])
    }

    @Test
    fun `real TokenStore coupling with NutritionPreferences — updateSettings uses both stores`() = runBlocking {
        TokenStore.resetForTesting()
        val tokenFakePrefs = FakeSharedPreferences()
        val tokenStore = TokenStore(tokenFakePrefs)

        val settingsFakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(settingsFakePrefs)
        val manager = NutritionSyncManager(prefs, tokenStore, AlwaysOkWriter())

        val ok = manager.updateSettings("https://test.example.com", "coupled-token", true, 60)

        assertTrue(ok)
        assertEquals("coupled-token", tokenStore.getToken())
        assertEquals("coupled-token", tokenFakePrefs.disk["nutrition_token"])
        assertNotNull(prefs.getSettingsTokenFingerprint())
        assertEquals("https://test.example.com", prefs.getBaseUrl())
    }

    @Test
    fun `real TokenStore registry isolated per logical key — two separate stores`() {
        TokenStore.resetForTesting()
        val fakeA = FakeSharedPreferences()
        val fakeB = FakeSharedPreferences()
        val storeA = TokenStore(fakeA)   // unique key = identity of fakeA
        val storeB = TokenStore(fakeB)   // unique key = identity of fakeB

        storeA.setToken("token-a")
        storeB.setToken("token-b")

        assertEquals("token-a", storeA.getToken())
        assertEquals("token-b", storeB.getToken())
    }

    @Test
    fun `real TokenStore registry shared when same logicalKey — simulates same encrypted file`() {
        TokenStore.resetForTesting()
        val sharedKey = "prod-file-key"
        val fakeA = FakeSharedPreferences()
        val fakeB = FakeSharedPreferences()
        val storeA = TokenStore(fakeA, sharedKey)
        val storeB = TokenStore(fakeB, sharedKey)

        storeA.setToken("shared-token")

        assertEquals("shared-token", storeA.getToken())
        assertEquals("shared-token", storeB.getToken())
    }

    @Test
    fun `real TokenStore registry isolated for two delegates wrapping same inner prefs — reference identity governs`() {
        TokenStore.resetForTesting()
        val inner = FakeSharedPreferences()
        // Two distinct delegate objects wrapping the same inner prefs; same structure, different identity.
        val delegateA = object : android.content.SharedPreferences by inner {}
        val delegateB = object : android.content.SharedPreferences by inner {}
        val storeA = TokenStore(delegateA)
        val storeB = TokenStore(delegateB)

        storeA.setToken("token-from-a")

        assertEquals("token-from-a", storeA.getToken())
        assertNull("storeB must be isolated from storeA despite wrapping same inner prefs",
            storeB.getToken())
    }

    // -------------------------------------------------------------------------
    // UI coordinator: beginSettingsUpdate + applySettings pattern
    // -------------------------------------------------------------------------

    @Test
    fun `UI pattern — beginSettingsUpdate on caller thread then applySettings in coroutine`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val manager = NutritionSyncManager(prefs, FakeTokenStorePort(null), AlwaysOkWriter())

        // Simulates UI thread calling beginSettingsUpdate synchronously
        val request = NutritionSyncManager.beginSettingsUpdate(
            "https://ui.example.com", "ui-token", true, 30)

        // Simulates IO coroutine calling applySettings
        val ok = manager.applySettings(request)

        assertTrue(ok)
        assertEquals("https://ui.example.com", prefs.getBaseUrl())
        assertEquals(30, prefs.getSyncIntervalMinutes())
        assertFalse("Pending must be cleared after successful apply", NutritionSyncManager.pendingUpdate.get())
    }

    @Test
    fun `UI pattern — two rapid saves, only newest ticket applies`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val manager = NutritionSyncManager(prefs, FakeTokenStorePort(null), AlwaysOkWriter())

        // Simulate user saving twice rapidly before either IO coroutine runs
        val firstRequest = NutritionSyncManager.beginSettingsUpdate(
            "https://first.example.com", "tok", true, 60)
        val secondRequest = NutritionSyncManager.beginSettingsUpdate(
            "https://second.example.com", "tok", true, 60)

        // Second IO coroutine arrives at mutex first (e.g. was scheduled earlier)
        val secondOk = manager.applySettings(secondRequest)
        assertTrue("Latest request must succeed", secondOk)
        assertEquals("https://second.example.com", prefs.getBaseUrl())
        assertFalse(NutritionSyncManager.pendingUpdate.get())

        // First coroutine arrives later — its generation is stale
        val firstOk = manager.applySettings(firstRequest)
        assertFalse("Stale first request must be rejected", firstOk)
        // URL unchanged — still from second request
        assertEquals("https://second.example.com", prefs.getBaseUrl())
    }

    // -------------------------------------------------------------------------
    // invalidateConsent() companion function
    // -------------------------------------------------------------------------

    @Test
    fun `invalidateConsent increments generation synchronously and sets pending`() {
        val before = NutritionSyncManager.consentGeneration.get()
        NutritionSyncManager.invalidateConsent()
        assertEquals(before + 1, NutritionSyncManager.consentGeneration.get())
        assertTrue("invalidateConsent must set pendingUpdate", NutritionSyncManager.pendingUpdate.get())
    }

    @Test
    fun `invalidateConsent then beginSettingsUpdate increments generation twice`() {
        val before = NutritionSyncManager.consentGeneration.get()

        NutritionSyncManager.invalidateConsent()
        NutritionSyncManager.beginSettingsUpdate("https://t.com", null, false, 60)

        assertEquals(
            "invalidateConsent + beginSettingsUpdate must each increment once",
            before + 2,
            NutritionSyncManager.consentGeneration.get(),
        )
    }

    // -------------------------------------------------------------------------
    // Commit-race: newer ticket issued DURING disk IO inside applySettings
    // -------------------------------------------------------------------------

    /**
     * A newer [beginSettingsUpdate] ticket is issued from a real thread inside
     * [TokenStorePort.setToken], proving no consent lock is held across disk IO.
     * The older [applySettings] detects the stale generation at finalization and returns
     * false — pending stays true, sync is blocked with zero HC/transport/ACK counters.
     * The captured latest ticket then applies to the SAME manager and wins.
     */
    @Test
    fun `newer ticket issued during token commit causes older applySettings to return false`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)

        var capturedRaceRequest: SettingsRequest? = null
        var transportCreated = 0
        var hcAvailabilityCalls = 0
        var hcPermissionCalls = 0
        var hcInsertCalls = 0
        var ackCalls = 0

        val trackingWriter = object : HcWriterPort {
            override fun isAvailable(): Boolean { hcAvailabilityCalls++; return true }
            override suspend fun hasWritePermission(): Boolean { hcPermissionCalls++; return true }
            override suspend fun insertRecord(record: NutritionQueueRecord): Result<Unit> {
                hcInsertCalls++; return Result.success(Unit)
            }
        }

        val racingTokenStore = object : TokenStorePort {
            private var stored: String? = null
            override fun getToken(): String? = stored
            override fun setToken(token: String): Boolean {
                // Issue from a real thread to prove consentLock is not held across token IO
                val t = Thread {
                    capturedRaceRequest = NutritionSyncManager.beginSettingsUpdate(
                        "https://t.example.com", null, false, 60)
                }
                t.start()
                t.join(2000)
                stored = token
                return true
            }
            override fun clearToken(): Boolean { stored = null; return true }
        }

        val manager = NutritionSyncManager(prefs, racingTokenStore, trackingWriter,
            transportFactory = { _, _ ->
                transportCreated++
                object : Transport {
                    override suspend fun fetchQueue(): Result<List<NutritionQueueRecord>> =
                        Result.success(emptyList())
                    override suspend fun ackRecord(id: String, ver: Int): Result<Unit> {
                        ackCalls++; return Result.success(Unit)
                    }
                }
            })
        val request = NutritionSyncManager.beginSettingsUpdate(
            "https://t.example.com", "new-token", true, 60)

        val ok = manager.applySettings(request)

        assertFalse("Stale ticket (newer arrived during token IO) must return false", ok)
        assertTrue("Pending must stay true — latest ticket not yet applied", NutritionSyncManager.pendingUpdate.get())
        assertNotNull("Race must have captured a newer request", capturedRaceRequest)

        // Blocked sync must touch zero HC/transport/network/ACK operations
        val blockedResult = manager.performSync()
        assertEquals("settings_pending", (blockedResult as SyncResult.Error).reason)
        assertEquals("No transport created while blocked", 0, transportCreated)
        assertEquals("No HC availability check while blocked", 0, hcAvailabilityCalls)
        assertEquals("No HC permission check while blocked", 0, hcPermissionCalls)
        assertEquals("No HC insert while blocked", 0, hcInsertCalls)
        assertEquals("No ACK while blocked", 0, ackCalls)

        // Apply the captured latest ticket to the SAME manager/prefs/tokenStore — it must win
        val latestOk = manager.applySettings(capturedRaceRequest!!)
        assertTrue("Latest ticket must succeed", latestOk)
        assertFalse("Pending must be cleared after latest applies", NutritionSyncManager.pendingUpdate.get())
        assertEquals("https://t.example.com", prefs.getBaseUrl())
        assertFalse("Latest ticket disabled sync", prefs.isSyncEnabled())
        // Token remains "new-token" (latest ticket had null tokenInput — no token change)
        assertEquals("new-token", racingTokenStore.getToken())
        // Binding fingerprint computed from latest URL + resolved token
        assertEquals(
            NutritionSyncManager.computePairingFingerprint("https://t.example.com", "new-token"),
            prefs.getSettingsTokenFingerprint(),
        )
    }

    /**
     * A newer ticket is issued from a real thread inside [SharedPreferences.Editor.commit],
     * proving no consent lock is held across settings disk IO.
     * The older [applySettings] detects the generation mismatch before clearing pending
     * and returns false. The captured latest ticket is then applied to the SAME
     * manager/prefs/tokenStore — it must win with the exact URL/token/binding committed.
     */
    @Test
    fun `newer ticket issued during settings commit causes older applySettings to return false and latest wins`() = runBlocking {
        val innerFakePrefs = FakeSharedPreferences()
        var capturedRaceRequest: SettingsRequest? = null
        var raceArmed = false

        // Delegating wrapper — avoids extending the final FakeSharedPreferences class.
        val racingPrefs = object : SharedPreferences by innerFakePrefs {
            override fun edit(): SharedPreferences.Editor {
                val ed = innerFakePrefs.edit()
                return object : SharedPreferences.Editor by ed {
                    // All fluent setters must return THIS wrapper, not ed, so that chained
                    // .commit() calls hit the overridden commit() below.
                    override fun putString(key: String, value: String?): SharedPreferences.Editor =
                        apply { ed.putString(key, value) }
                    override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
                        apply { ed.putStringSet(key, values) }
                    override fun putInt(key: String, value: Int): SharedPreferences.Editor =
                        apply { ed.putInt(key, value) }
                    override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                        apply { ed.putLong(key, value) }
                    override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
                        apply { ed.putFloat(key, value) }
                    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                        apply { ed.putBoolean(key, value) }
                    override fun remove(key: String): SharedPreferences.Editor =
                        apply { ed.remove(key) }
                    override fun clear(): SharedPreferences.Editor =
                        apply { ed.clear() }
                    override fun commit(): Boolean {
                        val result = ed.commit()
                        if (raceArmed) {
                            raceArmed = false
                            // Issue from a real thread to prove consentLock is not held across IO
                            val t = Thread {
                                capturedRaceRequest = NutritionSyncManager.beginSettingsUpdate(
                                    "https://newer.example.com", "newer-tok", true, 60)
                            }
                            t.start()
                            t.join(2000)
                        }
                        return result
                    }
                }
            }
        }

        val prefs = NutritionPreferences(racingPrefs)
        val tokenStore = FakeTokenStorePort("existing-token")
        val manager = NutritionSyncManager(prefs, tokenStore, AlwaysOkWriter(),
            transportFactory = { _, _ -> EmptyQueueTransport() })

        val request = NutritionSyncManager.beginSettingsUpdate(
            "https://first.example.com", null, true, 60)
        raceArmed = true  // arm the race before the IO coroutine runs

        val firstOk = manager.applySettings(request)

        // Assert race fired (thread completed inside commit()) before checking return value.
        assertNotNull("Race must have issued a newer request", capturedRaceRequest)
        assertFalse("Older ticket must be rejected when newer was issued during settings IO", firstOk)
        assertTrue("Pending must stay true", NutritionSyncManager.pendingUpdate.get())

        // Apply the captured latest ticket to the SAME manager/prefs/tokenStore — it must win
        val latestOk = manager.applySettings(capturedRaceRequest!!)
        assertTrue("Latest ticket must succeed", latestOk)
        assertFalse("Pending must be cleared after latest applies", NutritionSyncManager.pendingUpdate.get())
        assertEquals("https://newer.example.com", prefs.getBaseUrl())
        assertEquals("newer-tok", tokenStore.getToken())
        assertEquals(
            "Settings-token binding fingerprint must match computed value for newer URL+token",
            NutritionSyncManager.computePairingFingerprint("https://newer.example.com", "newer-tok"),
            prefs.getSettingsTokenFingerprint(),
        )
    }

    // -------------------------------------------------------------------------
    // Mutex serialises concurrent performSync and updateSettings
    // -------------------------------------------------------------------------

    @Test
    fun `concurrent performSync and updateSettings are serialised by mutex`() = runBlocking {
        var concurrent = 0
        var maxConcurrent = 0

        val j1 = launch(Dispatchers.IO) {
            NutritionSyncManager.syncMutex.withLock {
                concurrent++
                maxConcurrent = maxOf(maxConcurrent, concurrent)
                delay(80)
                concurrent--
            }
        }
        val j2 = launch(Dispatchers.IO) {
            delay(20)
            NutritionSyncManager.syncMutex.withLock {
                concurrent++
                maxConcurrent = maxOf(maxConcurrent, concurrent)
                concurrent--
            }
        }
        j1.join(); j2.join()
        assertEquals(1, maxConcurrent)
    }

    // -------------------------------------------------------------------------
    // HC unavailable / no permission / no token early exit
    // -------------------------------------------------------------------------

    @Test
    fun `performSync returns hc_not_available when writer is not available`() = runBlocking {
        val manager = seedManager(writer = UnavailableWriter())

        val result = manager.performSync()
        assertTrue(result is SyncResult.Error)
        assertEquals("hc_not_available", (result as SyncResult.Error).reason)
    }

    @Test
    fun `performSync returns no_token when token store is empty`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        // Seed with token, then clear it after seeding
        val tokenStore = FakeTokenStorePort(null)
        val manager = NutritionSyncManager(prefs, tokenStore, AlwaysOkWriter())
        manager.updateSettings("https://test.example.com", "tok", true, 60)

        // Clear token after settings were established
        tokenStore.clearToken()

        val result = manager.performSync()
        assertTrue(result is SyncResult.Error)
        assertEquals("no_token", (result as SyncResult.Error).reason)
    }

    @Test
    fun `performSync returns sync_disabled when prefs has sync disabled`() = runBlocking {
        val manager = seedManager(enabled = false)

        val result = manager.performSync()
        assertTrue(result is SyncResult.Error)
        assertEquals("sync_disabled", (result as SyncResult.Error).reason)
    }

    @Test
    fun `performSync returns no_base_url when url is not set`() = runBlocking {
        val fakePrefs = FakeSharedPreferences().also {
            it.data["sync_enabled"] = true
        }
        val prefs = NutritionPreferences(fakePrefs)
        // No updateSettings call — no fingerprint, no URL
        val manager = NutritionSyncManager(prefs, FakeTokenStorePort("tok"), AlwaysOkWriter())

        val result = manager.performSync()
        assertTrue(result is SyncResult.Error)
        // Will return no_settings_binding or no_base_url depending on check order;
        // both indicate sync is correctly blocked without a valid settings state.
        val reason = (result as SyncResult.Error).reason
        assertTrue("Must return a blocking error, got: $reason",
            reason == "no_base_url" || reason == "no_settings_binding" || reason == "sync_disabled")
    }

    // -------------------------------------------------------------------------
    // Failure retry/recovery after failed updateSettings
    // -------------------------------------------------------------------------

    @Test
    fun `recovery after failed settings commit — retry with same values succeeds`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val tokenStore = FakeTokenStorePort("tok")
        val manager = NutritionSyncManager(prefs, tokenStore, AlwaysOkWriter(),
            transportFactory = { _, _ -> EmptyQueueTransport() })

        // First attempt fails
        fakePrefs.commitResult = false
        val failOk = manager.updateSettings("https://test.example.com", null, true, 60)
        assertFalse(failOk)
        assertTrue("Pending must stay true after failed update", NutritionSyncManager.pendingUpdate.get())

        // Retry: commit succeeds
        fakePrefs.commitResult = true
        val retryOk = manager.updateSettings("https://test.example.com", null, true, 60)
        assertTrue("Retry must succeed", retryOk)
        assertFalse("Pending cleared after successful retry", NutritionSyncManager.pendingUpdate.get())

        val result = manager.performSync()
        assertTrue("Sync must succeed after recovery", result is SyncResult.Success)
    }

    // -------------------------------------------------------------------------
    // Pairing fingerprint
    // -------------------------------------------------------------------------

    @Test
    fun `pairing fingerprint changes when url changes`() {
        val fp1 = NutritionSyncManager.computePairingFingerprint("https://server-a.example.com", "tok")
        val fp2 = NutritionSyncManager.computePairingFingerprint("https://server-b.example.com", "tok")
        assertTrue(fp1 != fp2)
    }

    @Test
    fun `pairing fingerprint changes when token rotates`() {
        val fp1 = NutritionSyncManager.computePairingFingerprint("https://example.com", "token-A")
        val fp2 = NutritionSyncManager.computePairingFingerprint("https://example.com", "token-B")
        assertTrue(fp1 != fp2)
    }

    @Test
    fun `pairing fingerprint stable for same url and token`() {
        val url = "https://example.com"
        val tok = "same-token"
        assertEquals(
            NutritionSyncManager.computePairingFingerprint(url, tok),
            NutritionSyncManager.computePairingFingerprint(url, tok),
        )
    }

    // -------------------------------------------------------------------------
    // invalidateConsent regression: no-save invalidate blocks sync; re-save restores
    // -------------------------------------------------------------------------

    /**
     * Regression: after [NutritionSyncManager.invalidateConsent] without a subsequent
     * explicit [NutritionSyncManager.updateSettings], [performSync] must be blocked with
     * "settings_pending" and must not create any transport, trigger any HC availability,
     * permission, insert, or ACK call. A successful [updateSettings] re-enables sync.
     */
    // -------------------------------------------------------------------------
    // Disable + reenable at each suspension phase: regression tests
    // -------------------------------------------------------------------------

    /**
     * Disable fires while hasWritePermission() is suspended.
     * Old cycle detects consent_revoked at the post-permission check — no insert or ACK.
     * Re-enable via invalidateConsent + beginSettingsUpdate(enabled=true) + applySettings;
     * fresh cycle succeeds.
     */
    @Test
    fun `disable during hasWritePermission — no insert or ACK, reenable fresh cycle succeeds`() = runBlocking {
        val permissionGate = CompletableDeferred<Boolean>()
        val insertCalls = mutableListOf<String>()
        val ackCalls = mutableListOf<String>()

        val writer = object : HcWriterPort {
            override fun isAvailable() = true
            override suspend fun hasWritePermission(): Boolean = permissionGate.await()
            override suspend fun insertRecord(record: NutritionQueueRecord): Result<Unit> {
                insertCalls.add(record.clientRecordId); return Result.success(Unit)
            }
        }
        val transport = object : Transport {
            override suspend fun fetchQueue() =
                Result.success(listOf(queueRecord("a"), queueRecord("b")))
            override suspend fun ackRecord(id: String, ver: Int): Result<Unit> {
                ackCalls.add(id); return Result.success(Unit)
            }
        }

        val fakePrefs = FakeSharedPreferences()
        val tokenStore = FakeTokenStorePort(null)
        val prefs = NutritionPreferences(fakePrefs)
        val manager = NutritionSyncManager(prefs, tokenStore, writer,
            transportFactory = { _, _ -> transport })
        manager.updateSettings("https://test.example.com", "tok", true, 60)

        val syncJob = launch(Dispatchers.IO) { manager.performSync() }
        delay(50)

        NutritionSyncManager.invalidateConsent()
        val reEnableTicket = NutritionSyncManager.beginSettingsUpdate(
            "https://test.example.com", null, true, 60)

        permissionGate.complete(true)
        syncJob.join()

        assertTrue("No insert must happen after disable at permission phase", insertCalls.isEmpty())
        assertTrue("No ACK must happen after disable at permission phase", ackCalls.isEmpty())
        assertTrue("pendingUpdate must be true until ticket applied", NutritionSyncManager.pendingUpdate.get())

        val reEnableOk = manager.applySettings(reEnableTicket)
        assertTrue("Re-enable ticket must succeed", reEnableOk)
        assertFalse("Pending cleared after re-enable", NutritionSyncManager.pendingUpdate.get())

        val freshResult = manager.performSync()
        assertTrue("Fresh cycle must succeed after re-enable", freshResult is SyncResult.Success)
    }

    /**
     * Disable fires while fetchQueue() is suspended.
     * Old cycle fetches records but shouldContinue() is false at the first loop entry —
     * no insert or ACK performed. Re-enable; fresh cycle succeeds.
     */
    @Test
    fun `disable during fetchQueue — no insert or ACK, reenable fresh cycle succeeds`() = runBlocking {
        val fetchGate = CompletableDeferred<List<com.ravil.healthconnect.nutrition.data.NutritionQueueRecord>>()
        val insertCalls = mutableListOf<String>()
        val ackCalls = mutableListOf<String>()

        val transport = object : Transport {
            override suspend fun fetchQueue() =
                Result.success(fetchGate.await())
            override suspend fun ackRecord(id: String, ver: Int): Result<Unit> {
                ackCalls.add(id); return Result.success(Unit)
            }
        }
        val writer = object : HcWriterPort {
            override fun isAvailable() = true
            override suspend fun hasWritePermission() = true
            override suspend fun insertRecord(record: NutritionQueueRecord): Result<Unit> {
                insertCalls.add(record.clientRecordId); return Result.success(Unit)
            }
        }

        val fakePrefs = FakeSharedPreferences()
        val tokenStore = FakeTokenStorePort(null)
        val prefs = NutritionPreferences(fakePrefs)
        val manager = NutritionSyncManager(prefs, tokenStore, writer,
            transportFactory = { _, _ -> transport })
        manager.updateSettings("https://test.example.com", "tok", true, 60)

        val syncJob = launch(Dispatchers.IO) { manager.performSync() }
        delay(50)

        NutritionSyncManager.invalidateConsent()
        val reEnableTicket = NutritionSyncManager.beginSettingsUpdate(
            "https://test.example.com", null, true, 60)

        fetchGate.complete(listOf(queueRecord("a"), queueRecord("b")))
        syncJob.join()

        assertTrue("No insert after disable during fetch phase", insertCalls.isEmpty())
        assertTrue("No ACK after disable during fetch phase", ackCalls.isEmpty())
        assertTrue("pendingUpdate must be true until ticket applied", NutritionSyncManager.pendingUpdate.get())

        val reEnableOk = manager.applySettings(reEnableTicket)
        assertTrue("Re-enable ticket must succeed", reEnableOk)
        assertFalse("Pending cleared after re-enable", NutritionSyncManager.pendingUpdate.get())

        val freshResult = manager.performSync()
        assertTrue("Fresh cycle must succeed after re-enable", freshResult is SyncResult.Success)
    }

    /**
     * Disable fires while the first insertRecord() is suspended.
     * Insert for "a" completes; shouldContinue() is false at the post-persist pre-ACK check —
     * ACK for "a" is withheld. Records "b" and "c" are never started.
     * Re-enable; fresh cycle succeeds.
     */
    @Test
    fun `disable during first insert — first insert completes, zero ACK, remaining records skipped, reenable succeeds`() = runBlocking {
        val insertGate = CompletableDeferred<Unit>()
        val insertCalls = mutableListOf<String>()
        val ackCalls = mutableListOf<String>()

        val writer = object : HcWriterPort {
            var count = 0
            override fun isAvailable() = true
            override suspend fun hasWritePermission() = true
            override suspend fun insertRecord(record: NutritionQueueRecord): Result<Unit> {
                insertCalls.add(record.clientRecordId)
                if (count++ == 0) insertGate.await()
                return Result.success(Unit)
            }
        }
        val transport = object : Transport {
            override suspend fun fetchQueue() =
                Result.success(listOf(queueRecord("a"), queueRecord("b"), queueRecord("c")))
            override suspend fun ackRecord(id: String, ver: Int): Result<Unit> {
                ackCalls.add(id); return Result.success(Unit)
            }
        }

        val fakePrefs = FakeSharedPreferences()
        val tokenStore = FakeTokenStorePort(null)
        val prefs = NutritionPreferences(fakePrefs)
        val manager = NutritionSyncManager(prefs, tokenStore, writer,
            transportFactory = { _, _ -> transport })
        manager.updateSettings("https://test.example.com", "tok", true, 60)

        val syncJob = launch(Dispatchers.IO) { manager.performSync() }
        delay(50)

        NutritionSyncManager.invalidateConsent()
        val reEnableTicket = NutritionSyncManager.beginSettingsUpdate(
            "https://test.example.com", null, true, 60)

        insertGate.complete(Unit)
        syncJob.join()

        assertEquals("Only 'a' must be inserted", listOf("a"), insertCalls)
        assertTrue("ACK for 'a' must be withheld (shouldContinue false post-persist)", ackCalls.isEmpty())
        assertTrue("pendingUpdate must be true until ticket applied", NutritionSyncManager.pendingUpdate.get())

        val reEnableOk = manager.applySettings(reEnableTicket)
        assertTrue("Re-enable ticket must succeed", reEnableOk)
        assertFalse("Pending cleared after re-enable", NutritionSyncManager.pendingUpdate.get())

        val freshResult = manager.performSync()
        assertTrue("Fresh cycle must succeed after re-enable", freshResult is SyncResult.Success)
    }

    /**
     * Disable fires while the first ackRecord() network call is suspended.
     * ACK for "a" was already in-flight and completes; shouldContinue() is false at the
     * top of the next loop iteration — record "b" is never inserted or ACKed.
     * Re-enable; fresh cycle succeeds.
     */
    @Test
    fun `disable during first ACK — first ACK completes, remaining record not inserted or ACKed, reenable succeeds`() = runBlocking {
        val ackGate = CompletableDeferred<Unit>()
        val insertCalls = mutableListOf<String>()
        val ackCalls = mutableListOf<String>()

        val writer = object : HcWriterPort {
            override fun isAvailable() = true
            override suspend fun hasWritePermission() = true
            override suspend fun insertRecord(record: NutritionQueueRecord): Result<Unit> {
                insertCalls.add(record.clientRecordId); return Result.success(Unit)
            }
        }
        val transport = object : Transport {
            var count = 0
            override suspend fun fetchQueue() =
                Result.success(listOf(queueRecord("a"), queueRecord("b")))
            override suspend fun ackRecord(id: String, ver: Int): Result<Unit> {
                ackCalls.add(id)
                if (count++ == 0) ackGate.await()
                return Result.success(Unit)
            }
        }

        val fakePrefs = FakeSharedPreferences()
        val tokenStore = FakeTokenStorePort(null)
        val prefs = NutritionPreferences(fakePrefs)
        val manager = NutritionSyncManager(prefs, tokenStore, writer,
            transportFactory = { _, _ -> transport })
        manager.updateSettings("https://test.example.com", "tok", true, 60)

        val syncJob = launch(Dispatchers.IO) { manager.performSync() }
        delay(50)

        NutritionSyncManager.invalidateConsent()
        val reEnableTicket = NutritionSyncManager.beginSettingsUpdate(
            "https://test.example.com", null, true, 60)

        ackGate.complete(Unit)
        syncJob.join()

        assertEquals("Only 'a' must be inserted", listOf("a"), insertCalls)
        assertEquals("ACK for 'a' must complete (already in-flight)", listOf("a"), ackCalls)
        assertTrue("pendingUpdate must be true until ticket applied", NutritionSyncManager.pendingUpdate.get())

        val reEnableOk = manager.applySettings(reEnableTicket)
        assertTrue("Re-enable ticket must succeed", reEnableOk)
        assertFalse("Pending cleared after re-enable", NutritionSyncManager.pendingUpdate.get())

        val freshResult = manager.performSync()
        assertTrue("Fresh cycle must succeed after re-enable", freshResult is SyncResult.Success)
    }

    @Test
    fun `invalidateConsent without re-save blocks performSync — explicit save re-enables sync`() = runBlocking {
        var transportCreated = 0
        var fetchCalls = 0
        var hcAvailabilityCalls = 0
        var hcPermissionCalls = 0
        var hcInsertCalls = 0
        var ackCalls = 0

        val trackingWriter = object : HcWriterPort {
            override fun isAvailable(): Boolean { hcAvailabilityCalls++; return true }
            override suspend fun hasWritePermission(): Boolean { hcPermissionCalls++; return true }
            override suspend fun insertRecord(record: NutritionQueueRecord): Result<Unit> {
                hcInsertCalls++; return Result.success(Unit)
            }
        }

        val fakePrefs = FakeSharedPreferences()
        val prefs = NutritionPreferences(fakePrefs)
        val tokenStore = FakeTokenStorePort(null)
        val manager = NutritionSyncManager(prefs, tokenStore, trackingWriter,
            transportFactory = { _, _ ->
                transportCreated++
                object : Transport {
                    override suspend fun fetchQueue(): Result<List<NutritionQueueRecord>> {
                        fetchCalls++; return Result.success(emptyList())
                    }
                    override suspend fun ackRecord(id: String, ver: Int): Result<Unit> {
                        ackCalls++; return Result.success(Unit)
                    }
                }
            })

        // Establish valid enabled settings
        val initialOk = manager.updateSettings("https://test.example.com", "tok", true, 60)
        assertTrue("Initial settings must succeed", initialOk)
        assertFalse("Pending cleared after initial save", NutritionSyncManager.pendingUpdate.get())

        // Invalidate consent without saving new settings
        NutritionSyncManager.invalidateConsent()
        assertTrue("Pending must be true after invalidateConsent", NutritionSyncManager.pendingUpdate.get())

        // performSync must be blocked — zero transport/HC/fetch/insert/ACK
        val blockedResult = manager.performSync()
        assertTrue(blockedResult is SyncResult.Error)
        assertEquals("settings_pending", (blockedResult as SyncResult.Error).reason)
        assertEquals("No transport created when blocked", 0, transportCreated)
        assertEquals("No fetch when blocked", 0, fetchCalls)
        assertEquals("No HC availability check when blocked", 0, hcAvailabilityCalls)
        assertEquals("No HC permission check when blocked", 0, hcPermissionCalls)
        assertEquals("No HC insert when blocked", 0, hcInsertCalls)
        assertEquals("No ACK when blocked", 0, ackCalls)

        // Explicit re-save with valid settings re-enables sync
        val reEnableOk = manager.updateSettings("https://test.example.com", null, true, 60)
        assertTrue("Re-enable via explicit save must succeed", reEnableOk)
        assertFalse("Pending cleared after re-save", NutritionSyncManager.pendingUpdate.get())

        val syncResult = manager.performSync()
        assertTrue("Sync must succeed after re-save", syncResult is SyncResult.Success)
    }
}
