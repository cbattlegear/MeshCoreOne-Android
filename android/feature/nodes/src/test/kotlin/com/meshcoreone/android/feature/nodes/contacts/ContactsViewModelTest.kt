// PortedFrom: MC1Tests/ViewModels/ContactsViewModelTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.contacts

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.add.AddContactStateHolder
import com.meshcoreone.android.feature.nodes.model.NodeSegment
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.Harness
import com.meshcoreone.android.feature.nodes.support.OriginalCase
import com.meshcoreone.android.feature.nodes.support.scenario
import com.meshcoreone.android.feature.nodes.support.settle
import com.meshcoreone.android.feature.nodes.support.Fixtures.contact
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Test

class ContactsViewModelTest {
    private val flood: UByte = 0xFFu

    private fun Harness.holder(scope: kotlinx.coroutines.CoroutineScope) = ContactsStateHolder(dependencies, scope)

    private fun ContactsStateHolder.filtered(
        searchText: String = "",
        segment: NodeSegment = NodeSegment.CONTACTS,
        sortOrder: NodeSortOrder = NodeSortOrder.NAME,
        userLocation: Coordinate? = null,
    ) = filteredContacts(searchText, segment, sortOrder, userLocation)

    private fun ContactsStateHolder.setContacts(vararg rows: com.meshcoreone.android.core.model.ContactDTO) = seed { it.copy(contacts = rows.toList()) }

    @Test @OriginalCase("ContactsViewModelTests::hasLoadedOnce starts false()")
    fun `hasLoadedOnce starts false`() = scenario {
        assertFalse(Harness(clock).holder(scope).state.value.hasLoadedOnce)
    }

    @Test @OriginalCase("ContactsViewModelTests::isLoading starts false()")
    fun `isLoading starts false`() = scenario {
        assertFalse(Harness(clock).holder(scope).state.value.isLoading)
    }

    @Test @OriginalCase("ContactsViewModelTests::contacts starts empty()")
    fun `contacts starts empty`() = scenario {
        assertTrue(Harness(clock).holder(scope).state.value.contacts.isEmpty())
    }

    @Test @OriginalCase("ContactsViewModelTests::loadContacts with nil dataStore returns early without setting hasLoadedOnce()")
    fun `loadContacts with nil dataStore returns early without setting hasLoadedOnce`() = scenario {
        val holder = Harness(clock).holder(scope)
        holder.loadContacts(Fixtures.radio())
        val state = holder.state.value
        assertTrue(state.contacts.isEmpty())
        assertFalse(state.hasLoadedOnce)
        assertFalse(state.isLoading)
    }

    @Test @OriginalCase("ContactsViewModelTests::syncContacts with nil contactService returns early()")
    fun `syncContacts with nil contactService returns early`() = scenario {
        val holder = Harness(clock).holder(scope)
        holder.syncContacts(Fixtures.radio())
        assertFalse(holder.state.value.isSyncing)
        assertNull(holder.state.value.syncProgress)
    }

    @Test @OriginalCase("ContactsViewModelTests::toggleFavorite with nil contactService returns early()")
    fun `toggleFavorite with nil contactService returns early`() = scenario {
        val holder = Harness(clock).holder(scope)
        holder.toggleFavorite(contact(name = "Test"))
        assertNull(holder.state.value.togglingFavoriteId)
        assertNull(holder.state.value.errorMessage)
    }

    @Test @OriginalCase("ContactsViewModelTests::hasFavorites is false with no favorites()")
    fun `hasFavorites is false with no favorites`() = scenario {
        val holder = Harness(clock).holder(scope)
        val device = Fixtures.radio()
        holder.setContacts(contact(radioId = device, name = "Alice"), contact(radioId = device, name = "Bob"))
        assertFalse(holder.state.value.hasFavorites)
    }

    @Test @OriginalCase("ContactsViewModelTests::hasFavorites is false when contacts empty()")
    fun `hasFavorites is false when contacts empty`() = scenario {
        assertFalse(Harness(clock).holder(scope).state.value.hasFavorites)
    }

    @Test @OriginalCase("ContactsViewModelTests::hasFavorites is true when a favorite exists()")
    fun `hasFavorites is true when a favorite exists`() = scenario {
        val holder = Harness(clock).holder(scope)
        val device = Fixtures.radio()
        holder.setContacts(contact(radioId = device, name = "Alice"), contact(radioId = device, name = "Bob", isFavorite = true))
        assertTrue(holder.state.value.hasFavorites)
    }

