// PortedFrom: MC1Tests/ViewModels/PathManagementViewModelEditingTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.path

import com.meshcoreone.android.core.ui.hoppicker.PickerNode
import com.meshcoreone.android.core.ui.hoppicker.HopNodeMatching

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.Fixtures.pathContact
import com.meshcoreone.android.feature.nodes.support.Harness
import com.meshcoreone.android.feature.nodes.support.ManualClock
import com.meshcoreone.android.feature.nodes.support.MemoryPreferences
import com.meshcoreone.android.feature.nodes.support.OriginalCase
import com.meshcoreone.android.feature.nodes.support.scenario
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** Picker query matching and the per-radio recents LRU (second half of the Swift editing suite). */
class PathManagementViewModelMatchingTest {
    private fun named(name: String, key: Bytes = Fixtures.repeated(0xAA)) = PickerNode.Contact(pathContact(name = name, publicKey = key))

    @Test @OriginalCase("PathManagementViewModelEditingTests::Empty query matches everything()")
    fun `Empty query matches everything`() {
        assertTrue(HopNodeMatching.matches(named("Basecamp"), ""))
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::Name substring is case-insensitive()")
    fun `Name substring is case-insensitive`() {
        val node = named("Basecamp North")
        assertTrue(HopNodeMatching.matches(node, "bas"))
        assertTrue(HopNodeMatching.matches(node, "BASE"))
        assertTrue(HopNodeMatching.matches(node, "north"))
        assertFalse(HopNodeMatching.matches(node, "zzz"))
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::Turkish dotless-I folds correctly()")
    fun `Turkish dotless-I folds correctly`() {
        // Case- and diacritic-insensitive matching folds the dotted capital I regardless of locale.
        val saved = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale)
                assertTrue(HopNodeMatching.matches(named("\u0130stanbul Relay"), "istanbul"), "default locale $locale")
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::Hex prefix matches full pubkey()")
    fun `Hex prefix matches full pubkey`() {
        val node = named("Unrelated", Fixtures.key(0xA3, 0xF2))
        assertTrue(HopNodeMatching.matches(node, "a3f2"))
        assertTrue(HopNodeMatching.matches(node, "A3F2"))
        assertFalse(HopNodeMatching.matches(node, "b7"))
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::All-hex query matches both name and pubkey hex()")
    fun `All-hex query matches both name and pubkey hex`() {
        assertTrue(HopNodeMatching.matches(named("A3 Basecamp", Fixtures.key(0xA3)), "a3"))
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::Non-hex query ignores pubkey()")
    fun `Non-hex query ignores pubkey`() {
        assertFalse(HopNodeMatching.matches(named("Summit"), "foo"))
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::isHexQuery detects valid hex digits()")
    fun `isHexQuery detects valid hex digits`() {
        assertTrue(HopNodeMatching.isHexQuery("a3f2"))
        assertTrue(HopNodeMatching.isHexQuery("A3F2"))
        assertTrue(HopNodeMatching.isHexQuery("0123456789abcdefABCDEF"))
        assertFalse(HopNodeMatching.isHexQuery("a3z"))
        assertFalse(HopNodeMatching.isHexQuery(""))
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::filtered returns all nodes for an empty query()")
    fun `filtered returns all nodes for an empty query`() {
        assertEquals(2, HopNodeMatching.filtered(listOf(named("A"), named("B")), "").size)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::filtered keeps only name-substring matches for a non-hex query()")
    fun `filtered keeps only name-substring matches for a non-hex query`() {
        val result = HopNodeMatching.filtered(listOf(named("Basecamp"), named("Summit")), "base")
        assertEquals(1, result.size)
        assertEquals("Basecamp", result[0].displayName)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::filtered hex query matches both name and pubkey-hex prefix branches()")
    fun `filtered hex query matches both name and pubkey-hex prefix branches`() {
        val nodes = listOf(named("Foo", Fixtures.key(0xA3)), named("A3 Basecamp", Fixtures.key(0x89)), named("Zeta", Fixtures.key(0xF0)))
        assertEquals(2, HopNodeMatching.filtered(nodes, "a3").size)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::record recent moves to front()")
    fun `record recent moves to front`() = scenario {
        val vm = PathManagementStateHolder(Harness(clock).dependencies, scope)
        vm.loadRecentKeys(Fixtures.radio())
        val first = Fixtures.repeated(0x01)
        val second = Fixtures.repeated(0x02)
        vm.insert(pathContact(publicKey = first), AddHopIntent.APPEND)
        vm.insert(pathContact(publicKey = second), AddHopIntent.APPEND)
        vm.insert(pathContact(publicKey = first), AddHopIntent.APPEND)
        assertEquals(listOf(first, second), vm.state.value.recentPublicKeys)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::record recent trims to limit()")
    fun `record recent trims to limit`() = scenario {
        val vm = PathManagementStateHolder(Harness(clock).dependencies, scope)
        vm.loadRecentKeys(Fixtures.radio())
        for (index in 0 until 10) vm.insert(pathContact(publicKey = Fixtures.repeated(index)), AddHopIntent.APPEND)
        val recents = vm.state.value.recentPublicKeys
        assertEquals(8, recents.size)
        assertEquals(Fixtures.repeated(9), recents.first())
        assertEquals(Fixtures.repeated(2), recents.last())
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::recent keys persist across instances()")
    fun `recent keys persist across instances`() = scenario {
        val defaults = MemoryPreferences()
        val radio = Fixtures.radio()
        val key = Fixtures.repeated(0x0A)
        val first = PathManagementStateHolder(Harness(clock, defaults).dependencies, scope)
        first.loadRecentKeys(radio)
        first.insert(pathContact(publicKey = key), AddHopIntent.APPEND)

        val second = PathManagementStateHolder(Harness(ManualClock(), defaults).dependencies, scope)
        second.loadRecentKeys(radio)
        assertEquals(listOf(key), second.state.value.recentPublicKeys)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::recent keys scoped per radio()")
    fun `recent keys scoped per radio`() = scenario {
        val defaults = MemoryPreferences()
        val first = PathManagementStateHolder(Harness(clock, defaults).dependencies, scope)
        first.loadRecentKeys(Fixtures.radio())
        first.insert(pathContact(publicKey = Fixtures.repeated(0x0A)), AddHopIntent.APPEND)

        val second = PathManagementStateHolder(Harness(clock, defaults).dependencies, scope)
        second.loadRecentKeys(Fixtures.radio())
        assertTrue(second.state.value.recentPublicKeys.isEmpty())
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::recent keys stored lowercase()")
    fun `recent keys stored lowercase`() = scenario {
        val defaults = MemoryPreferences()
        val vm = PathManagementStateHolder(Harness(clock, defaults).dependencies, scope)
        val radio: RadioId = Fixtures.radio()
        vm.loadRecentKeys(radio)
        vm.insert(pathContact(publicKey = Fixtures.key(0xAB, 0xCD)), AddHopIntent.APPEND)
        val stored = defaults.stringList(RecentHopsStore.defaultsKey(radio)).orEmpty()
        assertEquals(1, stored.size)
        assertEquals(stored[0].lowercase(Locale.ROOT), stored[0])
        assertTrue(stored[0].startsWith("abcd"))
    }
}
