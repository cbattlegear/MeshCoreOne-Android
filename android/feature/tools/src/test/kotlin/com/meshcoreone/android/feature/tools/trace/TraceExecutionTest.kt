// AndroidOnly: WP-314 Fixture-topology execution, timeout, save and batch flows the source covered only through UI and radio runs.
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.TracePathPersisting
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SavedTracePathDTO
import com.meshcoreone.android.core.model.TracePathRunDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred

class TraceExecutionTest {
    private val harness = TraceHarness(configureDependencies = true)
    private val holder = harness.holder
    private val sender = FakeTraceSender()
    private val store = InMemoryTracePathStore { harness.time.now() }
    private val alpha = contact(0xA1, name = "Alpha")
    private val bravo = contact(0xB2, name = "Bravo")

    init {
        harness.deps.device = device()
        harness.deps.sender = sender
        harness.deps.store = store
        holder.setContactsForTesting(listOf(alpha, bravo))
        holder.addNode(alpha)
        holder.addNode(bravo)
    }

    private fun lastTag(): UInt = sender.sent.last().tag
    private fun answer(snrs: List<Double> = listOf(6.0, 4.0, 2.0)) {
        val hashes = listOf(0xA1, 0xB2, 0xA1)
        harness.respond(lastTag(), snrs.mapIndexed { index, snr -> node(hashes[index], snr) } + node(null, 1.0), RADIO)
    }

    @Test
    fun `runTrace sends the mirrored wire path and a response completes it with the clock duration`() {
        harness.complete { holder.runTrace() }
        val sent = sender.sent.single()
        assertEquals(Bytes.of(0xA1, 0xB2, 0xA1), sent.path)
        assertEquals(0u, sent.authCode)
        assertEquals(0u.toUByte(), sent.flags)
        assertTrue(harness.state.isRunning)
        harness.time.advanceBy(1234.milliseconds)
        answer()
        val result = assertNotNull(harness.state.result)
        assertEquals(1234L, result.durationMs)
        assertEquals(listOf("TestDevice", "Alpha", "Bravo", "Alpha", "TestDevice"), result.hops.map { it.resolvedName })
        assertFalse(harness.state.isRunning)
        assertNotNull(harness.state.resultID)
        assertEquals(0, harness.time.pendingSleepers)
    }

    @Test
    fun `a missing firmware hint waits the 30 s flood default before reporting no response`() {
        harness.complete { holder.runTrace() }
        harness.time.advanceBy(29_999.milliseconds)
        assertTrue(harness.state.isRunning)
        harness.time.advanceBy(1.milliseconds)
        assertFalse(harness.state.isRunning)
        assertEquals("No response received", harness.state.errorMessage)
        assertNull(harness.state.result)
    }

    @Test
    fun `a firmware hint is scaled by 1_2 plus 8 s of grace and clamped`() {
        sender.suggestedTimeoutMs = 10_000u
        harness.complete { holder.runTrace() }
        harness.time.advanceBy(19_999.milliseconds)
        assertTrue(harness.state.isRunning)
        harness.time.advanceBy(1.milliseconds)
        assertFalse(harness.state.isRunning)
        assertEquals(30.0, FloodTraceTimeout.sanitizedSeconds(0u))
        assertEquals(60.0, FloodTraceTimeout.sanitizedSeconds(100_000u))
    }

    @Test
    fun `a response after the timeout is ignored`() {
        harness.complete { holder.runTrace() }
        val tag = lastTag()
        harness.time.advanceBy(30.seconds)
        harness.respond(tag, listOf(node(0xA1, 5.0), node(null, 3.0)))
        assertNull(harness.state.result)
    }

    @Test
    fun `a send failure reports the error, stops running and records a failed run on the saved path`() {
        val saved = savedPath(Bytes.of(0xA1, 0xB2, 0xA1), radioId = RADIO)
        store.insert(saved)
        sender.failure = IOException("link lost")
        harness.complete { holder.runTrace() }
        assertEquals("Failed to send trace packet", harness.state.errorMessage)
        assertFalse(harness.state.isRunning)
        assertEquals("sendTrace", harness.diagnostics.failures.single().first)
        val runs = assertNotNull(store.paths[saved.id]).runs
        assertEquals(listOf(false), runs.map { it.success })
        assertEquals(saved.id, harness.state.activeSavedPath?.id)
        assertEquals(1, harness.state.activeSavedPath?.runs?.size)
    }

