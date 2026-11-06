// PortedFrom: MeshCore/Sources/MeshCore/Protocols/AdvertisingSessionOps.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/ChannelSessionOps.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/ConfigurationSessionOps.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/ContactSessionOps.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/DiagnosticsSessionOps.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/MeshCoreSessionProtocol.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/MessageFetchSessionOps.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/MessagingSessionOps.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/RemoteAccessSessionOps.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocols/SessionEventStreaming.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.*
import com.meshcoreone.android.core.protocol.model.*
import java.time.Instant
import kotlinx.coroutines.flow.Flow

interface SessionEventStreaming {
    val connectionState: Flow<ConnectionState>
    fun events(): Flow<MeshEvent>
    fun events(filter: EventFilter): Flow<MeshEvent>
    suspend fun waitForEvent(filter: EventFilter, timeout: Double? = null): MeshEvent?
}

/** Optional nonactivated bounded custody; terminal uncertainty is not a lossless/replay guarantee. */
interface CriticalSessionEventStreaming {
    fun criticalEvents(kind: CriticalEventKind): CriticalEventSubscription
}

interface MessagingSessionOps {
    val currentSelfInfo: SelfInfo?
    suspend fun sendMessage(
        destination: Bytes, text: String, timestamp: Instant = Instant.now(), attempt: UByte = 0u,
    ): MessageSentInfo
    suspend fun sendChannelMessage(channel: UByte, text: String, timestamp: Instant = Instant.now())
    /** Unsupported implementations preserve the old call and leave cancellation accounting UNKNOWN. */
    suspend fun sendMessage(
        destination: Bytes, text: String, timestamp: Instant = Instant.now(), attempt: UByte = 0u,
        dispatch: MessageWriteDispatch,
    ): MessageSentInfo {
        dispatch.claim()
        return sendMessage(destination, text, timestamp, attempt)
    }
    /** Use a fresh handle per attempt; a returned OK is not end-to-end message delivery. */
    suspend fun sendChannelMessage(
        channel: UByte, text: String, timestamp: Instant = Instant.now(), dispatch: MessageWriteDispatch,
    ) {
        dispatch.claim()
        sendChannelMessage(channel, text, timestamp)
    }
}

interface ContactSessionOps {
    suspend fun getContacts(since: Instant? = null): List<MeshContact>
    /** A terminal partial fetch retains its reported total but cannot establish a complete cache baseline. */
    suspend fun getContactsReportingTotal(since: Instant? = null): ContactFetchResult
    /** Only NOT_FOUND returns null; other device errors propagate with their raw code. */
    suspend fun getContact(publicKey: Bytes): MeshContact?
    suspend fun addContact(contact: MeshContact)
    /**
     * Completes an accepted write before releasing its response owner. The completion must not
     * issue another session request. Legacy implementations cannot prove cancellation was unsent.
     */
    suspend fun addContact(contact: MeshContact, dispatch: ContactWriteDispatch, onCommitted: suspend () -> Unit) {
        dispatch.exchangeDispatch.claim()
        try {
            dispatch.beforeDispatch()
            try {
                addContact(contact)
                dispatch.markAccepted()
            } catch (rejected: MeshCoreException.DeviceError) {
                dispatch.markRejected(rejected)
                throw rejected
            } catch (invalid: MeshCoreException.InvalidInput) {
                dispatch.markRejected(invalid)
                throw invalid
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                dispatch.markUncertain(failure)
                throw failure
            }
            onCommitted()
        } finally {
            dispatch.exchangeDispatch.exchangeFinished()
        }
    }
    suspend fun removeContact(publicKey: Bytes)
    /** Legacy roles cannot prove a failed removal was unsent. */
    suspend fun removeContact(publicKey: Bytes, dispatch: ChannelWriteDispatch) {
        dispatch.claim()
        removeContact(publicKey)
    }
    suspend fun resetPath(publicKey: Bytes)
    suspend fun sendPathDiscovery(destination: Bytes): MessageSentInfo
    suspend fun shareContact(publicKey: Bytes)
    /** A requested key must identify the leading key in the exported card, not an embedded payload value. */
    suspend fun exportContact(publicKey: Bytes? = null): String
    suspend fun importContact(cardData: Bytes)
    suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags)
}

