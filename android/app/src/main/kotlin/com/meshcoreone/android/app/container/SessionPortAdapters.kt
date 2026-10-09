// AndroidOnly: WP-303 App-layer adapters binding core:services ports to the concrete services built per connection.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.datastore.SecretStore
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ContactMessage
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.services.contacts.AdvertContactSyncOutcome
import com.meshcoreone.android.core.services.contacts.AdvertisementEvent
import com.meshcoreone.android.core.services.contacts.AdvertisementService
import com.meshcoreone.android.core.services.contacts.ChannelDecryptionCache
import com.meshcoreone.android.core.services.contacts.ChannelService
import com.meshcoreone.android.core.services.contacts.ContactCleanupNotifications
import com.meshcoreone.android.core.services.contacts.ContactCleanupRemoteSessions
import com.meshcoreone.android.core.services.contacts.ContactSyncCoordinating
import com.meshcoreone.android.core.services.device.SettingsService
import com.meshcoreone.android.core.services.diagnostics.RxLogRepeatProcessing
import com.meshcoreone.android.core.services.diagnostics.RxLogService
import com.meshcoreone.android.core.services.messaging.MessageService
import com.meshcoreone.android.core.services.notifications.NotificationConversationsNotifying
import com.meshcoreone.android.core.services.notifications.NotificationMessageSending
import com.meshcoreone.android.core.services.notifications.NotificationRoomReading
import com.meshcoreone.android.core.services.notifications.NotificationService
import com.meshcoreone.android.core.services.reactions.HeardRepeatsService
import com.meshcoreone.android.core.services.reactions.ReactionService
import com.meshcoreone.android.core.services.remote.NodeConfigChannelWriter
import com.meshcoreone.android.core.services.remote.NodeConfigContactsChangedNotifier
import com.meshcoreone.android.core.services.remote.NodeConfigSettingsPort
import com.meshcoreone.android.core.services.remote.RemoteNodePasswordStore
import com.meshcoreone.android.core.services.remote.RemoteNodeService
import com.meshcoreone.android.core.services.remote.RemoteNodeError
import com.meshcoreone.android.core.services.remote.RepeaterAdminService
import com.meshcoreone.android.core.services.remote.RoomAdminService
import com.meshcoreone.android.core.services.remote.RoomServerService
import com.meshcoreone.android.core.services.remote.handleBLEReconnection
import com.meshcoreone.android.core.services.remote.handleIncomingMessage
import com.meshcoreone.android.core.services.remote.markAsRead
import com.meshcoreone.android.core.services.remote.login
import com.meshcoreone.android.core.services.remote.logout
import com.meshcoreone.android.core.services.sync.claimManualContactSync
import com.meshcoreone.android.core.services.sync.setManualContactSyncActive
import com.meshcoreone.android.core.services.sync.SyncAdvertContactSyncOutcome
import com.meshcoreone.android.core.services.sync.SyncAdvertisementServicing
import com.meshcoreone.android.core.services.sync.SyncCoordinator
import com.meshcoreone.android.core.services.sync.SyncDiscoveryEvent
import com.meshcoreone.android.core.services.sync.SyncNotificationServicing
import com.meshcoreone.android.core.services.sync.SyncParsedDMReaction
import com.meshcoreone.android.core.services.sync.SyncPendingDMReaction
import com.meshcoreone.android.core.services.sync.SyncPendingReaction
import com.meshcoreone.android.core.services.sync.SyncReactionPersistResult
import com.meshcoreone.android.core.services.sync.SyncReactionServicing
import com.meshcoreone.android.core.services.sync.SyncRemoteNodeServicing
import com.meshcoreone.android.core.services.sync.SyncRepeaterAdminServicing
import com.meshcoreone.android.core.services.sync.SyncRoomAdminServicing
import com.meshcoreone.android.core.services.sync.SyncRoomServerServicing
import com.meshcoreone.android.core.services.sync.SyncRxLogServicing
import com.meshcoreone.android.core.services.sync.onDisconnected
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.meshcoreone.android.feature.tools.diagnostics.ToolsDiagnosticsDependencies
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliErrorPresentation
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliNodeDirectory
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliRemoteFault
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliRemoteNodePort
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliRepeaterAdminPort
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliSettingsPort
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliToolFeatureDependencies
import com.meshcoreone.android.feature.tools.diagnostics.noisefloor.RadioStatsSource
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogContactSource
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogFeatureDependencies
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogFeed

