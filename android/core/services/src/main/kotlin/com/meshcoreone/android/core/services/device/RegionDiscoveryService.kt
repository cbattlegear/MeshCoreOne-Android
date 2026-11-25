// PortedFrom: MC1Services/Sources/MC1Services/Services/RegionDiscoveryService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/ContactService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.DiscoveredNodePersisting
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.DiscoverResponse
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.MeshTransportError
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.ErrorCode
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.session.SessionCorrelationException
import java.text.Normalizer
import java.time.Instant
import java.util.logging.Logger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RegionDiscoveryService(
    private val session: MeshCoreSession,
    private val contacts: ContactPersisting,
    private val discoveredNodes: DiscoveredNodePersisting?,
    private val context: DeviceSettingsContext,
    private val clock: SessionClock,
) {
    sealed interface Failure {
        data class Protocol(val cause: MeshCoreException) : Failure
        data class Transport(val cause: MeshTransportError) : Failure
        data class Correlation(val cause: SessionCorrelationException) : Failure
        data class Storage(val cause: PersistenceStoreException) : Failure
    }

    sealed interface Outcome {
        data class SendFailed(val failure: Failure) : Outcome
        data object NoRepeatersResponded : Outcome
        data class ErrorLoadingRepeaters(val failure: Failure.Storage) : Outcome
        data class Completed(
            val newRegions: SnapshotList<String>,
            val allRepeatersTableFull: Boolean,
            val queryFailures: SnapshotList<Failure>,
        ) : Outcome {
            val isPartial: Boolean get() = queryFailures.isNotEmpty()
        }
    }

    private sealed interface QueryOutcome {
        data class Regions(val names: List<String>) : QueryOutcome
        data object TableFull : QueryOutcome
        data class Failed(val failure: Failure) : QueryOutcome
    }

    private val discoveryLock = Mutex()

    suspend fun discover(knownRegions: List<String>, supportsAdHocRequest: Boolean): Outcome = context.operation {
        discoveryLock.withLock {
            val known = knownRegions.map(::canonicalRegionName).toSet()
            val responders = try {
                listenForResponders()
            } catch (failure: MeshCoreException) {
                return@withLock Outcome.SendFailed(Failure.Protocol(failure))
            } catch (failure: MeshTransportError) {
                return@withLock Outcome.SendFailed(Failure.Transport(failure))
            } catch (failure: SessionCorrelationException) {
                return@withLock Outcome.SendFailed(Failure.Correlation(failure))
            }
            if (responders.isEmpty()) return@withLock Outcome.NoRepeatersResponded
            val targets = try {
                val contactRows = contacts.fetchContacts(context.token.radioId)
                context.requireCurrent()
                val nodeRows = discoveredNodes?.fetchDiscoveredNodes(context.token.radioId) ?: emptyList()
                context.requireCurrent()
                buildRegionQueryTargets(responders, contactRows, nodeRows, supportsAdHocRequest)
            } catch (failure: PersistenceStoreException) {
                return@withLock Outcome.ErrorLoadingRepeaters(Failure.Storage(failure))
            }
            if (targets.isEmpty()) return@withLock Outcome.NoRepeatersResponded
            val results = coroutineScope { targets.map { target -> async { query(target) } }.awaitAll() }
            // Compare canonically, but retain the advertised text for byte-sensitive flood-scope operations.
            val regions = results.filterIsInstance<QueryOutcome.Regions>().flatMap { it.names }
                .distinctBy(::canonicalRegionName)
                .filterNot { canonicalRegionName(it) in known }
                .sortedWith(::compareCanonicalRegions)
            val failures = results.filterIsInstance<QueryOutcome.Failed>().map { it.failure }.snapshot()
            if (failures.isNotEmpty()) Logger.getLogger("MeshCore.RegionDiscovery").warning("Region queries completed with typed failures")
            Outcome.Completed(
                regions.snapshot(),
                results.any { it == QueryOutcome.TableFull },
                failures,
            )
        }
    }

    private fun canonicalRegionName(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC)

    private fun compareCanonicalRegions(left: String, right: String): Int {
        val first = canonicalRegionName(left)
        val second = canonicalRegionName(right)
        var firstOffset = 0
        var secondOffset = 0
        while (firstOffset < first.length && secondOffset < second.length) {
            val firstScalar = first.codePointAt(firstOffset)
            val secondScalar = second.codePointAt(secondOffset)
            val comparison = firstScalar.compareTo(secondScalar)
            if (comparison != 0) return comparison
            firstOffset += Character.charCount(firstScalar)
            secondOffset += Character.charCount(secondScalar)
        }
        return (first.length - firstOffset).compareTo(second.length - secondOffset)
    }

    private suspend fun listenForResponders(): Set<Bytes> = coroutineScope {
        val subscription = session.eventsTracked()
        val lock = Any()
        var expectedTag: Bytes? = null
        val pending = mutableSetOf<DiscoverResponse>()
        val keys = mutableSetOf<Bytes>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            subscription.stream.collect { event ->
                if (event is MeshEvent.DiscoverResponse) synchronized(lock) {
                    val tag = expectedTag
                    if (tag == null) pending += event.response
                    else if (event.response.tag == tag) keys += event.response.publicKey
                }
            }
        }
        try {
            val tag = session.sendNodeDiscoverRequest(repeatersFilter, prefixOnly = false)
            val bytes = ByteWriter().appendUInt32LE(tag).toBytes()
            synchronized(lock) {
                expectedTag = bytes
                keys += pending.filter { it.tag == bytes }.map { it.publicKey }
                pending.clear()
            }
            context.requireCurrent()
            clock.sleepFor(listenDuration)
            context.requireCurrent()
            session.finishEvents(subscription.id)
            collector.join()
            synchronized(lock) { keys.toSet() }
        } finally {
            session.finishEvents(subscription.id)
            collector.cancelAndJoin()
        }
    }

    private suspend fun query(target: MeshContact): QueryOutcome {
        context.requireCurrent()
        return try {
            val names = session.requestRegions(target)
            context.requireCurrent()
            QueryOutcome.Regions(names)
        } catch (failure: MeshCoreException.DeviceError) {
            if (failure.code == ErrorCode.TABLE_FULL.rawValue) QueryOutcome.TableFull
            else QueryOutcome.Failed(Failure.Protocol(failure))
        } catch (failure: MeshCoreException) {
            QueryOutcome.Failed(Failure.Protocol(failure))
        } catch (failure: MeshTransportError) {
            QueryOutcome.Failed(Failure.Transport(failure))
        } catch (failure: SessionCorrelationException) {
            QueryOutcome.Failed(Failure.Correlation(failure))
        }
    }

    companion object {
        val repeatersFilter: UByte = 0x04u
        val listenDuration = 15.seconds

        fun buildRegionQueryTargets(
            responders: Set<Bytes>, contacts: List<ContactDTO>, discoveredNodes: List<DiscoveredNodeDTO>,
            supportsAdHocRequest: Boolean,
        ): SnapshotList<MeshContact> {
            val byKey = linkedMapOf<Bytes, MeshContact>()
            for (contact in contacts) {
                if (contact.type == ContactType.REPEATER && contact.publicKey in responders) {
                    byKey[contact.publicKey] = toMeshContact(contact.toContactFrame())
                }
            }
            if (supportsAdHocRequest) for (node in discoveredNodes) {
                if (node.nodeType == ContactType.REPEATER && node.publicKey in responders && node.publicKey !in byKey) {
                    byKey[node.publicKey] = toMeshContact(
                        ContactFrame(
                            node.publicKey, node.nodeType, 0u, node.outPathLength, node.outPath,
                            node.name, node.lastAdvertTimestamp, node.latitude, node.longitude, 0u,
                        ),
                    )
                }
            }
            return byKey.values.snapshot()
        }

        private fun toMeshContact(frame: ContactFrame): MeshContact = MeshContact(
            frame.publicKey.uppercaseHexString(), frame.publicKey, frame.type, ContactFlags(frame.flags),
            frame.outPathLength, frame.outPath, frame.name,
            Instant.ofEpochSecond(frame.lastAdvertTimestamp.toLong()), frame.latitude, frame.longitude,
            Instant.ofEpochSecond(frame.lastModified.toLong()), frame.typeRawValue,
        )
    }
}
