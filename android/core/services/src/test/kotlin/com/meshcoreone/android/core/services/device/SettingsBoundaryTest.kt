// PortedFrom: MC1Services/Sources/MC1Services/Services/SettingsService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/SettingsService+Verified.swift@db14559b39d32322b06477c6ae676112f583db50
// Native assertions: negative, capability, exact-threshold, cancellation and generation cases over the actual session.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.errors.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.FrequencyRange
import com.meshcoreone.android.core.protocol.model.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsBoundaryTest {
    @TestFactory
    fun verification() = listOf(
        nativeAsync("name verification truncates UTF-8 without splitting graphemes and reports actual mismatch") {
            val fixture = settingsFixture()
            try {
                val name = "a".repeat(29) + "\u00e9" + "\ud83d\ude00"
                val expected = "a".repeat(29) + "\u00e9"
                val subscription = fixture.settings.events()
                val write = request { fixture.settings.setNodeNameVerified(name) }
                reply(fixture, 2, PacketBuilder.setName(expected), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(name = "wrong"))
                val failure = assertFailsWith<SettingsServiceException> { write.await() }
                assertEquals(SettingsServiceError.VerificationFailed(expected, "wrong"), failure.error)
                subscription.close()
                assertTrue(subscription.events.toList().isEmpty())
            } finally { fixture.close() }
        },
        nativeAsync("name success publishes only the actual verified snapshot") {
            val fixture = settingsFixture()
            try {
                val subscription = fixture.settings.events()
                val write = request { fixture.settings.setNodeNameVerified("New name") }
                reply(fixture, 2, PacketBuilder.setName("New name"), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(name = "New name"))
                assertEquals("New name", write.await().name)
                subscription.close()
                val event = assertIs<SettingsEvent.DeviceUpdated>(subscription.events.toList().single().event)
                assertEquals("New name", event.info.name)
                assertNull(event.appliedRadioPresetID)
            } finally { fixture.close() }
        },
        nativeAsync("location verification accepts exactly two scaled units of tolerance") {
            val fixture = settingsFixture()
            try {
                val write = request { fixture.settings.setLocationVerified(0.0, 0.0) }
                reply(fixture, 2, PacketBuilder.setCoordinates(0.0, 0.0), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(latitudeScaled = 2, longitudeScaled = -2))
                val actual = write.await()
                assertEquals(0.000002, actual.latitude)
                assertEquals(-0.000002, actual.longitude)
            } finally { fixture.close() }
        },
        nativeAsync("location verification rejects three scaled units and retains coordinate metadata") {
            val fixture = settingsFixture()
            try {
                val subscription = fixture.settings.events()
                val write = request { fixture.settings.setLocationVerified(0.0, 0.0) }
                reply(fixture, 2, PacketBuilder.setCoordinates(0.0, 0.0), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(latitudeScaled = 3))
                val error = assertIs<SettingsServiceError.VerificationFailed>(
                    assertFailsWith<SettingsServiceException> { write.await() }.error,
                )
                assertEquals("(0.0, 0.0)", error.expected)
                assertEquals("(3e-06, 0.0)", error.actual)
                subscription.close()
                assertTrue(subscription.events.toList().isEmpty())
            } finally { fixture.close() }
        },
        nativeAsync("location clear verifies both coordinates and handles corrupt signed extremes without overflow") {
            val fixture = settingsFixture()
            try {
                val write = request { fixture.settings.setLocationVerified(0.0, 0.0) }
                reply(fixture, 2, PacketBuilder.setCoordinates(0.0, 0.0), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(latitudeScaled = Int.MIN_VALUE))
                val error = assertIs<SettingsServiceError.VerificationFailed>(
                    assertFailsWith<SettingsServiceException> { write.await() }.error,
                )
                assertEquals("(0.0, 0.0)", error.expected)
                assertEquals("(-2147.483648, 0.0)", error.actual)
            } finally { fixture.close() }
        },
        nativeAsync("nonfinite and nonrepresentable verified coordinates fail before any wire write") {
            val fixture = settingsFixture()
            try {
                for (latitude in listOf(Double.NaN, Double.POSITIVE_INFINITY, 3000.0)) {
                    val error = assertFailsWith<SettingsServiceException> {
                        fixture.settings.setLocationVerified(latitude, 0.0)
                    }
                    assertIs<MeshCoreException.InvalidInput>(assertIs<SettingsServiceError.SessionError>(error.error).error)
                }
                assertEquals(1, fixture.radio.sent.size)
            } finally { fixture.close() }
        },
        nativeAsync("manual location remains available on radios without device GPS") {
            val fixture = settingsFixture()
            try {
                val write = request { fixture.settings.setManualLocationVerified(1.0, 2.0) }
                reply(fixture, 2, PacketBuilder.getCustomVars(), customVarsPacket())
                reply(fixture, 3, PacketBuilder.setCoordinates(1.0, 2.0), okPacket())
                reply(fixture, 4, PacketBuilder.appStart("MCore"), selfPacket(latitudeScaled = 1_000_000, longitudeScaled = 2_000_000))
                assertEquals(1.0, write.await().latitude)
                assertEquals(4, fixture.radio.sent.size)
            } finally { fixture.close() }
        },
        nativeAsync("unsupported GPS readback fails even when both source boolean payloads are false") {
            val fixture = settingsFixture()
            try {
                val write = request { fixture.settings.setDeviceGPSEnabledVerified(false) }
                reply(fixture, 2, PacketBuilder.setCustomVar("gps", "0"), okPacket())
                reply(fixture, 3, PacketBuilder.getCustomVars(), customVarsPacket())
                assertEquals(
                    SettingsServiceError.DeviceGPSVerificationFailed(false, false),
                    assertFailsWith<SettingsServiceException> { write.await() }.error,
                )
                assertEquals(3, fixture.radio.sent.size)
            } finally { fixture.close() }
        },
        nativeAsync("GPS enabling mismatch retains expected-on and actual-off without publishing") {
            val fixture = settingsFixture()
            try {
                val subscription = fixture.settings.events()
                val write = request { fixture.settings.setDeviceGPSEnabledVerified(true) }
                reply(fixture, 2, PacketBuilder.setCustomVar("gps", "1"), okPacket())
                reply(fixture, 3, PacketBuilder.getCustomVars(), customVarsPacket("gps:invalid"))
                assertEquals(
                    SettingsServiceError.DeviceGPSVerificationFailed(true, false),
                    assertFailsWith<SettingsServiceException> { write.await() }.error,
                )
                subscription.close()
                assertTrue(subscription.events.toList().isEmpty())
            } finally { fixture.close() }
        },
        nativeAsync("GPS corrupt and disabled values preserve exact source optional and boolean parsing") {
            val fixture = settingsFixture()
            try {
                var count = 1
                for (value in listOf("0", "true", "2", " 1")) {
                    val read = request { fixture.settings.getDeviceGPSState() }
                    reply(fixture, ++count, PacketBuilder.getCustomVars(), customVarsPacket("gps:$value"))
                    assertEquals(DeviceGPSState(true, false), read.await())
                }
            } finally { fixture.close() }
        },
        nativeAsync("radio verification uses kHz frequency Hz bandwidth and exact SF CR readback") {
            val fixture = settingsFixture()
            try {
                val subscription = fixture.settings.events()
                val write = request { fixture.settings.setRadioParamsVerified(869_618u, 62_500u, 8u, 8u) }
                reply(fixture, 2, PacketBuilder.deviceQuery(), capabilitiesPacket())
                reply(fixture, 3, PacketBuilder.setRadio(869.618, 62.5, 8u, 8u), okPacket())
                reply(fixture, 4, PacketBuilder.appStart("MCore"),
                    selfPacket(frequency = 869_618u, bandwidth = 62_500u, sf = 8u, cr = 5u))
                val fault = assertIs<SettingsServiceError.VerificationFailed>(
                    assertFailsWith<SettingsServiceException> { write.await() }.error,
                )
                assertEquals("freq=869618, bw=62500, sf=8, cr=8", fault.expected)
                assertEquals("freq=869.618, bw=62.5, sf=8, cr=5", fault.actual)
                subscription.close()
                assertTrue(subscription.events.toList().isEmpty())
            } finally { fixture.close() }
        },
        nativeAsync("radio verified readback preserves floating-point boundary acceptance and rejects two encoded units") {
            val fixture = settingsFixture()
            try {
                val accepted = request { fixture.settings.setRadioParamsVerified(915_000u, 125_000u, 7u, 5u) }
                reply(fixture, 2, PacketBuilder.deviceQuery(), capabilitiesPacket())
                reply(fixture, 3, PacketBuilder.setRadio(915.0, 125.0, 7u, 5u), okPacket())
                reply(fixture, 4, PacketBuilder.appStart("MCore"), selfPacket(frequency = 915_001u))
                reply(fixture, 5, PacketBuilder.deviceQuery(), capabilitiesPacket())
                assertEquals(915.001, accepted.await().radioFrequency)
                val write = request { fixture.settings.setRadioParamsVerified(915_000u, 125_000u, 7u, 5u) }
                reply(fixture, 6, PacketBuilder.deviceQuery(), capabilitiesPacket())
                reply(fixture, 7, PacketBuilder.setRadio(915.0, 125.0, 7u, 5u), okPacket())
                reply(fixture, 8, PacketBuilder.appStart("MCore"), selfPacket(frequency = 915_002u))
                assertIs<SettingsServiceError.VerificationFailed>(
                    assertFailsWith<SettingsServiceException> { write.await() }.error,
                )
            } finally { fixture.close() }
        },
        nativeAsync("client repeat verification precedes device publication and preserves event order") {
            val fixture = settingsFixture()
            try {
                val subscription = fixture.settings.events()
                val write = request { fixture.settings.setRadioParamsVerified(918_000u, 62_500u, 7u, 8u, true, "repeat-918") }
                reply(fixture, 2, PacketBuilder.setRadio(918.0, 62.5, 7u, 8u, true), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(frequency = 918_000u, bandwidth = 62_500u, cr = 8u))
                reply(fixture, 4, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = true))
                write.await()
                subscription.close()
                val events = subscription.events.toList().map { it.event }
                assertEquals(SettingsEvent.ClientRepeatUpdated(true), events[0])
                assertEquals("repeat-918", assertIs<SettingsEvent.DeviceUpdated>(events[1]).appliedRadioPresetID)
            } finally { fixture.close() }
        },
        nativeAsync("repeat mismatch cannot publish an RF-only apparent success") {
            val fixture = settingsFixture()
            try {
                val subscription = fixture.settings.events()
                val write = request { fixture.settings.setRadioParamsVerified(915_000u, 125_000u, 7u, 5u, true) }
                reply(fixture, 2, PacketBuilder.setRadio(915.0, 125.0, 7u, 5u, true), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket())
                reply(fixture, 4, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = false))
                assertEquals(
                    SettingsServiceError.VerificationFailed("clientRepeat=true", "clientRepeat=false"),
                    assertFailsWith<SettingsServiceException> { write.await() }.error,
                )
                subscription.close()
                assertTrue(subscription.events.toList().isEmpty())
            } finally { fixture.close() }
        },
        nativeAsync("auto-add configuration mismatch retains both raw bitmask and max-hop strings") {
            val fixture = settingsFixture()
            try {
                val config = AutoAddConfig(0x81u, 7u)
                val write = request { fixture.settings.setAutoAddConfigVerified(config) }
                reply(fixture, 2, PacketBuilder.setAutoAddConfig(config), okPacket())
                reply(fixture, 3, PacketBuilder.getAutoAddConfig(), packet(ResponseCode.AUTO_ADD_CONFIG, Bytes.of(2, 3)))
                assertEquals(
                    SettingsServiceError.VerificationFailed("bitmask=129, maxHops=7", "bitmask=2, maxHops=3"),
                    assertFailsWith<SettingsServiceException> { write.await() }.error,
                )
            } finally { fixture.close() }
        },
        nativeAsync("auto-add verified success reports actual equality and refresh uses the same producer event") {
            val fixture = settingsFixture()
            try {
                val config = AutoAddConfig(0x81u, 7u)
                val subscription = fixture.settings.events()
                val write = request { fixture.settings.setAutoAddConfigVerified(config) }
                reply(fixture, 2, PacketBuilder.setAutoAddConfig(config), okPacket())
                reply(fixture, 3, PacketBuilder.getAutoAddConfig(), packet(ResponseCode.AUTO_ADD_CONFIG, Bytes.of(0x81, 7)))
                assertEquals(config, write.await())
                val refresh = request { fixture.settings.refreshAutoAddConfig() }
                reply(fixture, 4, PacketBuilder.getAutoAddConfig(), packet(ResponseCode.AUTO_ADD_CONFIG, Bytes.of(0x81, 7)))
                refresh.await()
                subscription.close()
                assertEquals(listOf(SettingsEvent.AutoAddConfigUpdated(config), SettingsEvent.AutoAddConfigUpdated(config)),
                    subscription.events.toList().map { it.event })
            } finally { fixture.close() }
        },
        nativeAsync("path hash mismatch cannot stamp a catalog alias after successful RF") {
            val fixture = settingsFixture()
            try {
                val subscription = fixture.settings.events()
                val preset = assertNotNull(RadioPresets.all.firstOrNull { it.id == "hu" })
                val write = request { fixture.settings.applyRadioPresetVerified(preset) }
                reply(fixture, 2, PacketBuilder.deviceQuery(), capabilitiesPacket(hash = 0u))
                reply(fixture, 3, PacketBuilder.setRadio(869.618, 62.5, 7u, 5u), okPacket())
                reply(fixture, 4, PacketBuilder.appStart("MCore"), selfPacket(frequency = 869_618u, bandwidth = 62_500u))
                reply(fixture, 5, PacketBuilder.deviceQuery(), capabilitiesPacket(hash = 0u))
                reply(fixture, 6, PacketBuilder.deviceQuery(), capabilitiesPacket(hash = 0u))
                reply(fixture, 7, PacketBuilder.setPathHashMode(1u), okPacket())
                reply(fixture, 8, PacketBuilder.deviceQuery(), capabilitiesPacket(hash = 0u))
                assertEquals(
                    SettingsServiceError.VerificationFailed("pathHashMode=1", "pathHashMode=0"),
                    assertFailsWith<SettingsServiceException> { write.await() }.error,
                )
                subscription.close()
                assertEquals(listOf(SettingsEvent.ClientRepeatUpdated(false)), subscription.events.toList().map { it.event })
            } finally { fixture.close() }
        },
        nativeAsync("path hash invalid mode retains the existing protocol typed failure and sends nothing") {
            val fixture = settingsFixture()
            try {
                val failure = assertFailsWith<SettingsServiceException> { fixture.settings.setPathHashMode(3u) }
                assertIs<MeshCoreException.InvalidInput>(assertIs<SettingsServiceError.SessionError>(failure.error).error)
                assertEquals(1, fixture.radio.sent.size)
            } finally { fixture.close() }
        },
        nativeAsync("TX power mismatch reports signed source values and never emits a desired snapshot") {
            val fixture = settingsFixture()
            try {
                val subscription = fixture.settings.events()
                val write = request { fixture.settings.setTxPowerVerified(-9) }
                reply(fixture, 2, PacketBuilder.setTxPower(-9), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(txPower = 0))
                assertEquals(SettingsServiceError.VerificationFailed("-9", "0"),
                    assertFailsWith<SettingsServiceException> { write.await() }.error)
                subscription.close()
                assertTrue(subscription.events.toList().isEmpty())
            } finally { fixture.close() }
        },
        nativeAsync("other parameters preserve auto-add inversion and actual full snapshot semantics") {
            val fixture = settingsFixture()
            try {
                val modes = TelemetryModes.of(2u, 1u, 3u)
                val write = request { fixture.settings.setOtherParamsVerified(true, modes, AdvertLocationPolicy.PREFS, 2u) }
                reply(fixture, 2, PacketBuilder.setOtherParams(false, 3u, 1u, 2u, 2u, 2u), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(manual = false, telemetry = 0u, multiAcks = 0u))
                val actual = write.await()
                assertFalse(actual.manualAddContacts)
                assertEquals(0.toUByte(), actual.multiAcks)
                assertEquals(0.toUByte(), actual.telemetryModeBase)
            } finally { fixture.close() }
        },
        nativeAsync("other parameter inversion mismatch reports both source autoAdd strings") {
            val fixture = settingsFixture()
            try {
                val write = request { fixture.settings.setOtherParamsVerified(true, TelemetryModes.of(), AdvertLocationPolicy.NONE, 2u) }
                reply(fixture, 2, PacketBuilder.setOtherParams(false, 0u, 0u, 0u, 0u, 2u), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(manual = true))
                assertEquals(SettingsServiceError.VerificationFailed("autoAdd=true", "autoAdd=false"),
                    assertFailsWith<SettingsServiceException> { write.await() }.error)
            } finally { fixture.close() }
        },
        nativeAsync("raw advertisement location policy is forwarded without coercion") {
            val fixture = settingsFixture()
            try {
                val write = request { fixture.settings.setOtherParams(false, TelemetryModes.of(), 0xFEu, 0xFFu) }
                reply(fixture, 2, PacketBuilder.setOtherParams(true, 0u, 0u, 0u, 0xFEu, 0xFFu), okPacket())
                write.await()
            } finally { fixture.close() }
        },
        nativeAsync("default flood scope wrong readback reports actual state and source verification strings") {
            val fixture = settingsFixture()
            try {
                val subscription = fixture.settings.events()
                val write = request { fixture.settings.setDefaultFloodScopeVerified("Germany") }
                reply(fixture, 2, PacketBuilder.setDefaultFloodScope("Germany", FloodScope.Region("Germany")), okPacket())
                reply(fixture, 3, PacketBuilder.getDefaultFloodScope(), floodScopePacket("Other"))
                assertEquals(SettingsServiceError.VerificationFailed("Germany", "Other"),
                    assertFailsWith<SettingsServiceException> { write.await() }.error)
                subscription.close()
                assertEquals(SettingsEvent.DefaultFloodScopeUpdated("Other"), subscription.events.toList().single().event)
            } finally { fixture.close() }
        },
        nativeAsync("default scope UTF-8 cap is thirty bytes and an empty string really clears") {
            val fixture = settingsFixture()
            try {
                val name = "\u00e9".repeat(16)
                val expected = "\u00e9".repeat(15)
                val write = request { fixture.settings.setDefaultFloodScopeVerified(name) }
                reply(fixture, 2, PacketBuilder.setDefaultFloodScope(expected, FloodScope.Region(expected)), okPacket())
                reply(fixture, 3, PacketBuilder.getDefaultFloodScope(), floodScopePacket(expected))
                assertEquals(expected, write.await())
                val clear = request { fixture.settings.setDefaultFloodScopeVerified("") }
                reply(fixture, 4, PacketBuilder.setDefaultFloodScope("", FloodScope.Disabled), okPacket())
                reply(fixture, 5, PacketBuilder.getDefaultFloodScope(), floodScopePacket(null))
                assertNull(clear.await())
            } finally { fixture.close() }
        },
        nativeAsync("older firmware default scope rejection retains its exact typed protocol cause") {
            val fixture = settingsFixture()
            try {
                val read = request { fixture.settings.getDefaultFloodScope() }
                reply(fixture, 2, PacketBuilder.getDefaultFloodScope(),
                    packet(ResponseCode.ERROR, Bytes.of(ErrorCode.UNSUPPORTED_COMMAND.rawValue.toInt())))
                val error = assertIs<SettingsServiceError.SessionError>(assertFailsWith<SettingsServiceException> { read.await() }.error)
                assertEquals(ErrorCode.UNSUPPORTED_COMMAND.rawValue, assertIs<MeshCoreException.DeviceError>(error.error).code)
            } finally { fixture.close() }
        },
    )

    @TestFactory
    fun lifetimeAndProtocol() = listOf(
        nativeAsync("connection-token change rejects stale readback before settings publication") {
            val fixture = settingsFixture()
            try {
                val subscription = fixture.settings.events()
                val write = request { fixture.settings.setNodeNameVerified("New") }
                reply(fixture, 2, PacketBuilder.setName("New"), okPacket())
                runCurrent()
                assertEquals(3, fixture.radio.sent.size)
                fixture.signals.replace(token(2))
                fixture.radio.receive(selfPacket(name = "New"))
                runCurrent()
                assertEquals(SettingsServiceError.NotConnected, assertFailsWith<SettingsServiceException> { write.await() }.error)
                subscription.close()
                assertTrue(subscription.events.toList().isEmpty())
            } finally { fixture.close() }
        },
        nativeAsync("disconnected context rejects configuration without a transport side effect") {
            val fixture = settingsFixture()
            try {
                fixture.signals.disconnect()
                assertEquals(SettingsServiceError.NotConnected,
                    assertFailsWith<SettingsServiceException> { fixture.settings.getBattery() }.error)
                assertEquals(1, fixture.radio.sent.size)
            } finally { fixture.close() }
        },
        nativeAsync("closing services finishes current and subsequently registered event subscriptions") {
            val fixture = settingsFixture()
            try {
                val subscription = fixture.settings.events()
                fixture.context.close()
                assertTrue(subscription.events.toList().isEmpty())
                assertTrue(fixture.settings.events().events.toList().isEmpty())
                assertEquals(0, fixture.radio.disconnects)
                assertTrue(fixture.radio.isConnected())
            } finally { fixture.close() }
        },
        nativeAsync("competing verified writes own the complete write and readback sequence") {
            val fixture = settingsFixture()
            try {
                val first = request { fixture.settings.setNodeNameVerified("First") }
                val second = request { fixture.settings.setNodeNameVerified("Second") }
                reply(fixture, 2, PacketBuilder.setName("First"), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(name = "First"))
                assertEquals("First", first.await().name)
                reply(fixture, 4, PacketBuilder.setName("Second"), okPacket())
                reply(fixture, 5, PacketBuilder.appStart("MCore"), selfPacket(name = "Second"))
                assertEquals("Second", second.await().name)
            } finally { fixture.close() }
        },
        nativeAsync("cancelled written settings requests do not let their late OK verify the next request") {
            val fixture = settingsFixture()
            try {
                val first = request { fixture.settings.setTxPower(1) }
                runCurrent()
                assertEquals(2, fixture.radio.sent.size)
                first.cancel()
                runCurrent()
                assertFailsWith<CancellationException> { first.await() }
                val next = request { fixture.settings.setTxPower(2) }
                runCurrent()
                assertEquals(2, fixture.radio.sent.size)
                fixture.radio.ok()
                runCurrent()
                assertEquals(3, fixture.radio.sent.size)
                assertFalse(next.isCompleted)
                assertEquals(PacketBuilder.setTxPower(2), fixture.radio.sent.last())
                fixture.radio.ok()
                runCurrent()
                next.await()
            } finally { fixture.close() }
        },
        nativeAsync("clock timeout retains the original cause and source retry classification") {
            val fixture = settingsFixture()
            try {
                val read = request { fixture.settings.getTime() }
                runCurrent()
                advanceTimeBy(1001)
                runCurrent()
                val failure = assertFailsWith<SettingsServiceException> { read.await() }
                val cause = assertIs<MeshCoreException.Timeout>(assertIs<SettingsServiceError.SessionError>(failure.error).error)
                assertSame(cause, failure.cause)
                assertTrue(failure.isRetryable)
            } finally { fixture.close() }
        },
        nativeAsync("corrupt battery response is not synthesized into a battery or successful read") {
            val fixture = settingsFixture()
            try {
                val read = request { fixture.settings.getBattery() }
                reply(fixture, 2, PacketBuilder.getBattery(), packet(ResponseCode.BATTERY, Bytes.of(0x80)))
                assertFalse(read.isCompleted)
                advanceTimeBy(1001)
                runCurrent()
                assertFailsWith<SettingsServiceException> { read.await() }
                assertTrue(fixture.diagnostics.isNotEmpty())
            } finally { fixture.close() }
        },
        nativeAsync("battery preserves unsigned millivolts and genuine absent or extended storage") {
            val fixture = settingsFixture()
            try {
                val minimal = request { fixture.settings.getBattery() }
                reply(fixture, 2, PacketBuilder.getBattery(), packet(ResponseCode.BATTERY, little16(0xFF80)))
                val battery = minimal.await()
                assertEquals(65_408L, battery.level)
                assertNull(battery.usedStorageKB)
                assertNull(battery.totalStorageKB)
                val extended = request { fixture.settings.getBattery() }
                reply(fixture, 3, PacketBuilder.getBattery(),
                    packet(ResponseCode.BATTERY, little16(4200) + little32(0x8000_0000L) + little32(0xFFFF_FFFFL)))
                val actual = extended.await()
                assertEquals(0x8000_0000L, actual.usedStorageKB)
                assertEquals(0xFFFF_FFFFL, actual.totalStorageKB)
            } finally { fixture.close() }
        },
        nativeAsync("clock high-bit seconds remain Unix seconds rather than milliseconds or signed Int") {
            val fixture = settingsFixture()
            try {
                val read = request { fixture.settings.getTime() }
                reply(fixture, 2, PacketBuilder.getTime(), packet(ResponseCode.CURRENT_TIME, little32(0xFFFF_FFFFL)))
                assertEquals(Instant.ofEpochSecond(0xFFFF_FFFFL), read.await())
            } finally { fixture.close() }
        },
        nativeAsync("repeat-range refresh publishes immutable raw frequency bounds") {
            val fixture = settingsFixture()
            try {
                val subscription = fixture.settings.events()
                val refresh = request { fixture.settings.refreshRepeatFreqRanges() }
                reply(fixture, 2, PacketBuilder.getRepeatFreq(),
                    packet(ResponseCode.ALLOWED_REPEAT_FREQ, little32(433_000) + little32(433_000) + little32(869_495) + little32(869_495)))
                refresh.await()
                subscription.close()
                assertEquals(
                    listOf(FrequencyRange(433_000u, 433_000u), FrequencyRange(869_495u, 869_495u)),
                    assertIs<SettingsEvent.AllowedRepeatFreqUpdated>(subscription.events.toList().single().event).ranges,
                )
            } finally { fixture.close() }
        },
        nativeAsync("device save failures preserve source reason cause and do not invoke success callbacks") {
            val fixture = settingsFixture()
            try {
                val rows = DeviceRows()
                val device = testDevice()
                rows.rows[device.id] = device
                val cause = PersistenceStoreException(PersistenceStoreError.SaveFailed("disk full"))
                rows.saveFailure = cause
                val service = DeviceService(rows, fixture.context)
                var callbacks = 0
                service.setDeviceUpdateCallback { callbacks++ }
                val failure = assertFailsWith<DeviceServiceException> {
                    service.updateOCVSettings(device.id, "liIon", null)
                }
                assertEquals(DeviceServiceError.PersistenceFailed("disk full"), failure.error)
                assertSame(cause, failure.cause)
                assertEquals(0, callbacks)
                assertNull(rows.rows.getValue(device.id).ocvPreset)
            } finally { fixture.close() }
        },
        nativeAsync("device fetch failures and cancellation are not rewritten as save failures") {
            val fixture = settingsFixture()
            try {
                val rows = DeviceRows()
                val fetch = PersistenceStoreException(PersistenceStoreError.FetchFailed("read failed"))
                rows.fetchFailure = fetch
                val service = DeviceService(rows, fixture.context)
                assertSame(fetch, assertFailsWith<PersistenceStoreException> {
                    service.updateOCVSettings(testDevice().id, "liIon", null)
                })
                rows.fetchFailure = null
                val device = testDevice()
                rows.rows[device.id] = device
                val entered = CompletableDeferred<Unit>()
                rows.beforeSave = { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
                val update = request { service.updateOCVSettings(device.id, "custom", "values") }
                runCurrent()
                entered.await()
                update.cancel()
                runCurrent()
                assertFailsWith<CancellationException> { update.await() }
                assertNull(rows.rows.getValue(device.id).ocvPreset)
            } finally { fixture.close() }
        },
        nativeAsync("device update callbacks may reenter without waiting on their own persistence mutex") {
            val fixture = settingsFixture()
            try {
                val rows = DeviceRows()
                val device = testDevice()
                rows.rows[device.id] = device
                val service = DeviceService(rows, fixture.context)
                val seen = mutableListOf<String?>()
                service.setDeviceUpdateCallback {
                    assertEquals(fixture.context.token, it.token)
                    seen += it.event.ocvPreset
                    if (it.event.ocvPreset == "liIon") service.updateOCVSettings(device.id, "liFePO4", null)
                }
                service.updateOCVSettings(device.id, "liIon", null)
                assertEquals(listOf<String?>("liIon", "liFePO4"), seen.toList())
                assertEquals("liFePO4", rows.rows.getValue(device.id).ocvPreset)
            } finally { fixture.close() }
        },
        nativeAsync("device lookup uses device UUID and retains persistent radio public-key identity") {
            val fixture = settingsFixture()
            try {
                val rows = DeviceRows()
                val device = testDevice()
                rows.rows[device.id] = device
                val service = DeviceService(rows, fixture.context)
                assertEquals(DeviceServiceError.DeviceNotFound, assertFailsWith<DeviceServiceException> {
                    service.updateOCVSettings(UUID.randomUUID(), "liIon", null)
                }.error)
                service.updateOCVSettings(device.id, "custom", "4200,4100,4000")
                val actual = rows.rows.getValue(device.id)
                assertEquals(device.id, actual.id)
                assertEquals(device.radioId, actual.radioId)
                assertEquals(device.publicKey, actual.publicKey)
                assertEquals("4200,4100,4000", actual.customOCVArrayString)
            } finally { fixture.close() }
        },
        nativeCase("source firmware policy codes and numeric metadata spellings are preserved") {
            assertEquals(3.toUByte(), FirmwareDeviceErrorCode.directMessageTableFull)
            assertEquals(2.toUByte(), FirmwareDeviceErrorCode.channelMessageNotFound)
            assertEquals(10.toUByte(), FirmwareDeviceErrorCode.remoteNodeNoResponseYet)
            assertEquals("0.0", sourceDoubleDescription(0.0))
            assertEquals("-0.0", sourceDoubleDescription(-0.0))
            assertEquals("1e-06", sourceDoubleDescription(0.000001))
            assertEquals("0.0001", sourceDoubleDescription(0.0001))
            assertEquals("1e+16", sourceDoubleDescription(1e16))
        },
    )
}