    @Test
    fun `runTrace adopts the most recently run matching saved path and appends the successful run`() {
        val older = savedPath(Bytes.of(0xA1, 0xB2, 0xA1), radioId = RADIO, runs = listOf(run(T0.minusSeconds(600))))
        val newer = savedPath(Bytes.of(0xA1, 0xB2, 0xA1), radioId = RADIO, runs = listOf(run(T0.minusSeconds(60))))
        val other = savedPath(Bytes.of(0xA1), radioId = RADIO, runs = listOf(run(T0)))
        listOf(older, newer, other).forEach(store::insert)
        harness.complete { holder.runTrace() }
        assertEquals(newer.id, harness.state.activeSavedPath?.id)
        answer(listOf(6.0, 4.0, 2.0))
        val runs = assertNotNull(store.paths[newer.id]).runs
        assertEquals(2, runs.size)
        assertEquals(SnapshotList.of(6.0, 4.0, 2.0), runs.last().hopsSNR)
        assertEquals(2, harness.state.activeSavedPath?.runs?.size)
    }

    @Test
    fun `savePath stores the traced bytes, width and intermediate SNRs only`() {
        harness.complete { holder.runTrace() }
        answer(listOf(6.0, 4.0, 2.0))
        assertTrue(harness.complete { holder.savePath("Loop") })
        val saved = store.paths.values.single()
        assertEquals(Bytes.of(0xA1, 0xB2, 0xA1), saved.pathBytes)
        assertEquals(1L, saved.hashSize)
        assertEquals(SnapshotList.of(6.0, 4.0, 2.0), saved.runs.single().hopsSNR)
        assertEquals(saved, harness.state.activeSavedPath)
    }

    @Test
    fun `savePath fails without a successful result, a device or a store`() {
        assertFalse(harness.complete { holder.savePath("Nothing yet") })
        harness.deps.device = null
        assertFalse(harness.complete { holder.savePath("No device") })
        assertTrue(store.paths.isEmpty())
    }

    @Test
    fun `batch runs every trace with a 500 ms gap, presents the first success and aggregates`() {
        holder.setBatchEnabled(true)
        holder.setBatchSize(3)
        val job = harness.start { holder.runBatchTrace() }
        assertEquals(1, harness.state.currentTraceIndex)
        harness.time.advanceBy(100.milliseconds)
        answer()
        val firstId = assertNotNull(harness.state.resultID)
        assertEquals(1, sender.sent.size)
        harness.time.advanceBy(499.milliseconds)
        assertEquals(1, sender.sent.size)
        harness.time.advanceBy(1.milliseconds)
        assertEquals(2, sender.sent.size)
        assertEquals(2, harness.state.currentTraceIndex)
        harness.time.advanceBy(200.milliseconds)
        answer()
        assertEquals(firstId, harness.state.resultID)
        harness.time.advanceBy(500.milliseconds)
        harness.time.advanceBy(300.milliseconds)
        answer()
        assertTrue(job.isCompleted)
        assertEquals(listOf(100L, 200L, 300L), harness.state.completedResults.map { it.durationMs })
        assertEquals(200L, harness.state.averageRTT)
        assertFalse(harness.state.isRunning)
        assertEquals(0, harness.state.currentTraceIndex)
        assertTrue(harness.state.canSavePath)
    }

    @Test
    fun `batch where every trace times out reports all failed`() {
        holder.setBatchEnabled(true)
        holder.setBatchSize(2)
        val job = harness.start { holder.runBatchTrace() }
        harness.time.advanceBy(30.seconds)
        harness.time.advanceBy(500.milliseconds)
        harness.time.advanceBy(30.seconds)
        assertTrue(job.isCompleted)
        assertEquals(listOf(false, false), harness.state.completedResults.map { it.success })
        assertEquals("All 2 traces failed", harness.state.errorMessage)
        assertNull(harness.state.result)
    }

    @Test
    fun `batch send failure records a failed result and the batch continues`() {
        holder.setBatchEnabled(true)
        holder.setBatchSize(2)
        sender.failure = IOException("busy")
        val job = harness.start { holder.runBatchTrace() }
        sender.failure = null
        harness.time.advanceBy(500.milliseconds)
        answer()
        assertTrue(job.isCompleted)
        assertEquals(listOf("Failed to send trace packet", null), harness.state.completedResults.map { it.errorMessage })
        assertNull(harness.state.errorMessage)
    }

