package com.ravil.healthconnect.nutrition

import com.ravil.healthconnect.nutrition.bookmarks.InsertBookmarks
import com.ravil.healthconnect.nutrition.data.NutritionQueueRecord
import com.ravil.healthconnect.nutrition.health.HcWriterPort
import com.ravil.healthconnect.nutrition.prefs.NutritionPreferences
import com.ravil.healthconnect.nutrition.sync.NutritionSyncManager
import com.ravil.healthconnect.nutrition.sync.RecordAction
import com.ravil.healthconnect.nutrition.sync.Transport
import com.ravil.healthconnect.nutrition.sync.executeSyncCycle
import com.ravil.healthconnect.nutrition.sync.planSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Injectable fake for HTTP transport — records ack calls and controls return values. */
class FakeTransport(
    var queueResult: Result<List<NutritionQueueRecord>> = Result.success(emptyList()),
    var ackResult: Result<Unit> = Result.success(Unit),
) : Transport {
    val ackCalls = mutableListOf<Pair<String, Int>>()

    override suspend fun fetchQueue(): Result<List<NutritionQueueRecord>> = queueResult
    override suspend fun ackRecord(clientRecordId: String, clientRecordVersion: Int): Result<Unit> {
        ackCalls.add(clientRecordId to clientRecordVersion)
        return ackResult
    }
}

/** Injectable fake for HC writer — records insert calls and controls return values. */
class FakeHcWriter(
    var insertResult: Result<Unit> = Result.success(Unit),
) : HcWriterPort {
    val insertCalls = mutableListOf<NutritionQueueRecord>()

    override fun isAvailable() = true
    override suspend fun hasWritePermission() = true
    override suspend fun insertRecord(record: NutritionQueueRecord): Result<Unit> {
        insertCalls.add(record)
        return insertResult
    }
}

private fun rec(id: String, ver: Int = 1) = NutritionQueueRecord(
    clientRecordId = id,
    clientRecordVersion = ver,
    name = "Meal",
    startTime = "2024-06-01T10:00:00Z",
    endTime = "2024-06-01T10:30:00Z",
    energyKcal = 400.0,
    proteinG = 25.0,
    carbohydrateG = 45.0,
    fatG = 15.0,
)

class SyncOrchestratorTest {

    @Before
    fun setUp() {
        NutritionSyncManager.resetForTesting()
        NutritionPreferences.bookmarksDurable = true
        NutritionPreferences.fingerprintDurable = true
        NutritionPreferences.settingsDurable = true
        NutritionPreferences.stableSettings = null
    }

    @After
    fun tearDown() {
        NutritionSyncManager.resetForTesting()
        NutritionPreferences.bookmarksDurable = true
        NutritionPreferences.fingerprintDurable = true
        NutritionPreferences.settingsDurable = true
        NutritionPreferences.stableSettings = null
    }

    // -------------------------------------------------------------------------
    // Ack ordering: write → bookmark-persist → ack
    // -------------------------------------------------------------------------

    @Test
    fun `ack is called after successful write and bookmark persist — exact order`() = runBlocking {
        val callOrder = mutableListOf<String>()
        val record = rec("r1")

        val transport = object : Transport {
            override suspend fun fetchQueue() = Result.success(listOf(record))
            override suspend fun ackRecord(clientRecordId: String, clientRecordVersion: Int): Result<Unit> {
                callOrder.add("ack:$clientRecordId")
                return Result.success(Unit)
            }
        }
        val writer = object : HcWriterPort {
            override fun isAvailable() = true
            override suspend fun hasWritePermission() = true
            override suspend fun insertRecord(record: NutritionQueueRecord): Result<Unit> {
                callOrder.add("write:${record.clientRecordId}")
                return Result.success(Unit)
            }
        }

        executeSyncCycle(
            records = listOf(record),
            bookmarks = InsertBookmarks.EMPTY,
            transport = transport,
            hcWriter = writer,
            onBookmarkPersist = { callOrder.add("bookmark:${record.clientRecordId}"); true },
        )

        assertEquals(listOf("write:r1", "bookmark:r1", "ack:r1"), callOrder)
    }

    @Test
    fun `ack is NOT called when insert fails`() = runBlocking {
        val transport = FakeTransport()
        val writer = FakeHcWriter(insertResult = Result.failure(IOException("hc_write_failed")))

        val result = executeSyncCycle(
            records = listOf(rec("r1")),
            bookmarks = InsertBookmarks.EMPTY,
            transport = transport,
            hcWriter = writer,
            onBookmarkPersist = { true },
        )

        assertEquals(0, transport.ackCalls.size)
        assertEquals(1, result.errors)
        assertEquals(0, result.applied)
    }