// region Contacts / node config

/** WP-209 [ContactSyncCoordinating] over the WP-214 coordinator. */
internal class ContactSyncCoordinatorAdapter(private val coordinator: SyncCoordinator) :
    ContactSyncCoordinating, NodeConfigContactsChangedNotifier {
    override suspend fun claimManualContactSync() = coordinator.claimManualContactSync()
    override suspend fun setManualContactSyncActive(active: Boolean) = coordinator.setManualContactSyncActive(active)
    override suspend fun notifyContactsChanged() = coordinator.notifyContactsChanged()
    override suspend fun notifyConversationsChanged() = coordinator.notifyConversationsChanged()
    override suspend fun refreshBlockedContactsCache(radioId: RadioId, dataStore: ContactPersisting) =
        coordinator.refreshBlockedContactsCache(radioId, dataStore)
}

internal class ConversationsNotifierAdapter(private val coordinator: SyncCoordinator) : NotificationConversationsNotifying {
    override fun notifyConversationsChanged() = coordinator.notifyConversationsChanged()
}

internal class CleanupNotificationsAdapter(private val notifications: NotificationService) : ContactCleanupNotifications {
    override suspend fun removeDeliveredNotifications(contactId: UUID) = notifications.removeDeliveredNotifications(contactId)
    override suspend fun updateBadgeCount() = notifications.updateBadgeCount()
}

internal class CleanupRemoteSessionsAdapter(
    private val radioId: RadioId,
    private val remote: RemoteNodeService,
) : ContactCleanupRemoteSessions {
    override suspend fun removeSession(id: UUID, publicKey: Bytes) = remote.removeSession(EntityKey(radioId, id), publicKey)
}

internal class NodeConfigSettingsAdapter(private val resolve: () -> SettingsService) : NodeConfigSettingsPort {
    private val settings: SettingsService get() = resolve()
    override suspend fun getSelfInfo() = settings.getSelfInfo()
    override suspend fun queryDevice() = settings.queryDevice()
    override suspend fun exportPrivateKey() = settings.exportPrivateKey()
    override suspend fun importPrivateKey(key: Bytes) = settings.importPrivateKey(key)
    override suspend fun setNodeName(name: String) = settings.setNodeName(name)
    override suspend fun setLocation(latitude: Double, longitude: Double) = settings.setLocation(latitude, longitude)
    override suspend fun setRadioParams(frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte) =
        settings.setRadioParams(frequencyKHz, bandwidthKHz, spreadingFactor, codingRate)
    override suspend fun setTxPower(power: Byte) = settings.setTxPower(power)
    override suspend fun setOtherParams(
        autoAddContacts: Boolean,
        telemetryModes: com.meshcoreone.android.core.model.TelemetryModes,
        advertLocationPolicyRaw: UByte,
        multiAcks: UByte,
    ) = settings.setOtherParams(autoAddContacts, telemetryModes, advertLocationPolicyRaw, multiAcks)
}

internal class NodeConfigChannelAdapter(private val channels: ChannelService) : NodeConfigChannelWriter {
    override suspend fun setChannelWithSecret(radioId: RadioId, index: UByte, name: String, secret: Bytes) =
        channels.setChannelWithSecret(radioId, index, name, secret)
}

// endregion

// region Notifications

internal class NotificationMessageSender(private val messages: MessageService) : NotificationMessageSending {
    override suspend fun sendDirectMessage(text: String, contact: ContactDTO): MessageDTO = messages.sendDirectMessage(text, contact)
    override suspend fun sendChannelMessage(text: String, channelIndex: UByte, radioId: RadioId) {
        messages.sendChannelMessage(text, channelIndex, radioId)
    }
}

internal class NotificationRoomReader(private val rooms: RoomServerService) : NotificationRoomReading {
    override suspend fun markAsRead(session: EntityKey) = rooms.markAsRead(session)
}

