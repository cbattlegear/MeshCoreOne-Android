// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/RegionDiscoveryServiceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native assertions: real-session discovery tags, timing, typed failures, cancellation and route/target behavior.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.AnonRequestType
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.FloodScope
import com.meshcoreone.android.core.protocol.model.ResponseCode
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class RegionDiscoveryTest {
    @TestFactory
    fun sourceCases() = listOf(
        original("RegionDiscoveryServiceTests", "Non-contact responders are queried when ad-hoc requests are supported") {
            val first = filled(0x11)
            val second = filled(0x22)
            val targets = RegionDiscoveryService.buildRegionQueryTargets(
                setOf(first, second), listOf(contact(first)), listOf(node(second)), true,
            )
            assertEquals(setOf(first, second), targets.map { it.publicKey }.toSet())
        },
        original("RegionDiscoveryServiceTests", "Non-contact responders are skipped when ad-hoc requests are unsupported") {
            val first = filled(0x11)
            val second = filled(0x22)
            val targets = RegionDiscoveryService.buildRegionQueryTargets(
                setOf(first, second), listOf(contact(first)), listOf(node(second)), false,
            )
            assertEquals(setOf(first), targets.map { it.publicKey }.toSet())
        },
    )

    @TestFactory
    fun nativeCases() = listOf(
        nativeCase("discovery contact routing wins over discovered snapshots and identity uses full key contents") {
            val key = filled(0x80)
            val existing = contact(key).copy(name = "Existing", flags = 0xA1u, outPathLength = 1u, outPath = Bytes.of(0xFE))
            val targets = RegionDiscoveryService.buildRegionQueryTargets(
                setOf(Bytes(key.toByteArray())), listOf(existing), listOf(node(key)), true,
            )
            val target = targets.single()
            assertEquals(key.uppercaseHexString(), target.id)
            assertEquals("Existing", target.advertisedName)
            assertEquals(0xA1.toUByte(), target.flags.rawValue)
            assertEquals(1.toUByte(), target.outPathLength)
            assertEquals(Bytes.of(0xFE), target.outPath)
        },
        nativeCase("discovery pool excludes nonresponders nonrepeaters and unknown keys without inventing routes") {
            val key = filled(0x11)
            val absent = filled(0x22)
            val room = contact(key).copy(typeRawValue = ContactType.ROOM.rawValue)
            assertTrue(RegionDiscoveryService.buildRegionQueryTargets(
                setOf(key), listOf(room, contact(absent)), listOf(node(absent)), true,
            ).isEmpty())
        },
        nativeAsync("discovery listens for the exact fifteen-second source window before no-responder completion") {
            val fixture = settingsFixture()
            try {
                val service = discovery(fixture)
                val scan = request { service.discover(emptyList(), true) }
                reply(fixture, 2, discoverRequest(), okPacket())
                advanceTimeBy(14_999)
                runCurrent()
                assertFalse(scan.isCompleted)
                advanceTimeBy(1)
                runCurrent()
                assertEquals(RegionDiscoveryService.Outcome.NoRepeatersResponded, scan.await())
            } finally { fixture.close() }
        },
        nativeAsync("discovery registered-before-send captures immediate matching responses and ignores wrong tags") {
            val fixture = settingsFixture()
            try {
                val key = filled(0x11)
                val service = discovery(fixture, ContactRows(listOf(contact(key))))
                fixture.radio.onSend = { bytes ->
                    if (bytes == discoverRequest()) {
                        fixture.radio.receive(discoverResponse(key, 0x99999999u))
                        fixture.radio.receive(discoverResponse(key))
                        fixture.radio.ok()
                    }
                }
                val scan = request { service.discover(listOf("Known"), true) }
                runCurrent()
                advanceTimeBy(15_000)
                runCurrent()
                assertEquals(PacketBuilder.sendAnonReq(key, AnonRequestType.REGIONS, 0u, Bytes.EMPTY), fixture.radio.sent.last())
                fixture.radio.receive(sentPacket())
                fixture.radio.receive(regionsPacket("Zulu,Known,Alpha,Alpha,*"))
                runCurrent()
                val outcome = assertIs<RegionDiscoveryService.Outcome.Completed>(scan.await())
                assertEquals(listOf("Alpha", "Zulu"), outcome.newRegions)
                assertFalse(outcome.allRepeatersTableFull)
                assertFalse(outcome.isPartial)
            } finally { fixture.close() }
        },
        nativeAsync("discovery excludes canonically equivalent known regions in either normalization form") {
            for (known in listOf("\u00E9", "e\u0301")) {
                val fixture = settingsFixture()
                try {
                    val key = filled(0x11)
                    val scan = request { discovery(fixture, ContactRows(listOf(contact(key)))).discover(listOf(known), false) }
                    reply(fixture, 2, discoverRequest(), okPacket())
                    fixture.radio.receive(discoverResponse(key))
                    advanceTimeBy(15_000)
                    reply(fixture, 3, PacketBuilder.sendAnonReq(key, AnonRequestType.REGIONS, 0u, Bytes.EMPTY), sentPacket())
                    fixture.radio.receive(regionsPacket("e\u0301,\u00E9,Other"))
                    runCurrent()
                    val result = assertIs<RegionDiscoveryService.Outcome.Completed>(scan.await())
                    assertEquals(listOf("Other"), result.newRegions)
                    assertFalse(result.isPartial)
                    assertFalse(result.allRepeatersTableFull)
                } finally { fixture.close() }
            }
        },
        nativeAsync("discovery deduplicates canonical regions without rewriting advertised UTF-8 or flood-scope keys") {
            val fixture = settingsFixture()
            try {
                val key = filled(0x11)
                val advertised = "e\u0301"
                val scan = request { discovery(fixture, ContactRows(listOf(contact(key)))).discover(emptyList(), false) }
                reply(fixture, 2, discoverRequest(), okPacket())
                fixture.radio.receive(discoverResponse(key))
                advanceTimeBy(15_000)
                reply(fixture, 3, PacketBuilder.sendAnonReq(key, AnonRequestType.REGIONS, 0u, Bytes.EMPTY), sentPacket())
                fixture.radio.receive(regionsPacket("$advertised,\u00E9,$advertised"))
                runCurrent()
                val result = assertIs<RegionDiscoveryService.Outcome.Completed>(scan.await())
                assertEquals(listOf(advertised), result.newRegions)
                val name = result.newRegions.single()
                assertEquals(Bytes.utf8(advertised), Bytes.utf8(name))
                assertNotEquals(FloodScope.Region("\u00E9").scopeKey(), FloodScope.Region(name).scopeKey())
                val write = request { fixture.settings.setDefaultFloodScopeVerified(name) }
                reply(fixture, 4, PacketBuilder.setDefaultFloodScope(advertised, FloodScope.Region(advertised)), okPacket())
                reply(fixture, 5, PacketBuilder.getDefaultFloodScope(), floodScopePacket(advertised))
                assertEquals(advertised, write.await())
            } finally { fixture.close() }
        },
        nativeAsync("discovery sorts canonical Unicode scalar values rather than raw UTF-16") {
            val fixture = settingsFixture()
            try {
                val key = filled(0x11)
                val scan = request { discovery(fixture, ContactRows(listOf(contact(key)))).discover(emptyList(), false) }
                reply(fixture, 2, discoverRequest(), okPacket())
                fixture.radio.receive(discoverResponse(key))
                advanceTimeBy(15_000)
                reply(fixture, 3, PacketBuilder.sendAnonReq(key, AnonRequestType.REGIONS, 0u, Bytes.EMPTY), sentPacket())
                fixture.radio.receive(regionsPacket("e\u0301,\uD83D\uDE80x,\uE000,z,aa,\uD83D\uDE80,a,A"))
                runCurrent()
                val result = assertIs<RegionDiscoveryService.Outcome.Completed>(scan.await())
                assertEquals(listOf("A", "a", "aa", "z", "e\u0301", "\uE000", "\uD83D\uDE80", "\uD83D\uDE80x"),
                    result.newRegions)
            } finally { fixture.close() }
        },
        nativeAsync("discovery rejects wrong-tag-only responses rather than fabricating targets") {
            val fixture = settingsFixture()
            try {
                val scan = request { discovery(fixture, ContactRows(listOf(contact(filled(0x11))))).discover(emptyList(), true) }
                reply(fixture, 2, discoverRequest(), okPacket())
                fixture.radio.receive(discoverResponse(filled(0x11), 0x99999999u))
                advanceTimeBy(15_000)
                runCurrent()
                assertEquals(RegionDiscoveryService.Outcome.NoRepeatersResponded, scan.await())
                assertEquals(2, fixture.radio.sent.size)
            } finally { fixture.close() }
        },
        nativeAsync("discovery command rejection returns its typed send-failure cause") {
            val fixture = settingsFixture()
            try {
                val scan = request { discovery(fixture).discover(emptyList(), true) }
                reply(fixture, 2, discoverRequest(), packet(ResponseCode.ERROR, Bytes.of(6)))
                val cause = assertIs<RegionDiscoveryService.Failure.Protocol>(
                    assertIs<RegionDiscoveryService.Outcome.SendFailed>(scan.await()).failure,
                ).cause
                assertEquals(6.toUByte(), assertIs<MeshCoreException.DeviceError>(cause).code)
            } finally { fixture.close() }
        },
        nativeAsync("discovery contact load failure is explicit and never an empty successful result") {
            val fixture = settingsFixture()
            try {
                val rows = ContactRows()
                val failure = PersistenceStoreException(PersistenceStoreError.FetchFailed("contacts unavailable"))
                rows.failure = failure
                val scan = request { discovery(fixture, rows).discover(emptyList(), true) }
                reply(fixture, 2, discoverRequest(), okPacket())
                fixture.radio.receive(discoverResponse(filled(0x11)))
                advanceTimeBy(15_000)
                runCurrent()
                assertSame(failure, assertIs<RegionDiscoveryService.Outcome.ErrorLoadingRepeaters>(scan.await()).failure.cause)
            } finally { fixture.close() }
        },
        nativeAsync("discovery optional offline-store failure is retained instead of dropping its data") {
            val fixture = settingsFixture()
            try {
                val rows = DiscoveredRows()
                val failure = PersistenceStoreException(PersistenceStoreError.FetchFailed("offline rows unavailable"))
                rows.failure = failure
                val scan = request { discovery(fixture, ContactRows(), rows).discover(emptyList(), true) }
                reply(fixture, 2, discoverRequest(), okPacket())
                fixture.radio.receive(discoverResponse(filled(0x11)))
                advanceTimeBy(15_000)
                runCurrent()
                assertSame(failure, assertIs<RegionDiscoveryService.Outcome.ErrorLoadingRepeaters>(scan.await()).failure.cause)
            } finally { fixture.close() }
        },
        nativeAsync("a single table-full query preserves the source any-table-full result flag") {
            val fixture = settingsFixture()
            try {
                val key = filled(0x11)
                val scan = request { discovery(fixture, ContactRows(listOf(contact(key)))).discover(emptyList(), false) }
                reply(fixture, 2, discoverRequest(), okPacket())
                fixture.radio.receive(discoverResponse(key))
                advanceTimeBy(15_000)
                runCurrent()
                fixture.radio.error(3u)
                runCurrent()
                val result = assertIs<RegionDiscoveryService.Outcome.Completed>(scan.await())
                assertTrue(result.allRepeatersTableFull)
                assertTrue(result.newRegions.isEmpty())
            } finally { fixture.close() }
        },
        nativeAsync("mixed successful and table-full repeaters retain the source any rather than all flag") {
            val fixture = settingsFixture()
            try {
                val first = filled(0x11)
                val second = filled(0x22)
                val service = discovery(fixture, ContactRows(listOf(contact(first), contact(second))))
                fixture.radio.onSend = { data ->
                    when (data) {
                        discoverRequest() -> {
                            fixture.radio.receive(discoverResponse(first))
                            fixture.radio.receive(discoverResponse(second))
                            fixture.radio.ok()
                        }
                        PacketBuilder.sendAnonReq(first, AnonRequestType.REGIONS, 0u, Bytes.EMPTY) -> fixture.radio.error(3u)
                        PacketBuilder.sendAnonReq(second, AnonRequestType.REGIONS, 0u, Bytes.EMPTY) -> {
                            fixture.radio.receive(sentPacket())
                            fixture.radio.receive(regionsPacket("Success"))
                        }
                        else -> throw AssertionError("Unexpected discovery wire command")
                    }
                }
                val scan = request { service.discover(emptyList(), false) }
                runCurrent()
                advanceTimeBy(15_000)
                runCurrent()
                val actual = assertIs<RegionDiscoveryService.Outcome.Completed>(scan.await())
                assertEquals(listOf("Success"), actual.newRegions)
                assertTrue(actual.allRepeatersTableFull)
                assertFalse(actual.isPartial)
            } finally { fixture.close() }
        },
        nativeAsync("non-table-full query failure remains typed partial metadata rather than fake success") {
            val fixture = settingsFixture()
            try {
                val key = filled(0x11)
                val scan = request { discovery(fixture, ContactRows(listOf(contact(key)))).discover(emptyList(), false) }
                reply(fixture, 2, discoverRequest(), okPacket())
                fixture.radio.receive(discoverResponse(key))
                advanceTimeBy(15_000)
                runCurrent()
                fixture.radio.error(6u)
                runCurrent()
                val result = assertIs<RegionDiscoveryService.Outcome.Completed>(scan.await())
                assertTrue(result.isPartial)
                assertFalse(result.allRepeatersTableFull)
                assertIs<MeshCoreException.DeviceError>(assertIs<RegionDiscoveryService.Failure.Protocol>(result.queryFailures.single()).cause)
            } finally { fixture.close() }
        },
        nativeAsync("discovery cancellation propagates and never leaves a query or success outcome") {
            val fixture = settingsFixture()
            try {
                val scan = request { discovery(fixture).discover(emptyList(), true) }
                reply(fixture, 2, discoverRequest(), okPacket())
                scan.cancel()
                runCurrent()
                assertFailsWith<CancellationException> { scan.await() }
                advanceTimeBy(15_001)
                runCurrent()
                assertEquals(2, fixture.radio.sent.size)
                assertEquals(1, fixture.radio.maximumCollectors)
            } finally { fixture.close() }
        },
    )

    private fun discovery(
        fixture: SettingsFixture, contacts: ContactRows = ContactRows(), nodes: DiscoveredRows? = null,
    ) = RegionDiscoveryService(fixture.session, contacts, nodes, fixture.context, fixture.clock)

    private fun contact(key: Bytes) = ContactDTO(
        UUID.randomUUID(), RADIO, key, "Repeater", typeRawValue = ContactType.REPEATER.rawValue,
        outPathLength = 0u, lastHeardTimestamp = null,
    )
    private fun node(key: Bytes) = DiscoveredNodeDTO(
        UUID.randomUUID(), RADIO, key, "Discovered", ContactType.REPEATER.rawValue, NOW, 0u,
        0.0, 0.0, 0u, Bytes.EMPTY, null, null,
    )
    private fun discoverRequest() = PacketBuilder.sendNodeDiscoverRequest(4u, false, 0x12345678u)
    private fun sentPacket() = packet(ResponseCode.MESSAGE_SENT, Bytes.of(0) + little32(0x44332211) + little32(1000))
    private fun regionsPacket(text: String) =
        packet(ResponseCode.BINARY_RESPONSE, Bytes.of(0) + little32(0x44332211) + filled(0, 4) + Bytes.utf8(text))
    private fun discoverResponse(key: Bytes, tag: UInt = 0x12345678u): Bytes =
        packet(ResponseCode.CONTROL_DATA, Bytes.of(0, 0, 0, 0x92, 0) + little32(tag.toLong()) + key)
}
