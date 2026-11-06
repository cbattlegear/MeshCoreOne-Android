// AndroidOnly: WP-317 Awaited path-hash persistence preserves radio verification, event ordering and session retirement.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.contracts.domain.SessionEventSubscription
import com.meshcoreone.android.core.protocol.command.PacketBuilder
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPathHashPersistenceTest {
    @Test fun replacingSubscriberFailsPresetFenceBeforeRadioOrMetadataWrites() = runTest {
        val fixture = settingsFixture()
        val subscription = fixture.settings.events()
        val received = CompletableDeferred<SettingsEvent.PersistenceBarrier>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            subscription.events.collect { received.complete(assertIs<SettingsEvent.PersistenceBarrier>(it.event)) }
        }
        var metadata = false
        try {
            val write = request {
                fixture.settings.applyRadioPresetVerified(RadioPresets.all.first()) { metadata = true }
            }
            assertNotNull(received.await().persistence)
            assertFalse(write.isCompleted)
            assertEquals(1, fixture.radio.sent.size)
            fixture.settings.events().close()
            runCurrent()
            val failure = kotlin.test.assertFailsWith<SettingsServiceException> { write.await() }
            assertEquals(SettingsServiceError.NotConnected, failure.error)
            assertFalse(metadata)
            assertEquals(1, fixture.radio.sent.size)
        } finally {
            subscription.close()
            collector.cancel()
            fixture.close()
        }
    }

    @Test fun cancelledPresetFenceCannotWriteRadioOrAcknowledgeRecovery() = runTest {
        val fixture = settingsFixture()
        val subscription = fixture.settings.events()
        val received = CompletableDeferred<SettingsEvent.PersistenceBarrier>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            subscription.events.collect { received.complete(assertIs<SettingsEvent.PersistenceBarrier>(it.event)) }
        }
        var metadata = false
        try {
            val write = backgroundScope.async {
                fixture.settings.applyRadioPresetVerified(RadioPresets.all.first()) { metadata = true }
            }
            val pending = received.await().persistence
            write.cancel()
            runCurrent()
            kotlin.test.assertFailsWith<CancellationException> { write.await() }
            assertTrue(pending.isCancelled)
            assertFalse(metadata)
            assertEquals(1, fixture.radio.sent.size)
            assertFalse(fixture.settings.pathHashModeNeedsRecovery)
        } finally {
            subscription.close()
            collector.cancel()
            fixture.close()
        }
    }

    @Test fun successWaitsForPersistenceWithoutHoldingTheRadioOperationLock() = runTest {
        val fixture = settingsFixture()
        val subscription = fixture.settings.events()
        val received = CompletableDeferred<SettingsEvent.PathHashModeUpdated>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            subscription.events.collect { received.complete(assertIs<SettingsEvent.PathHashModeUpdated>(it.event)) }
        }
        try {
            val write = request { fixture.settings.setPathHashModePersisted(2u) }
            reply(fixture, 2, PacketBuilder.setPathHashMode(2u), okPacket())
            reply(fixture, 3, PacketBuilder.deviceQuery(), capabilitiesPacket(hash = 2u))
            val event = received.await()
            assertEquals(2u.toUByte(), event.mode)
            assertFalse(write.isCompleted)
            val read = request { fixture.settings.queryDevice() }
            reply(fixture, 4, PacketBuilder.deviceQuery(), capabilitiesPacket(hash = 2u))
            assertEquals(2u.toUByte(), read.await().pathHashMode)
            assertFalse(write.isCompleted)
            assertNotNull(event.persistence).complete(Unit)
            runCurrent()
            assertEquals(2u.toUByte(), write.await())
        } finally {
            subscription.close()
            collector.cancel()
            fixture.close()
        }
    }

    @Test fun persistenceFailureReachesTheCallerAndTheSameModeCanBeRetried() = runTest {
        val fixture = settingsFixture()
        val subscription = fixture.settings.events()
        val failure = IllegalStateException("storage rejected the write")
        var attempts = 0
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            subscription.events.collect {
                val completion = assertNotNull(assertIs<SettingsEvent.PathHashModeUpdated>(it.event).persistence)
                if (attempts++ == 0) completion.completeExceptionally(failure) else completion.complete(Unit)
            }
        }
        try {
            val failed = backgroundScope.async { runCatching { fixture.settings.setPathHashModePersisted(2u) } }
            reply(fixture, 2, PacketBuilder.setPathHashMode(2u), okPacket())
            reply(fixture, 3, PacketBuilder.deviceQuery(), capabilitiesPacket(hash = 2u))
            val actual = assertIs<IllegalStateException>(failed.await().exceptionOrNull())
            assertEquals(failure.message, actual.message)
            assertSame(failure, generateSequence<Throwable>(actual) { it.cause }.last())
            val retried = request { fixture.settings.setPathHashModePersisted(2u) }
            reply(fixture, 4, PacketBuilder.setPathHashMode(2u), okPacket())
            reply(fixture, 5, PacketBuilder.deviceQuery(), capabilitiesPacket(hash = 2u))
            assertEquals(2u.toUByte(), retried.await())
            assertEquals(2, attempts)
        } finally {
            subscription.close()
            collector.cancel()
            fixture.close()
        }
    }

    @Test fun replacingTheSubscriberFailsAnOutstandingPersistenceWait() = runTest {
        val fixture = settingsFixture()
        val subscription = fixture.settings.events()
        try {
            val write = request { fixture.settings.setPathHashModePersisted(2u) }
            reply(fixture, 2, PacketBuilder.setPathHashMode(2u), okPacket())
            reply(fixture, 3, PacketBuilder.deviceQuery(), capabilitiesPacket(hash = 2u))
            assertFalse(write.isCompleted)
            fixture.settings.events().close()
            runCurrent()
            val failure = kotlin.test.assertFailsWith<SettingsServiceException> { write.await() }
            assertEquals(SettingsServiceError.NotConnected, failure.error)
        } finally {
            subscription.close()
            fixture.close()
        }
    }

    @Test fun noSubscriberFailsBeforeChangingTheRadio() = runTest {
        val fixture = settingsFixture()
        try {
            val write = request { fixture.settings.setPathHashModePersisted(2u) }
            runCurrent()
            kotlin.test.assertFailsWith<SettingsServiceException> { write.await() }
            assertEquals(1, fixture.radio.sent.size)
        } finally { fixture.close() }
    }

    @Test fun verificationFailureNeverRequestsPersistence() = runTest {
        val fixture = settingsFixture()
        val subscription = fixture.settings.events()
        var events = 0
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            subscription.events.collect { events++ }
        }
        try {
            val write = request { fixture.settings.setPathHashModePersisted(2u) }
            reply(fixture, 2, PacketBuilder.setPathHashMode(2u), okPacket())
            reply(fixture, 3, PacketBuilder.deviceQuery(), capabilitiesPacket(hash = 1u))
            kotlin.test.assertFailsWith<SettingsServiceException> { write.await() }
            assertEquals(0, events)
        } finally {
            subscription.close()
            collector.cancel()
            fixture.close()
        }
    }

    @Test fun retirementAfterPersistenceCannotReturnSuccess() = runTest {
        val fixture = settingsFixture()
        val subscription = fixture.settings.events()
        val received = CompletableDeferred<SettingsEvent.PathHashModeUpdated>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            subscription.events.collect { received.complete(assertIs<SettingsEvent.PathHashModeUpdated>(it.event)) }
        }
        try {
            val write = request { fixture.settings.setPathHashModePersisted(2u) }
            reply(fixture, 2, PacketBuilder.setPathHashMode(2u), okPacket())
            reply(fixture, 3, PacketBuilder.deviceQuery(), capabilitiesPacket(hash = 2u))
            fixture.signals.replace(token(generation = 2))
            assertTrue(assertNotNull(received.await().persistence).complete(Unit))
            runCurrent()
            kotlin.test.assertFailsWith<SettingsServiceException> { write.await() }
        } finally {
            subscription.close()
            collector.cancel()
            fixture.close()
        }
    }

    @Test fun unconfinedFailureReentryKeepsOnlyTheNewestSubscriberAndItsAcknowledgment() = runTest {
        val fixture = settingsFixture()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val context = DeviceSettingsContext(fixture.context.token, fixture.signals, backgroundScope, dispatcher)
        val service = SettingsService(fixture.session, context)
        val first = service.events()
        val received = CompletableDeferred<SettingsEvent.PathHashModeUpdated>()
        val firstCollector = backgroundScope.launch(dispatcher) {
            first.events.collect { received.complete(assertIs<SettingsEvent.PathHashModeUpdated>(it.event)) }
        }
        var reentered: SessionEventSubscription<SettingsEvent>? = null
        try {
            val write = backgroundScope.async(dispatcher) {
                try {
                    service.setPathHashModePersisted(2u)
                    error("Replacement must not acknowledge persistence")
                } catch (failure: SettingsServiceException) {
                    reentered = service.events()
                    failure
                }
            }
            reply(fixture, 2, PacketBuilder.setPathHashMode(2u), okPacket())
            reply(fixture, 3, PacketBuilder.deviceQuery(), capabilitiesPacket(hash = 2u))
            assertNotNull(received.await().persistence)
            assertFalse(write.isCompleted)
            val replaced = service.events()
            assertEquals(SettingsServiceError.NotConnected, write.await().error)
            assertTrue(firstCollector.isCompleted)
            val replacedCollector = backgroundScope.async(dispatcher) { replaced.events.toList() }
            assertTrue(replacedCollector.isCompleted, "Reentrant subscriber must retire the outer replacement")
            assertTrue(replacedCollector.await().isEmpty())
            val latest = assertNotNull(reentered)
            val latestCollector = backgroundScope.launch(dispatcher) {
                latest.events.collect {
                    assertEquals(context.token, it.token)
                    assertNotNull(assertIs<SettingsEvent.PathHashModeUpdated>(it.event).persistence).complete(Unit)
                }
            }
            assertFalse(latestCollector.isCompleted)
            val retry = request { service.setPathHashModePersisted(0u) }
            reply(fixture, 4, PacketBuilder.setPathHashMode(0u), okPacket())
            reply(fixture, 5, PacketBuilder.deviceQuery(), capabilitiesPacket(hash = 0u))
            assertEquals(0u.toUByte(), retry.await())
            service.finishEvents()
            assertTrue(latestCollector.isCompleted)
        } finally {
            first.close()
            reentered?.close()
            firstCollector.cancel()
            context.close()
            fixture.close()
        }
    }

    @Test fun cancellingPendingPersistenceDoesNotAcknowledgeOrUndoRadioAndReentryCanRestoreIt() = runTest {
        val fixture = settingsFixture()
        val first = fixture.settings.events()
        val received = CompletableDeferred<SettingsEvent.PathHashModeUpdated>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            first.events.collect { received.complete(assertIs<SettingsEvent.PathHashModeUpdated>(it.event)) }
        }
        try {
            val write = backgroundScope.async { fixture.settings.setPathHashModePersisted(2u) }
            reply(fixture, 2, PacketBuilder.setPathHashMode(2u), okPacket())
            reply(fixture, 3, PacketBuilder.deviceQuery(), capabilitiesPacket(hash = 2u))
            val pending = assertNotNull(received.await().persistence)
            assertFalse(write.isCompleted)
            write.cancel()
            runCurrent()
            kotlin.test.assertFailsWith<CancellationException> { write.await() }
            assertTrue(pending.isCancelled, "Cancelled waiting is not a successful persistence acknowledgment")
            assertTrue(fixture.settings.pathHashModeNeedsRecovery)
            assertEquals(3, fixture.radio.sent.size, "Cancellation must not invent a radio rollback write")
            val next = fixture.settings.events()
            val nextCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                next.events.collect {
                    assertNotNull(assertIs<SettingsEvent.PathHashModeUpdated>(it.event).persistence).complete(Unit)
                }
            }
            val retry = request { fixture.settings.setPathHashModePersisted(0u) }
            reply(fixture, 4, PacketBuilder.setPathHashMode(0u), okPacket())
            reply(fixture, 5, PacketBuilder.deviceQuery(), capabilitiesPacket(hash = 0u))
            assertEquals(0u.toUByte(), retry.await())
            assertFalse(fixture.settings.pathHashModeNeedsRecovery)
            next.close()
            nextCollector.cancel()
        } finally {
            first.close()
            collector.cancel()
            fixture.close()
        }
    }
}