internal class SyncNotificationsAdapter(private val notifications: NotificationService) : SyncNotificationServicing {
    override suspend fun isSuppressingNotifications(): Boolean = notifications.isSuppressingNotifications
    override suspend fun setSuppressingNotifications(suppressing: Boolean) { notifications.isSuppressingNotifications = suppressing }
    override suspend fun activeContactID(): UUID? = notifications.activeContactID
    override suspend fun activeChannelIndex(): UByte? = notifications.activeChannelIndex
    override suspend fun activeChannelRadioId(): RadioId? = notifications.activeChannelRadioId
    override suspend fun postDirectMessageNotification(
        from: String, contactID: UUID, messageText: String, messageID: UUID, isMuted: Boolean,
    ) = notifications.postDirectMessageNotification(from, contactID, messageText, messageID, isMuted)
    override suspend fun postChannelMessageNotification(
        channelName: String, channelIndex: UByte, radioId: RadioId, senderName: String?, messageText: String,
        messageID: UUID, notificationLevel: NotificationLevel, hasSelfMention: Boolean,
    ) = notifications.postChannelMessageNotification(
        channelName, channelIndex, radioId, senderName, messageText, messageID, notificationLevel, hasSelfMention,
    )
    override suspend fun postRoomMessageNotification(
        roomName: String, sessionID: UUID, senderName: String?, messageText: String, messageID: UUID,
        notificationLevel: NotificationLevel,
    ) = notifications.postRoomMessageNotification(roomName, sessionID, senderName, messageText, messageID, notificationLevel)
    override suspend fun postNewContactNotification(contactName: String, contactID: UUID, contactType: ContactType) =
        notifications.postNewContactNotification(contactName, contactID, contactType)
    override suspend fun updateBadgeCount() = notifications.updateBadgeCount()
}

// endregion

// region Sync ports

internal class SyncAdvertisementAdapter(private val adverts: AdvertisementService) : SyncAdvertisementServicing {
    override suspend fun setSyncingContacts(isSyncing: Boolean) = adverts.setSyncingContacts(isSyncing)
    override suspend fun setDeltaSyncHandler(handler: (suspend (fullRefetch: Boolean) -> SyncAdvertContactSyncOutcome)?) {
        adverts.setDeltaSyncHandler(handler?.let { forward ->
            { fullRefetch -> forward(fullRefetch).toAdvertOutcome() }
        })
    }
    override suspend fun materializeContactForPendingAdvert(prefix: Bytes, radioId: RadioId): ContactDTO? =
        adverts.materializeContactForPendingAdvert(prefix, radioId)
    override fun events(): Flow<SyncDiscoveryEvent> = adverts.events().map { event ->
        when (event) {
            is AdvertisementEvent.NewContactDiscovered ->
                SyncDiscoveryEvent.NewContactDiscovered(event.name, event.contactID, event.contactType)
            is AdvertisementEvent.OrphanDirectMessagesAdopted ->
                SyncDiscoveryEvent.OrphanDirectMessagesAdopted(event.contactIDs.toList())
            else -> SyncDiscoveryEvent.Other
        }
    }

    private fun SyncAdvertContactSyncOutcome.toAdvertOutcome(): AdvertContactSyncOutcome = when (this) {
        SyncAdvertContactSyncOutcome.SYNCED -> AdvertContactSyncOutcome.SYNCED
        SyncAdvertContactSyncOutcome.BUSY -> AdvertContactSyncOutcome.BUSY
        SyncAdvertContactSyncOutcome.FAILED -> AdvertContactSyncOutcome.FAILED
        SyncAdvertContactSyncOutcome.NOT_READY -> AdvertContactSyncOutcome.NOT_READY
    }
}

internal class SyncRxLogAdapter(private val rxLog: RxLogService) : SyncRxLogServicing {
    override suspend fun updatePrivateKey(key: Bytes?) = rxLog.updatePrivateKey(key)
    override suspend fun updateContactPublicKeys(keys: Map<UByte, List<Bytes>>) = rxLog.updateContactPublicKeys(keys)
    override suspend fun updateChannels(secrets: Map<UByte, Bytes>, names: Map<UByte, String>) = rxLog.updateChannels(secrets, names)
    override suspend fun decodedEntries(entries: List<RxLogEntryDTO>): List<RxLogEntryDTO> = rxLog.decodedEntries(entries)
}

internal class ChannelDecryptionAdapter(private val rxLog: RxLogService) : ChannelDecryptionCache {
    override suspend fun updateChannels(channels: List<ChannelDTO>) = rxLog.updateChannels(channels)
}

