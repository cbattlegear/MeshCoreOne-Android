// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/SettingsServiceClockTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/SettingsServiceDefaultFloodScopeTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/SettingsServiceEventStreamTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/SettingsServiceLocationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Services/SettingsServiceApplyPresetTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.model.FloodScope
import com.meshcoreone.android.core.protocol.model.ErrorCode
import com.meshcoreone.android.core.protocol.model.ResponseCode
import java.time.Instant
import kotlin.test.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSourceTest {
    @TestFactory
    fun clockAndFloodScope() = listOf(
        originalAsync("SettingsServiceClockTests", "getTime reads back the device clock") {
            val fixture = settingsFixture()
            try {
                val read = request { fixture.settings.getTime() }
                reply(fixture, 2, PacketBuilder.getTime(), packet(ResponseCode.CURRENT_TIME, little32(NOW.epochSecond)))
                assertEquals(NOW, read.await())
            } finally { fixture.close() }
        },
        originalAsync("SettingsServiceClockTests", "setTime writes the device clock") {
            val fixture = settingsFixture()
            try {
                val write = request { fixture.settings.setTime(Instant.ofEpochSecond(1_700_000_000)) }
                reply(fixture, 2, PacketBuilder.setTime(NOW), okPacket())
                write.await()
            } finally { fixture.close() }
        },
        originalAsync("SettingsServiceDefaultFloodScopeTests", "setDefaultFloodScopeVerified truncates overlong names before send and verify") {
            val fixture = settingsFixture()
            try {
                val expected = "a".repeat(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES)
                val write = request { fixture.settings.setDefaultFloodScopeVerified(expected + "aaaaa") }
                reply(fixture, 2, PacketBuilder.setDefaultFloodScope(expected, FloodScope.Region(expected)), okPacket())
                reply(fixture, 3, PacketBuilder.getDefaultFloodScope(), floodScopePacket(expected))
                assertEquals(expected, write.await())
            } finally { fixture.close() }
        },
        originalAsync("SettingsServiceDefaultFloodScopeTests", "setDefaultFloodScopeVerified forwards names at or below the cap unchanged") {
            val fixture = settingsFixture()
            try {
                val write = request { fixture.settings.setDefaultFloodScopeVerified("Germany") }
                reply(fixture, 2, PacketBuilder.setDefaultFloodScope("Germany", FloodScope.Region("Germany")), okPacket())
                reply(fixture, 3, PacketBuilder.getDefaultFloodScope(), floodScopePacket("Germany"))
                assertEquals("Germany", write.await())
            } finally { fixture.close() }
        },
        originalAsync("SettingsServiceDefaultFloodScopeTests", "setDefaultFloodScopeVerified clears the scope when name is nil") {
            val fixture = settingsFixture()
            try {
                val write = request { fixture.settings.setDefaultFloodScopeVerified(null) }
                reply(fixture, 2, PacketBuilder.setDefaultFloodScope("", FloodScope.Disabled), okPacket())
                reply(fixture, 3, PacketBuilder.getDefaultFloodScope(), floodScopePacket(null))
                assertNull(write.await())
            } finally { fixture.close() }
        },
    )

    @TestFactory
    fun eventAndLocation() = listOf(
        originalAsync("SettingsServiceEventStreamTests", "replacing the event subscriber keeps the replacement connected") {
            val fixture = settingsFixture()
            try {
                val first = fixture.settings.events()
                val replacement = fixture.settings.events()
                assertTrue(first.events.toList().isEmpty())
                val events = mutableListOf<SettingsEvent>()
                val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    replacement.events.collect { assertEquals(fixture.context.token, it.token); events += it.event }
                }
                val refresh = request { fixture.settings.refreshDeviceInfo() }
                reply(fixture, 2, PacketBuilder.appStart("MCore"), selfPacket())
                refresh.await()
                runCurrent()
                assertEquals(1, events.filterIsInstance<SettingsEvent.DeviceUpdated>().size)
                replacement.close()
                collector.join()
            } finally { fixture.close() }
        },
        originalAsync("SettingsServiceLocationTests", "getDeviceGPSState returns unsupported when gps custom var is missing") {
            val fixture = settingsFixture()
            try {
                val read = request { fixture.settings.getDeviceGPSState() }
                reply(fixture, 2, PacketBuilder.getCustomVars(), customVarsPacket())
                assertEquals(DeviceGPSState(false, false), read.await())
            } finally { fixture.close() }
        },
        originalAsync("SettingsServiceLocationTests", "getDeviceGPSState returns enabled when gps custom var is on") {
            val fixture = settingsFixture()
            try {
                val read = request { fixture.settings.getDeviceGPSState() }
                reply(fixture, 2, PacketBuilder.getCustomVars(), customVarsPacket("gps:1,foo:bar"))
                assertEquals(DeviceGPSState(true, true), read.await())
            } finally { fixture.close() }
        },
        originalAsync("SettingsServiceLocationTests", "setDeviceGPSEnabledVerified writes, verifies, and refreshes device info") {
            val fixture = settingsFixture(selfPacket(latitudeScaled = 47_491_031, longitudeScaled = -120_339_279))
            try {
                val write = request { fixture.settings.setDeviceGPSEnabledVerified(false) }
                reply(fixture, 2, PacketBuilder.setCustomVar("gps", "0"), okPacket())
                reply(fixture, 3, PacketBuilder.getCustomVars(), customVarsPacket("gps:0"))
                reply(fixture, 4, PacketBuilder.appStart("MCore"), selfPacket(latitudeScaled = 47_491_031, longitudeScaled = -120_339_279))
                assertEquals(DeviceGPSState(true, false), write.await())
            } finally { fixture.close() }
        },
        originalAsync("SettingsServiceLocationTests", "setManualLocationVerified disables device GPS before writing location") {
            val fixture = settingsFixture(selfPacket(latitudeScaled = 47_491_031, longitudeScaled = -120_339_279))
            try {
                val write = request { fixture.settings.setManualLocationVerified(0.0, 0.0) }
                reply(fixture, 2, PacketBuilder.getCustomVars(), customVarsPacket("gps:1"))
                reply(fixture, 3, PacketBuilder.setCustomVar("gps", "0"), okPacket())
                reply(fixture, 4, PacketBuilder.getCustomVars(), customVarsPacket("gps:0"))
                reply(fixture, 5, PacketBuilder.appStart("MCore"), selfPacket(latitudeScaled = 47_491_031, longitudeScaled = -120_339_279))
                reply(fixture, 6, PacketBuilder.setCoordinates(0.0, 0.0), okPacket())
                reply(fixture, 7, PacketBuilder.appStart("MCore"), selfPacket())
                val actual = write.await()
                assertEquals(0.0, actual.latitude)
                assertEquals(0.0, actual.longitude)
            } finally { fixture.close() }
        },
        originalAsync("SettingsServiceLocationTests", "setManualLocationVerified aborts when device GPS stays on") {
            val fixture = settingsFixture(selfPacket(latitudeScaled = 47_491_031, longitudeScaled = -120_339_279))
            try {
                val write = request { fixture.settings.setManualLocationVerified(0.0, 0.0) }
                reply(fixture, 2, PacketBuilder.getCustomVars(), customVarsPacket("gps:1"))
                reply(fixture, 3, PacketBuilder.setCustomVar("gps", "0"), okPacket())
                reply(fixture, 4, PacketBuilder.getCustomVars(), customVarsPacket("gps:1"))
                assertFailsWith<SettingsServiceException> { write.await() }
                assertEquals(4, fixture.radio.sent.size)
            } finally { fixture.close() }
        },
    )

    @TestFactory
    fun applyingPresets() = listOf(
        originalAsync("SettingsServiceApplyPresetTests", "Hungary on v10 writes RF and hash then stamps catalog id") {
            val fixture = settingsFixture()
            try {
                val events = mutableListOf<SettingsEvent>()
                val subscription = fixture.settings.events()
                val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    subscription.events.collect { events += it.event }
                }
                val write = request { fixture.settings.applyRadioPresetVerified(preset("hu")) }
                reply(fixture, 2, PacketBuilder.setRadio(869.618, 62.5, 7u, 5u), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(frequency = 869_618u, bandwidth = 62_500u))
                reply(fixture, 4, PacketBuilder.deviceQuery(), capabilitiesPacket(version = 10u))
                reply(fixture, 5, PacketBuilder.setPathHashMode(1u), okPacket())
                reply(fixture, 6, PacketBuilder.deviceQuery(), capabilitiesPacket(version = 10u, hash = 1u))
                write.await()
                runCurrent()
                val hash = events.indexOfFirst { it == SettingsEvent.PathHashModeUpdated(1u) }
                val catalog = events.indexOfFirst { it is SettingsEvent.DeviceUpdated && it.appliedRadioPresetID == "hu" }
                assertTrue(hash >= 0 && catalog > hash)
                assertEquals(listOf("hu"), events.filterIsInstance<SettingsEvent.DeviceUpdated>().map { it.appliedRadioPresetID })
                subscription.close()
                collector.join()
            } finally { fixture.close() }
        },
        originalAsync("SettingsServiceApplyPresetTests", "Hungary path-hash failure does not stamp catalog id") {
            val fixture = settingsFixture()
            try {
                val events = mutableListOf<SettingsEvent>()
                val subscription = fixture.settings.events()
                val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    subscription.events.collect { events += it.event }
                }
                val write = request { fixture.settings.applyRadioPresetVerified(preset("hu")) }
                reply(fixture, 2, PacketBuilder.setRadio(869.618, 62.5, 7u, 5u), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(frequency = 869_618u, bandwidth = 62_500u))
                reply(fixture, 4, PacketBuilder.deviceQuery(), capabilitiesPacket(version = 10u))
                reply(fixture, 5, PacketBuilder.setPathHashMode(1u),
                    packet(ResponseCode.ERROR, Bytes.of(ErrorCode.ILLEGAL_ARGUMENT.rawValue.toInt())))
                assertFailsWith<SettingsServiceException> { write.await() }
                val refresh = request { fixture.settings.refreshDeviceInfo() }
                reply(fixture, 6, PacketBuilder.appStart("MCore"), selfPacket(frequency = 869_618u, bandwidth = 62_500u))
                refresh.await()
                runCurrent()
                assertTrue(events.filterIsInstance<SettingsEvent.DeviceUpdated>().all { it.appliedRadioPresetID == null })
                assertEquals(1, events.filterIsInstance<SettingsEvent.DeviceUpdated>().size)
                subscription.close()
                collector.join()
            } finally { fixture.close() }
        },
        originalAsync("SettingsServiceApplyPresetTests", "USA writes RF, skips hash, and stamps catalog id") {
            val fixture = settingsFixture()
            try {
                val subscription = fixture.settings.events()
                val write = request { fixture.settings.applyRadioPresetVerified(preset("us-ca")) }
                reply(fixture, 2, PacketBuilder.setRadio(910.525, 62.5, 7u, 5u), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(frequency = 910_525u, bandwidth = 62_500u))
                write.await()
                subscription.close()
                val events = subscription.events.toList().map { it.event }
                assertEquals(3, fixture.radio.sent.size)
                assertEquals(listOf("us-ca"), events.filterIsInstance<SettingsEvent.DeviceUpdated>().map { it.appliedRadioPresetID })
            } finally { fixture.close() }
        },
        originalAsync("SettingsServiceApplyPresetTests", "Hungary on v9 writes RF and skips hash") {
            val fixture = settingsFixture()
            try {
                val write = request { fixture.settings.applyRadioPresetVerified(preset("hu")) }
                reply(fixture, 2, PacketBuilder.setRadio(869.618, 62.5, 7u, 5u), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(frequency = 869_618u, bandwidth = 62_500u))
                reply(fixture, 4, PacketBuilder.deviceQuery(), capabilitiesPacket(version = 9u))
                write.await()
                assertEquals(4, fixture.radio.sent.size)
            } finally { fixture.close() }
        },
    )

    private fun preset(id: String) = assertNotNull(RadioPresets.all.firstOrNull { it.id == id })
}