    // -------------------------------------------------------------------------
    // Persistence failure: failed commit prevents ACK and bookmark not advanced
    // -------------------------------------------------------------------------

    @Test
    fun `ack NOT sent when bookmark persist fails`() = runBlocking {
        val transport = FakeTransport()

        val result = executeSyncCycle(
            records = listOf(rec("r1")),
            bookmarks = InsertBookmarks.EMPTY,
            transport = transport,
            hcWriter = FakeHcWriter(),
            onBookmarkPersist = { false },
        )

        assertEquals("No ACK when bookmark persist fails", 0, transport.ackCalls.size)
        assertEquals("Counted as error for retry", 1, result.errors)
        assertEquals(0, result.applied)
        assertEquals(InsertBookmarks.EMPTY, result.updatedBookmarks)
    }

    @Test
    fun `in-memory bookmark NOT updated when persist fails — safe WriteAndAck retry`() = runBlocking {
        val record = rec("r1")

        val result = executeSyncCycle(
            records = listOf(record),
            bookmarks = InsertBookmarks.EMPTY,
            transport = FakeTransport(),
            hcWriter = FakeHcWriter(),
            onBookmarkPersist = { false },
        )

        // Bookmark must stay EMPTY so next planSync treats it as WriteAndAck, not AckOnly
        assertEquals(InsertBookmarks.EMPTY, result.updatedBookmarks)

        val nextPlan = planSync(listOf(record), result.updatedBookmarks)
        assertTrue("Next sync must retry as WriteAndAck", nextPlan[0] is RecordAction.WriteAndAck)
    }

    @Test
    fun `first record persists second fails — only first applied and acked`() = runBlocking {
        val transport = FakeTransport()
        var persistCount = 0

        val result = executeSyncCycle(
            records = listOf(rec("a"), rec("b")),
            bookmarks = InsertBookmarks.EMPTY,
            transport = transport,
            hcWriter = FakeHcWriter(),
            onBookmarkPersist = { _ ->
                persistCount++
                persistCount == 1  // first succeeds, second fails
            },
        )

        assertEquals(1, result.applied)
        assertEquals(1, result.errors)
        assertEquals(1, transport.ackCalls.size)
        assertEquals("a", transport.ackCalls[0].first)
        assertEquals(1, result.updatedBookmarks.appliedVersion("a"))
    }

    // -------------------------------------------------------------------------
    // Crash recovery: bookmark durable before ack, retried as AckOnly
    // -------------------------------------------------------------------------

    @Test
    fun `bookmark persisted before ack — crash recovery retried as AckOnly`() = runBlocking {
        val persistedBookmarks = mutableListOf<InsertBookmarks>()
        val record = rec("r1")

        val transport = object : Transport {
            override suspend fun fetchQueue() = Result.success(listOf(record))
            override suspend fun ackRecord(clientRecordId: String, clientRecordVersion: Int): Result<Unit> =
                Result.failure(IOException("simulated_crash"))
        }

        val result = executeSyncCycle(
            records = listOf(record),
            bookmarks = InsertBookmarks.EMPTY,
            transport = transport,
            hcWriter = FakeHcWriter(),
            onBookmarkPersist = { bm -> persistedBookmarks.add(bm); true },
        )

        assertEquals(1, persistedBookmarks.size)
        assertEquals(1, persistedBookmarks[0].appliedVersion("r1"))
        assertEquals(1, result.updatedBookmarks.appliedVersion("r1"))
        assertEquals(1, result.ackFailed)

        // Next sync: bookmark exists → AckOnly, no re-write
        val retryWriter = FakeHcWriter()
        val retryTransport = FakeTransport()
        executeSyncCycle(
            records = listOf(record),
            bookmarks = result.updatedBookmarks,
            transport = retryTransport,
            hcWriter = retryWriter,
            onBookmarkPersist = { true },
        )

        assertEquals(0, retryWriter.insertCalls.size)
        assertEquals(1, retryTransport.ackCalls.size)
        assertEquals("r1", retryTransport.ackCalls[0].first)
    }

    // -------------------------------------------------------------------------
    // Mid-cycle opt-out: shouldContinue checked at loop start AND post-persist pre-ACK
    // -------------------------------------------------------------------------