    @Test
    fun `cancelling a batch mid-wait keeps partial results and stops sending`() {
        holder.setBatchEnabled(true)
        holder.setBatchSize(5)
        val job = harness.start { holder.runBatchTrace() }
        answer()
        harness.time.advanceBy(500.milliseconds)
        assertEquals(2, sender.sent.size)
        holder.cancelBatchTrace()
        harness.main.runCurrent()
        assertTrue(job.isCompleted)
        harness.time.advanceBy(60.seconds)
        assertEquals(2, sender.sent.size)
        assertEquals(1, harness.state.completedResults.size)
        assertFalse(harness.state.isRunning)
        assertFalse(harness.state.isBatchInProgress)
    }

    @Test
    fun `batch save stores the first success and appends the other runs offset by their index`() {
        holder.setBatchEnabled(true)
        holder.setBatchSize(2)
        harness.start { holder.runBatchTrace() }
        answer()
        harness.time.advanceBy(500.milliseconds)
        harness.time.advanceBy(30.seconds)
        assertTrue(harness.complete { holder.savePath("Batch") })
        val saved = store.paths.values.single()
        assertEquals(listOf(true, false), saved.runs.map { it.success })
        assertEquals(saved.runs[0].date.plusSeconds(1), saved.runs[1].date)
        assertEquals(saved.id, harness.state.activeSavedPath?.id)
    }

    @Test
    fun `cancellation of a suspended send propagates, is not a failure and leaves nothing running`() {
        val gate = CompletableDeferred<MessageSentInfo>()
        harness.deps.sender = TraceSender { _, _, _, _ -> gate.await() }
        val job = harness.start { holder.runTrace() }
        assertTrue(harness.state.isRunning)
        job.cancel()
        harness.main.runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(harness.diagnostics.failures.isEmpty())
        assertFalse(harness.state.isRunning)
        harness.respond(0x1000u, listOf(node(0xA1, 5.0), node(null, 3.0)))
        assertNull(harness.state.result)
    }

    @Test
    fun `cancelling a batch caller mid-gap clears running state and correlation`() {
        holder.setBatchEnabled(true)
        holder.setBatchSize(3)
        val job = harness.start { holder.runBatchTrace() }
        answer()
        assertTrue(harness.state.isBatchInProgress)
        job.cancel()
        harness.main.runCurrent()
        assertTrue(job.isCancelled)
        assertFalse(harness.state.isRunning)
        assertEquals(0, harness.state.currentTraceIndex)
        assertEquals(1, harness.state.completedResults.size)
        assertEquals(0, harness.time.pendingSleepers)
    }

    @Test
    fun `a cancel or a response while a batch send is suspended never strands the batch`() {
        holder.setBatchEnabled(true)
        holder.setBatchSize(2)
        val gate = CompletableDeferred<MessageSentInfo>()
        val tags = mutableListOf<UInt>()
        harness.deps.sender = TraceSender { tag, _, _, _ -> tags += tag; gate.await() }
        val cancelled = harness.start { holder.runBatchTrace() }
        holder.cancelBatchTrace()
        gate.complete(MessageSentInfo(0u, Bytes.of(0, 0, 0, 0), 0u))
        harness.main.runCurrent()
        assertTrue(cancelled.isCompleted)
        assertEquals(1, tags.size)

        val early = CompletableDeferred<MessageSentInfo>()
        harness.deps.sender = TraceSender { tag, _, _, _ -> tags += tag; early.await() }
        val answered = harness.start { holder.runBatchTrace() }
        harness.respond(tags.last(), listOf(node(0xA1, 5.0), node(null, 3.0)))
        early.complete(MessageSentInfo(0u, Bytes.of(0, 0, 0, 0), 0u))
        harness.main.runCurrent()
        assertEquals(1, harness.state.completedResults.size)
        harness.time.advanceBy(500.milliseconds)
        assertEquals(3, tags.size)
        assertFalse(answered.isCompleted)
        holder.cancelBatchTrace()
        harness.main.runCurrent()
        assertTrue(answered.isCompleted)
    }

