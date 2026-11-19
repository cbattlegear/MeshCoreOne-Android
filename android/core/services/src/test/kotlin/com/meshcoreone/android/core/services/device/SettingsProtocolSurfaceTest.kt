// PortedFrom: MC1Services/Sources/MC1Services/Services/SettingsService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/SettingsService+Verified.swift@db14559b39d32322b06477c6ae676112f583db50
// Native assertions: exercise the remaining production session roles, not just the original tested happy paths.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.MeshTransportError
import com.meshcoreone.android.core.protocol.model.ResponseCode
import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsProtocolSurfaceTest {
    @TestFactory
    fun nativeCases() = listOf(
        nativeAsync("manual RF preserves actual protocol clipping but verification cannot call it desired success") {
            val fixture = settingsFixture()
            try {
                val subscription = fixture.settings.events()
                val write = request { fixture.settings.setRadioParamsVerified(0u, 0u, 5u, 5u) }
                reply(fixture, 2, hex("0bf0490200581b00000505"), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"),
                    selfPacket(frequency = 150_000u, bandwidth = 7_000u, sf = 5u))
                val fault = assertIs<SettingsServiceError.VerificationFailed>(
                    assertFailsWith<SettingsServiceException> { write.await() }.error,
                )
                assertEquals("freq=0, bw=0, sf=5, cr=5", fault.expected)
                assertEquals("freq=150.0, bw=7.0, sf=5, cr=5", fault.actual)
                subscription.close()
                assertTrue(subscription.events.toList().isEmpty())
            } finally { fixture.close() }
        },
        nativeAsync("firmware rejects an invalid raw SF CR tuple with its actual nonretryable device cause") {
            val fixture = settingsFixture()
            try {
                val write = request { fixture.settings.setRadioParams(915_000u, 125_000u, 0u, 255u) }
                reply(fixture, 2, PacketBuilder.setRadio(915.0, 125.0, 0u, 255u),
                    packet(ResponseCode.ERROR, Bytes.of(6)))
                val fault = assertFailsWith<SettingsServiceException> { write.await() }
                assertEquals(6.toUByte(), assertIs<MeshCoreException.DeviceError>(
                    assertIs<SettingsServiceError.SessionError>(fault.error).error,
                ).code)
                assertFalse(fault.isRetryable)
                assertEquals(2, fixture.radio.sent.size)
            } finally { fixture.close() }
        },
        nativeAsync("Bluetooth PIN forwards source zero fixed and full-width values without truncation") {
            val fixture = settingsFixture()
            try {
                var count = 1
                for (pin in listOf(0u, 100_000u, 999_999u, UInt.MAX_VALUE)) {
                    val write = request { fixture.settings.setBlePin(pin) }
                    reply(fixture, ++count, hex("25") + little32(pin.toLong()), okPacket())
                    write.await()
                }
            } finally { fixture.close() }
        },
        nativeAsync("firmware clock rollback rejection is preserved instead of retried or reported written") {
            val fixture = settingsFixture()
            try {
                val write = request { fixture.settings.setTime(NOW.minusSeconds(1)) }
                reply(fixture, 2, hex("06") + little32(NOW.epochSecond - 1), packet(ResponseCode.ERROR, Bytes.of(6)))
                val fault = assertFailsWith<SettingsServiceException> { write.await() }
                assertEquals(6.toUByte(), assertIs<MeshCoreException.DeviceError>(
                    assertIs<SettingsServiceError.SessionError>(fault.error).error,
                ).code)
                assertFalse(fault.isRetryable)
                assertEquals(2, fixture.radio.sent.size)
            } finally { fixture.close() }
        },
        nativeAsync("device capability query retains future raw hash mode repeat PIN and capacities") {
            val fixture = settingsFixture()
            try {
                val read = request { fixture.settings.queryDevice() }
                reply(fixture, 2, hex("1603"), capabilitiesPacket(repeat = true, hash = 255u, blePin = UInt.MAX_VALUE))
                val actual = read.await()
                assertEquals(13.toUByte(), actual.firmwareVersion)
                assertEquals(20L, actual.maxContacts)
                assertEquals(8L, actual.maxChannels)
                assertEquals(UInt.MAX_VALUE, actual.blePin)
                assertTrue(actual.clientRepeat)
                assertEquals(255.toUByte(), actual.pathHashMode)
                assertEquals(256, actual.hashSize)
                assertEquals("Test radio", actual.model)
            } finally { fixture.close() }
        },
        nativeAsync("other-parameter device defaults preserve the current DTO rather than creating new defaults") {
            val fixture = settingsFixture()
            try {
                val device = testDevice().copy(
                    manualAddContacts = true, telemetryModeBase = 2u, telemetryModeLoc = 1u,
                    telemetryModeEnv = 3u, advertLocationPolicy = 1u, multiAcks = 7u,
                )
                val write = request { fixture.settings.setOtherParamsVerified(device) }
                reply(fixture, 2, hex("2601360107"), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"),
                    selfPacket(manual = true, policy = 1u, telemetry = 0x36u, multiAcks = 7u))
                val actual = write.await()
                assertTrue(actual.manualAddContacts)
                assertEquals(3.toUByte(), actual.telemetryModeEnvironment)
                assertEquals(1.toUByte(), actual.telemetryModeLocation)
                assertEquals(2.toUByte(), actual.telemetryModeBase)
                assertEquals(7.toUByte(), actual.multiAcks)
            } finally { fixture.close() }
        },
        nativeAsync("compatibility location-sharing overload maps enabled to prefs rather than share") {
            val fixture = settingsFixture()
            try {
                @Suppress("DEPRECATION")
                val write = request { fixture.settings.setOtherParams(true, TelemetryModes.of(), true, 2u) }
                reply(fixture, 2, hex("2600000202"), okPacket())
                write.await()
            } finally { fixture.close() }
        },
        nativeAsync("unverified preset application does not invent a readback or settings update") {
            val fixture = settingsFixture()
            try {
                val subscription = fixture.settings.events()
                val preset = assertNotNull(RadioPresets.all.firstOrNull { it.id == "hu" })
                val write = request { fixture.settings.applyRadioPreset(preset) }
                reply(fixture, 2, PacketBuilder.setRadio(869.618, 62.5, 7u, 5u), okPacket())
                reply(fixture, 3, hex("1603"), capabilitiesPacket())
                reply(fixture, 4, hex("3d0001"), okPacket())
                write.await()
                subscription.close()
                assertTrue(subscription.events.toList().isEmpty())
                assertEquals(4, fixture.radio.sent.size)
            } finally { fixture.close() }
        },
        nativeAsync("core statistics retain unsigned voltage uptime error and queue units") {
            val fixture = settingsFixture()
            try {
                val read = request { fixture.settings.getStatsCore() }
                reply(fixture, 2, hex("3800"),
                    packet(ResponseCode.STATS, Bytes.of(0) + little16(65535) + little32(0xFFFF_FFFFL) + little16(0x8000) + Bytes.of(255)))
                val actual = read.await()
                assertEquals(UShort.MAX_VALUE, actual.batteryMV)
                assertEquals(UInt.MAX_VALUE, actual.uptimeSeconds)
                assertEquals(0x8000.toUShort(), actual.errors)
                assertEquals(UByte.MAX_VALUE, actual.queueLength)
            } finally { fixture.close() }
        },
        nativeAsync("radio statistics retain signed RF values quarter-dB SNR and airtime seconds") {
            val fixture = settingsFixture()
            try {
                val read = request { fixture.settings.getStatsRadio() }
                reply(fixture, 2, hex("3801"),
                    packet(ResponseCode.STATS, Bytes.of(1) + little16(-100) + Bytes.of(0x88, 0xFB) +
                        little32(0x8000_0000L) + little32(0xFFFF_FFFFL)))
                val actual = read.await()
                assertEquals((-100).toShort(), actual.noiseFloor)
                assertEquals((-120).toByte(), actual.lastRSSI)
                assertEquals(-1.25, actual.lastSNR)
                assertEquals(0x8000_0000u, actual.txAirtimeSeconds)
                assertEquals(UInt.MAX_VALUE, actual.rxAirtimeSeconds)
            } finally { fixture.close() }
        },
        nativeAsync("packet statistics preserve all counters and the genuine source absent-errors default") {
            val fixture = settingsFixture()
            try {
                val base = (1L..6L).fold(Bytes.EMPTY) { bytes, value -> bytes + little32(value) }
                val read = request { fixture.settings.getStatsPackets() }
                reply(fixture, 2, hex("3802"), packet(ResponseCode.STATS, Bytes.of(2) + base))
                assertEquals(0u, read.await().receiveErrors)
                val extended = request { fixture.settings.getStatsPackets() }
                reply(fixture, 3, hex("3802"), packet(ResponseCode.STATS, Bytes.of(2) + base + little32(0xFFFF_FFFFL)))
                val actual = extended.await()
                assertEquals(listOf(1u, 2u, 3u, 4u, 5u, 6u),
                    listOf(actual.received, actual.sent, actual.floodTx, actual.directTx, actual.floodRx, actual.directRx))
                assertEquals(UInt.MAX_VALUE, actual.receiveErrors)
            } finally { fixture.close() }
        },
        nativeAsync("custom variables preserve source key case and values containing further colons") {
            val fixture = settingsFixture()
            try {
                val write = request { fixture.settings.setCustomVar("future", "a:b") }
                reply(fixture, 2, hex("29") + Bytes.utf8("future:a:b"), okPacket())
                write.await()
                val read = request { fixture.settings.getCustomVars() }
                reply(fixture, 3, hex("28"), customVarsPacket("GPS:1,future:a:b"))
                assertEquals(mapOf("GPS" to "1", "future" to "a:b"), read.await())
            } finally { fixture.close() }
        },
        nativeAsync("device GPS enabling verifies actual enabled state before refreshing self info") {
            val fixture = settingsFixture()
            try {
                val subscription = fixture.settings.events()
                val write = request { fixture.settings.setDeviceGPSEnabledVerified(true) }
                reply(fixture, 2, hex("29") + Bytes.utf8("gps:1"), okPacket())
                reply(fixture, 3, hex("28"), customVarsPacket("gps:1"))
                reply(fixture, 4, PacketBuilder.appStart("MCore"), selfPacket(latitudeScaled = 1_000_000))
                assertEquals(DeviceGPSState(true, true), write.await())
                subscription.close()
                assertEquals(1.0, assertIs<SettingsEvent.DeviceUpdated>(subscription.events.toList().single().event).info.latitude)
            } finally { fixture.close() }
        },
        nativeAsync("private-key export uses actual bytes and a disabled device returns its typed cause") {
            val fixture = settingsFixture()
            try {
                val key = filled(0xA5, 64)
                val read = request { fixture.settings.exportPrivateKey() }
                reply(fixture, 2, hex("17"), packet(ResponseCode.PRIVATE_KEY, key))
                assertEquals(key, read.await())
                val disabled = request { fixture.settings.exportPrivateKey() }
                reply(fixture, 3, hex("17"), packet(ResponseCode.DISABLED))
                val failure = assertFailsWith<SettingsServiceException> { disabled.await() }
                assertIs<MeshCoreException.FeatureDisabled>(assertIs<SettingsServiceError.SessionError>(failure.error).error)
                assertFalse(failure.isRetryable)
            } finally { fixture.close() }
        },
        nativeAsync("private-key import enforces the actual expanded size and retains protocol self refresh") {
            val fixture = settingsFixture()
            try {
                for (size in listOf(32, 63, 65)) {
                    val failure = assertFailsWith<SettingsServiceException> { fixture.settings.importPrivateKey(filled(0xA5, size)) }
                    assertIs<MeshCoreException.InvalidInput>(assertIs<SettingsServiceError.SessionError>(failure.error).error)
                }
                assertEquals(1, fixture.radio.sent.size)
                val key = filled(0xA5, 64)
                val write = request { fixture.settings.importPrivateKey(key) }
                reply(fixture, 2, hex("18") + key, okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket())
                write.await()
            } finally { fixture.close() }
        },
        nativeAsync("signing awaits actual start capacity chunk completion and the complete signature") {
            val fixture = settingsFixture()
            try {
                val data = Bytes.utf8("Test")
                val signature = filled(0x5A, 64)
                val sign = request { fixture.settings.sign(data) }
                reply(fixture, 2, hex("21"), packet(ResponseCode.SIGN_START, Bytes.of(0) + little32(120)))
                reply(fixture, 3, hex("22") + data, okPacket())
                reply(fixture, 4, hex("23"), packet(ResponseCode.SIGNATURE, signature))
                assertEquals(signature, sign.await())
            } finally { fixture.close() }
        },
        nativeAsync("signing capacity failure retains max actual sizes and sends no chunk") {
            val fixture = settingsFixture()
            try {
                val sign = request { fixture.settings.sign(Bytes.utf8("Test")) }
                reply(fixture, 2, hex("21"), packet(ResponseCode.SIGN_START, Bytes.of(0) + little32(3)))
                val failure = assertIs<MeshCoreException.DataTooLarge>(
                    assertIs<SettingsServiceError.SessionError>(
                        assertFailsWith<SettingsServiceException> { sign.await() }.error,
                    ).error,
                )
                assertEquals(3L, failure.maxSize)
                assertEquals(4L, failure.actualSize)
                assertEquals(2, fixture.radio.sent.size)
            } finally { fixture.close() }
        },
        nativeAsync("test-only reset waits for OK while reboot preserves the source send-only contract") {
            val fixture = settingsFixture()
            try {
                val reset = request { fixture.settings.factoryReset() }
                reply(fixture, 2, hex("33") + Bytes.utf8("reset"), okPacket())
                reset.await()
                val reboot = request { fixture.settings.reboot() }
                runCurrent()
                reboot.await()
                assertEquals(hex("13") + Bytes.utf8("reboot"), fixture.radio.sent.last())
                assertEquals(3, fixture.radio.sent.size)
            } finally { fixture.close() }
        },
        nativeAsync("actual typed transport send failure stops the verified operation before readback") {
            val fixture = settingsFixture()
            try {
                val cause = MeshTransportError.SendFailed("deterministic test failure")
                fixture.radio.sendFailure = cause
                val write = request { fixture.settings.setNodeNameVerified("Never written") }
                assertSame(cause, assertFailsWith<MeshTransportError.SendFailed> { write.await() })
                assertEquals(1, fixture.radio.sent.size)
            } finally { fixture.close() }
        },
    )
}
