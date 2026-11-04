// AndroidOnly: WP-306 Read-only state controls; not device, iOS or radio-origin evidence.
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.contracts.domain.UnprovedDirectMessage
import com.meshcoreone.android.core.contracts.domain.UnprovedDirectMessageProvenance
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.feature.chats.list.support.FakeData
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class UnresolvedMessagesStateHolderTest {
    private val radio = RadioId(UUID(0, 306))
    private val failures = mutableListOf<Throwable?>()
    private val diagnostics = ChatListDiagnostics { _, failure -> failures += failure }
    private fun row(index: Long, selected: RadioId = radio) = UnprovedDirectMessage(
        UUID(0, index), selected, "Same text", 0u, Instant.EPOCH, UnprovedDirectMessageProvenance.UNRESOLVED,
    )

    @Test fun noRadioNeverReadsAndDoesNotPretendToLoad() = runBlocking {
        val holder = UnresolvedMessagesStateHolder(null, FakeData(), diagnostics)
        holder.refresh()
        assertNull(holder.state.value.radioId)
        assertFalse(holder.state.value.isLoading)
        assertFalse(holder.state.value.loadFailed)
    }

    @Test fun noContactsAndSameContentDistinctOccurrencesRemainReadable() = runBlocking {
        val data = FakeData()
        data.unprovedRead = { selected, limit, offset ->
            assertEquals(radio, selected); assertEquals(51L, limit); assertEquals(0L, offset)
            listOf(row(1), row(2))
        }
        val holder = UnresolvedMessagesStateHolder(radio, data, diagnostics)
        holder.refresh()
        assertTrue(data.contacts.isEmpty())
        assertTrue(data.calls.isEmpty(), "No contact or mutation calls are needed")
        assertEquals(listOf(UUID(0, 1), UUID(0, 2)), holder.state.value.messages.map { it.occurrenceID })
        assertEquals(listOf(0u, 0u), holder.state.value.messages.map { it.wireTimestamp })
        assertFalse(holder.state.value.hasMore)
    }

    @Test fun boundedPagesKeepOccurrenceIdentityAndAllHistoryCanBeReached() = runBlocking {
        val rows = (1L..103).map { row(it) }
        val reads = mutableListOf<Pair<Long, Long>>()
        val data = FakeData().apply {
            unprovedRead = { _, limit, offset ->
                reads += limit to offset
                rows.drop(offset.toInt()).take(limit.toInt())
            }
        }
        val holder = UnresolvedMessagesStateHolder(radio, data, diagnostics)
        holder.refresh()
        assertEquals(50, holder.state.value.messages.size)
        assertTrue(holder.state.value.hasMore)
        holder.loadMore(); holder.loadMore()
        assertEquals(rows, holder.state.value.messages)
        assertEquals(listOf(51L to 0L, 51L to 50L, 51L to 100L), reads)
        assertFalse(holder.state.value.hasMore)
        holder.loadMore()
        assertEquals(3, reads.size)
    }

    @Test fun loadingFailureAndRetryAreExplicitAndKeepPreviouslyReadableRows() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val data = FakeData().apply { unprovedRead = { _, _, _ -> gate.await(); listOf(row(1)) } }
        val holder = UnresolvedMessagesStateHolder(radio, data, diagnostics)
        val loading = launch { holder.refresh() }
        kotlinx.coroutines.yield()
        assertTrue(holder.state.value.isLoading)
        gate.complete(Unit); loading.join()
        val failure = IllegalStateException("controlled disk read")
        data.unprovedRead = { _, _, _ -> throw failure }
        holder.refresh()
        assertTrue(holder.state.value.loadFailed)
        assertEquals(listOf(row(1)), holder.state.value.messages)
        assertEquals(listOf(failure), failures)
        data.unprovedRead = { _, _, _ -> emptyList() }
        holder.refresh()
        assertTrue(holder.state.value.messages.isEmpty())
        assertFalse(holder.state.value.loadFailed)
    }

    @Test fun cancelledReadPropagatesWithoutErrorOrFabricatedEmptySuccess() = runBlocking {
        val data = FakeData().apply { unprovedRead = { _, _, _ -> throw CancellationException("screen left") } }
        val holder = UnresolvedMessagesStateHolder(radio, data, diagnostics)
        assertFailsWith<CancellationException> { holder.refresh() }
        assertFalse(holder.state.value.isLoading)
        assertFalse(holder.state.value.loadFailed)
        assertTrue(failures.isEmpty())
    }

    @Test fun radioSwitchUsesFreshHolderAndLateOldReadCannotPopulateIt() = runBlocking {
        val other = RadioId(UUID(0, 307))
        val gate = CompletableDeferred<Unit>()
        val data = FakeData().apply {
            unprovedRead = { selected, _, _ ->
                if (selected == radio) gate.await()
                listOf(row(1, selected))
            }
        }
        val first = UnresolvedMessagesStateHolder(radio, data, diagnostics)
        val pending = launch { first.refresh() }
        kotlinx.coroutines.yield()
        val next = UnresolvedMessagesStateHolder(other, data, diagnostics)
        assertTrue(next.state.value.messages.isEmpty())
        next.refresh()
        gate.complete(Unit); pending.join()
        assertEquals(listOf(other), next.state.value.messages.map { it.radioId })
        assertEquals(other, next.state.value.radioId)
    }

    @Test fun foreignRadioReadIsReportedNotDisplayed() = runBlocking {
        val data = FakeData().apply { unprovedRead = { _, _, _ -> listOf(row(1, RadioId(UUID(0, 999)))) } }
        val holder = UnresolvedMessagesStateHolder(radio, data, diagnostics)
        holder.refresh()
        assertTrue(holder.state.value.loadFailed)
        assertTrue(holder.state.value.messages.isEmpty())
        assertIs<IllegalStateException>(failures.single())
    }
}
