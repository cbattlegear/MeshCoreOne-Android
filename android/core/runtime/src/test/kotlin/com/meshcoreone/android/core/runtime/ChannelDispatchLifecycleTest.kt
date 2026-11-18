// AndroidOnly: WP-209 Actual runtime birth and retirement bind channel admission to the exact session.
package com.meshcoreone.android.core.runtime

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChannelDispatchLifecycleTest {
    @Test
    fun managerBindsAdmissionBeforeFactoryAndRevokesBeforeTeardown() = runTest {
        val f = RuntimeFixture(this)
        try {
            f.onFactory = { service, _ ->
                val connection = service.inputs.connection
                assertEquals(connection.token, connection.channelDispatchAdmission.token)
                assertSame(connection.session, connection.channelDispatchAdmission.connectionIdentity)
                assertFalse(connection.channelDispatchAdmission.isRevoked)
            }
            f.connect()
            val first = f.services.single().inputs.connection
            val admitted = checkNotNull(first.channelDispatchAdmission.tryAcquire(first.token, first.session))
            f.services.single().beforeTeardown = {
                assertTrue(first.channelDispatchAdmission.isRevoked)
                assertNull(first.channelDispatchAdmission.tryAcquire(first.token, first.session))
                assertEquals(1, first.channelDispatchAdmission.inFlightCount)
            }
            f.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            assertTrue(first.channelDispatchAdmission.isRevoked)
            admitted.complete()
            assertEquals(0, first.channelDispatchAdmission.inFlightCount)
            f.connect()
            val next = f.services.last().inputs.connection
            assertNotSame(first.channelDispatchAdmission, next.channelDispatchAdmission)
            assertNotSame(first.session, next.session)
            assertFalse(next.channelDispatchAdmission.isRevoked)
            assertNull(first.channelDispatchAdmission.tryAcquire(next.token, next.session))
        } finally { f.close() }
    }

    @Test
    fun disconnectRevokesAdmissionWhileTheFactoryIsStillSuspended() = runTest {
        val f = RuntimeFixture(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var connection: com.meshcoreone.android.core.contracts.domain.SessionInputs<
            com.meshcoreone.android.core.protocol.session.MeshCoreSession>? = null
        f.onFactory = { service, _ ->
            connection = service.inputs.connection
            entered.complete(Unit)
            // Model a factory whose own work still has not returned after retirement.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
        }
        try {
            val connect = launch { f.manager.connect(f.platform.target) }
            entered.await()
            val actual = checkNotNull(connection)
            val disconnect = async { f.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED) }
            runCurrent()
            assertTrue(actual.channelDispatchAdmission.isRevoked)
            assertNull(actual.channelDispatchAdmission.tryAcquire(actual.token, actual.session))
            release.complete(Unit)
            connect.join()
            disconnect.await()
            assertTrue(actual.channelDispatchAdmission.isRevoked)
        } finally {
            release.complete(Unit)
            f.close()
        }
    }
}
