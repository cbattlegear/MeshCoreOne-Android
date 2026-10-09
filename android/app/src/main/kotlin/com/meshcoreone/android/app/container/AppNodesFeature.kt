// PortedFrom: MC1/MC1App.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/ServiceContainer.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import android.content.Context
import com.meshcoreone.android.app.deeplinks.MeshCoreDeepLink
import com.meshcoreone.android.app.deeplinks.MeshCoreUriParser
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.contacts.AdvertisementEvent
import com.meshcoreone.android.core.services.contacts.AdvertisementService
import com.meshcoreone.android.core.services.contacts.ContactService
import com.meshcoreone.android.core.services.contacts.ContactServiceError
import com.meshcoreone.android.core.services.contacts.ContactServiceEvent
import com.meshcoreone.android.core.services.notifications.NotificationService
import com.meshcoreone.android.core.services.remote.BinaryProtocolService
import com.meshcoreone.android.feature.nodes.deps.ContactUriCodec
import com.meshcoreone.android.feature.nodes.deps.EventSubscription
import com.meshcoreone.android.feature.nodes.deps.FirmwareTimeouts
import com.meshcoreone.android.feature.nodes.deps.NodesAdvertEvent
import com.meshcoreone.android.feature.nodes.deps.NodesAdvertisementService
import com.meshcoreone.android.feature.nodes.deps.NodesClock
import com.meshcoreone.android.feature.nodes.deps.NodesContactFailure
import com.meshcoreone.android.feature.nodes.deps.NodesContactService
import com.meshcoreone.android.feature.nodes.deps.NodesDataStore
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesNotificationCleanup
import com.meshcoreone.android.feature.nodes.deps.NodesSession
import com.meshcoreone.android.feature.nodes.deps.NodesTraceService
import com.meshcoreone.android.feature.nodes.deps.ScannedContact
import com.meshcoreone.android.feature.nodes.deps.StringListPreferences
import com.meshcoreone.android.feature.nodes.deps.SyncProgress
import com.meshcoreone.android.feature.tools.diagnostics.FirmwareSuggestedTimeout
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import org.json.JSONArray

internal fun AppContainer.createNodesFeatureDependencies(): NodesFeatureDependencies =
    NodesFeatureDependencies(
        session = AppNodesSession(this),
        uriCodec = object : ContactUriCodec {
            override fun exportContactUri(name: String, publicKey: Bytes, type: com.meshcoreone.android.core.protocol.model.ContactType) =
                ContactService.exportContactURI(name, publicKey, type)

            override fun parseContactUri(text: String): ScannedContact? =
                (MeshCoreUriParser.parseContact(text) as? MeshCoreDeepLink.Contact)?.let {
                    ScannedContact(it.name, it.publicKey, it.contactType)
                }
        },
        timeouts = object : FirmwareTimeouts {
            override fun pathDiscoverySeconds(suggestedTimeoutMs: UInt) =
                FirmwareSuggestedTimeout.pathDiscoverySeconds(suggestedTimeoutMs)

            override fun pathDiscoveryRetransmitInterval(suggestedTimeoutMs: UInt) =
                FirmwareSuggestedTimeout.pathDiscoveryRetransmitInterval(suggestedTimeoutMs)

            override fun zeroHopSeconds(suggestedTimeoutMs: UInt) =
                FirmwareSuggestedTimeout.sanitizedSeconds(
                    suggestedTimeoutMs,
                    FirmwareSuggestedTimeout.Profile.ZERO_HOP,
                )
        },
        messages = nodesMessages,
        announcer = nodesAnnouncer,
        preferences = nodesPreferences,
        clock = object : NodesClock {
            override val wallNow: Instant get() = Instant.now()
            override val elapsed: Duration get() = System.nanoTime().nanoseconds
            override suspend fun sleep(duration: Duration) = delay(duration)
        },
    )

private class AppNodesSession(private val container: AppContainer) : NodesSession {
    override fun connectionState() = container.appState.connectionState
    override fun connectedDevice() = container.appState.connectedDevice
    override fun currentRadioId() = container.appState.currentRadioId
    override fun offlineDataStore() = container.appState.offlineDataStore?.let(::AppNodesDataStore)
    override fun servicesDataStore() = container.sessions.current?.dataStore?.let(::AppNodesDataStore)
    override fun contactService() = container.sessions.current?.contactService?.let(::AppNodesContactService)
    override fun advertisementService() =
        container.sessions.current?.advertisementService?.let(::AppNodesAdvertisementService)

    override fun traceService() = container.sessions.current?.binaryProtocolService?.let(::AppNodesTraceService)
    override fun notificationCleanup() =
        container.sessions.current?.notificationService?.let(::AppNodesNotificationCleanup)
}

private class AppNodesDataStore(private val store: PersistenceStoreProtocol) : NodesDataStore {
    override suspend fun fetchContacts(radioId: RadioId) = store.fetchContacts(radioId)
    override suspend fun fetchContact(key: EntityKey) = store.fetchContact(key)
    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = store.fetchContact(radioId, publicKey)
    override suspend fun fetchBlockedContacts(radioId: RadioId) = store.fetchBlockedContacts(radioId)
    override suspend fun fetchContactPublicKeys(radioId: RadioId) = store.fetchContactPublicKeys(radioId)
    override suspend fun touchContactHeard(radioId: RadioId, publicKey: Bytes, date: Instant) =
        store.touchContactHeard(radioId, publicKey, date)

