package com.ravil.healthconnect.nutrition

import com.ravil.healthconnect.nutrition.bookmarks.InsertBookmarks
import com.ravil.healthconnect.nutrition.data.NutritionQueueRecord
import com.ravil.healthconnect.nutrition.health.HcWriterPort
import com.ravil.healthconnect.nutrition.sync.NutritionSyncManager
import com.ravil.healthconnect.nutrition.sync.Transport
import com.ravil.healthconnect.nutrition.sync.executeSyncCycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // --- Ack ordering: bookmark → ack, never ack before write ---

    @Test
    fun `ack is called after successful write — not before`() = runBlocking {
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
            onBookmarkPersist = { callOrder.add("bookmark:${record.clientRecordId}") },
        )

        assertEquals(listOf("write:r1", "bookmark:r1", "ack:r1"), callOrder)
    }

    @Test
    fun `ack is NOT called when insert fails`() = runBlocking {
        val transport = FakeTransport()
        val writer = FakeHcWriter(insertResult = Result.failure(IOException("hc_write_failed")))
        val record = rec("r1")

        val result = executeSyncCycle(
            records = listOf(record),
            bookmarks = InsertBookmarks.EMPTY,
            transport = transport,
            hcWriter = writer,
            onBookmarkPersist = {},
        )

        assertEquals(0, transport.ackCalls.size)
        assertEquals(1, result.errors)
        assertEquals(0, result.applied)
    }

    @Test
    fun `bookmark persisted before ack — crash recovery scenario`() = runBlocking {
        val persistedBookmarks = mutableListOf<InsertBookmarks>()
        val ackCallOrder = mutableListOf<String>()
        val record = rec("r1")

        val transport = object : Transport {
            override suspend fun fetchQueue() = Result.success(listOf(record))
            override suspend fun ackRecord(clientRecordId: String, clientRecordVersion: Int): Result<Unit> {
                ackCallOrder.add(clientRecordId)
                return Result.failure(IOException("simulated_crash"))
            }
        }

        val result = executeSyncCycle(
            records = listOf(record),
            bookmarks = InsertBookmarks.EMPTY,
            transport = transport,
            hcWriter = FakeHcWriter(),
            onBookmarkPersist = { bm -> persistedBookmarks.add(bm) },
        )

        // Bookmark was persisted despite ack failure
        assertEquals(1, persistedBookmarks.size)
        assertEquals(1, persistedBookmarks[0].appliedVersion("r1"))
        assertEquals(1, result.updatedBookmarks.appliedVersion("r1"))
        assertEquals(1, result.ackFailed)

        // Simulate next sync with saved bookmark: should produce AckOnly, not WriteAndAck
        val retryWriter = FakeHcWriter()
        val retryTransport = FakeTransport(ackResult = Result.success(Unit))
        val retryResult = executeSyncCycle(
            records = listOf(record),
            bookmarks = result.updatedBookmarks,
            transport = retryTransport,
            hcWriter = retryWriter,
            onBookmarkPersist = {},
        )

        assertEquals(0, retryWriter.insertCalls.size)
        assertEquals(1, retryTransport.ackCalls.size)
        assertEquals("r1", retryTransport.ackCalls[0].first)
        assertEquals(0, retryResult.errors)
        assertEquals(0, retryResult.ackFailed)
    }

    @Test
    fun `insert failure does not increment applied or update bookmark`() = runBlocking {
        val writer = FakeHcWriter(insertResult = Result.failure(Exception("rejected")))
        val persistedBms = mutableListOf<InsertBookmarks>()

        val result = executeSyncCycle(
            records = listOf(rec("r1")),
            bookmarks = InsertBookmarks.EMPTY,
            transport = FakeTransport(),
            hcWriter = writer,
            onBookmarkPersist = { persistedBms.add(it) },
        )

        assertEquals(0, result.applied)
        assertEquals(1, result.errors)
        assertTrue(persistedBms.isEmpty())
        assertEquals(InsertBookmarks.EMPTY, result.updatedBookmarks)
    }

    @Test
    fun `higher version record skipped safely via bookmark`() = runBlocking {
        val bm = InsertBookmarks.EMPTY.withApplied("r1", 5)
        val staleRecord = rec("r1", ver = 3)
        val writer = FakeHcWriter()
        val transport = FakeTransport()

        val result = executeSyncCycle(
            records = listOf(staleRecord),
            bookmarks = bm,
            transport = transport,
            hcWriter = writer,
            onBookmarkPersist = {},
        )

        assertEquals(0, writer.insertCalls.size)
        assertEquals(1, transport.ackCalls.size)
        assertEquals(0, result.errors)
    }

    @Test
    fun `multiple records — each processed independently`() = runBlocking {
        val records = listOf(rec("a"), rec("b"), rec("c"))
        val writer = FakeHcWriter()
        val transport = FakeTransport()

        val result = executeSyncCycle(
            records = records,
            bookmarks = InsertBookmarks.EMPTY,
            transport = transport,
            hcWriter = writer,
            onBookmarkPersist = {},
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
            override suspend fun fetchQueue() = Result.success(listOf(rec("a"), rec("b")))
            override suspend fun ackRecord(clientRecordId: String, clientRecordVersion: Int): Result<Unit> {
                ackCount++
                return if (clientRecordId == "a") Result.failure(IOException("ack_fail_a"))
                else Result.success(Unit)
            }
        }
        val writer = FakeHcWriter()

        val result = executeSyncCycle(
            records = listOf(rec("a"), rec("b")),
            bookmarks = InsertBookmarks.EMPTY,
            transport = transport,
            hcWriter = writer,
            onBookmarkPersist = {},
        )

        assertEquals(2, writer.insertCalls.size)
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
            onBookmarkPersist = {},
        )

        assertEquals(0, result.applied)
        assertEquals(0, result.errors)
        assertEquals(0, result.ackFailed)
    }

    // --- Concurrency: mutex serializes worker + manual syncs ---

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

    // --- Pairing fingerprint: URL-only vs URL+token sensitivity ---

    @Test
    fun `pairing fingerprint changes when url changes`() {
        val fp1 = NutritionSyncManager.computePairingFingerprint("https://server-a.example.com", "tok")
        val fp2 = NutritionSyncManager.computePairingFingerprint("https://server-b.example.com", "tok")
        assertTrue("Different URLs must produce different fingerprints", fp1 != fp2)
    }

    @Test
    fun `pairing fingerprint changes when token rotates`() {
        val fp1 = NutritionSyncManager.computePairingFingerprint("https://example.com", "token-A")
        val fp2 = NutritionSyncManager.computePairingFingerprint("https://example.com", "token-B")
        assertTrue("Rotated token must produce a different fingerprint", fp1 != fp2)
    }

    @Test
    fun `pairing fingerprint is stable for same url and token`() {
        val url = "https://example.com"
        val tok = "same-token"
        assertEquals(
            NutritionSyncManager.computePairingFingerprint(url, tok),
            NutritionSyncManager.computePairingFingerprint(url, tok),
        )
    }

    @Test
    fun `url prefix cannot collide with token suffix`() {
        // "https://ab" + \0 + "c" must differ from "https://a" + \0 + "bc"
        val fp1 = NutritionSyncManager.computePairingFingerprint("https://ab", "c")
        val fp2 = NutritionSyncManager.computePairingFingerprint("https://a", "bc")
        assertTrue("Null-byte separator prevents prefix/suffix collision", fp1 != fp2)
    }

    // --- Description field preserved through executor ---

    @Test
    fun `record with description is passed to writer with description intact`() = runBlocking {
        val recordWithDesc = rec("r1").copy(description = "AI estimate")
        val writer = FakeHcWriter()

        executeSyncCycle(
            records = listOf(recordWithDesc),
            bookmarks = InsertBookmarks.EMPTY,
            transport = FakeTransport(),
            hcWriter = writer,
            onBookmarkPersist = {},
        )

        assertEquals(1, writer.insertCalls.size)
        assertEquals("AI estimate", writer.insertCalls[0].description)
    }
}
