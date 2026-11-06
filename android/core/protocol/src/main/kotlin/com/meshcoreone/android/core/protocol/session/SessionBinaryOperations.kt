// PortedFrom: MeshCore/Sources/MeshCore/Session/MeshCoreSession+BinaryProtocol.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Session/MeshCoreSession+Regions.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.*
import com.meshcoreone.android.core.protocol.model.AnonRequestType
import com.meshcoreone.android.core.protocol.model.BinaryRequestType
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.parser.*
import java.security.SecureRandom
import java.time.Instant
import kotlinx.coroutines.*

internal class BinaryRequestContext(
    val generation: Long,
    val publicKey: Bytes,
    val operation: String,
    val statusLayout: StatusResponse.Layout? = null,
    val neighbourPrefixLength: Int? = null,
) {
    var latestTag: Bytes? = null
    val tags = mutableSetOf<Bytes>()
    @Volatile var sends = 0L
    @Volatile var messageSentReplies = 0L
}

private val sessionRandom = SecureRandom()
internal fun randomNonzeroTag(): UInt = generateSequence { sessionRandom.nextInt().toUInt() }.first { it != 0u }

internal suspend fun <T> ExchangeOwner.binary(
    request: Bytes,
    requestContext: BinaryRequestContext,
    routedFamily: String? = null,
    matchRouted: ((MeshEvent) -> T?)? = null,
    parse: (Bytes, BinaryRequestContext) -> T?,
): T {
    core.checkCorrelation(generation, "messageSent", acceptsErrors = true)
    if (routedFamily != null) core.checkCorrelation(generation, routedFamily, acceptsErrors = false)
    val subscription = core.register(generation)
    val firstMessageSent = CompletableDeferred<Unit>()
    var cadence = core.configuration.binaryRequestRetransmitInterval ?: 0.0
    var rejectedBeforeMessageSent = false
    try {
        return core.clock.withDeadline(core.configuration.binaryRequestOverallTimeout) {
            coroutineScope {
                send(request, onWriteAttempt = { requestContext.sends += 1 })
                val retransmit = if (core.configuration.binaryRequestRetransmitInterval == null) null else launch {
                    firstMessageSent.await()
                    while (isActive) {
                        val interval = core.withLock { cadence }
                        core.clock.sleepFor(timeoutDuration(interval))
                        ensureActive()
                        // A failed resend does not erase an earlier answerable attempt.
                        supervisorScope {
                            val attempt = async { send(request, cleanup = true, onWriteAttempt = { requestContext.sends += 1 }) }
                            val completed = CompletableDeferred<Throwable?>()
                            attempt.invokeOnCompletion { completed.complete(it) }
                            val failure = completed.await()
                            if (failure is CancellationException) throw failure
                            if (failure != null) {
                                core.diagnostic(SessionDiagnostic.BackgroundFailure(generation.number, "binary-retransmit", failure))
                                core.requireCurrent(generation)
                            }
                        }
                    }
                }
                try {
                    for (event in subscription.channel) {
                        when (event) {
                            is MeshEvent.MessageSent -> {
                                if (requestContext.messageSentReplies >= requestContext.sends) continue
                                val tag = event.info.expectedAck
                                if (tag in generation.retiredBinaryTags) {
                                    core.unresolved(generation, "messageSent", acceptsErrors = true)
                                    throw MeshCoreException.ConnectionLost(SessionCorrelationException.ReusedTag(generation.number, tag))
                                }
                                requestContext.messageSentReplies += 1
                                requestContext.latestTag = tag
                                requestContext.tags += tag
                                core.withLock {
                                    cadence = maxOf(cadence, event.info.suggestedTimeoutMs.toDouble() /
                                        SessionConfiguration.MILLISECONDS_PER_SECOND * SessionConfiguration.BINARY_RETRANSMIT_RTT_HEADROOM)
                                }
                                firstMessageSent.complete(Unit)
                            }
                            is MeshEvent.Error -> {
                                val failure = MeshCoreException.DeviceError(event.code ?: 0u)
                                if (requestContext.latestTag == null) {
                                    rejectedBeforeMessageSent = true
                                    throw failure
                                }
                                core.diagnostic(SessionDiagnostic.BackgroundFailure(generation.number, "binary-device-error-after-send", failure))
                            }
                            is MeshEvent.BinaryResponse -> {
                                if (event.tag != requestContext.latestTag) continue
                                return@coroutineScope parse(event.data, requestContext)
                                    ?: throw MeshCoreException.ParseError("${requestContext.operation} binary response unparseable (${event.data.size} bytes)")
                            }
                            else -> matchRouted?.invoke(event)?.let { return@coroutineScope it }
                        }
                    }
                    throw generation.failure ?: MeshCoreException.ConnectionLost()
                } finally {
                    retransmit?.cancelAndJoin()
                }
            }
        }
    } catch (failure: MeshCoreException.Timeout) {
        if (routedFamily != null) core.unresolved(generation, routedFamily, acceptsErrors = false)
        throw failure
    } finally {
        core.withLock { generation.retiredBinaryTags += requestContext.tags }
        if (!rejectedBeforeMessageSent && requestContext.messageSentReplies < requestContext.sends && core.isCurrent(generation)) {
            core.unresolved(generation, "messageSent", acceptsErrors = true)
        }
        core.unregister(generation, subscription)
    }
}

