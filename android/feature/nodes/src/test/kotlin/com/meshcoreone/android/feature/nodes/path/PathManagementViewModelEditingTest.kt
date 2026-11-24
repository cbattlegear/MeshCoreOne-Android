// PortedFrom: MC1Tests/ViewModels/PathManagementViewModelEditingTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.path

import com.meshcoreone.android.core.ui.hoppicker.PickerNode

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.Fixtures.pathContact
import com.meshcoreone.android.feature.nodes.support.Harness
import com.meshcoreone.android.feature.nodes.support.OriginalCase
import com.meshcoreone.android.feature.nodes.support.scenario
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import org.junit.Test

/** Path editing: picker favorites, insert, initialize/normalize, resolve, save rejection and encoding. */
class PathManagementViewModelEditingTest {
    private fun vm(harness: Harness, scope: CoroutineScope) = PathManagementStateHolder(harness.dependencies, scope)

    private fun hop(vararg bytes: Int, publicKey: Bytes? = null, name: String? = null) = PathHop(Bytes.of(*bytes), publicKey, name)

    @Test @OriginalCase("PathManagementViewModelEditingTests::Contact with isFavorite=true reports favorite()")
    fun `Contact with isFavorite=true reports favorite`() {
        assertTrue(PickerNode.Contact(pathContact(isFavorite = true)).isFavorite)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::Discovered node is never favorite()")
    fun `Discovered node is never favorite`() {
        assertFalse(PickerNode.Discovered(Fixtures.discoveredRepeater()).isFavorite)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::insert with .append adds at end of editablePath()")
    fun `insert with append adds at end of editablePath`() = scenario {
        val vm = vm(Harness(clock), scope)
        val node = pathContact(name = "A", publicKey = Fixtures.repeated(0x01))
        vm.insert(node, AddHopIntent.APPEND)
        assertEquals(1, vm.state.value.editablePath.size)
        assertEquals(node.publicKey, vm.state.value.editablePath[0].publicKey)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::insert preserves insertionIntent so picker stays for multi-add()")
    fun `insert preserves insertionIntent so picker stays for multi-add`() = scenario {
        val vm = vm(Harness(clock), scope)
        vm.setInsertionIntent(AddHopIntent.APPEND)
        vm.insert(pathContact(), AddHopIntent.APPEND)
        assertEquals(AddHopIntent.APPEND, vm.state.value.insertionIntent)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::initializeEditablePath clears a stale insertionIntent()")
    fun `initializeEditablePath clears a stale insertionIntent`() = scenario {
        val vm = vm(Harness(clock), scope)
        vm.setInsertionIntent(AddHopIntent.APPEND)
        vm.initializeEditablePath(pathContact()) // flood, no path
        assertNull(vm.state.value.insertionIntent)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::initializeEditablePath resolves publicKey for uniquely-matching contact()")
    fun `initializeEditablePath resolves publicKey for uniquely-matching contact`() = scenario {
        val vm = vm(Harness(clock), scope)
        val radio = Fixtures.radio()
        val fullKey = Fixtures.key(0xA1, 0xB2, 0xC3)
        vm.seed { it.copy(allContacts = listOf(pathContact(name = "Hop1", publicKey = fullKey, radioId = radio))) }
        // One hop at hashSize 1: pathLength (0 << 6) | 1.
        val target = pathContact(name = "Target", publicKey = Fixtures.repeated(0xFE), radioId = radio, outPathLength = 0x01u, outPath = Bytes.of(0xA1))
        vm.initializeEditablePath(target)
        val path = vm.state.value.editablePath
        assertEquals(1, path.size)
        assertEquals(fullKey, path[0].publicKey)
        assertEquals("Hop1", path[0].resolvedName)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::resolveHashToPublicKey returns nil when multiple contacts share a prefix()")
    fun `resolveHashToPublicKey returns nil when multiple contacts share a prefix`() = scenario {
        val vm = vm(Harness(clock), scope)
        vm.seed { it.copy(allContacts = listOf(pathContact(name = "A", publicKey = Fixtures.key(0xA1, 0x00)), pathContact(name = "B", publicKey = Fixtures.key(0xA1, 0x01)))) }
        assertNull(vm.resolveHashToPublicKey(Bytes.of(0xA1)))
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::resolveHashToPublicKey falls through to discovered when contacts have no match()")
    fun `resolveHashToPublicKey falls through to discovered when contacts have no match`() = scenario {
        val vm = vm(Harness(clock), scope)
        val fullKey = Fixtures.key(0xDD, 0xEE)
        vm.seed { it.copy(allContacts = emptyList(), discoveredNodes = listOf(Fixtures.discoveredRepeater(publicKey = fullKey))) }
        assertEquals(fullKey, vm.resolveHashToPublicKey(Bytes.of(0xDD)))
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::resolveHashToPublicKey ignores discovered when contacts are ambiguous()")
    fun `resolveHashToPublicKey ignores discovered when contacts are ambiguous`() = scenario {
        // An ambiguous contact match never falls through: it could itself be the target.
        val vm = vm(Harness(clock), scope)
        vm.seed {
            it.copy(
                allContacts = listOf(pathContact(name = "A", publicKey = Fixtures.key(0xA1, 0x00)), pathContact(name = "B", publicKey = Fixtures.key(0xA1, 0x01))),
                discoveredNodes = listOf(Fixtures.discoveredRepeater(publicKey = Fixtures.key(0xA1, 0x02))),
            )
        }
        assertNull(vm.resolveHashToPublicKey(Bytes.of(0xA1)))
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::saveRejection flags hop missing publicKey when target hashSize grew()")
    fun `saveRejection flags hop missing publicKey when target hashSize grew`() {
        // Stored at hashSize 1; device now reports 2.
        assertNotNull(PathEditing.saveRejection(listOf(hop(0xA1)), targetHashSize = 2, maxHopCount = 32))
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::saveRejection allows widening when publicKey is available()")
    fun `saveRejection allows widening when publicKey is available`() {
        val hops = listOf(hop(0xA1, publicKey = Fixtures.key(0xA1, 0xB2), name = "H1"))
        assertNull(PathEditing.saveRejection(hops, targetHashSize = 2, maxHopCount = 32))
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::saveRejection allows same-size encode even without publicKey()")
    fun `saveRejection allows same-size encode even without publicKey`() {
        assertNull(PathEditing.saveRejection(listOf(hop(0xA1)), targetHashSize = 1, maxHopCount = 63))
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::saveRejection flags hop-count over budget()")
    fun `saveRejection flags hop-count over budget`() {
        // 22 hops x 3 bytes = 66 > 64-byte budget; maxHopCount at hashSize 3 is 21.
        val hops = (0 until 22).map { index ->
            val key = Fixtures.key(index, 0x00, 0x00)
            PathHop(key.prefix(3), key, "H$index")
        }
        assertNotNull(PathEditing.saveRejection(hops, targetHashSize = 3, maxHopCount = 21))
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::encodeEditablePath uses full publicKey when available()")
    fun `encodeEditablePath uses full publicKey when available`() {
        val hops = listOf(hop(0xA1, publicKey = Fixtures.key(0xA1, 0xB2), name = "H1"), hop(0xC3, publicKey = Fixtures.key(0xC3, 0xD4), name = "H2"))
        val encoded = PathEditing.encodeEditablePath(hops, targetHashSize = 2)
        assertEquals(Bytes.of(0xA1, 0xB2, 0xC3, 0xD4), encoded.path)
        // (hashSize - 1) << 6 | hopCount = 0x42.
        assertEquals(0x42u.toUByte(), encoded.length)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::encodeEditablePath truncates publicKey to targetHashSize()")
    fun `encodeEditablePath truncates publicKey to targetHashSize`() {
        val encoded = PathEditing.encodeEditablePath(listOf(hop(0xA1, 0xB2, publicKey = Fixtures.key(0xA1, 0xB2), name = "H1")), targetHashSize = 1)
        assertEquals(Bytes.of(0xA1), encoded.path)
        assertEquals(0x01u.toUByte(), encoded.length)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::encodeEditablePath falls back to hashBytes when publicKey is nil()")
    fun `encodeEditablePath falls back to hashBytes when publicKey is nil`() {
        val encoded = PathEditing.encodeEditablePath(listOf(hop(0xA1, 0xB2), hop(0xC3, 0xD4)), targetHashSize = 2)
        assertEquals(Bytes.of(0xA1, 0xB2, 0xC3, 0xD4), encoded.path)
        assertEquals(0x42u.toUByte(), encoded.length)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::normalizeHop widens via publicKey when target grew()")
    fun `normalizeHop widens via publicKey when target grew`() {
        val key = Fixtures.key(0xA1, 0xB2, 0xC3)
        val normalized = PathEditing.normalizeHop(hop(0xA1, publicKey = key, name = "H1"), targetHashSize = 3)
        assertEquals(Bytes.of(0xA1, 0xB2, 0xC3), normalized.hashBytes)
        assertEquals(key, normalized.publicKey)
        assertEquals("H1", normalized.resolvedName)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::normalizeHop narrows via publicKey when target shrank()")
    fun `normalizeHop narrows via publicKey when target shrank`() {
        val key = Fixtures.key(0xA1, 0xB2, 0xC3)
        val normalized = PathEditing.normalizeHop(hop(0xA1, 0xB2, 0xC3, publicKey = key, name = "H1"), targetHashSize = 1)
        assertEquals(Bytes.of(0xA1), normalized.hashBytes)
        assertEquals(key, normalized.publicKey)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::normalizeHop narrows via hashBytes when publicKey unresolved()")
    fun `normalizeHop narrows via hashBytes when publicKey unresolved`() {
        val normalized = PathEditing.normalizeHop(hop(0xA1, 0xB2, 0xC3), targetHashSize = 1)
        assertEquals(Bytes.of(0xA1), normalized.hashBytes)
        assertNull(normalized.publicKey)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::normalizeHop leaves narrower unresolved hop unchanged()")
    fun `normalizeHop leaves narrower unresolved hop unchanged`() {
        val normalized = PathEditing.normalizeHop(hop(0xA1), targetHashSize = 3)
        assertEquals(Bytes.of(0xA1), normalized.hashBytes, "Narrower unresolved hops remain short so saveRejection can catch them")
        assertNull(normalized.publicKey)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::normalizeHop widens via publicKey even when stored is already at target width()")
    fun `normalizeHop widens via publicKey even when stored is already at target width`() {
        val normalized = PathEditing.normalizeHop(hop(0xA1, 0xB2, publicKey = Fixtures.key(0xA1, 0xB2), name = "H1"), targetHashSize = 2)
        assertEquals(Bytes.of(0xA1, 0xB2), normalized.hashBytes)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::initializeEditablePath narrows wider stored hops when resolution fails()")
    fun `initializeEditablePath narrows wider stored hops when resolution fails`() = scenario {
        // No device: hashSize 1. Stored mode 1 (2 bytes/hop), unresolvable hop.
        val vm = vm(Harness(clock), scope)
        vm.initializeEditablePath(pathContact(name = "Target", outPathLength = 0x41u, outPath = Bytes.of(0xA1, 0xB2)))
        val path = vm.state.value.editablePath
        assertEquals(1, path.size)
        assertEquals(Bytes.of(0xA1), path[0].hashBytes, "Wider stored hops should narrow to the device's hashSize on load")
        assertNull(path[0].publicKey)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::initializeEditablePath resliced resolved hop to device hashSize()")
    fun `initializeEditablePath resliced resolved hop to device hashSize`() = scenario {
        val vm = vm(Harness(clock), scope)
        val radio = Fixtures.radio()
        val fullKey = Fixtures.key(0xA1, 0xB2, 0xC3)
        vm.seed { it.copy(allContacts = listOf(pathContact(name = "Hop1", publicKey = fullKey, type = ContactType.REPEATER, radioId = radio))) }
        val target = pathContact(name = "Target", publicKey = Fixtures.repeated(0xFE), radioId = radio, outPathLength = 0x41u, outPath = Bytes.of(0xA1, 0xB2))
        vm.initializeEditablePath(target)
        val path = vm.state.value.editablePath
        assertEquals(1, path.size)
        assertEquals(fullKey, path[0].publicKey)
        assertEquals(Bytes.of(0xA1), path[0].hashBytes, "Resolved hop should be re-sliced to the device's hashSize")
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::initializeEditablePath leaves narrower unresolved hop unchanged so saveRejection blocks save()")
    fun `initializeEditablePath leaves narrower unresolved hop unchanged so saveRejection blocks save`() {
        // The normalize-then-reject pipeline initializeEditablePath runs under a wider device hash size.
        val storedHashSize = 1
        val storedPath = Bytes.of(0xA1)
        val targetHashSize = 3
        val hops = (0 until storedPath.size step storedHashSize).map { start ->
            PathEditing.normalizeHop(PathHop(storedPath.slice(start, minOf(start + storedHashSize, storedPath.size)), null, null), targetHashSize)
        }
        assertEquals(1, hops.size)
        assertEquals(Bytes.of(0xA1), hops[0].hashBytes, "Narrower unresolved hop must stay short so saveRejection can flag it.")
        assertNull(hops[0].publicKey)
        assertNotNull(PathEditing.saveRejection(hops, targetHashSize, maxHopCount = 21))
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::isPathFull is false with no hops at hashSize=1()")
    fun `isPathFull is false with no hops at hashSize=1`() = scenario {
        val vm = vm(Harness(clock), scope)
        assertFalse(vm.isPathFull)
        assertEquals(63, vm.maxHopCount)
    }

    @Test @OriginalCase("PathManagementViewModelEditingTests::insert is a no-op once isPathFull()")
    fun `insert is a no-op once isPathFull`() = scenario {
        val vm = vm(Harness(clock), scope)
        vm.seed { state -> state.copy(editablePath = (0 until vm.maxHopCount).map { PathHop(Bytes.of(it % 256), null, "Hop$it") }) }
        assertTrue(vm.isPathFull)
        vm.insert(pathContact(name = "Overflow", publicKey = Fixtures.repeated(0xFF)), AddHopIntent.APPEND)
        assertEquals(vm.maxHopCount, vm.state.value.editablePath.size)
        assertNotEquals("Overflow", vm.state.value.editablePath.last().resolvedName)
    }
}