    @Test @OriginalCase("ContactsViewModelTests::filteredContacts favorites segment returns only favorites()")
    fun `filteredContacts favorites segment returns only favorites`() = scenario {
        val holder = Harness(clock).holder(scope)
        val device = Fixtures.radio()
        holder.setContacts(
            contact(radioId = device, name = "Alice", isFavorite = true),
            contact(radioId = device, name = "Bob"),
            contact(radioId = device, name = "Charlie", isFavorite = true),
        )
        val names = holder.filtered(segment = NodeSegment.FAVORITES).map { it.name }
        assertEquals(2, names.size)
        assertTrue("Alice" in names)
        assertTrue("Charlie" in names)
        assertFalse("Bob" in names)
    }

    @Test @OriginalCase("ContactsViewModelTests::filteredContacts contacts segment returns only chat type()")
    fun `filteredContacts contacts segment returns only chat type`() = scenario {
        val holder = Harness(clock).holder(scope)
        val device = Fixtures.radio()
        holder.setContacts(
            contact(radioId = device, name = "Alice"),
            contact(radioId = device, name = "Relay1", type = ContactType.REPEATER),
            contact(radioId = device, name = "Room1", type = ContactType.ROOM),
        )
        val result = holder.filtered()
        assertEquals(1, result.size)
        assertEquals("Alice", result.first().name)
    }

    @Test @OriginalCase("ContactsViewModelTests::filteredContacts repeaters segment returns only repeater type()")
    fun `filteredContacts repeaters segment returns only repeater type`() = scenario {
        val holder = Harness(clock).holder(scope)
        val device = Fixtures.radio()
        holder.setContacts(
            contact(radioId = device, name = "Alice"),
            contact(radioId = device, name = "Relay1", type = ContactType.REPEATER),
            contact(radioId = device, name = "Relay2", type = ContactType.REPEATER),
            contact(radioId = device, name = "Room1", type = ContactType.ROOM),
        )
        val result = holder.filtered(segment = NodeSegment.REPEATERS)
        assertEquals(2, result.size)
        assertTrue(result.all { it.type == ContactType.REPEATER })
    }

    @Test @OriginalCase("ContactsViewModelTests::filteredContacts rooms segment returns only room type()")
    fun `filteredContacts rooms segment returns only room type`() = scenario {
        val holder = Harness(clock).holder(scope)
        val device = Fixtures.radio()
        holder.setContacts(
            contact(radioId = device, name = "Alice"),
            contact(radioId = device, name = "Relay1", type = ContactType.REPEATER),
            contact(radioId = device, name = "Room1", type = ContactType.ROOM),
            contact(radioId = device, name = "Room2", type = ContactType.ROOM),
        )
        val result = holder.filtered(segment = NodeSegment.ROOMS)
        assertEquals(2, result.size)
        assertTrue(result.all { it.type == ContactType.ROOM })
    }

    @Test @OriginalCase("ContactsViewModelTests::filteredContacts with search text ignores segment and filters by name()")
    fun `filteredContacts with search text ignores segment and filters by name`() = scenario {
        val holder = Harness(clock).holder(scope)
        val device = Fixtures.radio()
        holder.setContacts(
            contact(radioId = device, name = "Alice"),
            contact(radioId = device, name = "Relay-Alpha", type = ContactType.REPEATER),
            contact(radioId = device, name = "Bob"),
        )
        val names = holder.filtered(searchText = "al").map { it.name }
        assertEquals(2, names.size)
        assertTrue("Alice" in names)
        assertTrue("Relay-Alpha" in names)
    }

    @Test @OriginalCase("ContactsViewModelTests::filteredContacts with no matching search returns empty()")
    fun `filteredContacts with no matching search returns empty`() = scenario {
        val holder = Harness(clock).holder(scope)
        holder.setContacts(contact(name = "Alice"), contact(name = "Bob"))
        assertTrue(holder.filtered(searchText = "zzz").isEmpty())
    }

    @Test @OriginalCase("ContactsViewModelTests::filteredContacts sorted by name returns alphabetical order()")
    fun `filteredContacts sorted by name returns alphabetical order`() = scenario {
        val holder = Harness(clock).holder(scope)
        val device = Fixtures.radio()
        holder.setContacts(contact(radioId = device, name = "Charlie"), contact(radioId = device, name = "Alice"), contact(radioId = device, name = "Bob"))
        assertEquals(listOf("Alice", "Bob", "Charlie"), holder.filtered().map { it.name })
    }

    @Test @OriginalCase("ContactsViewModelTests::filteredContacts sorted by lastHeard returns most recent first()")
    fun `filteredContacts sorted by lastHeard returns most recent first`() = scenario {
        val holder = Harness(clock).holder(scope)
        val device = Fixtures.radio()
        holder.setContacts(
            contact(radioId = device, name = "Old", lastModified = 100u),
            contact(radioId = device, name = "Recent", lastModified = 300u),
            contact(radioId = device, name = "Middle", lastModified = 200u),
        )
        assertEquals(listOf("Recent", "Middle", "Old"), holder.filtered(sortOrder = NodeSortOrder.LAST_HEARD).map { it.name })
    }