    @Test
    fun `path edits retire held sends late responses and timeouts without resurrecting history`() {
        val edits: List<(TracePathStateHolder) -> Unit> = listOf(
            { it.clearPath() },
            { it.moveRepeater(setOf(0), 2) },
            { it.removeRepeater(0) },
            { it.addNode(contact(0xC3, name = "Charlie")) },
            { it.addRepeatersFromCodes("C3"); Unit },
            { it.setTraceHashMode(2u) },
            { it.setAutoReturnPath(false) },
            { it.loadSavedPath(savedPath(Bytes.of(0xD4), radioId = RADIO)) },
        )
        for (batch in listOf(false, true)) for (held in listOf(false, true)) {
            for (sendFails in listOf(false, true)) for (edit in edits) {
                val h = TraceHarness(configureDependencies = true)
                val persistence = InMemoryTracePathStore { h.time.now() }
                h.deps.device = device()
                h.deps.store = persistence
                h.holder.setContactsForTesting(listOf(contact(0xC3, name = "Charlie")))
                h.holder.addNode(alpha)
                h.holder.addNode(bravo)
                h.holder.setBatchEnabled(batch)
                h.holder.setBatchSize(3)
                val saved = savedPath(h.state.fullPathData, radioId = RADIO)
                persistence.insert(saved)
                val gate = CompletableDeferred<MessageSentInfo>()
                val tags = mutableListOf<UInt>()
                h.deps.sender = TraceSender { tag, _, _, _ ->
                    tags += tag
                    if (held && (!batch || tags.size == 2)) gate.await()
                    else MessageSentInfo(0u, Bytes.EMPTY, 0u)
                }
                val job = h.start { h.holder.runBatchTrace() }
                if (batch) {
                    h.respond(tags.last(), listOf(node(0xA1, 5.0), node(null, 3.0)), RADIO)
                    h.time.advanceBy(500.milliseconds)
                    assertEquals(1, h.state.completedResults.size)
                }
                val oldTag = tags.last()
                val history = h.state.completedResults
                edit(h.holder)
                val edited = h.state
                assertFalse(edited.isRunning)
                assertNull(edited.displayedResult)
                assertNull(h.holder.pendingTag)
                assertNull(h.holder.pendingPathHash)
                h.respond(oldTag, listOf(node(0xA1, 5.0), node(null, 3.0)), RADIO)
                if (sendFails) gate.completeExceptionally(IOException("retired send"))
                else gate.complete(MessageSentInfo(0u, Bytes.EMPTY, 0u))
                h.main.runCurrent()
                h.time.advanceBy(60.seconds)
                h.respond(oldTag, listOf(node(0xA1, 5.0), node(null, 3.0)), RADIO)
                assertTrue(job.isCompleted)
                assertEquals(edited, h.state, "Retired work must not change the edited path or presentation")
                assertEquals(history, h.state.completedResults)
                assertEquals(if (batch) 1 else 0, persistence.paths.getValue(saved.id).runs.size)
                assertEquals(if (batch) 2 else 1, tags.size, "No later batch trace may be sent")
                assertEquals(0, h.time.pendingSleepers)
            }
        }
    }

    @Test
    fun `retired send completion cannot stop or overwrite a newer single or batch run`() {
        for (batch in listOf(false, true)) for (sendFails in listOf(false, true)) {
            val h = TraceHarness(configureDependencies = true)
            h.deps.device = device()
            h.holder.addNode(alpha)
            h.holder.setBatchEnabled(batch)
            h.holder.setBatchSize(1)
            val gate = CompletableDeferred<MessageSentInfo>()
            val tags = mutableListOf<UInt>()
            h.deps.sender = TraceSender { tag, _, _, _ ->
                tags += tag
                if (tags.size == 1) gate.await() else MessageSentInfo(0u, Bytes.EMPTY, 0u)
            }
            val old = h.start { h.holder.runBatchTrace() }
            h.holder.clearPath()
            h.holder.addNode(bravo)
            val current = h.start { h.holder.runBatchTrace() }
            val currentTag = tags.last()
            val running = h.state
            if (sendFails) gate.completeExceptionally(IOException("old send failed"))
            else gate.complete(MessageSentInfo(0u, Bytes.EMPTY, 0u))
            h.main.runCurrent()
            assertTrue(old.isCompleted)
            assertEquals(running, h.state)
            assertEquals(currentTag, h.holder.pendingTag)
            h.respond(tags.first(), listOf(node(0xA1, 5.0), node(null, 3.0)), RADIO)
            assertEquals(running, h.state)
            h.respond(currentTag, listOf(node(0xB2, 5.0), node(null, 3.0)), RADIO)
            assertTrue(current.isCompleted)
            assertEquals(Bytes.of(0xB2), assertNotNull(h.state.result).tracedPathBytes)
            val finished = h.state
            h.time.advanceBy(60.seconds)
            assertEquals(finished, h.state)
        }
    }

