// AndroidOnly: WP-317 Default-scope acknowledgment and cancellation-safe selection/removal ordering.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.model.FloodScope
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDefaultScopePersistenceTest {
    private suspend fun SettingsService.selectScope(name: String?) = setDefaultFloodScopePersisted(name) {
        DeviceDTO(radioId = RADIO, publicKey = filled(1), knownRegions = listOf("EU868", "US915").snapshot())
    }

    @Test fun persistenceFailureReachesTheCallerAndTheSameScopeCanBeRetried() = runTest {
        val fixture = settingsFixture()
        val subscription = fixture.settings.events()
        val failure = IllegalStateException("scope storage failure")
        var attempts = 0
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            subscription.events.collect {
                assertEquals(fixture.context.token, it.token)
                val completion = assertNotNull(assertIs<SettingsEvent.DefaultFloodScopeUpdated>(it.event).persistence)
                if (attempts++ == 0) completion.completeExceptionally(failure) else completion.complete(Unit)
            }
        }
        try {
            val failed = backgroundScope.async { runCatching { fixture.settings.selectScope("EU868") } }
            reply(fixture, 2, PacketBuilder.setDefaultFloodScope("EU868", FloodScope.Region("EU868")), okPacket())
            reply(fixture, 3, PacketBuilder.getDefaultFloodScope(), floodScopePacket("EU868"))
            val actual = assertIs<IllegalStateException>(failed.await().exceptionOrNull())
            assertEquals(failure.message, actual.message)
            assertSame(failure, generateSequence<Throwable>(actual) { it.cause }.last())
            val retried = request { fixture.settings.selectScope("EU868") }
            reply(fixture, 4, PacketBuilder.setDefaultFloodScope("EU868", FloodScope.Region("EU868")), okPacket())
            reply(fixture, 5, PacketBuilder.getDefaultFloodScope(), floodScopePacket("EU868"))
            assertEquals("EU868", retried.await())
            assertEquals(2, attempts)
        } finally {
            subscription.close()
            collector.cancel()
            fixture.close()
        }
    }

    @Test fun cancelledQueuedCallerCannotReleaseItsSuccessorBeforeTheRunningWriteAcknowledges() = runTest {
        val fixture = settingsFixture()
        val subscription = fixture.settings.events()
        val events = mutableListOf<SettingsEvent.DefaultFloodScopeUpdated>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            subscription.events.collect { events += assertIs<SettingsEvent.DefaultFloodScopeUpdated>(it.event) }
        }
        try {
            val first = request { fixture.settings.selectScope("EU868") }
            reply(fixture, 2, PacketBuilder.setDefaultFloodScope("EU868", FloodScope.Region("EU868")), okPacket())
            reply(fixture, 3, PacketBuilder.getDefaultFloodScope(), floodScopePacket("EU868"))
            assertEquals(1, events.size)
            val cancelled = request { fixture.settings.selectScope("US915") }
            runCurrent()
            cancelled.cancel()
            runCurrent()
            val last = request { fixture.settings.selectScope(null) }
            runCurrent()
            assertEquals(3, fixture.radio.sent.size)
            assertFalse(first.isCompleted)
            assertFalse(last.isCompleted)
            val independentRead = request { fixture.settings.queryDevice() }
            reply(fixture, 4, PacketBuilder.deviceQuery(), capabilitiesPacket())
            independentRead.await()
            assertFalse(first.isCompleted, "Waiting for Room does not hold the radio-operation lock")
            assertNotNull(events.single().persistence).complete(Unit)
            reply(fixture, 5, PacketBuilder.setDefaultFloodScope("", FloodScope.Disabled), okPacket())
            reply(fixture, 6, PacketBuilder.getDefaultFloodScope(), floodScopePacket(null))
            assertEquals(2, events.size)
            assertNotNull(events.last().persistence).complete(Unit)
            assertEquals("EU868", first.await())
            assertEquals(null, last.await())
        } finally {
            subscription.close()
            collector.cancel()
            fixture.close()
        }
    }

    @Test fun replacedSubscriberCannotDrainItsBufferedAcknowledgedScopeEvent() = runTest {
        val fixture = settingsFixture()
        val old = fixture.settings.events()
        try {
            val write = request { fixture.settings.selectScope("EU868") }
            reply(fixture, 2, PacketBuilder.setDefaultFloodScope("EU868", FloodScope.Region("EU868")), okPacket())
            reply(fixture, 3, PacketBuilder.getDefaultFloodScope(), floodScopePacket("EU868"))
            assertFalse(write.isCompleted)
            val replacement = fixture.settings.events()
            assertEquals(
                SettingsServiceError.NotConnected,
                assertFailsWith<SettingsServiceException> { write.await() }.error,
            )
            var oldEvents = 0
            old.events.collect { oldEvents++ }
            assertEquals(0, oldEvents)
            replacement.close()
        } finally {
            old.close()
            fixture.close()
        }
    }

    @Test fun noSubscriberFailsBeforeTheRadioAndWrongReadbackNeverRequestsPersistence() = runTest {
        val fixture = settingsFixture()
        try {
            val missing = request { fixture.settings.selectScope("EU868") }
            assertEquals(
                SettingsServiceError.NotConnected,
                assertFailsWith<SettingsServiceException> { missing.await() }.error,
            )
            assertEquals(1, fixture.radio.sent.size)
            val subscription = fixture.settings.events()
            var events = 0
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                subscription.events.collect { events++ }
            }
            try {
                val write = request { fixture.settings.selectScope("EU868") }
                reply(fixture, 2, PacketBuilder.setDefaultFloodScope("EU868", FloodScope.Region("EU868")), okPacket())
                reply(fixture, 3, PacketBuilder.getDefaultFloodScope(), floodScopePacket("Other"))
                assertIs<SettingsServiceError.VerificationFailed>(assertFailsWith<SettingsServiceException> { write.await() }.error)
                assertEquals(0, events)
            } finally { subscription.close(); collector.cancel() }
        } finally { fixture.close() }
    }

    @Test fun successfulCompletionCannotAcknowledgeARetiredOriginalSession() = runTest {
        val fixture = settingsFixture()
        val subscription = fixture.settings.events()
        val event = CompletableDeferred<SettingsEvent.DefaultFloodScopeUpdated>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            subscription.events.collect { event.complete(assertIs<SettingsEvent.DefaultFloodScopeUpdated>(it.event)) }
        }
        try {
            val write = request { fixture.settings.selectScope("EU868") }
            reply(fixture, 2, PacketBuilder.setDefaultFloodScope("EU868", FloodScope.Region("EU868")), okPacket())
            reply(fixture, 3, PacketBuilder.getDefaultFloodScope(), floodScopePacket("EU868"))
            fixture.signals.replace(token(2))
            assertTrue(assertNotNull(event.await().persistence).complete(Unit))
            assertEquals(
                SettingsServiceError.NotConnected,
                assertFailsWith<SettingsServiceException> { write.await() }.error,
            )
        } finally {
            subscription.close()
            collector.cancel()
            fixture.close()
        }
    }

    @Test fun removalComparesTheActualRadioNameUsingTheSameUtf8LimitAsSelection() = runTest {
        val fixture = settingsFixture()
        val subscription = fixture.settings.events()
        val region = "\u00e9".repeat(16)
        val radioName = "\u00e9".repeat(15)
        val device = DeviceDTO(
            radioId = RADIO, publicKey = filled(1), firmwareVersion = 11u, knownRegions = listOf(region).snapshot(),
        )
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            subscription.events.collect {
                val update = assertIs<SettingsEvent.DefaultFloodScopeUpdated>(it.event)
                assertEquals(null, update.name)
                assertNotNull(update.persistence).complete(Unit)
            }
        }
        var removed = false
        try {
            val removal = request {
                fixture.settings.removeKnownRegionPersisted(region, { device }) { removed = true }
            }
            reply(fixture, 2, PacketBuilder.getDefaultFloodScope(), floodScopePacket(radioName))
            assertFalse(removed)
            reply(fixture, 3, PacketBuilder.setDefaultFloodScope("", FloodScope.Disabled), okPacket())
            reply(fixture, 4, PacketBuilder.getDefaultFloodScope(), floodScopePacket(null))
            removal.await()
            assertTrue(removed)
        } finally {
            subscription.close()
            collector.cancel()
            fixture.close()
        }
    }
}