    @Test @OriginalCase("ContactsViewModelTests::filteredContacts sorted by distance falls back to name without location()")
    fun `filteredContacts sorted by distance falls back to name without location`() = scenario {
        val holder = Harness(clock).holder(scope)
        val device = Fixtures.radio()
        holder.setContacts(contact(radioId = device, name = "Charlie"), contact(radioId = device, name = "Alice"))
        assertEquals(listOf("Alice", "Charlie"), holder.filtered(sortOrder = NodeSortOrder.DISTANCE).map { it.name })
    }

    @Test @OriginalCase("ContactsViewModelTests::filteredContacts sorted by distance with user location orders by proximity()")
    fun `filteredContacts sorted by distance with user location orders by proximity`() = scenario {
        val holder = Harness(clock).holder(scope)
        val device = Fixtures.radio()
        val sanFrancisco = Coordinate(37.7749, -122.4194)
        holder.setContacts(
            contact(radioId = device, name = "FarAway", latitude = 40.7128, longitude = -74.0060),
            contact(radioId = device, name = "Nearby", latitude = 37.8044, longitude = -122.2712),
        )
        val result = holder.filtered(sortOrder = NodeSortOrder.DISTANCE, userLocation = sanFrancisco)
        assertEquals("Nearby", result.first().name)
        assertEquals("FarAway", result.last().name)
    }

    @Test @OriginalCase("ContactsViewModelTests::filteredContacts sorted by hops returns fewest first with flood-routed last()")
    fun `filteredContacts sorted by hops returns fewest first with flood-routed last`() = scenario {
        val holder = Harness(clock).holder(scope)
        val device = Fixtures.radio()
        holder.setContacts(
            contact(radioId = device, name = "ThreeHop", outPathLength = 3u),
            contact(radioId = device, name = "Flood", outPathLength = flood),
            contact(radioId = device, name = "OneHop", outPathLength = 1u),
        )
        assertEquals(listOf("OneHop", "ThreeHop", "Flood"), holder.filtered(sortOrder = NodeSortOrder.HOPS).map { it.name })
    }

    @Test @OriginalCase("ContactsViewModelTests::filteredContacts sorted by hops breaks ties by distance, including the flood group()")
    fun `filteredContacts sorted by hops breaks ties by distance, including the flood group`() = scenario {
        val holder = Harness(clock).holder(scope)
        val device = Fixtures.radio()
        holder.setContacts(
            contact(radioId = device, name = "FarFlood", latitude = 0.0, longitude = 5.0, outPathLength = flood),
            contact(radioId = device, name = "FarSameHop", latitude = 0.0, longitude = 1.0, outPathLength = 2u),
            contact(radioId = device, name = "NearSameHop", latitude = 0.0, longitude = 0.1, outPathLength = 2u),
            contact(radioId = device, name = "NearFlood", latitude = 0.0, longitude = 0.5, outPathLength = flood),
        )
        val result = holder.filtered(sortOrder = NodeSortOrder.HOPS, userLocation = Coordinate(0.0, 0.0))
        // Direct nodes first, nearest first; the flood group last, nearest first.
        assertEquals(listOf("NearSameHop", "FarSameHop", "NearFlood", "FarFlood"), result.map { it.name })
    }

    @Test @OriginalCase("ContactsViewModelTests::filteredContacts sorted by hops orders a flood node by its known inbound hop count()")
    fun `filteredContacts sorted by hops orders a flood node by its known inbound hop count`() = scenario {
        val holder = Harness(clock).holder(scope)
        val device = Fixtures.radio()
        val oneHop = contact(radioId = device, name = "OneHop", outPathLength = 1u)
        val floodTwoInbound = contact(radioId = device, name = "FloodTwoInbound", outPathLength = flood)
        val threeHop = contact(radioId = device, name = "ThreeHop", outPathLength = 3u)
        val floodUnknown = contact(radioId = device, name = "FloodUnknown", outPathLength = flood)
        holder.seed { it.copy(contacts = listOf(floodUnknown, threeHop, floodTwoInbound, oneHop), inboundHopByKey = mapOf(floodTwoInbound.publicKey to 2L)) }
        val result = holder.filtered(sortOrder = NodeSortOrder.HOPS)
        // The flood node interleaves by its inbound count; only the unknown flood node sorts last.
        assertEquals(listOf("OneHop", "FloodTwoInbound", "ThreeHop", "FloodUnknown"), result.map { it.name })
    }

