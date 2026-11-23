// AndroidOnly: WP-314 Trace picker shared ordering, matching and room resolution.
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.ui.hoppicker.HopPickerFilter
import com.meshcoreone.android.core.ui.hoppicker.buildHopPickerResults
import java.util.Locale
import java.util.UUID
import com.meshcoreone.android.core.model.RadioId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class TraceHopPickerTest {
    @Test fun recentSelectionsAreIsolatedAcrossRadioRebindings() {
        val h = TraceHarness(configureDependencies = true)
        val secondRadio = RadioId(UUID.randomUUID())
        val firstNode = contact(0xAA, name = "First")
        val secondNode = contact(0xBB, name = "Second")
        h.deps.device = device()
        h.deps.directory = FakeNodeDirectory(contacts = listOf(firstNode, secondNode))
        h.holder.bindConnection(RADIO, 1)
        h.complete { h.holder.loadContacts(RADIO) }
        h.holder.appendHop(firstNode)
        h.deps.device = device(radioId = secondRadio)
        h.holder.bindConnection(secondRadio, 2)
        assertTrue(h.state.recentPublicKeys.isEmpty())
        h.complete { h.holder.loadContacts(secondRadio) }
        h.holder.appendHop(secondNode)
        assertEquals(listOf(secondNode.publicKey), h.state.recentPublicKeys)
        h.deps.device = device()
        h.holder.bindConnection(RADIO, 3)
        h.complete { h.holder.loadContacts(RADIO) }
        assertEquals(listOf(firstNode.publicKey), h.state.recentPublicKeys)
    }

    @Test fun sectionsDeduplicateAndSingleFiltersRetainTheirNodes() {
        val favorite = contact(0xCA, name = "Café", isFavorite = true)
        val room = contact(0xBB, name = "Room", type = ContactType.ROOM)
        val scout = discovered(key(0xCC), "Scout")
        val duplicate = discovered(favorite.publicKey, "Duplicate")
        fun results(filter: HopPickerFilter, query: String = "") = buildHopPickerResults(
            listOf(favorite), listOf(room), listOf(scout, duplicate),
            listOf(room.publicKey, favorite.publicKey), filter, query, Locale.US,
        )
        val all = results(HopPickerFilter.ALL)
        assertEquals(listOf(room.publicKey, favorite.publicKey), all.recent.map { it.underlying.publicKey })
        assertTrue(all.favorites.isEmpty())
        assertTrue(all.rooms.isEmpty())
        assertEquals(listOf(scout.publicKey), all.discovered.map { it.underlying.publicKey })
        assertEquals(listOf(favorite.publicKey), results(HopPickerFilter.FAVORITES, "CAFE").favorites.map { it.underlying.publicKey })
        assertEquals(listOf(scout.publicKey), results(HopPickerFilter.DISCOVERED, "cc").discovered.map { it.underlying.publicKey })
        assertEquals(2, results(HopPickerFilter.DISCOVERED).discovered.size)
    }
}