    @Test
    fun `path edit during saved path lookup prevents a trace from starting or restoring the old match`() {
        for (batch in listOf(false, true)) {
            val h = TraceHarness(configureDependencies = true)
            val persistence = InMemoryTracePathStore { h.time.now() }
            val gate = CompletableDeferred<SnapshotList<SavedTracePathDTO>>()
            val recorded = FakeTraceSender()
            h.deps.device = device()
            h.deps.sender = recorded
            h.deps.store = object : TracePathPersisting by persistence {
                override suspend fun fetchSavedTracePaths(radioId: RadioId) = gate.await()
            }
            h.holder.addNode(alpha)
            h.holder.setBatchEnabled(batch)
            val matched = savedPath(h.state.fullPathData, radioId = RADIO)
            val job = h.start { h.holder.runBatchTrace() }
            assertTrue(h.state.isRunning)
            h.holder.clearPath()
            gate.complete(SnapshotList.of(matched))
            h.main.runCurrent()
            assertTrue(job.isCompleted)
            assertTrue(recorded.sent.isEmpty())
            assertTrue(h.state.outboundPath.isEmpty())
            assertNull(h.state.activeSavedPath)
            assertNull(h.state.result)
            assertFalse(h.state.isRunning)
        }
    }

    @Test
    fun `a saved run refresh completing after clear never restores the old saved path reference`() {
        val persistence = InMemoryTracePathStore { harness.time.now() }
        val gate = CompletableDeferred<Unit>()
        val saved = savedPath(harness.state.fullPathData, radioId = RADIO)
        persistence.insert(saved)
        harness.deps.store = object : TracePathPersisting by persistence {
            override suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO? {
                gate.await()
                return persistence.fetchSavedTracePath(key)
            }
        }
        harness.complete { holder.runTrace() }
        answer()
        holder.clearPath()
        gate.complete(Unit)
        harness.main.runCurrent()
        assertNull(harness.state.activeSavedPath)
        assertNull(harness.state.result)
        assertEquals(1, persistence.paths.getValue(saved.id).runs.size, "The already accepted historical run remains")
    }

    @Test
    fun `held history refresh cannot overwrite another radio or a reselected saved path`() {
        for (differentRadio in listOf(false, true)) {
            val h = TraceHarness(configureDependencies = true)
            val persistence = InMemoryTracePathStore { h.time.now() }
            val gate = CompletableDeferred<Unit>()
            val recorded = FakeTraceSender()
            h.deps.device = device()
            h.deps.sender = recorded
            h.holder.addNode(alpha)
            val saved = savedPath(h.state.fullPathData, radioId = RADIO)
            persistence.insert(saved)
            h.deps.store = object : TracePathPersisting by persistence {
                override suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO? {
                    gate.await()
                    return persistence.fetchSavedTracePath(key)
                }
            }
            h.complete { h.holder.runTrace() }
            h.respond(recorded.sent.single().tag, listOf(node(0xA1, 5.0), node(null, 3.0)), RADIO)
            h.holder.clearPath()
            val selected = saved.copy(
                radioId = if (differentRadio) RadioId(UUID.fromString("00000000-0000-0000-0000-0000000000b2")) else RADIO,
                name = "New selection",
            )
            h.holder.loadSavedPath(selected)
            gate.complete(Unit)
            h.main.runCurrent()
            assertEquals(selected, h.state.activeSavedPath)
            assertNull(h.state.result)
            assertEquals(1, persistence.paths.getValue(saved.id).runs.size)
        }
    }

