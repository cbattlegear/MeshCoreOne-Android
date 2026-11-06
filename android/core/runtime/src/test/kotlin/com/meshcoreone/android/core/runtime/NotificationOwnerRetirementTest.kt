// AndroidOnly: WP-215 Actual runtime detach cuts retire the original factory notification admission.
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.notifications.NotificationPostAdmission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationOwnerRetirementTest {
    @Test fun explicitDisconnectRetiresBeforeServiceCleanup() = runTest {
        checkDetach { manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP) }
    }

    @Test fun reconnectTeardownRetiresBeforeServiceCleanup() = runTest {
        checkDetach { manager.teardownSessionForReconnect() }
    }

    @Test fun reconnectionFailureRetiresBeforeServiceCleanup() = runTest {
        checkDetach { manager.handleReconnectionFailure() }
    }

    @Test fun transportDisconnectRetiresBeforeServiceCleanup() = runTest {
        checkDetach { manager.disconnectTransport() }
    }

    @Test fun physicalLossRetiresBeforeServiceCleanup() = runTest {
        checkDetach {
            links.single().callbacks!!.onDisconnected(null)
            test.runCurrent()
        }
    }

    @Test fun shutdownRetiresBeforeServiceCleanup() = runTest {
        checkDetach { manager.close() }
    }

    @Test fun replacementRetiresOnlyTheOriginalFactoryPermit() = runTest {
        withFixture {
            val owners = mutableListOf<FactoryOwnership>()
            onFactory = { _, owner -> owners += owner }
            connect()
            val old = owners.single()
            connect(target())
            val successor = owners.last()
            assertNotSame(old, successor)
            assertNotSame(old.notificationPostPermit, successor.notificationPostPermit)
            assertFalse(old.notificationPostPermit.isActive)
            assertTrue(successor.notificationPostPermit.isActive)
            old.close()
            assertTrue(successor.notificationPostPermit.isActive, "delayed old cleanup must not retire B")
        }
    }

    @Test fun admittedOldEffectDrainsWithoutHoldingRuntimeLockBeforeSuccessorFactory() = runTest {
        withFixture {
            val owners = mutableListOf<FactoryOwnership>()
            onFactory = { _, owner -> owners += owner }
            connect()
            val original = owners.single()
            val lease = requireNotNull(original.notificationPostPermit.tryAcquire())
            try {
                val stopped = async(start = CoroutineStart.UNDISPATCHED) {
                    manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP)
                }
                test.runCurrent()
                assertFalse(original.notificationPostPermit.isActive)
                assertFalse(stopped.isCompleted, "admitted effect retains cleanup")
                val replacement = async { manager.connect(target()) }
                test.runCurrent()
                assertEquals(1, owners.size, "successor factory cannot overtake the admitted old effect")
                manager.clearConnectedDevice()
                assertEquals(
                    NotificationPostAdmission.Revoked,
                    original.notificationPostPermit.runIfActive { error("late effect admitted") },
                )
                assertEquals("admitted", lease.runSynchronous { "admitted" })
                lease.close()
                stopped.await()
                replacement.await()
                test.runCurrent()
                assertEquals(2, owners.size)
                assertTrue(owners.last().notificationPostPermit.isActive)
                assertSame(original, owners.first())
            } finally {
                lease.close()
            }
        }
    }

    @Test fun directPartialFactoryCloseRevokesAndDrainsTheOriginalPermit() = runTest {
        withFixture {
            var selected: FactoryOwnership? = null
            onFactory = { _, owner ->
                selected = owner
                owner.close()
                throw IllegalStateException("partial graph refused")
            }
            assertFailsWith<Exception> { manager.connect(platform.target) }
            val original = requireNotNull(selected)
            assertFalse(original.notificationPostPermit.isActive)
            assertEquals(
                NotificationPostAdmission.Revoked,
                original.notificationPostPermit.runIfActive { error("partial factory remained live") },
            )
        }
    }

    @Test fun cancellationDuringFactoryConstructionRetiresOriginalAdmission() = runTest {
        withFixture {
            val entered = CompletableDeferred<FactoryOwnership>()
            onFactory = { _, owner -> entered.complete(owner); awaitCancellation() }
            val connecting = launch { manager.connect(platform.target) }
            test.runCurrent()
            val original = entered.await()
            assertTrue(original.notificationPostPermit.isActive)
            connecting.cancel()
            test.runCurrent()
            connecting.join()
            assertFalse(original.notificationPostPermit.isActive)
        }
    }

    private suspend fun TestScope.checkDetach(action: suspend RuntimeFixture.() -> Unit) {
        withFixture {
            var original: FactoryOwnership? = null
            onFactory = { _, owner -> original = owner }
            connect()
            val captured = requireNotNull(original)
            val token = captured.token
            services.single().beforeTeardown = {
                assertFalse(captured.notificationPostPermit.isActive)
                assertEquals(token, captured.token, "revocation must not replace the original factory identity")
                assertEquals(
                    NotificationPostAdmission.Revoked,
                    captured.notificationPostPermit.runIfActive { error("post admitted after retirement") },
                )
            }
            action()
            test.runCurrent()
            assertFalse(captured.notificationPostPermit.isActive)
            assertEquals(token, captured.token)
            assertEquals(1, services.first().teardowns)
        }
    }
}
