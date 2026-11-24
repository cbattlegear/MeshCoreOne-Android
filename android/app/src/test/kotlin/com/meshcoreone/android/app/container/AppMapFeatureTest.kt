// AndroidOnly: WP-312 Real App map adapters over the process Room store and existing synthetic radio graph.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.maps.OfflineMapController
import com.meshcoreone.android.core.maps.UnavailableOfflineMapBackend
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.contacts.ContactServiceError
import com.meshcoreone.android.feature.map.MapSelection
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.*
import org.junit.Test

class AppMapFeatureTest : RoomProcessTest() {
    @Test fun duplicateDiscoveredRowsDoNotEraseKnownContactHops() = runTest {
        val h = ContainerHarness(this, store, offlineMaps = offlineMaps())
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val radio = assertNotNull(h.appState.currentRadioId)
            val contact = ContactDTO(
                radioId = radio, publicKey = key(42), name = "Contact",
                latitude = 37.0, longitude = -122.0, lastHeardTimestamp = 0u,
            )
            store.saveContact(contact)
            val node = store.upsertDiscoveredNode(radio, ContactFrame(
                contact.publicKey, contact.type, 0u, 255u,
                com.meshcoreone.android.core.protocol.bytes.Bytes.EMPTY, "Node", 1u, 37.0, -122.0, 1u,
            )).node
            db.discoveredNodes().insert(node.copy(id = java.util.UUID.randomUUID(), inboundHopCount = null).toEntity())
            val rows = store.fetchDiscoveredNodes(radio)
            db.discoveredNodes().upsert(rows.first().copy(inboundHopCount = 2).toEntity())
            assertNull(store.fetchDiscoveredNodes(radio).last().inboundHopCount)
            assertEquals(2L, h.container.mapFeature.dataSource.load().markers.single().displayedHops)
        } finally { h.container.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun floodAdvertInvalidatesMapAfterInboundHopPersistence() = runTest {
        val f = RxIngestionFixture(this, store, offlineMaps = offlineMaps())
        try {
            f.connect()
            val contact = ContactDTO(
                radioId = f.radio, publicKey = key(42), name = "Contact",
                latitude = 37.0, longitude = -122.0, lastHeardTimestamp = 0u,
            )
            store.saveContact(contact)
            store.upsertDiscoveredNode(f.radio, ContactFrame(
                contact.publicKey, contact.type, 0u, 255u,
                com.meshcoreone.android.core.protocol.bytes.Bytes.EMPTY, "Node", 1u, 37.0, -122.0, 1u,
            ))
            val before = f.harness.appState.contactsVersion.value
            val payload = contact.publicKey + com.meshcoreone.android.core.protocol.bytes.Bytes.of(1, 0, 0, 0)
            f.live.rxLogService.process(com.meshcoreone.android.core.protocol.event.ParsedRxLogData(
                8.0, -70, payload, com.meshcoreone.android.core.protocol.event.RouteType.FLOOD,
                com.meshcoreone.android.core.protocol.event.PayloadType.ADVERT, 0u, 0u, null,
                2u, emptyList<UByte>(), payload,
            ))
            f.harness.eventually("map hop invalidation") { f.harness.appState.contactsVersion.value > before }
            runCurrent()
            assertEquals(2L, f.harness.container.mapFeature.dataSource.load().markers.single().displayedHops)
        } finally { f.harness.container.close() }
    }

    @Test fun locatedMarkersKeepActualIdentityAndUseExistingDetailAndChatNavigation() = runTest {
        val h = ContainerHarness(this, store, offlineMaps = offlineMaps())
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val radio = assertNotNull(h.appState.currentRadioId)
            val contact = ContactDTO(
                radioId = radio, publicKey = key(42), name = "Map contact", typeRawValue = 1u,
                latitude = 37.0, longitude = -122.0, lastHeardTimestamp = 0u, isFavorite = true,
            )
            store.saveContact(contact)
            store.upsertDiscoveredNode(
                radio,
                ContactFrame(
                    contact.publicKey, contact.type, 0u, 255u,
                    com.meshcoreone.android.core.protocol.bytes.Bytes.EMPTY,
                    "Already a contact", 1u, 38.0, -123.0, 1u,
                ),
            )
            store.setInboundHopCount(radio, contact.publicKey, 2, 1u)
            val invalid = store.upsertDiscoveredNode(
                radio,
                ContactFrame(
                    key(44), contact.type, 0u, 255u,
                    com.meshcoreone.android.core.protocol.bytes.Bytes.EMPTY,
                    "Invalid fix", 1u, 91.0, -123.0, 1u,
                ),
            ).node
            val feature = h.container.mapFeature
            val markers = feature.dataSource.load().markers
            assertFalse(markers.any { it.marker.id == invalid.id || it.isDiscovered })
            val row = markers.single { it.marker.id == contact.id }
            assertEquals(contact, (row.selection as MapSelection.Contact).contact)
            assertTrue(row.isFavorite)
            assertEquals(2L, row.displayedHops)
            val actions = assertNotNull(feature.actions)
            actions.openContact(contact)
            assertEquals(contact, h.container.navigation.state.value.pendingContactDetail)
            actions.openChat(contact)
            assertEquals(contact, h.container.navigation.state.value.pendingChatContact)
        } finally { h.container.close() }
    }

    @Test fun discoveryRetainsItsDtoAndAStaleRadioCannotNavigateOrAddToTheReplacementSession() = runTest {
        val h = ContainerHarness(this, store, offlineMaps = offlineMaps())
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val radio = assertNotNull(h.appState.currentRadioId)
            val frame = ContactFrame(
                key(43), com.meshcoreone.android.core.protocol.model.ContactType.REPEATER,
                0u, 255u, com.meshcoreone.android.core.protocol.bytes.Bytes.EMPTY,
                "Discovered map node", 20u, 38.0, -123.0, 20u,
            )
            val node = store.upsertDiscoveredNode(radio, frame).node
            val feature = h.container.mapFeature
            val row = feature.dataSource.load().markers.single { it.marker.id == node.id }
            assertEquals(node, (row.selection as MapSelection.Discovered).node)
            val oldContact = ContactDTO(
                radioId = radio, publicKey = key(42), name = "Old radio contact",
                latitude = 37.0, longitude = -122.0, lastHeardTimestamp = 0u,
            )
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED); h.settle()
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            // Both links have the same synthetic radio key by default; use a genuinely foreign identity.
            val foreign = RadioId(java.util.UUID(0, 999))
            val actions = assertNotNull(feature.actions)
            assertFailsWith<ContactServiceError.NotConnected> { actions.openContact(oldContact.copy(radioId = foreign)) }
            assertFailsWith<ContactServiceError.NotConnected> { actions.addDiscovered(node.copy(radioId = foreign)) }
            assertNull(h.container.navigation.state.value.pendingContactDetail)
        } finally { h.container.close() }
    }

    private fun offlineMaps() = OfflineMapController(
        backend = UnavailableOfflineMapBackend,
        policies = emptyMap(),
        availableBytes = { error("App map adapter tests must not request downloads") },
        networkAvailable = { false },
    )
}