/** Android-only cancellation fence for a contact write, using the existing serialized-write accounting. */
class ContactWriteDispatch(
    beforeDispatchAction: (ContactWriteDispatch) -> Unit = {},
    onExchangeFinishedAction: (ContactWriteDispatch) -> Unit = {},
    onOutcomeChangedAction: (ContactWriteDispatch) -> Unit = {},
) {
    private val beforeDispatchCallback = java.util.concurrent.atomic.AtomicReference<((ContactWriteDispatch) -> Unit)?>(beforeDispatchAction)
    private val onExchangeFinishedCallback = java.util.concurrent.atomic.AtomicReference<((ContactWriteDispatch) -> Unit)?>(onExchangeFinishedAction)
    private val onOutcomeChangedCallback = java.util.concurrent.atomic.AtomicReference<((ContactWriteDispatch) -> Unit)?>(onOutcomeChangedAction)
    internal val exchangeDispatch = ChannelWriteDispatch {
        beforeDispatchCallback.set(null)
        onOutcomeChangedCallback.set(null)
        onExchangeFinishedCallback.getAndSet(null)?.invoke(this)
    }

    private val terminalOutcome = java.util.concurrent.atomic.AtomicReference<ContactWriteDispatchOutcome?>(null)
    private val failure = java.util.concurrent.atomic.AtomicReference<Exception?>(null)

    /** Read after cancellation; false means unknown or potentially written, never rolled back. */
    val definitelyUnsent: Boolean
        get() = outcome == ContactWriteDispatchOutcome.DEFINITELY_UNSENT

    val cancellationDisposition: ChannelWriteCancellationDisposition
        get() = exchangeDispatch.cancellationDisposition

    val terminalFailure: Exception? get() = failure.get()

    val outcome: ContactWriteDispatchOutcome
        get() {
            val terminal = terminalOutcome.get()
            val disposition = exchangeDispatch.cancellationDisposition
            if (terminal == ContactWriteDispatchOutcome.MAY_HAVE_WRITTEN &&
                disposition == ChannelWriteCancellationDisposition.DEFINITELY_UNSENT) {
                return ContactWriteDispatchOutcome.DEFINITELY_UNSENT
            }
            return terminal ?: when (disposition) {
                ChannelWriteCancellationDisposition.UNKNOWN -> ContactWriteDispatchOutcome.UNKNOWN
                ChannelWriteCancellationDisposition.DEFINITELY_UNSENT -> ContactWriteDispatchOutcome.DEFINITELY_UNSENT
                ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN -> ContactWriteDispatchOutcome.MAY_HAVE_WRITTEN
            }
        }

    internal fun beforeDispatch() {
        try {
            beforeDispatchCallback.get()?.invoke(this)
        } catch (failure: Exception) {
            markDefinitelyUnsent(failure)
            throw failure
        }
    }

    internal fun markAccepted() {
        terminalOutcome.set(ContactWriteDispatchOutcome.ACCEPTED)
        onOutcomeChangedCallback.get()?.invoke(this)
    }

    internal fun markRejected(failure: Exception) {
        this.failure.set(failure)
        terminalOutcome.set(ContactWriteDispatchOutcome.REJECTED)
        onOutcomeChangedCallback.get()?.invoke(this)
    }

    internal fun markUncertain(failure: Exception) {
        this.failure.set(failure)
        if (terminalOutcome.compareAndSet(null, ContactWriteDispatchOutcome.MAY_HAVE_WRITTEN)) {
            onOutcomeChangedCallback.get()?.invoke(this)
        }
    }

    private fun markDefinitelyUnsent(failure: Exception) {
        this.failure.set(failure)
        terminalOutcome.set(ContactWriteDispatchOutcome.DEFINITELY_UNSENT)
        onOutcomeChangedCallback.get()?.invoke(this)
    }
}

enum class ContactWriteDispatchOutcome {
    UNKNOWN,
    DEFINITELY_UNSENT,
    MAY_HAVE_WRITTEN,
    ACCEPTED,
    REJECTED,
}

interface ChannelSessionOps {
    /**
     * An unanswered earlier read of this index fails with ConnectionLost/UnresolvedReply before sending.
     * Recover through the connection owner with a real disconnect/reconnect, not a same-link logical restart.
     */
    suspend fun getChannel(index: UByte): ChannelInfo
    /** Missing sent indices remain uncorrelated; preserve unread slots and reconnect before retrying them. */
    suspend fun getChannels(indices: List<UByte>): ChannelFetchResult
    suspend fun setChannel(index: UByte, name: String, secret: Bytes)
    /** Alternate implementations without a dispatch fence conservatively leave the handle UNKNOWN. */
    suspend fun setChannel(index: UByte, name: String, secret: Bytes, dispatch: ChannelWriteDispatch) {
        dispatch.claim()
        setChannel(index, name, secret)
    }
}

interface MessageFetchSessionOps {
    suspend fun getMessage(timeout: Double? = null): MessageResult
    suspend fun startAutoMessageFetching()
    suspend fun stopAutoMessageFetching()
}

interface MeshCoreSessionProtocol :
    SessionEventStreaming, MessagingSessionOps, ContactSessionOps, ChannelSessionOps, MessageFetchSessionOps