internal suspend fun SessionCore.requestStatus(publicKey: Bytes, type: ContactType): StatusResponse {
    requireFullPublicKey(publicKey, "requestStatus")
    val layout = if (type == ContactType.ROOM) StatusResponse.Layout.ROOM_SERVER else StatusResponse.Layout.REPEATER
    return exchange {
        val prefix = publicKey.prefix(6)
        val request = BinaryRequestContext(generation.number, publicKey, "Status", statusLayout = layout)
        binary(PacketBuilder.sendStatusRequest(publicKey), request, "status:${prefix.hexString}", { event ->
            val response = (event as? MeshEvent.StatusResponse)?.response?.takeIf { it.publicKeyPrefix == prefix }
            if (response != null && layout == StatusResponse.Layout.ROOM_SERVER && response.layout == StatusResponse.Layout.REPEATER) {
                response.copy(
                    layout = layout, rxAirtime = 0u, receiveErrors = 0u,
                    roomServerPostedCount = response.rxAirtime.toUShort(),
                    roomServerPostPushCount = (response.rxAirtime shr 16).toUShort(),
                )
            } else response
        }) { payload, context ->
            Parsers.StatusResponse.parseFromBinaryResponse(payload, context.publicKey.prefix(6), checkNotNull(context.statusLayout))
        }
    }
}

internal suspend fun SessionCore.requestTelemetry(publicKey: Bytes): TelemetryResponse {
    requireFullPublicKey(publicKey, "requestTelemetry")
    return exchange {
        val prefix = publicKey.prefix(6)
        binary(
            PacketBuilder.getSelfTelemetry(publicKey), BinaryRequestContext(generation.number, publicKey, "Telemetry"),
            "telemetry:${prefix.hexString}",
            { (it as? MeshEvent.TelemetryResponse)?.response?.takeIf { response -> response.publicKeyPrefix == prefix } },
        ) { payload, context -> Parsers.TelemetryResponse.parseFromBinaryResponse(payload, context.publicKey.prefix(6)) }
    }
}

internal suspend fun SessionCore.requestOwnerInfo(publicKey: Bytes): OwnerInfoResponse {
    requireFullPublicKey(publicKey, "requestOwnerInfo")
    return exchange {
        binary(PacketBuilder.binaryRequest(publicKey, BinaryRequestType.OWNER_INFO),
            BinaryRequestContext(generation.number, publicKey, "Owner info")) { payload, _ ->
            val text = payload.exactUtf8OrEmpty("Owner info")
            val components = text.split('\n', limit = 3)
            OwnerInfoResponse(components[0], components.getOrElse(1) { "" }, components.getOrElse(2) { "" })
        }
    }
}

private fun ExchangeOwner.reportPartial(operation: String, diagnostic: BinaryParseDiagnostic) {
    core.diagnostic(SessionDiagnostic.BackgroundFailure(generation.number, operation, BinaryParseException(diagnostic)))
}

internal suspend fun SessionCore.requestMMA(publicKey: Bytes, start: Instant, end: Instant): MMAResponse {
    requireFullPublicKey(publicKey, "requestMMA")
    val payload = ByteWriter().appendUInt32LE(PacketBuilder.epochSeconds32(start))
        .appendUInt32LE(PacketBuilder.epochSeconds32(end)).append(Bytes.of(0, 0)).toBytes()
    return exchange {
        binary(PacketBuilder.binaryRequest(publicKey, BinaryRequestType.MMA, payload),
            BinaryRequestContext(generation.number, publicKey, "MMA")) { data, context ->
            val decoded = MMAParser.decode(data)
            if (decoded is BinaryListDecodeResult.Incomplete) {
                reportPartial("MMA", decoded.diagnostic)
                if (decoded.entries.isEmpty()) throw MeshCoreException.ParseError("MMA response contains no complete record")
            }
            MMAResponse(context.publicKey.prefix(6), checkNotNull(context.latestTag), decoded.entries)
        }
    }
}

