// AndroidOnly: WP-317 A queued RF edit must revalidate actual radio state inside the real service lock.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRadioSerializationTest {
    @Test fun cancellingTheUiWaiterDoesNotDiscardItsAlreadyVerifiedCurrentSessionPublications() = runTest {
        val fixture = settingsFixture()
        val events = fixture.settings.events()
        try {
            val write = request { fixture.settings.setRadioParamsPersisted(915_000u, 125_000u, 7u, 5u, true) }
            reply(fixture, 2, PacketBuilder.setRadio(915.0, 125.0, 7u, 5u, true), okPacket())
            reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket())
            reply(fixture, 4, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = true))
            assertFalse(write.isCompleted)
            write.cancel()
            runCurrent()
            assertFailsWith<CancellationException> { write.await() }
            val received = mutableListOf<SettingsEvent>()
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                events.events.collect { received += it.event }
            }
            try {
                runCurrent()
                assertEquals(2, received.size)
                val repeat = assertIs<SettingsEvent.ClientRepeatUpdated>(received.first())
                val device = assertIs<SettingsEvent.DeviceUpdated>(received.last())
                assertTrue(repeat.enabled)
                assertEquals(915.0, device.info.radioFrequency)
                assertTrue(assertNotNull(repeat.persistence).isCancelled)
                assertTrue(assertNotNull(device.persistence).isCancelled)
                assertEquals(token(), fixture.signals.snapshot.value.token)
                assertEquals(4, fixture.radio.sent.size, "Cancelled acknowledgement cannot invent rollback traffic")
            } finally { collector.cancel() }
        } finally { events.close(); fixture.close() }
    }

    @Test fun persistedRadioWaitsForBothPublicationsWithoutHoldingTheRadioOperationLock() = runTest {
        val fixture = settingsFixture()
        val events = fixture.settings.events()
        val repeat = CompletableDeferred<SettingsEvent.ClientRepeatUpdated>()
        val device = CompletableDeferred<SettingsEvent.DeviceUpdated>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.events.collect {
                when (val event = it.event) {
                    is SettingsEvent.ClientRepeatUpdated -> repeat.complete(event)
                    is SettingsEvent.DeviceUpdated -> device.complete(event)
                    else -> error("Unexpected radio event ${it.event}")
                }
            }
        }
        try {
            val write = request { fixture.settings.setRadioParamsPersisted(915_000u, 125_000u, 7u, 5u, true) }
            reply(fixture, 2, PacketBuilder.setRadio(915.0, 125.0, 7u, 5u, true), okPacket())
            reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket())
            reply(fixture, 4, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = true))
            assertFalse(write.isCompleted)
            assertNotNull(device.await().persistence).complete(Unit)
            runCurrent()
            assertFalse(write.isCompleted, "RF persistence alone cannot hide a missing Repeat acknowledgement")
            val read = request { fixture.settings.queryDevice() }
            reply(fixture, 5, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = true))
            assertEquals(true, read.await().clientRepeat)
            assertFalse(write.isCompleted, "A waiting consumer must not hold the radio lock")
            assertNotNull(repeat.await().persistence).complete(Unit)
            assertEquals(915.0, write.await().radioFrequency)
        } finally { events.close(); collector.cancel(); fixture.close() }
    }

    @Test fun failureOfEitherPersistedRadioPublicationReachesTheCallerAndCanBeRetried() = runTest {
        for (failRepeat in listOf(false, true)) {
            val fixture = settingsFixture()
            val events = fixture.settings.events()
            val failure = IllegalStateException("Radio persistence unavailable")
            var rejecting = true
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                events.events.collect {
                    val completion = when (val event = it.event) {
                        is SettingsEvent.ClientRepeatUpdated -> assertNotNull(event.persistence)
                        is SettingsEvent.DeviceUpdated -> assertNotNull(event.persistence)
                        else -> error("Unexpected radio event $event")
                    }
                    if (rejecting && (it.event is SettingsEvent.ClientRepeatUpdated) == failRepeat) {
                        completion.completeExceptionally(failure)
                    } else completion.complete(Unit)
                }
            }
            try {
                val write = backgroundScope.async {
                    runCatching { fixture.settings.setRadioParamsPersisted(915_000u, 125_000u, 7u, 5u, true) }
                }
                reply(fixture, 2, PacketBuilder.setRadio(915.0, 125.0, 7u, 5u, true), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket())
                reply(fixture, 4, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = true))
                val actual = assertIs<IllegalStateException>(write.await().exceptionOrNull())
                assertSame(failure, generateSequence<Throwable>(actual) { it.cause }.last())
                rejecting = false
                val retry = request { fixture.settings.setRadioParamsPersisted(915_000u, 125_000u, 7u, 5u, true) }
                reply(fixture, 5, PacketBuilder.setRadio(915.0, 125.0, 7u, 5u, true), okPacket())
                reply(fixture, 6, PacketBuilder.appStart("MCore"), selfPacket())
                reply(fixture, 7, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = true))
                assertEquals(915.0, retry.await().radioFrequency)
            } finally { events.close(); collector.cancel(); fixture.close() }
        }
    }

    @Test fun powerWaitsForItsConsumerAndReplacingThatConsumerFailsTheOriginalWait() = runTest {
        for (replace in listOf(false, true)) {
            val fixture = settingsFixture()
            val events = fixture.settings.events()
            val device = CompletableDeferred<SettingsEvent.DeviceUpdated>()
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                events.events.collect { device.complete(assertIs<SettingsEvent.DeviceUpdated>(it.event)) }
            }
            try {
                val power = request { fixture.settings.setTxPowerPersisted(10) }
                reply(fixture, 2, PacketBuilder.setTxPower(10), okPacket())
                reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(txPower = 10))
                val completion = assertNotNull(device.await().persistence)
                assertFalse(power.isCompleted)
                if (replace) {
                    fixture.settings.events().close()
                    assertEquals(
                        SettingsServiceError.NotConnected,
                        assertFailsWith<SettingsServiceException> { power.await() }.error,
                    )
                } else {
                    completion.complete(Unit)
                    assertEquals(10.toByte(), power.await().txPower)
                }
            } finally { events.close(); collector.cancel(); fixture.close() }
        }
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
