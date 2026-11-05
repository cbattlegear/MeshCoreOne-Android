// AndroidOnly: WP-317 A queued RF edit must revalidate actual radio state inside the real service lock.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PreRepeatSettingsAttempt
import com.meshcoreone.android.core.contracts.domain.RepeatSnapshotRestoreAuthority
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.session.RadioWritePermit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    @Test fun omittedRepeatPreservesTheQueriedModeAcrossEverySettingsRadioEntryPoint() = runTest {
        for (entry in listOf("raw", "verified", "persisted", "preset-raw", "preset-verified", "preset-persisted")) {
            val fixture = settingsFixture()
            val subscription = fixture.settings.events()
            val published = mutableListOf<SettingsEvent>()
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                subscription.events.collect {
                    published += it.event
                    when (val event = it.event) {
                        is SettingsEvent.ClientRepeatUpdated -> event.persistence?.complete(Unit)
                        is SettingsEvent.DeviceUpdated -> event.persistence?.complete(Unit)
                        else -> error("Unexpected RF event $event")
                    }
                }
            }
            try {
                val preset = RadioPresets.all.first { it.id == "us-ca" }
                val isPreset = entry.startsWith("preset-")
                val frequency = if (isPreset) preset.frequencyKHz else 915_000u
                val bandwidth = if (isPreset) preset.bandwidthHz else 125_000u
                val sf: UByte = if (isPreset) preset.spreadingFactor else 7u
                val cr: UByte = if (isPreset) preset.codingRate else 5u
                val write = request {
                    when (entry) {
                        "raw" -> fixture.settings.setRadioParams(frequency, bandwidth, sf, cr)
                        "verified" -> fixture.settings.setRadioParamsVerified(frequency, bandwidth, sf, cr)
                        "persisted" -> fixture.settings.setRadioParamsPersisted(frequency, bandwidth, sf, cr)
                        "preset-raw" -> fixture.settings.applyRadioPreset(preset)
                        "preset-verified" -> fixture.settings.applyRadioPresetVerified(preset)
                        else -> fixture.settings.applyRadioPresetPersisted(preset)
                    }
                }
                reply(fixture, 2, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = true))
                reply(fixture, 3, PacketBuilder.setRadio(
                    frequency.toDouble() / 1000, bandwidth.toDouble() / 1000, sf, cr, true,
                ), okPacket())
                if (entry != "raw" && entry != "preset-raw") {
                    reply(fixture, 4, PacketBuilder.appStart("MCore"), selfPacket(frequency = frequency, bandwidth = bandwidth, sf = sf, cr = cr))
                    reply(fixture, 5, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = true))
                }
                write.await()
                runCurrent()
                assertEquals(listOf(true), published.filterIsInstance<SettingsEvent.ClientRepeatUpdated>().map { it.enabled }, entry)
                assertEquals(1, fixture.radio.sent.single { it[0] == 0x0b.toUByte() }[11].toInt(), entry)
                if (entry.endsWith("persisted")) assertFalse(fixture.settings.radioSettingsNeedsRecovery, entry)
            } finally { subscription.close(); collector.cancel(); fixture.close() }
        }
    }

    @Test fun repeatBaselineMismatchReconcilesAndPermitsAFreshRadioAction() = runTest {
        val fixture = settingsFixture()
        val subscription = fixture.settings.events()
        val modes = mutableListOf<Boolean>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            subscription.events.collect {
                when (val event = it.event) {
                    is SettingsEvent.ClientRepeatUpdated -> { modes += event.enabled; event.persistence?.complete(Unit) }
                    is SettingsEvent.DeviceUpdated -> event.persistence?.complete(Unit)
                    else -> error("Unexpected RF event $event")
                }
            }
        }
        try {
            val stale = DeviceDTO(
                radioId = RADIO, publicKey = filled(1), nodeName = "Test",
                frequency = 915_000u, bandwidth = 125_000u, spreadingFactor = 7u, codingRate = 5u, clientRepeat = true,
            )
            val rejected = request { runCatching {
                fixture.settings.setRadioParamsPersisted(868_100u, 125_000u, 7u, 5u, false, expectedDevice = stale)
            } }
            reply(fixture, 2, PacketBuilder.appStart("MCore"), selfPacket())
            reply(fixture, 3, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = false))
            assertIs<SettingsServiceException>(rejected.await().exceptionOrNull())
            assertTrue(fixture.settings.radioSettingsNeedsRecovery)
            assertFalse(fixture.radio.sent.any { it[0] == 0x0b.toUByte() })
            val recovery = request { fixture.settings.reconcileRadioSettingsIfNeeded() }
            reply(fixture, 4, PacketBuilder.appStart("MCore"), selfPacket())
            reply(fixture, 5, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = false))
            recovery.await()
            assertEquals(listOf(false), modes)
            assertFalse(fixture.settings.radioSettingsNeedsRecovery)
            val fresh = request {
                fixture.settings.setRadioParamsPersisted(
                    868_100u, 125_000u, 7u, 5u, false, expectedDevice = stale.copy(clientRepeat = false),
                )
            }
            reply(fixture, 6, PacketBuilder.appStart("MCore"), selfPacket())
            reply(fixture, 7, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = false))
            reply(fixture, 8, PacketBuilder.setRadio(868.1, 125.0, 7u, 5u, false), okPacket())
            reply(fixture, 9, PacketBuilder.appStart("MCore"), selfPacket(frequency = 868_100u))
            reply(fixture, 10, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = false))
            assertEquals(868.1, fresh.await().radioFrequency)
            assertFalse(fixture.settings.radioSettingsNeedsRecovery)
        } finally { subscription.close(); collector.cancel(); fixture.close() }
    }

    @Test fun restoreRetirementRequiresVerifiedRfAndRepeatOffBeforeEitherPublication() = runTest {
        println("WP305_REPEAT_CLEAR|verified-hook|START")
        for (outcome in listOf("success", "cancel-before-ack", "rf-mismatch", "repeat-mismatch")) {
            val fixture = settingsFixture()
            val events = fixture.settings.events()
            var verified = 0
            var published = 0
            val authority = object : RepeatSnapshotRestoreAuthority {
                override suspend fun withWrite(write: suspend (RadioWritePermit) -> Unit) {
                    val permit = RadioWritePermit {}
                    try { write(permit) } finally { permit.releaseIfUnclaimed() }
                }
                override fun onRestoreVerified() { verified++ }
            }
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                events.events.collect {
                    assertEquals(1, verified, "Retirement proof precedes even the first durable publication")
                    published++
                    when (val event = it.event) {
                        is SettingsEvent.ClientRepeatUpdated -> assertNotNull(event.persistence).complete(Unit)
                        is SettingsEvent.DeviceUpdated -> assertNotNull(event.persistence).complete(Unit)
                        else -> error("Unexpected restore event $event")
                    }
                }
            }
            try {
                val write = request {
                    fixture.settings.setRadioParamsPersisted(
                        915_000u, 125_000u, 7u, 5u, false, restoreAuthority = authority,
                    )
                }
                runCurrent()
                assertEquals(0, verified)
                if (outcome == "cancel-before-ack") {
                    write.cancel()
                    assertFailsWith<CancellationException> { write.await() }
                    runCurrent()
                } else {
                    reply(fixture, 2, PacketBuilder.setRadio(915.0, 125.0, 7u, 5u, false), okPacket())
                    assertEquals(0, verified, "An ACK is not verified restoration")
                    reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket(
                        frequency = if (outcome == "rf-mismatch") 868_000u else 915_000u,
                    ))
                    if (outcome != "rf-mismatch") {
                        assertEquals(0, verified, "RF readback alone does not confirm Repeat-off")
                        reply(fixture, 4, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = outcome == "repeat-mismatch"))
                    }
                    if (outcome == "success") write.await()
                    else assertFailsWith<SettingsServiceException> { write.await() }
                }
                assertEquals(if (outcome == "success") 1 else 0, verified)
                assertEquals(if (outcome == "success") 2 else 0, published)
            } finally { events.close(); collector.cancel(); fixture.close() }
            println("WP305_REPEAT_CLEAR|verified-hook|$outcome|END|SUCCESS")
        }
    }

    @Test fun failedRfAcknowledgementReconcilesActualCurrentRadioAndBothPublicationsBeforeClearingRecovery() = runTest {
        val fixture = settingsFixture()
        val events = fixture.settings.events()
        val failure = IllegalStateException("RF storage rejected")
        val recoveryDevice = CompletableDeferred<SettingsEvent.DeviceUpdated>()
        val recoveryRepeat = CompletableDeferred<SettingsEvent.ClientRepeatUpdated>()
        var recovering = false
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.events.collect {
                when (val event = it.event) {
                    is SettingsEvent.ClientRepeatUpdated -> if (recovering) recoveryRepeat.complete(event)
                        else assertNotNull(event.persistence).complete(Unit)
                    is SettingsEvent.DeviceUpdated -> if (recovering) recoveryDevice.complete(event)
                        else assertNotNull(event.persistence).completeExceptionally(failure)
                    else -> error("Unexpected RF event $event")
                }
            }
        }
        try {
            val failed = backgroundScope.async {
                runCatching { fixture.settings.setRadioParamsPersisted(915_000u, 125_000u, 7u, 5u, false) }
            }
            reply(fixture, 2, PacketBuilder.setRadio(915.0, 125.0, 7u, 5u, false), okPacket())
            reply(fixture, 3, PacketBuilder.appStart("MCore"), selfPacket())
            reply(fixture, 4, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = false))
            assertIs<IllegalStateException>(failed.await().exceptionOrNull())
            assertTrue(fixture.settings.radioSettingsNeedsRecovery)
            recovering = true
            val recovery = request { fixture.settings.reconcileRadioSettingsIfNeeded() }
            // Read the radio now, not a saved failed-write DTO: a later RF/Repeat change must survive.
            reply(fixture, 5, PacketBuilder.appStart("MCore"), selfPacket(frequency = 910_525u, bandwidth = 62_500u))
            reply(fixture, 6, PacketBuilder.deviceQuery(), capabilitiesPacket(repeat = true))
            val device = recoveryDevice.await()
            val repeat = recoveryRepeat.await()
            assertEquals(910.525, device.info.radioFrequency)
            assertEquals(62.5, device.info.radioBandwidth)
            assertTrue(repeat.enabled)
            assertFalse(recovery.isCompleted)
            assertNotNull(device.persistence).complete(Unit)
            runCurrent()
            assertFalse(recovery.isCompleted)
            assertTrue(fixture.settings.radioSettingsNeedsRecovery)
            assertNotNull(repeat.persistence).complete(Unit)
            recovery.await()
            assertFalse(fixture.settings.radioSettingsNeedsRecovery)
            assertEquals(1, fixture.radio.sent.count { it[0] == 0x0b.toUByte() }, "Reconciliation must never replay a stale SET")
            fixture.settings.reconcileRadioSettingsIfNeeded()
            assertEquals(6, fixture.radio.sent.size, "Acknowledged state does not need another reconciliation")
        } finally { events.close(); collector.cancel(); fixture.close() }
    }

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
        for (persisted in listOf(false, true)) {
        val fixture = settingsFixture()
        val events = fixture.settings.events()
        try {
            val prior = request { fixture.settings.setTxPowerVerified(20) }
            runCurrent()
            assertEquals(2, fixture.radio.sent.size)
            val attempt = preparedAttempt()
            val queued = request {
                if (persisted) fixture.settings.setRadioParamsPersisted(910_525u, 125_000u, 7u, 5u, true, repeatAttempt = attempt)
                else fixture.settings.setRadioParamsVerified(910_525u, 125_000u, 7u, 5u, true, repeatAttempt = attempt)
            }
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
        } finally { events.close(); fixture.close() }
        }
    }

    @Test fun repeatCancelledInsideActualProtocolQueueIsDefinitelyUnsentBeforeCleanup() = runTest {
        for (persisted in listOf(false, true)) {
        val fixture = settingsFixture()
        val events = fixture.settings.events()
        try {
            val prior = request { fixture.session.getBattery() }
            runCurrent()
            assertEquals(2, fixture.radio.sent.size)
            val attempt = preparedAttempt()
            val queued = request {
                if (persisted) fixture.settings.setRadioParamsPersisted(910_525u, 125_000u, 7u, 5u, true, repeatAttempt = attempt)
                else fixture.settings.setRadioParamsVerified(910_525u, 125_000u, 7u, 5u, true, repeatAttempt = attempt)
            }
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
        } finally { events.close(); fixture.close() }
        }
    }

    @Test fun repeatCancelledAfterActualSetPreservesTheRestorationSnapshotForUncertainWireOutcome() = runTest {
        for (persisted in listOf(false, true)) {
        val fixture = settingsFixture()
        val events = fixture.settings.events()
        try {
            val attempt = preparedAttempt()
            val sent = request {
                if (persisted) fixture.settings.setRadioParamsPersisted(910_525u, 125_000u, 7u, 5u, true, repeatAttempt = attempt)
                else fixture.settings.setRadioParamsVerified(910_525u, 125_000u, 7u, 5u, true, repeatAttempt = attempt)
            }
            runCurrent()
            assertEquals(PacketBuilder.setRadio(910.525, 125.0, 7u, 5u, true), fixture.radio.sent.last())
            sent.cancel()
            assertFailsWith<CancellationException> { sent.await() }
            runCurrent()
            assertNull(attempt.takeAbortSnapshot(), "A possible SET must never authorize clearing restoration data")
            fixture.radio.ok()
            runCurrent()
            assertEquals(1, fixture.radio.sent.count { it[0] == 0x0b.toUByte() })
        } finally { events.close(); fixture.close() }
        }
    }

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
                assertTrue(fixture.settings.radioSettingsNeedsRecovery, "A fresh action must reconcile the mismatched baseline")
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