internal suspend fun SessionCore.requestACL(publicKey: Bytes): ACLResponse {
    requireFullPublicKey(publicKey, "requestACL")
    return exchange {
        binary(PacketBuilder.binaryRequest(publicKey, BinaryRequestType.ACL, Bytes.of(0, 0)),
            BinaryRequestContext(generation.number, publicKey, "ACL")) { data, context ->
            val decoded = ACLParser.decode(data)
            if (decoded is BinaryListDecodeResult.Incomplete) {
                reportPartial("ACL", decoded.diagnostic)
                if (decoded.entries.isEmpty()) throw MeshCoreException.ParseError("ACL response contains no complete record")
            }
            ACLResponse(context.publicKey.prefix(6), checkNotNull(context.latestTag), decoded.entries)
        }
    }
}

internal suspend fun ExchangeOwner.neighbours(
    publicKey: Bytes, count: UByte, offset: UShort, orderBy: UByte, pubkeyPrefixLength: UByte,
    randomTag: () -> UInt,
): NeighboursResponse {
    val random = randomTag()
    if (random == 0u) throw MeshCoreException.InvalidInput("Generated neighbour tag must be nonzero")
    val payload = ByteWriter().appendUInt8(0u).appendUInt8(count).appendUInt16LE(offset)
        .appendUInt8(orderBy).appendUInt8(pubkeyPrefixLength).appendUInt32LE(random).toBytes()
    return binary(PacketBuilder.binaryRequest(publicKey, BinaryRequestType.NEIGHBOURS, payload),
        BinaryRequestContext(generation.number, publicKey, "Neighbours", neighbourPrefixLength = pubkeyPrefixLength.toInt())) { data, context ->
        val decoded = NeighboursParser.decode(
            data, context.publicKey.prefix(6), checkNotNull(context.latestTag), checkNotNull(context.neighbourPrefixLength),
        )
        if (decoded.diagnostic != null) {
            reportPartial("Neighbours", decoded.diagnostic)
            if (decoded.bytesConsumed == 0) throw MeshCoreException.ParseError("Neighbours response has no complete header")
        }
        decoded.response
    }
}

internal suspend fun SessionCore.requestNeighbours(
    publicKey: Bytes, count: UByte, offset: UShort, orderBy: UByte, pubkeyPrefixLength: UByte,
    randomTag: () -> UInt,
): NeighboursResponse {
    requireFullPublicKey(publicKey, "requestNeighbours")
    return exchange { neighbours(publicKey, count, offset, orderBy, pubkeyPrefixLength, randomTag) }
}

internal suspend fun SessionCore.fetchAllNeighbours(
    publicKey: Bytes, orderBy: UByte, pubkeyPrefixLength: UByte, randomTag: () -> UInt,
): NeighboursResponse {
    requireFullPublicKey(publicKey, "fetchAllNeighbours")
    return exchange {
        NeighboursResponse.collectingAllPages { offset ->
            if (offset > 0u) clock.sleepFor(NeighboursResponse.INTER_PAGE_DELAY)
            neighbours(publicKey, 255u, offset, orderBy, pubkeyPrefixLength, randomTag)
        }
    }
}

internal suspend fun SessionCore.requestRegions(contact: MeshContact): List<String> {
    requireFullPublicKey(contact.publicKey, "requestRegions")
    validateContactPath(contact.outPathLength, contact.outPath)
    return exchange {
        val flood = contact.isFloodPath
        var routeChanged = false
        var primaryFailure: Exception? = null
        try {
            if (flood) {
                simple(PacketBuilder.updateContact(contact.copy(outPathLength = 0u, outPath = Bytes.EMPTY)))
                routeChanged = true
            }
            binary(
                PacketBuilder.sendAnonReq(contact.publicKey, AnonRequestType.REGIONS,
                    if (flood) 0u else contact.outPathLength, if (flood) Bytes.EMPTY else contact.outPath),
                BinaryRequestContext(generation.number, contact.publicKey, "Regions"),
            ) { data, _ -> RegionsParser.parse(data) }
        } catch (failure: Exception) {
            primaryFailure = failure
            throw failure
        } finally {
            if (routeChanged) {
                try {
                    rollbackSimple(PacketBuilder.resetPath(contact.publicKey))
                } catch (restoreFailure: Exception) {
                    core.diagnostic(SessionDiagnostic.RestoreFailure(generation.number, restoreFailure))
                    if (primaryFailure == null) throw restoreFailure
                    primaryFailure.addSuppressed(restoreFailure)
                }
            }
        }
    }
}