interface AdvertisingSessionOps {
    suspend fun sendAdvertisement(flood: Boolean = false)
    suspend fun setName(name: String)
    suspend fun setCoordinates(latitude: Double, longitude: Double)
    suspend fun getContact(publicKey: Bytes): MeshContact?
}

interface ConfigurationSessionOps {
    suspend fun sendAppStart(): SelfInfo
    suspend fun queryDevice(): DeviceCapabilities
    suspend fun getBattery(): BatteryInfo
    suspend fun getTime(): Instant
    suspend fun setTime(date: Instant)
    suspend fun setName(name: String)
    suspend fun setCoordinates(latitude: Double, longitude: Double)
    suspend fun setTxPower(power: Byte)
    suspend fun setRadio(
        frequency: Double, bandwidth: Double, spreadingFactor: UByte, codingRate: UByte,
        clientRepeat: Boolean? = null,
    )
    suspend fun getRepeatFreq(): List<FrequencyRange>
    suspend fun setOtherParams(
        manualAddContacts: Boolean, telemetryModeEnvironment: UByte, telemetryModeLocation: UByte,
        telemetryModeBase: UByte, advertisementLocationPolicy: UByte, multiAcks: UByte? = null,
    )
    suspend fun setDevicePin(pin: UInt)
    suspend fun getAutoAddConfig(): AutoAddConfig
    suspend fun setAutoAddConfig(config: AutoAddConfig)
    suspend fun setPathHashMode(mode: UByte)
    suspend fun setDefaultFloodScope(name: String, scope: FloodScope)
    suspend fun getDefaultFloodScope(): DefaultFloodScope?
    suspend fun reboot()
    suspend fun factoryReset()
    suspend fun getStatsCore(): CoreStats
    suspend fun getStatsRadio(): RadioStats
    suspend fun getStatsPackets(): PacketStats
    suspend fun getCustomVars(): Map<String, String>
    suspend fun setCustomVar(key: String, value: String)
    suspend fun exportPrivateKey(): Bytes
    suspend fun importPrivateKey(key: Bytes)
    suspend fun importPrivateKey(key: Bytes, onCommitted: () -> Unit) {
        importPrivateKey(key)
        onCommitted()
    }
    suspend fun sign(data: Bytes, chunkSize: Long = 120, timeout: Double? = null): Bytes
}

interface DiagnosticsSessionOps {
    suspend fun requestStatus(publicKey: Bytes): StatusResponse
    suspend fun requestStatus(publicKey: Bytes, type: ContactType): StatusResponse
    suspend fun requestTelemetry(publicKey: Bytes): TelemetryResponse
    suspend fun requestNeighbours(
        publicKey: Bytes, count: UByte = 255u, offset: UShort = 0u, orderBy: UByte = 0u,
        pubkeyPrefixLength: UByte = 4u,
    ): NeighboursResponse
    suspend fun fetchAllNeighbours(
        publicKey: Bytes, orderBy: UByte = 0u, pubkeyPrefixLength: UByte = 4u,
    ): NeighboursResponse
    suspend fun requestMMA(publicKey: Bytes, start: Instant, end: Instant): MMAResponse
    suspend fun requestACL(publicKey: Bytes): ACLResponse
    suspend fun getSelfTelemetry(): TelemetryResponse
    suspend fun sendPathDiscovery(destination: Bytes): MessageSentInfo
    suspend fun sendTrace(tag: UInt? = null, authCode: UInt? = null, flags: UByte = 0u, path: Bytes? = null): MessageSentInfo
}

interface RemoteAccessSessionOps {
    suspend fun sendLogin(destination: Bytes, password: String): MessageSentInfo
    suspend fun sendLogout(destination: Bytes)
    suspend fun sendCommand(destination: Bytes, command: String, timestamp: Instant = Instant.now()): MessageSentInfo
    suspend fun sendKeepAlive(publicKey: Bytes, syncSince: UInt): MessageSentInfo
    suspend fun requestOwnerInfo(publicKey: Bytes): OwnerInfoResponse
    suspend fun requestStatus(publicKey: Bytes, type: ContactType): StatusResponse
    suspend fun requestTelemetry(publicKey: Bytes): TelemetryResponse
    suspend fun requestNeighbours(
        publicKey: Bytes, count: UByte, offset: UShort, orderBy: UByte, pubkeyPrefixLength: UByte,
    ): NeighboursResponse
    suspend fun getMessage(timeout: Double?): MessageResult
    suspend fun sendMessageWithRetry(
        destination: Bytes, text: String, timestamp: Instant = Instant.now(),
        maxAttempts: Long = 3, floodAfter: Long = 2, maxFloodAttempts: Long = 2, timeout: Double? = null,
    ): MessageSentInfo?
    suspend fun sendPathDiscovery(destination: Bytes): MessageSentInfo
}
