// AndroidOnly: WP-309 Real App feature/graph binding and standard uncaught reporting; source-only controls.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotSet
import java.util.UUID
import com.meshcoreone.android.feature.chats.actions.DirectAckConfirmationStatus
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DirectAckConfirmationGraphTest : RoomProcessTest() {
    private fun wildcard(radio: RadioId) = DirectAckRadioConfirmationEvidence.PersistedWildcard(
        radio, SnapshotSet(listOf(DirectAckRadioConfirmationWildcardReason.UNRESOLVED_PHYSICAL_EMISSION)),
    )

    @Test fun realAppBindingSharesOneObserverAcrossConsumersAndQuietGraphReplacement() = runTest {
        val reads = mutableListOf<RadioId>()
        var active = 0
        var maximum = 0
        val observing = object : PersistenceStoreProtocol by store, DirectAckRadioConfirmationEvidenceObserving {
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId) = flow {
                reads += radioId
                active++
                maximum = maxOf(maximum, active)
                try {
                    emit(DirectAckRadioConfirmationObservation.Inspecting(radioId))
                    emit(DirectAckRadioConfirmationObservation.Snapshot(wildcard(radioId)))
                    awaitCancellation()
                } finally { active-- }
            }
        }
        val harness = ContainerHarness(this, store, persistenceOverride = observing)
        try {
            harness.manager.connect(harness.target())
            harness.eventually("first confirmation graph ready") {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            val feature = harness.container.chatsFeature
            val first = feature.directAckConfirmation
            assertSame(first, feature.directAckConfirmation)
            assertTrue(reads.isEmpty())
            first.start()
            first.start()
            harness.eventually("first persisted confirmation evidence") {
                first.state.value.status is DirectAckConfirmationStatus.Inspected
            }
            val previous = requireNotNull(first.state.value.observation)
            assertTrue(feature.isConfirmationObservationCurrent(previous))
            assertEquals(1, active)
            assertEquals(1, reads.size)

            harness.manager.disconnect()
            harness.eventually("old graph retired") { harness.sessions.current == null }
            harness.manager.connect(harness.target())
            harness.eventually("replacement confirmation graph ready") {
                harness.manager.connectionState == DeviceConnectionState.READY &&
                    harness.sessions.current?.token != previous.sessionToken
            }
            harness.eventually("new confirmation owner") {
                first.state.value.observation?.sessionToken == harness.sessions.current?.token &&
                    first.state.value.status is DirectAckConfirmationStatus.Inspected
            }
            assertSame(first, feature.directAckConfirmation)
            assertFalse(feature.isConfirmationObservationCurrent(previous))
            assertTrue(feature.isConfirmationObservationCurrent(requireNotNull(first.state.value.observation)))
            assertEquals(1, active)
            assertEquals(1, maximum)
            assertTrue(reads.size >= 2)
        } finally {
            harness.container.close()
        }
        assertEquals(0, active)
    }

    @Test fun unexpectedRealGraphObserverFailureIsLoggedWithoutCancellingUnrelatedWork() = runTest {
        val gate = CompletableDeferred<Unit>()
        val failure = IllegalStateException("unexpected actual graph fixture")
        val observing = object : PersistenceStoreProtocol by store, DirectAckRadioConfirmationEvidenceObserving {
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId) = flow {
                emit(DirectAckRadioConfirmationObservation.Snapshot(
                    DirectAckRadioConfirmationEvidence.NoPersistedWildcard(radioId),
                ))
                gate.await()
                throw failure
            }
        }
        val records = mutableListOf<LogRecord>()
        val logger = Logger.getLogger("com.mc1.AppChatsFeature")
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        val harness = ContainerHarness(this, store, persistenceOverride = observing)
        try {
            harness.manager.connect(harness.target())
            harness.eventually("supervised graph ready") {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            val monitor = harness.container.chatsFeature.directAckConfirmation
            monitor.start()
            harness.eventually("historical absence observed") {
                monitor.state.value.status is DirectAckConfirmationStatus.Inspected
            }
            gate.complete(Unit)
            harness.eventually("unexpected observation explicitly unavailable") {
                monitor.state.value.status == DirectAckConfirmationStatus.UnexpectedUnavailable
            }
            assertSame(failure, records.single {
                it.message == "Delivery confirmation observation unavailable"
            }.thrown)
            assertTrue(harness.container.uiScope.isActive)
            var siblingFinished = false
            harness.container.uiScope.launch { siblingFinished = true }.join()
            assertTrue(siblingFinished)
            assertEquals(DeviceConnectionState.READY, harness.manager.connectionState)
        } finally {
            harness.container.close()
            logger.removeHandler(handler)
        }
    }

    @Test fun messageInspectorFailureUsesTheActualGraphHandlerAndPreservesRadioMonitorAndSibling() = runTest {
        val failure = IllegalStateException("unexpected message inspector fixture")
        val gate = CompletableDeferred<Unit>()
        val records = mutableListOf<LogRecord>()
        val logger = Logger.getLogger("com.mc1.AppChatsFeature")
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() {}
            override fun close() {}
        }
        val observing = object : PersistenceStoreProtocol by store,
            DirectAckRadioConfirmationEvidenceObserving, DirectMessageConfirmationEvidenceInspecting {
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId) = flow {
                emit(DirectAckRadioConfirmationObservation.Snapshot(
                    DirectAckRadioConfirmationEvidence.NoPersistedWildcard(radioId),
                ))
                awaitCancellation()
            }
            override suspend fun inspectDirectMessageConfirmationEvidence(key: EntityKey): DirectMessageConfirmationEvidence {
                gate.await()
                throw failure
            }
        }
        logger.addHandler(handler)
        val harness = ContainerHarness(this, store, persistenceOverride = observing)
        try {
            harness.manager.connect(harness.target())
            harness.eventually("message failure graph ready") {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            val monitor = harness.container.chatsFeature.directAckConfirmation
            val radio = requireNotNull(harness.sessions.current).token.radioId
            monitor.messages.register(this, setOf(EntityKey(radio, UUID(309, 99))))
            monitor.start()
            harness.eventually("message query and independent radio snapshot") {
                monitor.messages.state.value.status ==
                    com.meshcoreone.android.feature.chats.actions.DirectMessageConfirmationReadStatus.Checking &&
                    monitor.state.value.status is DirectAckConfirmationStatus.Inspected
            }
            gate.complete(Unit)
            harness.eventually("message failure explicitly unavailable") {
                monitor.messages.state.value.status ==
                    com.meshcoreone.android.feature.chats.actions.DirectMessageConfirmationReadStatus.UnexpectedUnavailable
            }
            assertSame(failure, records.single {
                it.message == "Delivery confirmation observation unavailable"
            }.thrown)
            assertTrue(monitor.state.value.status is DirectAckConfirmationStatus.Inspected)
            assertTrue(monitor.messages.state.value.rowDiagnosticsUnavailable)
            assertFalse(monitor.messages.state.value.hasHistoricalUncertainty)
            var siblingFinished = false
            harness.container.uiScope.launch { siblingFinished = true }.join()
            assertTrue(siblingFinished)
            assertEquals(DeviceConnectionState.READY, harness.manager.connectionState)
        } finally {
            harness.container.close()
            logger.removeHandler(handler)
        }
    }

    @Test fun missingRealStoreCapabilityRemainsExplicitAndNeverSubstitutesNoWildcard() = runTest {
        val unsupported = object : PersistenceStoreProtocol by store {}
        val harness = ContainerHarness(this, store, persistenceOverride = unsupported)
        try {
            harness.manager.connect(harness.target())
            harness.eventually("unsupported confirmation graph ready") {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            val monitor = harness.container.chatsFeature.directAckConfirmation
            monitor.start()
            harness.eventually("missing capability") {
                monitor.state.value.status == DirectAckConfirmationStatus.CapabilityUnavailable
            }
            assertNull(monitor.state.value.lastEvidence)
            assertEquals(harness.sessions.current?.token, monitor.state.value.observation?.sessionToken)
        } finally { harness.container.close() }
    }
}