internal class HeardRepeatProcessingAdapter(private val heardRepeats: HeardRepeatsService) : RxLogRepeatProcessing {
    override suspend fun processForRepeats(entry: RxLogEntryDTO) { heardRepeats.processForRepeats(entry) }
}

internal class SyncRoomServerAdapter(private val rooms: RoomServerService) : SyncRoomServerServicing {
    override suspend fun handleIncomingMessage(
        senderPublicKeyPrefix: Bytes, timestamp: UInt, authorPrefix: Bytes, text: String,
    ) = rooms.handleIncomingMessage(senderPublicKeyPrefix, timestamp, authorPrefix, text)
}

internal class SyncRoomAdminAdapter(private val admin: RoomAdminService) : SyncRoomAdminServicing {
    override suspend fun invokeCLIHandler(message: ContactMessage, contact: ContactDTO) = admin.invokeCLIHandler(message, contact)
}

internal class SyncRepeaterAdminAdapter(private val admin: RepeaterAdminService) : SyncRepeaterAdminServicing {
    override suspend fun invokeCLIHandler(message: ContactMessage, contact: ContactDTO) = admin.invokeCLIHandler(message, contact)
}

internal class SyncRemoteNodeAdapter(private val remote: RemoteNodeService) : SyncRemoteNodeServicing {
    override suspend fun handleBLEReconnection(sessions: Set<EntityKey>) = remote.handleBLEReconnection(sessions)
}

// endregion

// region Tools diagnostics

fun AppContainer.createToolsDiagnosticsDependencies(): ToolsDiagnosticsDependencies {
    fun session(): RadioSessionContainer? = sessions.current as? RadioSessionContainer
    val directory = ToolsNodeDirectoryAdapter { session() }
    val remote = ToolsRemoteNodeAdapter { session() }
    val settings = ToolsSettingsAdapter { session() }
    val rxLog = ToolsRxLogFeedAdapter { session() }
    return ToolsDiagnosticsDependencies(
        cli = CliToolFeatureDependencies(
            repeaterAdminService = {
                session()?.let { graph ->
                    CliRepeaterAdminPort { key, command, timeout ->
                        graph.repeaterAdminService.sendRawCommand(key, command, timeout)
                    }
                }
            },
            remoteNodeService = { session()?.let { remote } },
            settingsService = { session()?.let { settings } },
            dataStore = { session()?.let { directory } },
            radioId = { appState.currentRadioId },
            connectedDevice = { appState.connectedDevice },
            sendSelfAdvert = { flood -> session()?.advertisementService?.sendSelfAdvertisement(flood) ?: Unit },
        ),
        cliErrors = CliErrorPresentation(
            remoteFault = { failure ->
                when (failure) {
                    is RemoteNodeError.Timeout -> CliRemoteFault.Timeout
                    is RemoteNodeError.PasswordNotFound -> CliRemoteFault.PasswordNotFound
                    is RemoteNodeError.LoginFailed -> CliRemoteFault.LoginFailed(failure.reason)
                    is RemoteNodeError.Cancelled -> CliRemoteFault.Cancelled
                    else -> null
                }
            },
        ),
        rxLog = RxLogFeatureDependencies(
            rxLogService = { session()?.let { rxLog } },
            dataStore = { session()?.let { RxLogContactSource { radioId -> it.dataStore.fetchContacts(radioId) } } },
            radioId = { appState.currentRadioId },
        ),
        radioStats = { session()?.let { graph -> RadioStatsSource { graph.getStatsRadio() } } },
        isConnected = { session() != null },
        localPublicKeyPrefix = { appState.connectedDevice?.publicKey },
        connectionVersion = appState.servicesVersion,
    )
}

private class ToolsNodeDirectoryAdapter(private val resolve: () -> RadioSessionContainer?) : CliNodeDirectory {
    override suspend fun fetchContacts(radioId: RadioId) = checkNotNull(resolve()).dataStore.fetchContacts(radioId)
    override suspend fun fetchChannels(radioId: RadioId) = checkNotNull(resolve()).dataStore.fetchChannels(radioId)
}

