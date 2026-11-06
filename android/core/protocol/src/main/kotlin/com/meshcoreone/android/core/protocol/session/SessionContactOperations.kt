// PortedFrom: MeshCore/Sources/MeshCore/Session/MeshCoreSession+Contacts.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.PathEncoding
import com.meshcoreone.android.core.protocol.model.decodePathLen
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope

internal fun validateContactPath(length: UByte, path: Bytes) {
    if (path.size > PathEncoding.MAX_PATH_BYTES) throw MeshCoreException.InvalidInput("Contact path exceeds 64 bytes")
    if (length == PacketBuilder.FLOOD_PATH_SENTINEL) return
    val decoded = decodePathLen(length) ?: throw MeshCoreException.InvalidInput("Reserved path hash mode")
    if (decoded.byteLength > PathEncoding.MAX_PATH_BYTES || decoded.byteLength != path.size) {
        throw MeshCoreException.InvalidInput("Encoded contact path length does not match the supplied bytes")
    }
}

internal fun legacyContactFrame(
    publicKey: Bytes, typeRawValue: UByte, flags: ContactFlags, outPathLength: UByte,
    outPath: Bytes, advertisedName: String, lastAdvertisement: Instant, latitude: Double, longitude: Double,
): Bytes {
    requireFullPublicKey(publicKey, "updateContact")
    validateContactPath(outPathLength, outPath)
    return ByteWriter().appendUInt8(CommandCode.UPDATE_CONTACT.rawValue).append(publicKey)
        .appendUInt8(typeRawValue).appendUInt8(flags.rawValue).appendUInt8(outPathLength)
        .append(outPath.paddedOrTruncated(64))
        .append(Bytes.utf8(advertisedName).prefix(32).paddedOrTruncated(32))
        .appendUInt32LE(PacketBuilder.epochSeconds32(lastAdvertisement))
        .appendInt32LE(PacketBuilder.scaledCoordinate(latitude, PacketBuilder.LATITUDE_RANGE))
        .appendInt32LE(PacketBuilder.scaledCoordinate(longitude, PacketBuilder.LONGITUDE_RANGE)).toBytes()
}

internal suspend fun SessionCore.fetchContacts(since: Instant?): ContactFetchResult = exchange {
    core.checkCorrelation(generation, "contacts", acceptsErrors = true)
    val subscription = core.register(generation)
    val progress = StreamProgressTracker(clock)
    val contacts = mutableListOf<com.meshcoreone.android.core.protocol.model.MeshContact>()
    var total: Long? = null
    var modified: Instant? = null
    var possiblyWritten = false
    var terminalResponse = false
    var fetchCompleted = false
    val invalidation = generation.contacts.invalidationGeneration
    val incrementalBaseline = since != null && generation.contacts.hasBaseline(since)
    fun snapshot(completed: Boolean) = core.withLock {
        generation.contactProgress = ContactStreamProgress(generation.number, total, contacts.size.toLong(), modified, completed)
    }
    snapshot(false)
    try {
        supervisorScope {
            val consumer = async {
                send(PacketBuilder.getContacts(since), onWriteAttempt = { possiblyWritten = true })
                for (event in subscription.channel) {
                    when (event) {
                        is MeshEvent.ContactsStart -> { total = event.count; progress.markProgress(); snapshot(false) }
                        is MeshEvent.Contact -> { contacts += event.contact; progress.markProgress(); snapshot(false) }
                        is MeshEvent.ContactsEnd -> {
                            modified = event.lastModified
                            terminalResponse = true
                            progress.markProgress()
                            return@async ContactFetchResult(contacts, total)
                        }
                        is MeshEvent.Error -> {
                            terminalResponse = true
                            throw MeshCoreException.DeviceError(event.code ?: 0u)
                        }
                        else -> Unit
                    }
                }
                throw generation.failure ?: MeshCoreException.ConnectionLost()
            }
            val watchdog = async {
                val hard = timeoutDuration(configuration.contactStreamHardTimeout)
                val inactivity = timeoutDuration(configuration.contactStreamInactivityTimeout)
                while (true) {
                    val before = progress.snapshot()
                    if (before.elapsed >= hard) throw MeshCoreException.Timeout()
                    clock.sleepFor(minOf(inactivity, hard - before.elapsed))
                    val after = progress.snapshot()
                    if (after.elapsed >= hard || after.generation == before.generation) throw MeshCoreException.Timeout()
                }
            }
            try {
                val result = select {
                    consumer.onAwait { it }
                    watchdog.onAwait { throw MeshCoreException.Timeout() }
                }
                core.requireCurrent(generation)
                val complete = generation.contacts.commitFetch(
                    checkNotNull(modified), incrementalBaseline || (since == null && total != null && contacts.size.toLong() == total),
                    invalidation,
                )
                snapshot(complete)
                if (!complete) {
                    core.diagnostic(SessionDiagnostic.BackgroundFailure(
                        generation.number, "partial-contact-stream",
                        MeshCoreException.InvalidResponse("complete contactsStart total", "count=${contacts.size}, total=$total"),
                    ))
                }
                fetchCompleted = true
                result
            } finally {
                consumer.cancel()
                watchdog.cancel()
                consumer.cancelAndJoin()
                watchdog.cancelAndJoin()
            }
        }
    } finally {
        if (!fetchCompleted) {
            snapshot(false)
            generation.contacts.invalidateBaseline()
        }
        if (possiblyWritten && !terminalResponse && core.isCurrent(generation)) {
            core.unresolved(generation, "contacts", acceptsErrors = true)
        }
        core.unregister(generation, subscription)
    }
}