    /**
     * When opt-out fires between bookmark persist and ACK (post-persist pre-ACK), the ACK
     * MUST be withheld. The record is correctly counted as applied (HC write + durable
     * bookmark succeeded), and the next sync cycle retries only the ACK via AckOnly.
     *
     * This replaces the previous incorrect expectation that ACK=1 after first persist.
     */
    @Test
    fun `opt-out post-persist pre-ACK — ACK withheld, bookmark durable, next sync retries AckOnly`() = runBlocking {
        val transport = FakeTransport()
        var persistCount = 0

        val result = executeSyncCycle(
            records = listOf(rec("a"), rec("b"), rec("c")),
            bookmarks = InsertBookmarks.EMPTY,
            transport = transport,
            hcWriter = FakeHcWriter(),
            onBookmarkPersist = { ++persistCount; true },
            // shouldContinue: true before first persist, false after (simulates opt-out
            // arriving between persist and ACK of "a")
            shouldContinue = { persistCount < 1 },
        )

        // "a" was written and its bookmark was durably persisted
        assertEquals(1, result.applied)
        assertEquals(1, result.updatedBookmarks.appliedVersion("a"))

        // ACK was withheld — opt-out fired before it could be sent
        assertEquals(0, transport.ackCalls.size)

        // "b" and "c" were never started
        assertEquals(0, result.errors)

        // Prove retry safety: next sync with the saved bookmark → AckOnly → ACK sent
        val retryTransport = FakeTransport()
        val retryWriter = FakeHcWriter()
        val retryResult = executeSyncCycle(
            records = listOf(rec("a")),
            bookmarks = result.updatedBookmarks,
            transport = retryTransport,
            hcWriter = retryWriter,
            onBookmarkPersist = { true },
        )

        assertEquals(0, retryWriter.insertCalls.size)   // no re-write
        assertEquals(1, retryTransport.ackCalls.size)   // ACK retried
        assertEquals("a", retryTransport.ackCalls[0].first)
        assertEquals(0, retryResult.errors)
    }

    @Test
    fun `shouldContinue always false processes zero records`() = runBlocking {
        val result = executeSyncCycle(
            records = listOf(rec("a"), rec("b")),
            bookmarks = InsertBookmarks.EMPTY,
            transport = FakeTransport(),
            hcWriter = FakeHcWriter(),
            onBookmarkPersist = { true },
            shouldContinue = { false },
        )

        assertEquals(0, result.applied)
        assertEquals(0, result.errors)
        assertEquals(InsertBookmarks.EMPTY, result.updatedBookmarks)
    }

    // -------------------------------------------------------------------------
    // Consent generation: immediate cancellation before mutex
    // -------------------------------------------------------------------------

    @Test
    fun `consent generation change immediately invalidates in-flight shouldContinue`() {
        val snapshot = NutritionSyncManager.consentGeneration.get()
        val shouldContinue = { NutritionSyncManager.consentGeneration.get() == snapshot }

        assertTrue("Same generation = continue", shouldContinue())

        NutritionSyncManager.consentGeneration.incrementAndGet()  // simulates updateSettings
        assertFalse("Changed generation = stop", shouldContinue())
    }

    @Test
    fun `re-enable after opt-out does not restore consent for in-flight cycle`() {
        val snapshot = NutritionSyncManager.consentGeneration.get()
        val shouldContinue = { NutritionSyncManager.consentGeneration.get() == snapshot }

        // User disables
        NutritionSyncManager.consentGeneration.incrementAndGet()
        assertFalse(shouldContinue())

        // User immediately re-enables — generation advances again, snapshot still stale
        NutritionSyncManager.consentGeneration.incrementAndGet()
        assertFalse("Re-enable does not restore in-flight consent", shouldContinue())
    }

    @Test
    fun `generation change mid-cycle stops processing after first record`() = runBlocking {
        val snapshot = NutritionSyncManager.consentGeneration.get()
        val transport = FakeTransport()
        val writer = FakeHcWriter()
        var persistCount = 0

        val result = executeSyncCycle(
            records = listOf(rec("a"), rec("b"), rec("c")),
            bookmarks = InsertBookmarks.EMPTY,
            transport = transport,
            hcWriter = writer,
            onBookmarkPersist = { _ ->
                persistCount++
                // Simulate updateSettings firing after first persist
                if (persistCount == 1) NutritionSyncManager.consentGeneration.incrementAndGet()
                true
            },
            shouldContinue = { NutritionSyncManager.consentGeneration.get() == snapshot },
        )

        // "a" was applied (write + persist succeeded before consent check)
        assertEquals(1, result.applied)
        // ACK for "a" was withheld — generation changed between persist and ACK
        assertEquals(0, transport.ackCalls.size)
        // "b" and "c" never started
        assertEquals(0, result.errors)
        assertEquals(1, result.updatedBookmarks.appliedVersion("a"))
    }