    @Test
    fun `auto return changes retire old routes but unchanged values preserve pending work`() {
        for (batch in listOf(false, true)) {
            val h = TraceHarness(configureDependencies = true)
            val recorded = FakeTraceSender()
            val persistence = InMemoryTracePathStore { h.time.now() }
            h.deps.device = device()
            h.deps.sender = recorded
            h.deps.store = persistence
            h.holder.addNode(alpha)
            h.holder.addNode(bravo)
            h.holder.setBatchEnabled(batch)
            h.holder.setBatchSize(1)
            val oldPath = h.state.fullPathData
            val saved = savedPath(oldPath, radioId = RADIO)
            persistence.insert(saved)
            h.start { h.holder.runBatchTrace() }
            val oldTag = recorded.sent.last().tag
            val running = h.state
            h.holder.setAutoReturnPath(true)
            assertEquals(running, h.state)
            assertEquals(oldTag, h.holder.pendingTag)
            h.holder.setAutoReturnPath(false)
            assertFalse(h.state.isRunning)
            assertNull(h.state.activeSavedPath)
            assertNull(h.state.result)
            assertEquals(Bytes.of(0xA1, 0xB2), h.state.fullPathData)
            val edited = h.state
            h.respond(oldTag, listOf(node(0xA1, 5.0), node(null, 3.0)), RADIO)
            h.time.advanceBy(60.seconds)
            assertEquals(edited, h.state)
            assertTrue(persistence.paths.getValue(saved.id).runs.isEmpty())
            val next = h.start { h.holder.runBatchTrace() }
            assertEquals(edited.fullPathData, recorded.sent.last().path)
            h.respond(recorded.sent.last().tag, listOf(node(0xB2, 5.0), node(null, 3.0)), RADIO)
            assertTrue(next.isCompleted)
            assertEquals(edited.fullPathData, assertNotNull(h.state.result).tracedPathBytes)
        }
    }

    @Test
    fun `history refresh remains valid when another trace starts or cancels on the same selection`() {
        for (cancelSecond in listOf(false, true)) {
            val h = TraceHarness(configureDependencies = true)
            val recorded = FakeTraceSender()
            val persistence = InMemoryTracePathStore { h.time.now() }
            val gate = CompletableDeferred<Unit>()
            h.deps.device = device()
            h.deps.sender = recorded
            h.holder.addNode(alpha)
            val previous = run(T0.minusSeconds(60))
            val saved = savedPath(h.state.fullPathData, radioId = RADIO, runs = listOf(previous))
            persistence.insert(saved)
            h.deps.store = object : TracePathPersisting by persistence {
                override suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO? {
                    gate.await()
                    return persistence.fetchSavedTracePath(key)
                }
            }
            h.complete { h.holder.runTrace() }
            h.respond(recorded.sent.last().tag, listOf(node(0xA1, 5.0), node(null, 3.0)), RADIO)
            assertEquals(2, persistence.paths.getValue(saved.id).runs.size)
            h.complete { h.holder.runTrace() }
            if (cancelSecond) h.holder.cancelBatchTrace()
            val beforeRefresh = h.state
            gate.complete(Unit)
            h.main.runCurrent()
            assertEquals(2, h.state.activeSavedPath?.runs?.size)
            assertEquals(previous, h.state.previousRun)
            assertEquals(beforeRefresh.isRunning, h.state.isRunning)
            if (!cancelSecond) {
                h.respond(recorded.sent.last().tag, listOf(node(0xA1, 6.0), node(null, 3.0)), RADIO)
                assertEquals(3, h.state.activeSavedPath?.runs?.size)
            }
        }
    }

    @Test
    fun `a held older store snapshot cannot overwrite a newer published run history`() {
        val h = TraceHarness(configureDependencies = true)
        val recorded = FakeTraceSender()
        val persistence = InMemoryTracePathStore { h.time.now() }
        val gate = CompletableDeferred<Unit>()
        h.deps.device = device()
        h.deps.sender = recorded
        h.holder.addNode(alpha)
        val saved = savedPath(h.state.fullPathData, radioId = RADIO)
        persistence.insert(saved)
        var fetches = 0
        h.deps.store = object : TracePathPersisting by persistence {
            override suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO? {
                val readSnapshot = persistence.fetchSavedTracePath(key)
                if (++fetches == 1) gate.await()
                return readSnapshot
            }
        }
        h.complete { h.holder.runTrace() }
        h.respond(recorded.sent.last().tag, listOf(node(0xA1, 5.0), node(null, 3.0)), RADIO)
        assertEquals(1, persistence.paths.getValue(saved.id).runs.size)
        h.complete { h.holder.runTrace() }
        h.respond(recorded.sent.last().tag, listOf(node(0xA1, 6.0), node(null, 3.0)), RADIO)
        assertEquals(2, h.state.activeSavedPath?.runs?.size)
        val newer = h.state.activeSavedPath
        val comparison = h.state.previousRun
        assertNotNull(comparison)
        gate.complete(Unit)
        h.main.runCurrent()
        assertEquals(newer, h.state.activeSavedPath)
        assertEquals(comparison, h.state.previousRun)
    }

