// PortedFrom: MC1Tests/ViewModels/TracePathViewModelTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceCodeInputTest {
    private val harness = TraceHarness()
    private val holder = harness.holder

    private fun repeaters(vararg contacts: ContactDTO) =
        holder.editStateForTesting { it.copy(availableRepeaters = contacts.toList()) }

    @Test @OriginalCase("CodeInputParsingTests::parses valid comma-separated codes and adds repeaters()")
    fun `parses valid comma-separated codes and adds repeaters`() {
        repeaters(contact(0xA3, name = "Alpha"), contact(0xB7, name = "Bravo"), contact(0xF2, name = "Foxtrot"))
        val result = holder.addRepeatersFromCodes("A3, B7")
        assertEquals(listOf("A3", "B7"), result.added)
        assertTrue(result.notFound.isEmpty())
        assertTrue(result.alreadyInPath.isEmpty())
        assertEquals(listOf(Bytes.of(0xA3), Bytes.of(0xB7)), harness.state.outboundPath.map { it.hashBytes })
    }

    @Test @OriginalCase("CodeInputParsingTests::handles case insensitive input()")
    fun `handles case insensitive input`() {
        repeaters(contact(0xA3, name = "Alpha"))
        assertEquals(listOf("A3"), holder.addRepeatersFromCodes("a3").added)
        assertEquals(1, harness.state.outboundPath.size)
    }

    @Test @OriginalCase("CodeInputParsingTests::handles codes without spaces after commas()")
    fun `handles codes without spaces after commas`() {
        repeaters(contact(0xA3, name = "Alpha"), contact(0xB7, name = "Bravo"))
        assertEquals(2, holder.addRepeatersFromCodes("A3,B7").added.size)
        assertEquals(2, harness.state.outboundPath.size)
    }

    @Test @OriginalCase("CodeInputParsingTests::reports codes not found in available repeaters()")
    fun `reports codes not found in available repeaters`() {
        repeaters(contact(0xA3, name = "Alpha"))
        val result = holder.addRepeatersFromCodes("A3, 11, FF")
        assertEquals(listOf("A3"), result.added)
        assertEquals(listOf("11", "FF"), result.notFound)
        assertEquals(1, harness.state.outboundPath.size)
    }

    @Test @OriginalCase("CodeInputParsingTests::reports codes already in outbound path()")
    fun `reports codes already in outbound path`() {
        val alpha = contact(0xA3, name = "Alpha")
        repeaters(alpha)
        holder.addNode(alpha)
        val result = holder.addRepeatersFromCodes("A3")
        assertTrue(result.added.isEmpty())
        assertEquals(listOf("A3"), result.alreadyInPath)
        assertEquals(1, harness.state.outboundPath.size)
    }

    @Test @OriginalCase("CodeInputParsingTests::deduplicates codes in input()")
    fun `deduplicates codes in input`() {
        repeaters(contact(0xA3, name = "Alpha"))
        assertEquals(listOf("A3"), holder.addRepeatersFromCodes("A3, A3, a3").added)
        assertEquals(1, harness.state.outboundPath.size)
    }

    @Test @OriginalCase("CodeInputParsingTests::reports invalid hex format()")
    fun `reports invalid hex format`() {
        repeaters(contact(0xA3, name = "Alpha"))
        val result = holder.addRepeatersFromCodes("A3, ZZ, 123, X")
        assertEquals(listOf("A3"), result.added)
        assertEquals(listOf("ZZ", "123", "X"), result.invalidFormat)
    }

    @Test @OriginalCase("CodeInputParsingTests::handles empty input()")
    fun `handles empty input`() {
        val result = holder.addRepeatersFromCodes("")
        assertTrue(result.added.isEmpty())
        assertTrue(result.notFound.isEmpty())
        assertTrue(result.invalidFormat.isEmpty())
    }

    @Test @OriginalCase("CodeInputParsingTests::handles whitespace-only input()")
    fun `handles whitespace-only input`() = assertTrue(holder.addRepeatersFromCodes("   ,  , ").added.isEmpty())

    @Test @OriginalCase("CodeInputParsingTests::hasErrors returns false when all codes are valid and new()")
    fun `hasErrors returns false when all codes are valid and new`() {
        repeaters(contact(0xA3, name = "Alpha"))
        val result = holder.addRepeatersFromCodes("A3")
        assertFalse(result.hasErrors)
        assertNull(result.errorMessage(EnglishTraceStrings))
    }

    @Test @OriginalCase("CodeInputParsingTests::hasErrors returns true when errors exist()")
    fun `hasErrors returns true when errors exist`() {
        repeaters()
        val result = holder.addRepeatersFromCodes("A3")
        assertTrue(result.hasErrors)
        assertNotNull(result.errorMessage(EnglishTraceStrings))
    }

    @Test @OriginalCase("CodeInputParsingTests::errorMessage formats multiple error types with separator()")
    fun `errorMessage formats multiple error types with separator`() {
        val alpha = contact(0xA3, name = "Alpha")
        repeaters(alpha)
        holder.addNode(alpha)
        val message = assertNotNull(holder.addRepeatersFromCodes("ZZ, 11, A3").errorMessage(EnglishTraceStrings))
        assertTrue("Invalid format: ZZ" in message)
        assertTrue("11 not found" in message)
        assertTrue("A3 already in path" in message)
        assertEquals("Invalid format: ZZ · 11 not found · A3 already in path", message)
    }

    @Test @OriginalCase("CodeInputParsingTests::clears saved path state when repeaters are added()")
    fun `clears saved path state when repeaters are added`() {
        repeaters(contact(0xA3, name = "Alpha"))
        holder.editStateForTesting { it.copy(activeSavedPath = savedPath(Bytes.of(0x01, 0x02, 0x01))) }
        holder.addRepeatersFromCodes("A3")
        assertNull(harness.state.activeSavedPath)
    }

    // MARK: Outbound path name resolution

    @Test @OriginalCase("OutboundPathNameResolutionTests::resolves name using best match when contact collision exists()")
    fun `resolves name using best match when contact collision exists`() {
        val flint = contact(0x3F, name = "Flint Hill - KC3ELT", lastAdvertTimestamp = 10u, lastHeardTimestamp = null)
        val other = contact(0x3F, 0x01, name = "Other Tower", lastAdvertTimestamp = 50u, lastHeardTimestamp = null)
        holder.setContactsForTesting(listOf(flint, other))
        holder.addNode(flint)
        holder.setPendingTagForTesting(12345u)
        harness.respond(12345u, listOf(node(0x3F, 5.0), node(null, 3.0)))
        assertEquals("Flint Hill - KC3ELT", harness.state.result?.hops?.get(1)?.resolvedName)
    }

    @Test @OriginalCase("OutboundPathNameResolutionTests::addRepeater stores full public key in PathHop()")
    fun `addRepeater stores full public key in PathHop`() {
        holder.addNode(contact(0x3F, name = "Tower", lastHeardTimestamp = null))
        assertEquals(1, harness.state.outboundPath.size)
        assertEquals(key(0x3F), harness.state.outboundPath[0].publicKey)
        assertEquals(Bytes.of(0x3F), harness.state.outboundPath[0].hashBytes)
    }

    @Test @OriginalCase("OutboundPathNameResolutionTests::handleTraceResponse resolves correct repeater when hash collision exists using stored key()")
    fun `handleTraceResponse resolves correct repeater when hash collision exists using stored key`() {
        val near = contact(0x3F, 0x01, name = "Near Tower", lastAdvertTimestamp = 100u, latitude = 37.0, longitude = -122.0, lastHeardTimestamp = null)
        val far = contact(0x3F, 0x02, name = "Far Tower", lastAdvertTimestamp = 200u, latitude = 38.0, longitude = -123.0, lastHeardTimestamp = null)
        holder.setContactsForTesting(listOf(near, far))
        holder.addNode(near)
        holder.setPendingTagForTesting(42u)
        harness.respond(42u, listOf(node(0x3F, 5.0), node(null, 3.0)))
        assertEquals("Near Tower", harness.state.result?.hops?.get(1)?.resolvedName)
    }

    @Test @OriginalCase("OutboundPathNameResolutionTests::falls back to contact lookup when hop not in outboundPath()")
    fun `falls back to contact lookup when hop not in outboundPath`() {
        holder.setContactsForTesting(listOf(contact(0xAB, name = "Test Tower", lastHeardTimestamp = null)))
        holder.setPendingTagForTesting(12345u)
        harness.respond(12345u, listOf(node(0xAB, 5.0), node(null, 3.0)))
        assertEquals("Test Tower", harness.state.result?.hops?.get(1)?.resolvedName)
    }

    // MARK: Rooms

    @Test @OriginalCase("RoomSupportTests::setContactsForTesting populates both availableRepeaters and availableRooms()")
    fun `setContactsForTesting populates both availableRepeaters and availableRooms`() {
        holder.setContactsForTesting(listOf(contact(0xA1, name = "Repeater"), contact(0xB2, name = "Room", type = ContactType.ROOM)))
        assertEquals(listOf("Repeater"), harness.state.availableRepeaters.map { it.name })
        assertEquals(listOf("Room"), harness.state.availableRooms.map { it.name })
    }

    @Test @OriginalCase("RoomSupportTests::availableNodes returns union of repeaters and rooms()")
    fun `availableNodes returns union of repeaters and rooms`() {
        holder.setContactsForTesting(listOf(contact(0xA1, name = "Repeater"), contact(0xB2, name = "Room", type = ContactType.ROOM)))
        assertEquals(setOf("Repeater", "Room"), harness.state.availableNodes.map { it.name }.toSet())
        assertEquals(2, harness.state.availableNodes.size)
    }

    @Test @OriginalCase("RoomSupportTests::addRepeater works with a room contact()")
    fun `addRepeater works with a room contact`() {
        holder.addNode(contact(0xB2, name = "Room Server", type = ContactType.ROOM))
        assertEquals(1, harness.state.outboundPath.size)
        assertEquals("Room Server", harness.state.outboundPath[0].resolvedName)
        assertEquals(Bytes.of(0xB2), harness.state.outboundPath[0].hashBytes)
    }

    @Test @OriginalCase("RoomSupportTests::addRepeatersFromCodes finds rooms via availableNodes()")
    fun `addRepeatersFromCodes finds rooms via availableNodes`() {
        holder.setContactsForTesting(listOf(contact(0xB2, name = "Room Server", type = ContactType.ROOM)))
        val result = holder.addRepeatersFromCodes("B2")
        assertEquals(listOf("B2"), result.added)
        assertTrue(result.notFound.isEmpty())
        assertEquals("Room Server", harness.state.outboundPath.single().resolvedName)
    }
}