    // -------------------------------------------------------------------------
    // Quarantine: phantom bookmark state forces safe WriteAndAck retry
    // -------------------------------------------------------------------------

    /**
     * When bookmarksDurable=false (prior commit failed), NutritionSyncManager passes
     * InsertBookmarks.EMPTY to executeSyncCycle. This test proves that EMPTY bookmarks
     * cause a WriteAndAck (safe retry) rather than a phantom AckOnly.
     */
    @Test
    fun `quarantined bookmarks empty forces WriteAndAck instead of phantom AckOnly`() = runBlocking {
        val record = rec("r1", ver = 3)
        // This is what would happen with a non-quarantined stale bookmark:
        val staleBookmark = InsertBookmarks.EMPTY.withApplied("r1", 3)
        val writerWithStale = FakeHcWriter()
        val transportWithStale = FakeTransport()
        executeSyncCycle(
            records = listOf(record), bookmarks = staleBookmark,
            transport = transportWithStale, hcWriter = writerWithStale,
            onBookmarkPersist = { true },
        )
        assertEquals("Without quarantine: skips write (AckOnly)", 0, writerWithStale.insertCalls.size)

        // With quarantine (NutritionSyncManager passes EMPTY because bookmarksDurable=false):
        val writerWithEmpty = FakeHcWriter()
        val transportWithEmpty = FakeTransport()
        executeSyncCycle(
            records = listOf(record), bookmarks = InsertBookmarks.EMPTY,
            transport = transportWithEmpty, hcWriter = writerWithEmpty,
            onBookmarkPersist = { true },
        )
        assertEquals("With quarantine: performs write (WriteAndAck)", 1, writerWithEmpty.insertCalls.size)
    }

    @Test
    fun `quarantined bookmark same-process recovery succeeds when persistence recovers`() = runBlocking {
        val record = rec("r1")
        var persistShouldSucceed = false  // first attempt fails, second succeeds
        val transport = FakeTransport()

        // First attempt: persist fails → no ACK, bookmark not in result
        val firstResult = executeSyncCycle(
            records = listOf(record),
            bookmarks = InsertBookmarks.EMPTY,
            transport = transport,
            hcWriter = FakeHcWriter(),
            onBookmarkPersist = { persistShouldSucceed },
        )

        assertEquals(0, firstResult.applied)
        assertEquals(InsertBookmarks.EMPTY, firstResult.updatedBookmarks)
        assertEquals(0, transport.ackCalls.size)

        // Second attempt (same-process recovery): persist succeeds → ACK sent
        persistShouldSucceed = true
        val secondTransport = FakeTransport()
        val secondResult = executeSyncCycle(
            records = listOf(record),
            bookmarks = InsertBookmarks.EMPTY,  // quarantine returned EMPTY
            transport = secondTransport,
            hcWriter = FakeHcWriter(),
            onBookmarkPersist = { persistShouldSucceed },
        )

        assertEquals(1, secondResult.applied)
        assertEquals(1, secondTransport.ackCalls.size)
        assertEquals("r1", secondTransport.ackCalls[0].first)
    }

    @Test
    fun `NutritionPreferences durability flags are process-scoped and writable`() {
        val origBm = NutritionPreferences.bookmarksDurable
        val origFp = NutritionPreferences.fingerprintDurable
        try {
            NutritionPreferences.bookmarksDurable = false
            NutritionPreferences.fingerprintDurable = false
            assertFalse(NutritionPreferences.bookmarksDurable)
            assertFalse(NutritionPreferences.fingerprintDurable)
            NutritionPreferences.bookmarksDurable = true
            NutritionPreferences.fingerprintDurable = true
            assertTrue(NutritionPreferences.bookmarksDurable)
            assertTrue(NutritionPreferences.fingerprintDurable)
        } finally {
            NutritionPreferences.bookmarksDurable = origBm
            NutritionPreferences.fingerprintDurable = origFp
        }
    }

    // -------------------------------------------------------------------------
    // Other correctness
    // -------------------------------------------------------------------------

