// PortedFrom: MeshCore/Sources/MeshCore/Session/MeshCoreSession.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Session/MeshCoreSession+DeviceConfig.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Session/MeshCoreSession+Contacts.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Session/MeshCoreSession+Channels.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Session/MeshCoreSession+Messaging.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Session/MeshCoreSession+EventWaiting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Session/MeshCoreSession+Signing.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.OtherParamsConfig
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.*
import com.meshcoreone.android.core.protocol.model.*
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

class MeshCoreSession(
    transport: MeshTransport,
    configuration: SessionConfiguration = SessionConfiguration.DEFAULT,
    clock: SessionClock = SystemSessionClock(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
    private val randomTag: () -> UInt = ::randomNonzeroTag,
    onDiagnostic: (SessionDiagnostic) -> Unit = ::logSessionDiagnostic,
) : MeshCoreSessionProtocol, ConfigurationSessionOps, AdvertisingSessionOps, DiagnosticsSessionOps, RemoteAccessSessionOps {
    internal val core = SessionCore(transport, configuration, clock, coroutineContext, onDiagnostic)

    override val connectionState: Flow<ConnectionState> get() = core.connectionStates()
    override val currentSelfInfo: SelfInfo? get() = synchronized(core.lock) { core.active?.selfInfo }
    val deviceTime: Instant? get() = synchronized(core.lock) { core.active?.deviceTime }
    val currentDeviceCapabilities: DeviceCapabilities? get() = synchronized(core.lock) { core.active?.capabilities }
    val cachedContacts: List<MeshContact> get() = synchronized(core.lock) { core.active?.contacts?.cachedContacts ?: EventList(emptyList()) }
    val cachedPendingContacts: List<MeshContact> get() = synchronized(core.lock) { core.active?.contacts?.cachedPendingContacts ?: EventList(emptyList()) }
    val isContactsDirty: Boolean get() = synchronized(core.lock) { core.active?.contacts?.needsRefresh ?: true }
    val lastContactFetchProgress: ContactStreamProgress? get() = synchronized(core.lock) { core.active?.contactProgress }

    suspend fun start(reconnectingAttempt: Long? = null, disconnectTransportOnFailure: Boolean = true) =
        core.start(reconnectingAttempt, disconnectTransportOnFailure)
    suspend fun stop(disconnectTransport: Boolean = true) = core.stop(disconnectTransport)
    override fun events(): Flow<MeshEvent> = core.eventsTracked().stream
    override fun events(filter: EventFilter): Flow<MeshEvent> = core.eventsTracked(filter).stream
    fun eventsTracked(): EventSubscription = core.eventsTracked()
    fun finishEvents(id: UUID) = core.finishEvents(id)
    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? =
        core.waitForEvent(filter::matches, timeout)
    suspend fun waitForEvent(matching: (MeshEvent) -> Boolean, timeout: Double? = null): MeshEvent? =
        core.waitForEvent(matching, timeout)
    suspend fun <T> sendAndWait(data: Bytes, timeout: Double? = null, matching: (MeshEvent) -> T?): T =
        core.exchange { query(data, ARBITRARY_RESPONSE_FAMILY, timeout, predicate = matching) }

    override suspend fun sendAppStart(): SelfInfo = core.exchange {
        query(PacketBuilder.appStart(core.configuration.clientIdentifier), "selfInfo") { (it as? MeshEvent.SelfInfo)?.info }
    }
    override suspend fun queryDevice(): DeviceCapabilities = core.exchange {
        query(PacketBuilder.deviceQuery(), "deviceInfo") { (it as? MeshEvent.DeviceInfo)?.info }
    }
    override suspend fun getBattery(): BatteryInfo = core.exchange {
        query(PacketBuilder.getBattery(), "battery") { (it as? MeshEvent.Battery)?.info }
    }
    override suspend fun getTime(): Instant = core.exchange {
        query(PacketBuilder.getTime(), "currentTime") { (it as? MeshEvent.CurrentTime)?.time }
    }
    override suspend fun setTime(date: Instant) = simple(PacketBuilder.setTime(date))
    override suspend fun setName(name: String) = simple(PacketBuilder.setName(name))
    override suspend fun setCoordinates(latitude: Double, longitude: Double) = simple(PacketBuilder.setCoordinates(latitude, longitude))
    override suspend fun setTxPower(power: Byte) = simple(PacketBuilder.setTxPower(power))
    override suspend fun setRadio(
        frequency: Double, bandwidth: Double, spreadingFactor: UByte, codingRate: UByte, clientRepeat: Boolean?,
    ) = simple(PacketBuilder.setRadio(frequency, bandwidth, spreadingFactor, codingRate, clientRepeat))
    override suspend fun getRepeatFreq(): List<FrequencyRange> = core.exchange {
        query(PacketBuilder.getRepeatFreq(), "repeatFreq") { (it as? MeshEvent.AllowedRepeatFreq)?.ranges }
    }
    suspend fun setTuning(rxDelay: UInt, af: UInt) = simple(PacketBuilder.setTuning(rxDelay, af))
    override suspend fun setOtherParams(
        manualAddContacts: Boolean, telemetryModeEnvironment: UByte, telemetryModeLocation: UByte,
        telemetryModeBase: UByte, advertisementLocationPolicy: UByte, multiAcks: UByte?,
    ) = simple(PacketBuilder.setOtherParams(
        manualAddContacts, telemetryModeEnvironment, telemetryModeLocation, telemetryModeBase, advertisementLocationPolicy, multiAcks,
    ))
    override suspend fun setDevicePin(pin: UInt) = simple(PacketBuilder.setDevicePin(pin))
    override suspend fun getAutoAddConfig(): AutoAddConfig = core.exchange {
        query(PacketBuilder.getAutoAddConfig(), "autoAddConfig") { (it as? MeshEvent.AutoAddConfig)?.config }
    }
    override suspend fun setAutoAddConfig(config: AutoAddConfig) = simple(PacketBuilder.setAutoAddConfig(config))
    override suspend fun setPathHashMode(mode: UByte) {
        if (mode > PathEncoding.MAX_PATH_HASH_MODE.toUByte()) throw MeshCoreException.InvalidInput("Path hash mode must be 0, 1, or 2")
        simple(PacketBuilder.setPathHashMode(mode))
    }
    suspend fun setTelemetryModeBase(mode: UByte) = mutateOtherParams { it.copy(telemetryModeBase = (mode.toInt() and 3).toUByte()) }
    suspend fun setTelemetryModeLocation(mode: UByte) = mutateOtherParams { it.copy(telemetryModeLocation = (mode.toInt() and 3).toUByte()) }
    suspend fun setTelemetryModeEnvironment(mode: UByte) = mutateOtherParams { it.copy(telemetryModeEnvironment = (mode.toInt() and 3).toUByte()) }
    suspend fun setManualAddContacts(enabled: Boolean) = mutateOtherParams { it.copy(manualAddContacts = enabled) }
    suspend fun setMultiAcks(count: UByte) = mutateOtherParams { it.copy(multiAcks = count) }
    suspend fun setAdvertisementLocationPolicy(policy: UByte) = mutateOtherParams { it.copy(advertisementLocationPolicy = policy) }

    private suspend fun mutateOtherParams(transform: (OtherParamsConfig) -> OtherParamsConfig) = core.exchange {
        val info = synchronized(core.lock) { generation.selfInfo } ?: query(
            PacketBuilder.appStart(core.configuration.clientIdentifier), "selfInfo",
        ) { (it as? MeshEvent.SelfInfo)?.info }
        val config = transform(OtherParamsConfig(info))
        simple(PacketBuilder.setOtherParams(
            config.manualAddContacts, config.telemetryModeEnvironment, config.telemetryModeLocation,
            config.telemetryModeBase, config.advertisementLocationPolicy, config.multiAcks,
        ))
        query(PacketBuilder.appStart(core.configuration.clientIdentifier), "selfInfo", cleanup = true) { (it as? MeshEvent.SelfInfo)?.info }
        Unit
    }

    override suspend fun reboot() = core.exchange { send(PacketBuilder.reboot()) }
    override suspend fun factoryReset() = simple(PacketBuilder.factoryReset())
    override suspend fun getStatsCore(): CoreStats = core.exchange {
        query(PacketBuilder.getStatsCore(), "statsCore") { (it as? MeshEvent.StatsCore)?.stats }
    }
    override suspend fun getStatsRadio(): RadioStats = core.exchange {
        query(PacketBuilder.getStatsRadio(), "statsRadio") { (it as? MeshEvent.StatsRadio)?.stats }
    }
    override suspend fun getStatsPackets(): PacketStats = core.exchange {
        query(PacketBuilder.getStatsPackets(), "statsPackets") { (it as? MeshEvent.StatsPackets)?.stats }
    }
    override suspend fun getCustomVars(): Map<String, String> = core.exchange {
        query(PacketBuilder.getCustomVars(), "customVars", errorMatcher = ::deviceError) { (it as? MeshEvent.CustomVars)?.values }
    }
    override suspend fun setCustomVar(key: String, value: String) = simple(PacketBuilder.setCustomVar(key, value))
    override suspend fun getSelfTelemetry(): TelemetryResponse = core.exchange {
        val prefix = synchronized(core.lock) { generation.selfInfo?.publicKey?.prefix(6) }
        query(PacketBuilder.getSelfTelemetry(), "selfTelemetry") {
            (it as? MeshEvent.TelemetryResponse)?.response?.takeIf { response -> prefix == null || response.publicKeyPrefix == prefix }
        }
    }
    override suspend fun exportPrivateKey(): Bytes = core.exchange {
        query(PacketBuilder.exportPrivateKey(), "privateKey", errorMatcher = {
            if (it is MeshEvent.Disabled) MeshCoreException.FeatureDisabled() else deviceError(it)
        }) { (it as? MeshEvent.PrivateKey)?.key }
    }
    override suspend fun importPrivateKey(key: Bytes) = importPrivateKey(key) {}

    override suspend fun importPrivateKey(key: Bytes, onCommitted: () -> Unit) {
        if (key.size != PacketBuilder.PRIVATE_KEY_SIZE) throw MeshCoreException.InvalidInput("Full 64-byte expanded private key required for importPrivateKey")
        core.exchange {
            match(PacketBuilder.importPrivateKey(key), "ok", acceptsErrors = true) {
                when {
                    it is MeshEvent.Ok && it.value == null -> ResponseDisposition.Success(Unit)
                    it is MeshEvent.Disabled -> ResponseDisposition.Failure(MeshCoreException.FeatureDisabled())
                    it is MeshEvent.Error && it.code != null -> ResponseDisposition.Failure(MeshCoreException.DeviceError(it.code))
                    else -> ResponseDisposition.Ignore
                }
            }
            onCommitted()
            query(PacketBuilder.appStart(core.configuration.clientIdentifier), "selfInfo", cleanup = true) { (it as? MeshEvent.SelfInfo)?.info }
        }
    }

    fun getContactByName(name: String, exactMatch: Boolean = false): MeshContact? =
        synchronized(core.lock) { core.active?.contacts?.getByName(name, exactMatch) }
    fun getContactByKeyPrefix(prefix: String): MeshContact? = synchronized(core.lock) { core.active?.contacts?.getByKeyPrefix(prefix) }
    fun getContactByKeyPrefix(prefix: Bytes): MeshContact? = synchronized(core.lock) { core.active?.contacts?.getByKeyPrefix(prefix) }
    fun popPendingContact(publicKey: String): MeshContact? = synchronized(core.lock) { core.active?.contacts?.popPending(publicKey) }
    fun flushPendingContacts() = synchronized(core.lock) { core.active?.contacts?.flushPending(); Unit }
    fun setAutoUpdateContacts(enabled: Boolean) = synchronized(core.lock) { core.generation().contacts.setAutoUpdate(enabled) }
    suspend fun ensureContacts(force: Boolean = false): List<MeshContact> {
        val contacts = core.generation().contacts
        return if (force || contacts.needsRefresh || contacts.isEmpty) getContacts(contacts.contactsLastModified) else contacts.cachedContacts
    }
    override suspend fun getContacts(since: Instant?): List<MeshContact> = core.fetchContacts(since).contacts
    override suspend fun getContactsReportingTotal(since: Instant?): ContactFetchResult = core.fetchContacts(since)
    override suspend fun getContact(publicKey: Bytes): MeshContact? {
        requireFullPublicKey(publicKey, "getContact")
        return core.exchange {
            match(PacketBuilder.getContactByKey(publicKey), "contact:${publicKey.hexString}", acceptsErrors = true) {
                when {
                    it is MeshEvent.Contact && it.contact.publicKey == publicKey -> ResponseDisposition.Success(it.contact)
                    it is MeshEvent.Error && it.code == ErrorCode.NOT_FOUND.rawValue -> ResponseDisposition.Success(null)
                    it is MeshEvent.Error -> ResponseDisposition.Failure(MeshCoreException.DeviceError(it.code ?: 0u))
                    else -> ResponseDisposition.Ignore
                }
            }
        }
    }
    override suspend fun addContact(contact: MeshContact) {
        requireFullPublicKey(contact.publicKey, "addContact")
        validateContactPath(contact.outPathLength, contact.outPath)
        simple(PacketBuilder.updateContact(contact))
    }
    override suspend fun removeContact(publicKey: Bytes) { requireFullPublicKey(publicKey, "removeContact"); simple(PacketBuilder.removeContact(publicKey)) }
    override suspend fun resetPath(publicKey: Bytes) { requireFullPublicKey(publicKey, "resetPath"); simple(PacketBuilder.resetPath(publicKey)) }
    override suspend fun shareContact(publicKey: Bytes) { requireFullPublicKey(publicKey, "shareContact"); simple(PacketBuilder.shareContact(publicKey)) }
    override suspend fun exportContact(publicKey: Bytes?): String {
        publicKey?.let { requireFullPublicKey(it, "exportContact") }
        return core.exchange {
            query(PacketBuilder.exportContact(publicKey), "contactURI:${publicKey?.hexString ?: "self"}") {
                (it as? MeshEvent.ContactURI)?.uri?.takeIf { uri -> publicKey == null || uri.startsWith("meshcore://${publicKey.hexString}") }
            }
        }
    }
    override suspend fun importContact(cardData: Bytes) = simple(Bytes.of(CommandCode.IMPORT_CONTACT.rawValue.toInt()) + cardData)
    suspend fun updateContact(
        publicKey: Bytes, type: ContactType, flags: ContactFlags, outPathLength: UByte, outPath: Bytes,
        advertisedName: String, lastAdvertisement: Instant, latitude: Double, longitude: Double,
    ) = simple(legacyContactFrame(publicKey, type.rawValue, flags, outPathLength, outPath, advertisedName, lastAdvertisement, latitude, longitude))
    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) = simple(legacyContactFrame(
        contact.publicKey, contact.typeRawValue, flags, contact.outPathLength, contact.outPath,
        contact.advertisedName, contact.lastAdvertisement, contact.latitude, contact.longitude,
    ))
    suspend fun changeContactPath(contact: MeshContact, path: Bytes, hashSize: UByte = 1u) {
        if (hashSize.toInt() !in 1..3 || path.size % hashSize.toInt() != 0) throw MeshCoreException.InvalidInput("Path requires complete 1-, 2-, or 3-byte hashes")
        val length = if (path.isEmpty) PacketBuilder.FLOOD_PATH_SENTINEL else encodePathLen(hashSize.toInt(), path.size / hashSize.toInt())
        simple(legacyContactFrame(contact.publicKey, contact.typeRawValue, contact.flags, length, path,
            contact.advertisedName, contact.lastAdvertisement, contact.latitude, contact.longitude))
    }

    override suspend fun getChannel(index: UByte): ChannelInfo = core.exchange { channel(index) }
    override suspend fun getChannels(indices: List<UByte>): ChannelFetchResult = core.getChannels(indices)
    override suspend fun setChannel(index: UByte, name: String, secret: Bytes) {
        if (secret.size < 16) throw MeshCoreException.InvalidInput("Channel secret requires at least 16 bytes")
        simple(PacketBuilder.setChannel(index, name, secret))
    }
    suspend fun setChannel(index: UByte, name: String, secret: ChannelSecret = ChannelSecret.DeriveFromName) =
        setChannel(index, name, secret.secretData(name))
    override suspend fun sendMessage(destination: Bytes, text: String, timestamp: Instant, attempt: UByte): MessageSentInfo {
        requirePrefix(destination, "sendMessage")
        return messageSent(PacketBuilder.sendMessage(destination, text, timestamp, attempt))
    }
    suspend fun sendMessage(destination: Destination, text: String, timestamp: Instant = Instant.now(), attempt: UByte = 0u): MessageSentInfo =
        sendMessage(destination.publicKey(6), text, timestamp, attempt)
    override suspend fun sendChannelMessage(channel: UByte, text: String, timestamp: Instant) =
        simple(PacketBuilder.sendChannelMessage(channel, text, timestamp))
    override suspend fun sendCommand(destination: Bytes, command: String, timestamp: Instant): MessageSentInfo {
        requirePrefix(destination, "sendCommand")
        return messageSent(PacketBuilder.sendCommand(destination, command, timestamp))
    }
    override suspend fun sendLogin(destination: Bytes, password: String): MessageSentInfo {
        requireFullPublicKey(destination, "sendLogin")
        return messageSent(PacketBuilder.sendLogin(destination, password))
    }
    suspend fun sendLogin(destination: Destination, password: String): MessageSentInfo = sendLogin(destination.fullPublicKey(), password)
    override suspend fun sendLogout(destination: Bytes) { requireFullPublicKey(destination, "sendLogout"); simple(PacketBuilder.sendLogout(destination)) }
    override suspend fun sendKeepAlive(publicKey: Bytes, syncSince: UInt): MessageSentInfo {
        requireFullPublicKey(publicKey, "sendKeepAlive")
        return messageSent(PacketBuilder.binaryRequest(publicKey, BinaryRequestType.KEEP_ALIVE, ByteWriter().appendUInt32LE(syncSince).toBytes()))
    }
    suspend fun sendStatusRequest(destination: Bytes): MessageSentInfo {
        requireFullPublicKey(destination, "sendStatusRequest")
        return messageSent(PacketBuilder.sendStatusRequest(destination))
    }
    suspend fun sendTelemetryRequest(destination: Bytes): MessageSentInfo {
        requireFullPublicKey(destination, "sendTelemetryRequest")
        return messageSent(PacketBuilder.getSelfTelemetry(destination))
    }
    override suspend fun sendPathDiscovery(destination: Bytes): MessageSentInfo {
        requireFullPublicKey(destination, "sendPathDiscovery")
        return messageSent(PacketBuilder.sendPathDiscovery(destination))
    }
    override suspend fun sendAdvertisement(flood: Boolean) = simple(PacketBuilder.sendAdvertisement(flood))
    override suspend fun getMessage(timeout: Double?): MessageResult = core.getMessage(timeout)
    override suspend fun startAutoMessageFetching() = core.startAutoMessageFetching()
    override suspend fun stopAutoMessageFetching() = core.stopAutoMessageFetching()
    override suspend fun sendMessageWithRetry(
        destination: Bytes, text: String, timestamp: Instant, maxAttempts: Long,
        floodAfter: Long, maxFloodAttempts: Long, timeout: Double?,
    ): MessageSentInfo? = core.sendMessageWithRetry(destination, text, timestamp, maxAttempts, floodAfter, maxFloodAttempts, timeout)

    suspend fun sendChannelData(
        channelIndex: UByte, dataType: UShort, payload: Bytes,
        pathLength: UByte = PacketBuilder.FLOOD_PATH_SENTINEL, pathBytes: Bytes = Bytes.EMPTY,
    ) {
        if (dataType == 0.toUShort()) throw MeshCoreException.InvalidInput("Channel data type zero is reserved")
        if (pathLength != PacketBuilder.FLOOD_PATH_SENTINEL) validateContactPath(pathLength, pathBytes)
        requireFirmware(11)
        simple(PacketBuilder.sendChannelData(channelIndex, dataType, payload, pathLength, pathBytes))
    }
    override suspend fun sendTrace(tag: UInt?, authCode: UInt?, flags: UByte, path: Bytes?): MessageSentInfo {
        if (path == null || path.isEmpty) throw MeshCoreException.InvalidInput("Trace requires at least one path byte")
        return messageSent(PacketBuilder.sendTrace(tag ?: generatedTag(), authCode ?: generatedTag(), flags, path))
    }
    suspend fun setFloodScope(scopeKey: Bytes) {
        if (scopeKey.size < 16) throw MeshCoreException.InvalidInput("Flood scope requires at least 16 key bytes")
        simple(PacketBuilder.setFloodScope(scopeKey))
    }
    suspend fun setFloodScope(scope: FloodScope) = setFloodScope(scope.scopeKey())
    suspend fun setFloodScopeUnscoped() { requireFirmware(12); simple(PacketBuilder.setFloodScopeUnscoped()) }
    suspend fun setDefaultFloodScope(name: String, scopeKey: Bytes) { requireFirmware(11); simple(PacketBuilder.setDefaultFloodScope(name, scopeKey)) }
    override suspend fun setDefaultFloodScope(name: String, scope: FloodScope) { requireFirmware(11); simple(PacketBuilder.setDefaultFloodScope(name, scope)) }
    override suspend fun getDefaultFloodScope(): DefaultFloodScope? = core.exchange {
        match(PacketBuilder.getDefaultFloodScope(), "defaultFloodScope", acceptsErrors = true) {
            when (it) {
                is MeshEvent.DefaultFloodScope -> ResponseDisposition.Success(it.scope)
                is MeshEvent.Error -> ResponseDisposition.Failure(MeshCoreException.DeviceError(it.code ?: 0u))
                else -> ResponseDisposition.Ignore
            }
        }
    }
    override suspend fun requestStatus(publicKey: Bytes): StatusResponse = core.requestStatus(publicKey, ContactType.REPEATER)
    override suspend fun requestStatus(publicKey: Bytes, type: ContactType): StatusResponse = core.requestStatus(publicKey, type)
    suspend fun requestStatus(contact: MeshContact): StatusResponse = requestStatus(contact.publicKey, contact.type)
    suspend fun requestStatus(destination: Destination): StatusResponse = when (destination) {
        is Destination.Contact -> requestStatus(destination.value)
        else -> requestStatus(destination.fullPublicKey())
    }
    override suspend fun requestTelemetry(publicKey: Bytes): TelemetryResponse = core.requestTelemetry(publicKey)
    suspend fun requestTelemetry(destination: Destination): TelemetryResponse = requestTelemetry(destination.fullPublicKey())
    override suspend fun requestOwnerInfo(publicKey: Bytes): OwnerInfoResponse = core.requestOwnerInfo(publicKey)
    override suspend fun requestMMA(publicKey: Bytes, start: Instant, end: Instant): MMAResponse = core.requestMMA(publicKey, start, end)
    override suspend fun requestACL(publicKey: Bytes): ACLResponse = core.requestACL(publicKey)
    override suspend fun requestNeighbours(publicKey: Bytes, count: UByte, offset: UShort, orderBy: UByte, pubkeyPrefixLength: UByte): NeighboursResponse =
        core.requestNeighbours(publicKey, count, offset, orderBy, pubkeyPrefixLength, ::generatedTag)
    override suspend fun fetchAllNeighbours(publicKey: Bytes, orderBy: UByte, pubkeyPrefixLength: UByte): NeighboursResponse =
        core.fetchAllNeighbours(publicKey, orderBy, pubkeyPrefixLength, ::generatedTag)
    suspend fun requestRegions(contact: MeshContact): List<String> = core.requestRegions(contact)

    suspend fun signStart(): Long = core.exchange { signingStart() }
    suspend fun signData(chunk: Bytes) = simple(PacketBuilder.signData(chunk))
    suspend fun signFinish(timeout: Double? = null): Bytes = core.exchange { signingFinish(timeout) }
    override suspend fun sign(data: Bytes, chunkSize: Long, timeout: Double?): Bytes = core.sign(data, chunkSize, timeout)
    suspend fun sendControlData(type: UByte, payload: Bytes) = simple(PacketBuilder.sendControlData(type, payload))
    suspend fun sendNodeDiscoverRequest(filter: UByte, prefixOnly: Boolean = true, tag: UInt? = null, since: Instant? = null): UInt {
        val actual = tag ?: generatedTag()
        simple(PacketBuilder.sendNodeDiscoverRequest(filter, prefixOnly, actual, since?.let(PacketBuilder::epochSeconds32)))
        return actual
    }

    private suspend fun simple(data: Bytes) = core.exchange { simple(data) }
    private suspend fun messageSent(data: Bytes): MessageSentInfo = core.exchange {
        query(data, "messageSent", errorMatcher = ::deviceError) { (it as? MeshEvent.MessageSent)?.info }
    }
    private fun generatedTag(): UInt = randomTag().also {
        if (it == 0u) throw MeshCoreException.InvalidInput("Generated session tag must be nonzero")
    }
    private fun requireFirmware(minimum: Int) {
        if (currentDeviceCapabilities?.firmwareVersion?.toInt()?.let { it < minimum } == true) {
            throw MeshCoreException.DeviceError(ErrorCode.UNSUPPORTED_COMMAND.rawValue)
        }
    }
}
