// AndroidOnly: WP-317 A queued RF edit must revalidate actual radio state inside the real service lock.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PreRepeatSettingsAttempt
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRadioSerializationTest {
    private fun preparedAttempt(): PreRepeatSettingsAttempt {
        val device = DeviceDTO(
            radioId = RADIO, publicKey = filled(1), nodeName = "Test",
            frequency = 915_000u, bandwidth = 125_000u, spreadingFactor = 7u, codingRate = 5u,
        ).savingPreRepeatSettings()
        return PreRepeatSettingsAttempt(token()).also {
            it.claimPreparation()
            it.recordCommitted(DeviceWriteSnapshot(device, 2, 1), created = true)
        }
    }

    @Test fun repeatCancelledBehindRealServiceLockNeverDispatchesAndRetainsAbortAuthority() = runTest {
        val fixture = settingsFixture()
        try {
            val prior = request { fixture.settings.setTxPowerVerified(20) }
            runCurrent()
            assertEquals(2, fixture.radio.sent.size)
            val attempt = preparedAttempt()
            val queued = request { fixture.settings.setRadioParamsVerified(910_525u, 125_000u, 7u, 5u, true, repeatAttempt = attempt) }
            runCurrent()
            assertFalse(queued.isCompleted)
            queued.cancel()
            assertFailsWith<CancellationException> { queued.await() }
            runCurrent()
            assertNotNull(attempt.takeAbortSnapshot())
            reply(fixture, 2, PacketBuilder.setTxPower(20), okPacket())
            reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(txPower = 20))
            prior.await()
            assertEquals(0, fixture.radio.sent.count { it[0] == 0x0b.toUByte() })
        } finally { fixture.close() }
    }

    @Test fun repeatCancelledInsideActualProtocolQueueIsDefinitelyUnsentBeforeCleanup() = runTest {
        val fixture = settingsFixture()
        try {
            val prior = request { fixture.session.getBattery() }
            runCurrent()
            assertEquals(2, fixture.radio.sent.size)
            val attempt = preparedAttempt()
            val queued = request { fixture.settings.setRadioParamsVerified(910_525u, 125_000u, 7u, 5u, true, repeatAttempt = attempt) }
            runCurrent()
            assertFalse(queued.isCompleted)
            queued.cancel()
            assertFailsWith<CancellationException> { queued.await() }
            runCurrent()
            assertNotNull(attempt.takeAbortSnapshot(), "Real protocol cancellation must settle before creation cleanup")
            fixture.radio.receive(Bytes.of(12, 0x10, 0x0e))
            runCurrent()
            prior.await()
            assertEquals(0, fixture.radio.sent.count { it[0] == 0x0b.toUByte() })
        } finally { fixture.close() }
    }

    @Test fun repeatCancelledAfterActualSetPreservesTheRestorationSnapshotForUncertainWireOutcome() = runTest {
        val fixture = settingsFixture()
        try {
            val attempt = preparedAttempt()
            val sent = request { fixture.settings.setRadioParamsVerified(910_525u, 125_000u, 7u, 5u, true, repeatAttempt = attempt) }
            runCurrent()
            assertEquals(PacketBuilder.setRadio(910.525, 125.0, 7u, 5u, true), fixture.radio.sent.last())
            sent.cancel()
            assertFailsWith<CancellationException> { sent.await() }
            runCurrent()
            assertNull(attempt.takeAbortSnapshot(), "A possible SET must never authorize clearing restoration data")
            fixture.radio.ok()
            runCurrent()
            assertEquals(1, fixture.radio.sent.count { it[0] == 0x0b.toUByte() })
        } finally { fixture.close() }
    }

    @Test fun queuedWriteRejectsActualRfOrRepeatChangesBeforeMutationWithoutWaitingForPublishedEvents() = runTest {
        for ((rfChanges, oldRepeat, newRepeat) in listOf(
            Triple(true, false, false), Triple(false, false, true), Triple(false, true, false),
        )) {
            val fixture = settingsFixture()
            val events = fixture.settings.events()
            try {
                val old = DeviceDTO(
                    radioId = RADIO, publicKey = filled(1), nodeName = "Test",
                    frequency = 915_000u, bandwidth = 125_000u, spreadingFactor = 7u, codingRate = 5u,
                    clientRepeat = oldRepeat,
                )
                val fresh = old.copy(
                    frequency = if (rfChanges) 916_000u else old.frequency,
                    bandwidth = if (rfChanges) 62_500u else old.bandwidth,
                    spreadingFactor = if (rfChanges) 9u else old.spreadingFactor,
                    codingRate = if (rfChanges) 8u else old.codingRate,
                    clientRepeat = newRepeat,
                )
                val actual = selfPacket(
                    frequency = fresh.frequency, bandwidth = fresh.bandwidth,
                    sf = fresh.spreadingFactor, cr = fresh.codingRate,
                )
                val prior = request {
                    fixture.settings.setRadioParamsVerified(
                        fresh.frequency, fresh.bandwidth, fresh.spreadingFactor, fresh.codingRate, newRepeat,
                    )
                }
                reply(fixture, 2, PacketBuilder.setRadio(
                    fresh.frequency.toDouble() / 1000, fresh.bandwidth.toDouble() / 1000,
                    fresh.spreadingFactor, fresh.codingRate, newRepeat,
                ), okPacket())
                runCurrent()
                assertEquals(3, fixture.radio.sent.size)
                assertEquals(PacketBuilder.appStart("MCore"), fixture.radio.sent.last())
                val queued = request {
                    fixture.settings.setRadioParamsVerified(
                        910_525u, old.bandwidth, old.spreadingFactor, old.codingRate, oldRepeat, expectedDevice = old,
                    )
                }
                runCurrent()
                assertFalse(prior.isCompleted)
                assertFalse(queued.isCompleted)
                assertEquals(3, fixture.radio.sent.size, "Guarded write must wait for the real operation lock")
                reply(fixture, 3, PacketBuilder.appStart("MCore"), actual)
                reply(fixture, 4, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = newRepeat))
                prior.await()
                // Leave both prior events buffered: runtime/Room/UI publication is deliberately not an oracle.
                reply(fixture, 5, PacketBuilder.appStart("MCore"), actual)
                reply(fixture, 6, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = newRepeat))
                val failure = assertIs<SettingsServiceError.VerificationFailed>(
                    assertFailsWith<SettingsServiceException> { queued.await() }.error,
                )
                assertEquals("radio settings changed while the operation was queued", failure.actual)
                assertEquals(1, fixture.radio.sent.count { it[0] == 0x0b.toUByte() })
                assertEquals(token(), fixture.signals.snapshot.value.token)
                events.close()
                val published = events.events.toList().map { it.event }
                assertEquals(2, published.size)
                assertEquals(SettingsEvent.ClientRepeatUpdated(newRepeat), published.first())
                val priorDevice = assertIs<SettingsEvent.DeviceUpdated>(published.last()).info
                assertEquals(fresh.frequency.toDouble() / 1000, priorDevice.radioFrequency)
                assertEquals(fresh.bandwidth.toDouble() / 1000, priorDevice.radioBandwidth)
                assertEquals(fresh.spreadingFactor, priorDevice.radioSpreadingFactor)
                assertEquals(fresh.codingRate, priorDevice.radioCodingRate)
                val retry = request {
                    fixture.settings.setRadioParamsVerified(
                        910_525u, fresh.bandwidth, fresh.spreadingFactor, fresh.codingRate,
                        newRepeat, expectedDevice = fresh,
                    )
                }
                reply(fixture, 7, PacketBuilder.appStart("MCore"), actual)
                reply(fixture, 8, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = newRepeat))
                reply(fixture, 9, PacketBuilder.setRadio(
                    910.525, fresh.bandwidth.toDouble() / 1000, fresh.spreadingFactor, fresh.codingRate, newRepeat,
                ), okPacket())
                reply(fixture, 10, PacketBuilder.appStart("MCore"), selfPacket(
                    frequency = 910_525u, bandwidth = fresh.bandwidth, sf = fresh.spreadingFactor, cr = fresh.codingRate,
                ))
                reply(fixture, 11, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = newRepeat))
                assertEquals(910.525, retry.await().radioFrequency)
                assertEquals(2, fixture.radio.sent.count { it[0] == 0x0b.toUByte() })
            } finally { events.close(); fixture.close() }
        }
    }
}