    @Test
    fun `insert failure does not call persist or increment applied`() = runBlocking {
        val writer = FakeHcWriter(insertResult = Result.failure(Exception("rejected")))
        val persistCalls = mutableListOf<InsertBookmarks>()

        val result = executeSyncCycle(
            records = listOf(rec("r1")),
            bookmarks = InsertBookmarks.EMPTY,
            transport = FakeTransport(),
            hcWriter = writer,
            onBookmarkPersist = { bm -> persistCalls.add(bm); true },
        )

        assertEquals(0, result.applied)
        assertEquals(1, result.errors)
        assertTrue(persistCalls.isEmpty())
        assertEquals(InsertBookmarks.EMPTY, result.updatedBookmarks)
    }

    @Test
    fun `higher version record skipped safely via bookmark`() = runBlocking {
        val writer = FakeHcWriter()
        val transport = FakeTransport()

        executeSyncCycle(
            records = listOf(rec("r1", ver = 3)),
            bookmarks = InsertBookmarks.EMPTY.withApplied("r1", 5),
            transport = transport,
            hcWriter = writer,
            onBookmarkPersist = { true },
        )

        assertEquals(0, writer.insertCalls.size)
        assertEquals(1, transport.ackCalls.size)
    }

    @Test
    fun `multiple records each processed independently`() = runBlocking {
        val writer = FakeHcWriter()
        val transport = FakeTransport()

        val result = executeSyncCycle(
            records = listOf(rec("a"), rec("b"), rec("c")),
            bookmarks = InsertBookmarks.EMPTY,
            transport = transport,
            hcWriter = writer,
            onBookmarkPersist = { true },
        )

        assertEquals(3, writer.insertCalls.size)
        assertEquals(3, transport.ackCalls.size)
        assertEquals(3, result.applied)
        assertEquals(0, result.errors)
    }

    @Test
    fun `ack failure for one record does not block subsequent records`() = runBlocking {
        var ackCount = 0
        val transport = object : Transport {
            override suspend fun fetchQueue() = Result.success(emptyList<NutritionQueueRecord>())
            override suspend fun ackRecord(clientRecordId: String, clientRecordVersion: Int): Result<Unit> {
                ackCount++
                return if (clientRecordId == "a") Result.failure(IOException("ack_fail_a"))
                else Result.success(Unit)
            }
        }

        val result = executeSyncCycle(
            records = listOf(rec("a"), rec("b")),
            bookmarks = InsertBookmarks.EMPTY,
            transport = transport,
            hcWriter = FakeHcWriter(),
            onBookmarkPersist = { true },
        )

        assertEquals(2, ackCount)
        assertEquals(2, result.applied)
        assertEquals(1, result.ackFailed)
    }

    @Test
    fun `empty queue produces zero result`() = runBlocking {
        val result = executeSyncCycle(
            records = emptyList(),
            bookmarks = InsertBookmarks.EMPTY,
            transport = FakeTransport(),
            hcWriter = FakeHcWriter(),
            onBookmarkPersist = { true },
        )

        assertEquals(0, result.applied)
        assertEquals(0, result.errors)
        assertEquals(0, result.ackFailed)
    }

    // -------------------------------------------------------------------------
    // Concurrency: mutex serializes worker + manual syncs
    // -------------------------------------------------------------------------

    @Test
    fun `sync mutex prevents concurrent execution`() = runBlocking {
        var concurrent = 0
        var maxConcurrent = 0

        val j1 = launch(Dispatchers.IO) {
            NutritionSyncManager.syncMutex.withLock {
                concurrent++
                maxConcurrent = maxOf(maxConcurrent, concurrent)
                Thread.sleep(80)
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
        joinAll(j1, j2)

        assertEquals(1, maxConcurrent)
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

    @Test
    fun `null-byte separator prevents url-token prefix collision`() {
        val fp1 = NutritionSyncManager.computePairingFingerprint("https://ab", "c")
        val fp2 = NutritionSyncManager.computePairingFingerprint("https://a", "bc")
        assertTrue(fp1 != fp2)
    }

    // -------------------------------------------------------------------------
    // Description field preserved through executor
    // -------------------------------------------------------------------------

    @Test
    fun `record with description is passed to writer with description intact`() = runBlocking {
        val writer = FakeHcWriter()

        executeSyncCycle(
            records = listOf(rec("r1").copy(description = "AI estimate")),
            bookmarks = InsertBookmarks.EMPTY,
            transport = FakeTransport(),
            hcWriter = writer,
            onBookmarkPersist = { true },
        )

        assertEquals(1, writer.insertCalls.size)
        assertEquals("AI estimate", writer.insertCalls[0].description)
    }
}