    @Test @OriginalCase("ContactsViewModelTests::upsert appends a contact that is not already in the list()")
    fun `upsert appends a contact that is not already in the list`() = scenario {
        val holder = Harness(clock).holder(scope)
        val existing = contact(name = "Alex")
        val added = contact(name = "Sam")
        holder.seed { it.copy(contacts = listOf(existing), pendingRemovalIds = setOf(added.id)) }
        holder.upsert(added)
        assertEquals(listOf(existing.id, added.id), holder.state.value.contacts.map { it.id })
        assertFalse(added.id in holder.state.value.pendingRemovalIds)
    }

    @Test @OriginalCase("ContactsViewModelTests::upsert replaces an existing contact with the same id()")
    fun `upsert replaces an existing contact with the same id`() = scenario {
        val holder = Harness(clock).holder(scope)
        val original = contact(name = "Alex")
        val updated = contact(id = original.id, radioId = original.radioId, publicKey = original.publicKey, name = "Alex Updated")
        holder.seed { it.copy(contacts = listOf(original), pendingRemovalIds = setOf(original.id)) }
        holder.upsert(updated)
        val contacts = holder.state.value.contacts
        assertEquals(1, contacts.size)
        assertEquals("Alex Updated", contacts.first().name)
        assertEquals(original.id, contacts.first().id)
        assertFalse(holder.isDeletePending(updated.id))
    }

    @Test @OriginalCase("ContactsViewModelTests::upsert replaces an existing contact with the same public key and radio()")
    fun `upsert replaces an existing contact with the same public key and radio`() = scenario {
        val holder = Harness(clock).holder(scope)
        val radio = Fixtures.radio()
        val key = Fixtures.randomKey()
        val original = contact(radioId = radio, publicKey = key, name = "Alex")
        val updated = contact(id = UUID.randomUUID(), radioId = radio, publicKey = key, name = "Sam")
        holder.seed { it.copy(contacts = listOf(original), pendingRemovalIds = setOf(original.id, updated.id)) }
        holder.upsert(updated)
        val contacts = holder.state.value.contacts
        assertEquals(1, contacts.size)
        assertEquals(updated.id, contacts.first().id)
        assertEquals("Sam", contacts.first().name)
        assertFalse(holder.isDeletePending(original.id))
        assertFalse(holder.isDeletePending(updated.id))
    }

    @Test @OriginalCase("ContactsViewModelTests::loadContacts keeps an upserted contact until the fetch includes it()", "native-equivalent")
    fun `loadContacts keeps an upserted contact until the fetch includes it`() = scenario {
        // In-memory store double replaces the SwiftData PersistenceStore container.
        val harness = Harness(clock)
        val radio = Fixtures.radio()
        harness.session.offlineStore = harness.store
        val existing = contact(radioId = radio, name = "Alex")
        val added = contact(radioId = radio, name = "Sam")
        harness.store.contacts += existing
        val holder = harness.holder(scope)
        holder.loadContacts(radio)
        holder.upsert(added)

        holder.loadContacts(radio)
        assertEquals(setOf(existing.id, added.id), holder.filtered().map { it.id }.toSet())

        harness.store.contacts += added
        holder.loadContacts(radio)
        assertEquals(1, holder.state.value.contacts.count { it.id == added.id })
    }

    @Test
    fun `real add result survives an already held first fetch until store includes its identity`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val existing = contact(radioId = radio, name = "Alex")
        val added = contact(radioId = radio, publicKey = Fixtures.repeated(0xAB), name = "Sam")
        harness.store.contacts += existing
        harness.contactService.addBehavior = { _, _ -> }
        harness.contactService.getContactBehavior = { _, _ -> added }
        val holder = harness.holder(scope)
        holder.seed { it.copy(pendingRemovalIds = setOf(added.id)) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.store.fetchContactsGate = {
            entered.complete(Unit)
            release.await()
        }
        val loading = scope.launch { holder.loadContacts(radio) }
        entered.await()
        val form = AddContactStateHolder(harness.dependencies)
        form.setContactName(added.name)
        form.setPublicKeyHex(added.publicKey.hexString)
        holder.upsert(checkNotNull(form.addContact()))
        assertEquals(listOf(added.id), holder.filtered().map { it.id })
        assertFalse(holder.isDeletePending(added.id))
        release.complete(Unit)
        loading.join()
        harness.store.fetchContactsGate = null
        assertEquals(setOf(existing.id, added.id), holder.filtered().map { it.id }.toSet())
        holder.loadContacts(radio)
        assertEquals(1, holder.filtered().count { it.id == added.id })

        harness.store.contacts += added
        holder.loadContacts(radio)
        holder.loadContacts(radio)
        settle()
        assertEquals(1, holder.filtered().count { it.id == added.id })
        assertEquals(listOf("add:Sam", "getContact"), harness.contactService.calls)
    }
}
