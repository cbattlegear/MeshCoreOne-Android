// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncDependencies.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.AppStateProvider
import com.meshcoreone.android.core.contracts.domain.ChannelReactionTargetAuthority
import com.meshcoreone.android.core.contracts.domain.ChannelServiceProtocol
import com.meshcoreone.android.core.contracts.domain.ContactServiceProtocol
import com.meshcoreone.android.core.contracts.domain.MessagePollingServiceProtocol
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes

/**
 * The narrow dependency surface [SyncCoordinator] needs to run a sync cycle.
 *
 * Swift builds it from a live `ServiceContainer`; WP-303 builds it from the Kotlin service graph. The
 * merged `core:contracts` protocols are used directly; collaborators from unmerged work packages are
 * reached through the ports in `SyncServicePorts.kt`. [codecs] carries the Swift static helpers
 * (reaction parsers, deduplication key, RX correlation, mention and CLI-echo parsing), which Kotlin must
 * inject because their owners are not on this module's base.
 */
class SyncDependencies(
    /** Persistence store for device, contact, channel, message, and RX log operations. */
    val dataStore: PersistenceStoreProtocol,
    /** Service performing the contact sync phase (WP-209 `ContactService`). */
    val contactService: ContactServiceProtocol,
    /** Service performing the channel sync phase (WP-209 `ChannelService`). */
    val channelService: ChannelServiceProtocol,
    /** Message polling, auto-fetch, and ingestion handler wiring (WP-208 `MessagePollingService`). */
    val messagePollingService: MessagePollingServiceProtocol,
    /** Notifications and suppression during sync. */
    val notificationService: SyncNotificationServicing,
    /** Emoji reactions on direct and channel messages. */
    val reactionService: SyncReactionServicing,
    /** Advertisement events and contact discovery. */
    val advertisementService: SyncAdvertisementServicing,
    /** RX log decryption caches (private key, contact public keys, channel secrets). */
    val rxLogService: SyncRxLogServicing,
    /** Extra incoming flood paths and sent-echo repeats. */
    val heardRepeatsService: SyncHeardRepeatsServicing,
    /** Persists signed room messages. */
    val roomServerService: SyncRoomServerServicing,
    /** Routes CLI responses from room contacts. */
    val roomAdminService: SyncRoomAdminServicing,
    /** Routes CLI responses from repeater contacts. */
    val repeaterAdminService: SyncRepeaterAdminServicing,
    /** Swift static text helpers. */
    val codecs: SyncMessageCodecs,
    /** Foreground/background state; null defaults sync to foreground behavior (channels sync). */
    val appStateProvider: AppStateProvider? = null,
    /** Starts service event monitoring for the connected radio. */
    val startEventMonitoring: suspend (radioId: RadioId, enableAutoFetch: Boolean) -> Unit,
    /** Exports the device private key for direct message decryption. */
    val exportPrivateKey: suspend () -> Bytes,
    /** Optional original-target authority, used only by the separately guarded channel path. */
    val channelReactionTargets: ChannelReactionTargetAuthority? = null,
    /** The guarded path is nonactivated until polling reserves exact terminal custody. */
    val guardedChannelReactionsEnabled: Boolean = false,
) {
    /** Copy with replaced members (Swift test helper `with(dataStore:messagePollingService:...)`). */
    fun copy(
        dataStore: PersistenceStoreProtocol = this.dataStore,
        contactService: ContactServiceProtocol = this.contactService,
        channelService: ChannelServiceProtocol = this.channelService,
        messagePollingService: MessagePollingServiceProtocol = this.messagePollingService,
        notificationService: SyncNotificationServicing = this.notificationService,
        reactionService: SyncReactionServicing = this.reactionService,
        advertisementService: SyncAdvertisementServicing = this.advertisementService,
        rxLogService: SyncRxLogServicing = this.rxLogService,
        heardRepeatsService: SyncHeardRepeatsServicing = this.heardRepeatsService,
        roomServerService: SyncRoomServerServicing = this.roomServerService,
        roomAdminService: SyncRoomAdminServicing = this.roomAdminService,
        repeaterAdminService: SyncRepeaterAdminServicing = this.repeaterAdminService,
        codecs: SyncMessageCodecs = this.codecs,
        appStateProvider: AppStateProvider? = this.appStateProvider,
        startEventMonitoring: suspend (RadioId, Boolean) -> Unit = this.startEventMonitoring,
        exportPrivateKey: suspend () -> Bytes = this.exportPrivateKey,
        channelReactionTargets: ChannelReactionTargetAuthority? = this.channelReactionTargets,
        guardedChannelReactionsEnabled: Boolean = this.guardedChannelReactionsEnabled,
    ): SyncDependencies = SyncDependencies(
        dataStore, contactService, channelService, messagePollingService, notificationService, reactionService,
        advertisementService, rxLogService, heardRepeatsService, roomServerService, roomAdminService,
        repeaterAdminService, codecs, appStateProvider, startEventMonitoring, exportPrivateKey, channelReactionTargets,
        guardedChannelReactionsEnabled,
    )
}
