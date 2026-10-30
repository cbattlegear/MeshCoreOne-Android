// AndroidOnly: WP-303 The slice of the per-connection service graph app state wires; implemented by the container.
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.contracts.domain.MessageStatusEvent
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.SessionEventSubscription
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.services.contacts.AdvertisementService
import com.meshcoreone.android.core.services.contacts.ChannelService
import com.meshcoreone.android.core.services.contacts.ContactService
import com.meshcoreone.android.core.services.device.DeviceService
import com.meshcoreone.android.core.services.device.SettingsService
import com.meshcoreone.android.core.services.diagnostics.RxLogService
import com.meshcoreone.android.core.services.messaging.MessageService
import com.meshcoreone.android.core.services.notifications.NotificationActionHandler
import com.meshcoreone.android.core.services.notifications.NotificationService
import com.meshcoreone.android.core.services.reactions.HeardRepeatEvent
import com.meshcoreone.android.core.services.reactions.ReactionService
import com.meshcoreone.android.core.services.remote.RemoteNodeEvent
import com.meshcoreone.android.core.services.remote.RemoteNodeService
import com.meshcoreone.android.core.services.remote.RoomServerEvent
import com.meshcoreone.android.core.services.sync.SyncCoordinator
import com.meshcoreone.android.core.services.sync.SyncDataEvent
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Every event stream the message dispatcher consumes, subscribed at construction (registration happens at the
 * subscribing call, not at collection) so no event emitted in the connection-ready / sync-start window is lost.
 */
class MessageEventSources(
    val dataEvents: Flow<SyncDataEvent>,
    val heardRepeats: Flow<HeardRepeatEvent>,
    val regionUpdates: Flow<SnapshotList<UUID>>,
    val remoteNode: Flow<RemoteNodeEvent>,
    val roomServer: Flow<RoomServerEvent>,
    val messageStatus: SessionEventSubscription<MessageStatusEvent>,
)

/** The per-connection service graph as app state sees it (Swift `ServiceContainer`). */
interface AppSession {
    val token: SessionToken
    val transportType: TransportType
    val dataStore: PersistenceStoreProtocol
    val syncCoordinator: SyncCoordinator
    val advertisementService: AdvertisementService
    val contactService: ContactService
    val channelService: ChannelService
    val settingsService: SettingsService
    val deviceService: DeviceService
    val messageService: MessageService
    val notificationService: NotificationService
    val notificationActionHandler: NotificationActionHandler
    val remoteNodeService: RemoteNodeService
    val rxLogService: RxLogService
    val reactionService: ReactionService
    val batteryServices: BatteryServices

    /** Subscribes to every dispatcher stream now. Each call registers fresh single-collection subscriptions. */
    fun subscribeMessageEvents(): MessageEventSources
}
