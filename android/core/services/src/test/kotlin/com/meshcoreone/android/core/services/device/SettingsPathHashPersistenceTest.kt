// AndroidOnly: WP-317 Awaited path-hash persistence preserves radio verification, event ordering and session retirement.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPathHashPersistenceTest {
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
            assertSame(failure, failed.await().exceptionOrNull())
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
}