    override suspend fun fetchDiscoveredNodes(radioId: RadioId) = store.fetchDiscoveredNodes(radioId)
    override suspend fun deleteDiscoveredNode(key: EntityKey) = store.deleteDiscoveredNode(key)
    override suspend fun clearDiscoveredNodes(radioId: RadioId) = store.clearDiscoveredNodes(radioId)
}

private class AppNodesContactService(private val service: ContactService) : NodesContactService {
    override fun syncProgressEvents(): EventSubscription<SyncProgress> {
        val subscription = service.events()
        return object : EventSubscription<SyncProgress> {
            override val events = subscription.events.map {
                (it as? ContactServiceEvent.SyncProgress)?.let { progress ->
                    SyncProgress(progress.received, progress.total)
                }
            }.filterNotNull()

            override fun close() = subscription.close()
        }
    }

    override suspend fun syncContactsForRefresh(radioId: RadioId) =
        mapContactFailure { service.syncContactsForRefresh(radioId); Unit }

    override suspend fun getContact(radioId: RadioId, publicKey: Bytes) = service.getContact(radioId, publicKey)
    override suspend fun addOrUpdateContact(radioId: RadioId, contact: ContactFrame) =
        mapContactFailure { service.addOrUpdateContact(radioId, contact) }

    override suspend fun removeContact(radioId: RadioId, publicKey: Bytes) =
        mapContactFailure { service.removeContact(radioId, publicKey) }

    override suspend fun removeLocalContact(contact: EntityKey, publicKey: Bytes) =
        service.removeLocalContact(contact, publicKey)

    override suspend fun clearContactMessages(contact: EntityKey) = service.clearContactMessages(contact)
    override suspend fun resetPath(radioId: RadioId, publicKey: Bytes) = service.resetPath(radioId, publicKey)
    override suspend fun sendPathDiscovery(radioId: RadioId, publicKey: Bytes) =
        service.sendPathDiscovery(radioId, publicKey)

    override suspend fun setPath(radioId: RadioId, publicKey: Bytes, path: Bytes, pathLength: UByte) =
        service.setPath(radioId, publicKey, path, pathLength)

    override suspend fun shareContact(publicKey: Bytes) =
        mapContactFailure { service.shareContact(publicKey) }

    override suspend fun updateContactPreferences(contact: EntityKey, nickname: String?, isBlocked: Boolean?) =
        service.updateContactPreferences(contact, nickname, isBlocked)

    override suspend fun updateContactAvatar(contact: EntityKey, imageData: Bytes?) =
        service.updateContactAvatar(contact, imageData)

    override suspend fun setContactFavorite(contact: EntityKey, isFavorite: Boolean) =
        service.setContactFavorite(contact, isFavorite)

    private suspend fun <T> mapContactFailure(block: suspend () -> T): T = try {
        block()
    } catch (_: ContactServiceError.ContactNotFound) {
        throw NodesContactFailure.ContactNotFound()
    } catch (_: ContactServiceError.ContactTableFull) {
        throw NodesContactFailure.ContactTableFull()
    } catch (_: ContactServiceError.ShareContactUnavailable) {
        throw NodesContactFailure.ShareContactUnavailable()
    }
}

private class AppNodesAdvertisementService(
    private val service: AdvertisementService,
) : NodesAdvertisementService {
    override fun events(): EventSubscription<NodesAdvertEvent> = object : EventSubscription<NodesAdvertEvent> {
        override val events = service.events().map {
            when (it) {
                is AdvertisementEvent.PathDiscoveryResponse -> NodesAdvertEvent.PathDiscoveryResponse(it.path)
                is AdvertisementEvent.TraceSnrObserved ->
                    NodesAdvertEvent.TraceSnrObserved(it.tag, it.localSnr, it.remoteSnr)
                else -> null
            }
        }.filterNotNull()

        override fun close() = Unit
    }

    override suspend fun setSyncingContacts(isSyncing: Boolean) = service.setSyncingContacts(isSyncing)
}

private class AppNodesTraceService(private val service: BinaryProtocolService) : NodesTraceService {
    override suspend fun sendTrace(tag: UInt, flags: UByte, path: Bytes) =
        service.sendTrace(tag = tag, flags = flags, path = path)
}

private class AppNodesNotificationCleanup(
    private val service: NotificationService,
) : NodesNotificationCleanup {
    override suspend fun removeDeliveredNotifications(contactId: java.util.UUID) =
        service.removeDeliveredNotifications(contactId)

    override suspend fun updateBadgeCount() = service.updateBadgeCount()
}

internal class SharedPreferencesStringLists(context: Context) : StringListPreferences {
    private val preferences = context.getSharedPreferences("nodes-feature", Context.MODE_PRIVATE)

    override fun stringList(key: String): List<String>? =
        preferences.getString(key, null)?.let { encoded ->
            runCatching {
                val array = JSONArray(encoded)
                List(array.length()) { index -> array.getString(index) }
            }.getOrNull()
        }

    override fun setStringList(key: String, value: List<String>) {
        val array = JSONArray()
        value.forEach(array::put)
        preferences.edit().putString(key, array.toString()).apply()
    }
}