    @Test
    fun `changing batch mode retires the pending operation without sending another trace`() {
        for (initialBatch in listOf(false, true)) for (held in listOf(false, true)) {
            val h = TraceHarness(configureDependencies = true)
            val gate = CompletableDeferred<MessageSentInfo>()
            val tags = mutableListOf<UInt>()
            h.deps.device = device()
            h.holder.addNode(alpha)
            h.holder.addNode(bravo)
            h.holder.setBatchEnabled(initialBatch)
            h.holder.setBatchSize(2)
            h.deps.sender = TraceSender { tag, _, _, _ ->
                tags += tag
                if (held) gate.await() else MessageSentInfo(0u, Bytes.EMPTY, 0u)
            }
            val running = h.start { h.holder.runBatchTrace() }
            val beforeNoOp = h.state
            h.holder.setBatchEnabled(initialBatch)
            assertEquals(beforeNoOp, h.state)
            h.holder.setBatchEnabled(!initialBatch)
            assertFalse(h.state.isRunning)
            val changed = h.state
            gate.complete(MessageSentInfo(0u, Bytes.EMPTY, 0u))
            h.main.runCurrent()
            h.respond(tags.single(), listOf(node(0xA1, 5.0), node(null, 3.0)), RADIO)
            h.time.advanceBy(60.seconds)
            assertTrue(running.isCompleted)
            assertEquals(changed, h.state)
            assertEquals(1, tags.size)
            assertNull(h.state.result)
            assertTrue(h.state.completedResults.isEmpty())
            h.holder.setBatchSize(1)
            val next = h.start { h.holder.runBatchTrace() }
            assertEquals(2, tags.size)
            h.respond(tags.last(), listOf(node(0xA1, 6.0), node(null, 3.0)), RADIO)
            assertTrue(next.isCompleted)
            assertFalse(h.state.isRunning)
            assertTrue(assertNotNull(h.state.result).success)
            assertEquals(if (initialBatch) 0 else 1, h.state.completedResults.size)
        }
    }

    @Test
    fun `held save continuations preserve durable history without restoring an edited selection`() {
        for (batch in listOf(false, true)) for (holdFetch in listOf(false, true)) {
            if (!batch && holdFetch) continue
            for (reselect in listOf(false, true)) {
                val h = TraceHarness(configureDependencies = true)
                val recorded = FakeTraceSender()
                val persistence = InMemoryTracePathStore { h.time.now() }
                val gate = CompletableDeferred<Unit>()
                h.deps.device = device()
                h.deps.sender = recorded
                h.holder.addNode(alpha)
                h.holder.setBatchEnabled(batch)
                h.holder.setBatchSize(2)
                h.deps.store = object : TracePathPersisting by persistence {
                    override suspend fun createSavedTracePath(
                        radioId: RadioId, name: String, pathBytes: Bytes, hashSize: Long, initialRun: TracePathRunDTO?,
                    ): SavedTracePathDTO {
                        val saved = persistence.createSavedTracePath(radioId, name, pathBytes, hashSize, initialRun)
                        if (!holdFetch) gate.await()
                        return saved
                    }

                    override suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO? {
                        if (holdFetch) gate.await()
                        return persistence.fetchSavedTracePath(key)
                    }
                }
                val execution = h.start { h.holder.runBatchTrace() }
                h.respond(recorded.sent.last().tag, listOf(node(0xA1, 5.0), node(null, 3.0)), RADIO)
                if (batch) {
                    h.time.advanceBy(500.milliseconds)
                    h.respond(recorded.sent.last().tag, listOf(node(0xA1, 6.0), node(null, 3.0)), RADIO)
                }
                assertTrue(execution.isCompleted)
                var accepted: Boolean? = null
                val save = h.start { accepted = h.holder.savePath("Accepted history") }
                assertFalse(save.isCompleted)
                h.holder.clearPath()
                val selected = savedPath(Bytes.of(0xB2), radioId = RADIO)
                if (reselect) h.holder.loadSavedPath(selected)
                val edited = h.state
                gate.complete(Unit)
                h.main.runCurrent()
                assertTrue(save.isCompleted)
                assertEquals(true, accepted, "Durably committed save must still report success")
                assertEquals(edited, h.state)
                val durable = persistence.paths.values.single()
                assertEquals("Accepted history", durable.name)
                assertEquals(if (batch) 2 else 1, durable.runs.size)
                assertTrue(durable.runs.all { it.success })
            }
        }
    }

