// AndroidOnly: WP-308 Recovery pagination, explicit selection and nondestructive copy state.
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.feature.chats.list.support.Fixtures
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RecoveredDraftStateHolderTest {
    private fun row(index: Int) = MessageDTO(
        id = UUID(0, index.toLong()), radioId = Fixtures.radio, channelIndex = 2u,
        text = "Original \u4e2d\uD83D\uDE80 $index", timestamp = index.toUInt(),
        status = MessageStatus.FAILED, createdAt = Fixtures.epoch.plusSeconds(index.toLong()),
    )

    private class Port(var rows: List<MessageDTO>) : RecoveredDraftPort {
        val requests = mutableListOf<Int>()
        val copies = mutableListOf<Pair<EntityKey, ComposerTarget>>()
        var fetchFailure: Exception? = null
        var copyFailure: Exception? = null
        var copyGate: CompletableDeferred<Unit>? = null
        override suspend fun fetchPage(radioId: RadioId, limit: Int, offset: Int): List<MessageDTO> {
            requests += offset
            fetchFailure?.let { throw it }
            return rows.drop(offset).take(limit)
        }
        override suspend fun copyToDraft(source: EntityKey, destination: ComposerTarget) {
            copyGate?.await()
            copyFailure?.let { throw it }
            copies += source to destination
        }
    }

    @Test fun pagesPreserveOrderAndSelectionAndStopAtKnownEnd() = runBlocking {
        val rows = (1..43).map(::row)
        val port = Port(rows)
        val holder = RecoveredDraftStateHolder(Fixtures.radio, port, { _, failure -> throw failure })
        holder.refresh()
        val key = EntityKey(Fixtures.radio, rows.first().id)
        holder.select(key)
        holder.loadMore()
        holder.loadMore()
        assertEquals(listOf(0, 40), port.requests)
        assertEquals(rows, holder.state.value.messages)
        assertEquals(key, holder.state.value.selected)
        assertFalse(holder.state.value.hasMore)
        assertTrue(port.copies.isEmpty())
    }

    @Test fun failedRefreshRetainsTextSelectionAndRetryOffset() = runBlocking {
        val port = Port(listOf(row(1)))
        val reported = mutableListOf<Throwable>()
        val holder = RecoveredDraftStateHolder(Fixtures.radio, port, { _, failure -> reported += failure })
        holder.refresh()
        val key = EntityKey(Fixtures.radio, row(1).id)
        holder.select(key)
        val failure = IllegalStateException("Storage unavailable")
        port.fetchFailure = failure
        holder.refresh()
        assertEquals(port.rows, holder.state.value.messages)
        assertEquals(key, holder.state.value.selected)
        assertSame(failure, holder.state.value.failure)
        assertEquals(listOf<Throwable>(failure), reported)
        assertFalse(holder.state.value.loading)
        port.fetchFailure = null
        holder.refresh()
        assertNull(holder.state.value.failure)
        assertEquals(listOf(0, 0, 0), port.requests)
    }

    @Test fun copyRequiresExplicitSelectionAndDestinationAndLeavesSourceListed() = runBlocking {
        val source = row(1)
        val port = Port(listOf(source))
        val holder = RecoveredDraftStateHolder(Fixtures.radio, port, { _, failure -> throw failure })
        val destination = ComposerTarget.Direct(Fixtures.contact("Selected destination"))
        holder.refresh()
        assertFalse(holder.copySelectedTo(destination))
        assertTrue(port.copies.isEmpty())
        val key = EntityKey(source.radioId, source.id)
        holder.select(key)
        assertTrue(holder.copySelectedTo(destination))
        assertEquals(listOf<Pair<EntityKey, ComposerTarget>>(key to destination), port.copies)
        assertEquals(listOf(source), holder.state.value.messages)
        assertEquals(listOf(source), port.rows)
        assertNull(holder.state.value.selected)
        assertTrue(holder.state.value.copied)
        assertFalse(holder.copySelectedTo(destination))
        assertEquals(1, port.copies.size)
    }

    @Test fun rejectedCopyPreservesSelectionAndExposesErrorForExistingDraftConflict() = runBlocking {
        val port = Port(listOf(row(1)))
        val reported = mutableListOf<Throwable>()
        val holder = RecoveredDraftStateHolder(Fixtures.radio, port, { _, failure -> reported += failure })
        holder.refresh()
        val key = EntityKey(Fixtures.radio, row(1).id)
        holder.select(key)
        val failure = IllegalStateException("Destination already has newer text")
        port.copyFailure = failure
        assertFalse(holder.copySelectedTo(ComposerTarget.Channel(Fixtures.channel("B"))))
        assertSame(failure, holder.state.value.failure)
        assertEquals(listOf<Throwable>(failure), reported)
        assertEquals(key, holder.state.value.selected)
        assertEquals(port.rows, holder.state.value.messages)
        assertFalse(holder.state.value.copying)
        assertFalse(holder.state.value.copied)
        assertTrue(port.copies.isEmpty())
    }

    @Test fun cancellationPreservesSelectionAndNeverReportsCopySuccess() = runBlocking {
        val port = Port(listOf(row(1)))
        port.copyGate = CompletableDeferred()
        val holder = RecoveredDraftStateHolder(Fixtures.radio, port, { _, failure -> throw failure })
        holder.refresh()
        val key = EntityKey(Fixtures.radio, row(1).id)
        holder.select(key)
        var completed = false
        val operation = launch(start = CoroutineStart.UNDISPATCHED) {
            completed = holder.copySelectedTo(ComposerTarget.Channel(Fixtures.channel("B")))
        }
        assertTrue(holder.state.value.copying)
        operation.cancel(CancellationException("Screen closed"))
        operation.join()
        assertFalse(completed)
        assertEquals(key, holder.state.value.selected)
        assertFalse(holder.state.value.copying)
        assertNull(holder.state.value.failure)
        assertFalse(holder.state.value.copied)
        assertTrue(port.copies.isEmpty())
    }

    @Test fun aForeignRadioPageIsAnExplicitFailureNotAnEmptySuccess() = runBlocking {
        val foreign = row(1).copy(radioId = RadioId(UUID(0, 99)))
        val holder = RecoveredDraftStateHolder(Fixtures.radio, Port(listOf(foreign)), { _, _ -> })
        holder.refresh()
        assertTrue(holder.state.value.failure is IllegalStateException)
        assertTrue(holder.state.value.hasMore)
        assertTrue(holder.state.value.messages.isEmpty())
        assertFalse(holder.state.value.loading)
    }
}
