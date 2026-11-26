// PortedFrom: MeshCore/Sources/MeshCore/Events/NeighboursResponse+Pagination.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.event

import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class NeighboursPaginationTest {
    private val key = Bytes.of(1)
    private fun neighbour(value: Int): Neighbour = Neighbour(Bytes.of(value), value.toLong(), -1.0)

    @Test
    fun `Pages advance by accumulated rows preserving order first-page metadata and latest total`() = runTest {
        val offsets = mutableListOf<UShort>()
        val result = NeighboursResponse.collectingAllPages { offset ->
            offsets += offset
            when (offset.toInt()) {
                0 -> NeighboursResponse(key, Bytes.of(2), 99, listOf(neighbour(1), neighbour(2)))
                2 -> NeighboursResponse(Bytes.of(9), Bytes.of(8), 3, listOf(neighbour(3)))
                else -> error("Unexpected offset")
            }
        }
        assertEquals(listOf(0u.toUShort(), 2u.toUShort()), offsets)
        assertEquals(key, result.publicKeyPrefix)
        assertEquals(Bytes.of(2), result.tag)
        assertEquals(3L, result.totalCount)
        assertEquals(listOf(neighbour(1), neighbour(2), neighbour(3)), result.neighbours)
    }

    @Test
    fun `Empty first page still retains actual metadata and advertised total`() = runTest {
        var calls = 0
        val result = NeighboursResponse.collectingAllPages {
            calls++
            NeighboursResponse(key, Bytes.of(3), 100, emptyList())
        }
        assertEquals(1, calls)
        assertEquals(key, result.publicKeyPrefix)
        assertEquals(100L, result.totalCount)
        assertEquals(emptyList(), result.neighbours)
    }

    @Test
    fun `An empty later page stops stalled firmware without claiming complete count`() = runTest {
        var calls = 0
        val result = NeighboursResponse.collectingAllPages { offset ->
            calls++
            NeighboursResponse(key, Bytes.EMPTY, 10, if (offset == 0u.toUShort()) listOf(neighbour(1)) else emptyList())
        }
        assertEquals(2, calls)
        assertEquals(10L, result.totalCount)
        assertEquals(listOf(neighbour(1)), result.neighbours)
    }

    @Test
    fun `Pathological changing totals stop at the actual 512-page cap`() = runTest {
        var calls = 0
        val result = NeighboursResponse.collectingAllPages { offset ->
            assertEquals(calls.toUShort(), offset)
            calls++
            NeighboursResponse(key, Bytes.EMPTY, Long.MAX_VALUE, listOf(neighbour(calls % 256)))
        }
        assertEquals(512, calls)
        assertEquals(512, result.neighbours.size)
        assertEquals(Long.MAX_VALUE, result.totalCount)
        assertEquals(1.seconds, NeighboursResponse.INTER_PAGE_DELAY)
    }

    @Test
    fun `Wire offsets saturate at UInt16 maximum without wrapping or hidden row loss`() = runTest {
        val offsets = mutableListOf<UShort>()
        val rows = List(65_536) { neighbour(1) }
        val result = NeighboursResponse.collectingAllPages { offset ->
            offsets += offset
            NeighboursResponse(key, Bytes.EMPTY, 65_537, if (offset == 0u.toUShort()) rows else listOf(neighbour(2)))
        }
        assertEquals(listOf(0u.toUShort(), UShort.MAX_VALUE), offsets)
        assertEquals(65_537, result.neighbours.size)
    }

    @Test
    fun `Fetch failure propagates unchanged without a partial success substitute`() = runTest {
        val failure = IllegalStateException("source fetch failed")
        var calls = 0
        val actual = assertFailsWith<IllegalStateException> {
            NeighboursResponse.collectingAllPages {
                calls++
                if (calls == 2) throw failure
                NeighboursResponse(key, Bytes.EMPTY, 10, listOf(neighbour(1)))
            }
        }
        kotlin.test.assertTrue(actual === failure)
        assertEquals(2, calls)
    }

    @Test
    fun `Cancelling a suspended fetch ends aggregation rather than continuing or fabricating rows`() = runTest {
        var calls = 0
        val task = async {
            NeighboursResponse.collectingAllPages {
                calls++
                awaitCancellation()
            }
        }
        runCurrent()
        task.cancel()
        assertFailsWith<CancellationException> { task.await() }
        assertEquals(1, calls)
    }
}