    @Test
    fun `loadContacts splits repeaters and rooms, keeps discovered repeaters and drops an unsupported override`() {
        val directory = FakeNodeDirectory(
            contacts = listOf(alpha, contact(0xC3, name = "Room", type = com.meshcoreone.android.core.protocol.model.ContactType.ROOM),
                contact(0xD4, name = "Chat", type = com.meshcoreone.android.core.protocol.model.ContactType.CHAT)),
            nodes = listOf(discovered(key(0xE5), "Hill"), discovered(key(0xF6), "Sensor", type = com.meshcoreone.android.core.protocol.model.ContactType.CHAT)),
        )
        harness.deps.directory = directory
        harness.deps.device = device(firmwareVersion = 7u, firmwareVersionString = "v1.9.0")
        holder.setTraceHashMode(1u)
        harness.complete { holder.loadContacts(RADIO) }
        assertEquals(listOf("Alpha"), harness.state.availableRepeaters.map { it.name })
        assertEquals(listOf("Room"), harness.state.availableRooms.map { it.name })
        assertEquals(listOf("Hill"), harness.state.discoveredRepeaters.map { it.name })
        assertNull(harness.state.traceHashMode)
        directory.failure = IOException("db")
        harness.complete { holder.loadContacts(RADIO) }
        assertTrue(harness.state.availableNodes.isEmpty())
        assertEquals("loadContacts", harness.diagnostics.failures.last().first)
    }

    @Test
    fun `appended hops are recorded as per-radio recents, newest first and capped at 8`() {
        harness.deps.directory = FakeNodeDirectory()
        harness.complete { holder.loadContacts(RADIO) }
        (1..9).forEach { holder.appendHop(contact(it, name = "N$it")) }
        holder.appendHop(contact(3, name = "N3"))
        assertEquals(listOf(3, 9, 8, 7, 6, 5, 4, 2), harness.state.recentPublicKeys.map { it[0].toInt() })
        val stored = assertNotNull(harness.recents.values[RecentHopsStore.defaultsKey(RADIO)])
        assertEquals("pathEdit.recentPublicKeys.00000000-0000-0000-0000-0000000000A1", RecentHopsStore.defaultsKey(RADIO))
        assertEquals(key(3).hexString, stored.first())
        assertEquals(stored.map { com.meshcoreone.android.core.model.applicationBytesFromHex(it) }, RecentHopsStore(harness.recents).load(RADIO))
    }

    @Test
    fun `loadSavedPath on a radio that honors the override adopts the saved width`() {
        harness.deps.device = device(firmwareVersion = 9u)
        holder.loadSavedPath(savedPath(Bytes.of(0xA1, 0x00, 0x00, 0x00), hashSize = 4))
        assertEquals(2u.toUByte(), harness.state.traceHashMode)
        assertEquals(4, holder.hashSize)
        val previous = harness.state.activeSavedPath
        holder.loadSavedPath(savedPath(Bytes.EMPTY, hashSize = 1))
        assertTrue(harness.state.outboundPath.isEmpty())
        // An empty saved path clears the builder but, as in the source, keeps the prior reference and mode.
        assertEquals(previous, harness.state.activeSavedPath)
        assertEquals(2u.toUByte(), harness.state.traceHashMode)
    }

    @Test
    fun `path names, clipboard and deleted saved path follow the source rules`() {
        assertEquals("Alpha → Bravo", holder.generatePathName())
        holder.addNode(contact(0xC3, name = "Charlie"))
        assertEquals("Alpha → ... → Charlie", holder.generatePathName())
        holder.clearPath()
        holder.loadSavedPath(savedPath(Bytes.of(0x0E, 0x0F, 0x0E)))
        assertEquals("Path 0E,0F,0E", holder.generatePathName())
        val copied = mutableListOf<String>()
        holder.copyPathToClipboard { copied += it }
        assertEquals(listOf("0E,0F,0E"), copied)
        val active = assertNotNull(harness.state.activeSavedPath)
        holder.handleSavedPathDeleted(java.util.UUID.randomUUID())
        assertNotNull(harness.state.activeSavedPath)
        holder.handleSavedPathDeleted(active.id)
        assertNull(harness.state.activeSavedPath)
    }

    @Test
    fun `classifyCodes previews without mutating and the picker is never full`() {
        val preview = holder.classifyCodes("a1, c3, zz")
        assertEquals(listOf(HopCodeStatus.AlreadyInPath, HopCodeStatus.NotFound, HopCodeStatus.InvalidFormat), preview.map { it.status })
        assertEquals(2, holder.currentHopCount)
        assertNull(holder.hopLimit)
        assertFalse(holder.isPathFull)
    }
}
