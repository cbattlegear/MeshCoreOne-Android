// AndroidOnly: WP-314 Trace picker shared ordering, matching and room resolution.
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.ui.hoppicker.HopPickerFilter
import com.meshcoreone.android.core.ui.hoppicker.buildHopPickerResults
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class TraceHopPickerTest {
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
