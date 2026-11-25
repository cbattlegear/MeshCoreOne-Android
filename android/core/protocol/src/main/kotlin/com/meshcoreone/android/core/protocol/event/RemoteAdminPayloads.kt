// PortedFrom: MeshCore/Sources/MeshCore/Events/RemoteAdminPayloads.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/Events/NeighboursResponse+Pagination.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.event

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.lpp.LPPDataPoint
import com.meshcoreone.android.core.protocol.lpp.LPPDecodeResult
import com.meshcoreone.android.core.protocol.lpp.LPPDecoder
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class LoginInfo(
    val permissions: UByte,
    val isAdmin: Boolean,
    val publicKeyPrefix: Bytes,
    val serverTime: Instant? = null,
)

data class TelemetryResponse(val publicKeyPrefix: Bytes, val tag: Bytes?, val rawData: Bytes) {
    val decodeResult: LPPDecodeResult by lazy { LPPDecoder.decode(rawData) }
    val dataPoints: List<LPPDataPoint> get() = decodeResult.dataPoints
}

data class MMAResponse(val publicKeyPrefix: Bytes, val tag: Bytes, val data: EventList<MMAEntry>) {
    constructor(publicKeyPrefix: Bytes, tag: Bytes, data: Collection<MMAEntry>) :
        this(publicKeyPrefix, tag, EventList(data))
}

data class MMAEntry(
    val channel: UByte,
    val type: String,
    val min: Double,
    val max: Double,
    val avg: Double,
) {
    private val fields get() = arrayOf(channel, type, min, max, avg)
    override fun equals(other: Any?): Boolean = other is MMAEntry && eventFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = eventFieldsHash(fields)
}

data class ACLResponse(val publicKeyPrefix: Bytes, val tag: Bytes, val entries: EventList<ACLEntry>) {
    constructor(publicKeyPrefix: Bytes, tag: Bytes, entries: Collection<ACLEntry>) :
        this(publicKeyPrefix, tag, EventList(entries))
}

data class ACLEntry(val keyPrefix: Bytes, val permissions: UByte)

data class NeighboursResponse(
    val publicKeyPrefix: Bytes,
    val tag: Bytes,
    val totalCount: Long,
    val neighbours: EventList<Neighbour>,
) {
    constructor(publicKeyPrefix: Bytes, tag: Bytes, totalCount: Long, neighbours: Collection<Neighbour>) :
        this(publicKeyPrefix, tag, totalCount, EventList(neighbours))

    companion object {
        const val MAX_PAGINATION_PAGES = 512
        val INTER_PAGE_DELAY: Duration = 1.seconds

        suspend fun collectingAllPages(fetchPage: suspend (UShort) -> NeighboursResponse): NeighboursResponse {
            val neighbours = mutableListOf<Neighbour>()
            var firstPage: NeighboursResponse? = null
            var totalCount = 0L
            repeat(MAX_PAGINATION_PAGES) {
                currentCoroutineContext().ensureActive()
                val offset = minOf(neighbours.size, UShort.MAX_VALUE.toInt()).toUShort()
                val page = fetchPage(offset)
                val initial = firstPage ?: page.also { firstPage = it }
                totalCount = page.totalCount
                if (page.neighbours.isEmpty()) {
                    return NeighboursResponse(initial.publicKeyPrefix, initial.tag, totalCount, neighbours)
                }
                neighbours += page.neighbours
                if (neighbours.size >= totalCount) {
                    return NeighboursResponse(initial.publicKeyPrefix, initial.tag, totalCount, neighbours)
                }
            }
            val initial = checkNotNull(firstPage) { "A nonzero pagination cap must fetch the first page" }
            return NeighboursResponse(initial.publicKeyPrefix, initial.tag, totalCount, neighbours)
        }
    }
}

data class Neighbour(val publicKeyPrefix: Bytes, val secondsAgo: Long, val snr: Double) {
    private val fields get() = arrayOf(publicKeyPrefix, secondsAgo, snr)
    override fun equals(other: Any?): Boolean = other is Neighbour && eventFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = eventFieldsHash(fields)
}
