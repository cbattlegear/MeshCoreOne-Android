// PortedFrom: MC1Tests/Views/Chats/ChannelInfoRegionQueryTargetsTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ChannelInfoRegionQueryTargetsTest {
    private val radio = RadioId(UUID.fromString("11111111-2222-3333-4444-555555555555"))
    private val keyA = key(0x0A)
    private val keyB = key(0x0B)
    private val keyC = key(0x0C)
    private val keyD = key(0x0D)
    private val keyE = key(0x0E)

    @Test
    fun `unions contacts and discovered nodes without duplication`() {
        val targets = RegionDiscoveryService.buildRegionQueryTargets(
            setOf(keyA, keyB, keyC),
            listOf(contact(keyA)),
            listOf(node(keyA), node(keyB), node(keyC)),
            true,
        )

        assertEquals(3, targets.size)
        assertEquals(setOf(keyA, keyB, keyC), targets.map { it.publicKey }.toSet())
    }

    @Test
    fun `excludes non-repeater types from both pools while retaining both repeaters`() {
        val targets = RegionDiscoveryService.buildRegionQueryTargets(
            setOf(keyA, keyB, keyC, keyD),
            listOf(contact(keyA).copy(typeRawValue = ContactType.CHAT.rawValue), contact(keyB)),
            listOf(node(keyC).copy(typeRawValue = ContactType.ROOM.rawValue), node(keyD)),
            true,
        )

        assertEquals(2, targets.size)
        assertEquals(setOf(keyB, keyD), targets.map { it.publicKey }.toSet())
    }

    @Test
    fun `drops responders that are in neither pool without dropping the known responder`() {
        val targets = RegionDiscoveryService.buildRegionQueryTargets(
            setOf(keyA, keyE), listOf(contact(keyA)), emptyList(), true,
        )

        assertEquals(listOf(keyA), targets.map { it.publicKey })
    }

    @Test
    fun `drops non-responders even if they are repeater contacts`() {
        val targets = RegionDiscoveryService.buildRegionQueryTargets(
            setOf(keyA), listOf(contact(keyA), contact(keyB)), emptyList(), true,
        )

        assertEquals(listOf(keyA), targets.map { it.publicKey })
    }

    @Test
    fun `forwards outPath bytes from contact source`() {
        val targets = RegionDiscoveryService.buildRegionQueryTargets(
            setOf(keyA),
            listOf(contact(keyA).copy(outPathLength = 3u, outPath = Bytes.of(0x11, 0x22, 0x33))),
            emptyList(),
            true,
        )

        val target = targets.single()
        assertEquals(keyA, target.publicKey)
        assertEquals(3.toUByte(), target.outPathLength)
        assertEquals(Bytes.of(0x11, 0x22, 0x33), target.outPath)
    }

    @Test
    fun `forwards high-bit outPath bytes from discovered-node source`() {
        val targets = RegionDiscoveryService.buildRegionQueryTargets(
            setOf(keyA),
            emptyList(),
            listOf(node(keyA).copy(outPathLength = 2u, outPath = Bytes.of(0xAA, 0xBB))),
            true,
        )

        val target = targets.single()
        assertEquals(keyA, target.publicKey)
        assertEquals(2.toUByte(), target.outPathLength)
        assertEquals(Bytes.of(0xAA, 0xBB), target.outPath)
    }

    @Test
    fun `uses contact outPath when both sources have different routing data`() {
        val targets = RegionDiscoveryService.buildRegionQueryTargets(
            setOf(keyA),
            listOf(contact(keyA).copy(name = "from-contact", outPathLength = 3u, outPath = Bytes.of(0x11, 0x22, 0x33))),
            listOf(node(keyA).copy(name = "from-discovery", outPathLength = 2u, outPath = Bytes.of(0xAA, 0xBB))),
            true,
        )

        val target = targets.single()
        assertEquals(keyA, target.publicKey)
        assertEquals("from-contact", target.advertisedName)
        assertEquals(3.toUByte(), target.outPathLength)
        assertEquals(Bytes.of(0x11, 0x22, 0x33), target.outPath)
    }

    private fun key(value: Int) = Bytes(ByteArray(32) { value.toByte() })

    private fun contact(key: Bytes) = ContactDTO(
        UUID.fromString("22222222-2222-3333-4444-555555555555"), radio, key, "contact",
        typeRawValue = ContactType.REPEATER.rawValue, outPathLength = 0xFFu, outPath = Bytes.EMPTY,
        lastHeardTimestamp = null,
    )

    private fun node(key: Bytes) = DiscoveredNodeDTO(
        UUID.fromString("33333333-2222-3333-4444-555555555555"), radio, key, "discovered",
        ContactType.REPEATER.rawValue, Instant.EPOCH, 0u, 0.0, 0.0, 0xFFu, Bytes.EMPTY, null, null,
    )
}