private class ToolsRemoteNodeAdapter(private val resolve: () -> RadioSessionContainer?) : CliRemoteNodePort {
    private val service get() = checkNotNull(resolve()).remoteNodeService
    override suspend fun createSession(radioId: RadioId, contact: ContactDTO) =
        service.createSession(radioId, contact).let { EntityKey(it.radioId, it.id) }
    override suspend fun login(
        session: EntityKey, password: String, pathLength: UByte, onTimeoutKnown: suspend (Long) -> Unit,
    ) = service.login(session, password, pathLength, onTimeoutKnown).success
    override suspend fun logout(session: EntityKey) = service.logout(session)
    override suspend fun retrievePassword(contact: ContactDTO) = service.retrievePassword(contact)
    override suspend fun storePassword(password: String, publicKey: Bytes) = service.storePassword(password, publicKey)
    override suspend fun deletePassword(contact: ContactDTO) = service.deletePassword(contact)
}

private class ToolsSettingsAdapter(private val resolve: () -> RadioSessionContainer?) : CliSettingsPort {
    private val service get() = checkNotNull(resolve()).settingsService
    override suspend fun getTime() = service.getTime()
    override suspend fun setTime(date: java.time.Instant) = service.setTime(date)
    override suspend fun queryDevice() = service.queryDevice()
    override suspend fun getSelfInfo() = service.getSelfInfo()
    override suspend fun getBattery() = service.getBattery()
    override suspend fun setNodeNameVerified(name: String) = service.setNodeNameVerified(name)
    override suspend fun setManualLocationVerified(latitude: Double, longitude: Double) =
        service.setManualLocationVerified(latitude, longitude)
    override suspend fun setTxPowerVerified(power: Byte) = service.setTxPowerVerified(power)
    override suspend fun setRadioParamsVerified(
        frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte, codingRate: UByte,
    ) = service.setRadioParamsVerified(frequencyKHz, bandwidthKHz, spreadingFactor, codingRate)
    override suspend fun setOtherParamsVerified(device: com.meshcoreone.android.core.model.DeviceDTO, multiAcks: UByte) =
        service.setOtherParamsVerified(device, multiAcks = multiAcks)
    override suspend fun setPathHashModeVerified(mode: UByte) = service.setPathHashModeVerified(mode)
    override suspend fun getCustomVars() = service.getCustomVars()
    override suspend fun setCustomVar(key: String, value: String) = service.setCustomVar(key, value)
    override suspend fun reboot() = service.reboot()
}

private class ToolsRxLogFeedAdapter(private val resolve: () -> RadioSessionContainer?) : RxLogFeed {
    private val service get() = checkNotNull(resolve()).rxLogService
    override suspend fun loadExistingEntries() = service.loadExistingEntries()
    override fun entryStream() = service.entryStream()
    override suspend fun clearEntries() = service.clearEntries()
}

// endregion

/** Where per-radio node passwords live (Swift keychain); production binds the encrypted secret store. */
interface NodePasswordVault {
    suspend fun store(password: String, radioId: RadioId, nodePublicKey: Bytes)
    suspend fun retrieve(radioId: RadioId, nodePublicKey: Bytes): String?
    suspend fun has(radioId: RadioId, nodePublicKey: Bytes): Boolean
    suspend fun delete(radioId: RadioId, nodePublicKey: Bytes)
}

class SecretStoreVault(private val secrets: SecretStore) : NodePasswordVault {
    override suspend fun store(password: String, radioId: RadioId, nodePublicKey: Bytes) =
        secrets.storePassword(password, radioId, nodePublicKey)
    override suspend fun retrieve(radioId: RadioId, nodePublicKey: Bytes): String? = secrets.retrievePassword(radioId, nodePublicKey)
    override suspend fun has(radioId: RadioId, nodePublicKey: Bytes): Boolean = secrets.hasPassword(radioId, nodePublicKey)
    override suspend fun delete(radioId: RadioId, nodePublicKey: Bytes) = secrets.deletePassword(radioId, nodePublicKey)
}

/** Keychain analogue: node passwords are scoped to one radio. */
internal class SecretPasswordStore(private val vault: NodePasswordVault, private val radioId: RadioId) : RemoteNodePasswordStore {
    override suspend fun storePassword(password: String, publicKey: Bytes) = vault.store(password, radioId, publicKey)
    override suspend fun retrievePassword(publicKey: Bytes): String? = vault.retrieve(radioId, publicKey)
    override suspend fun hasPassword(publicKey: Bytes): Boolean = vault.has(radioId, publicKey)
    override suspend fun deletePassword(publicKey: Bytes) = vault.delete(radioId, publicKey)
}
