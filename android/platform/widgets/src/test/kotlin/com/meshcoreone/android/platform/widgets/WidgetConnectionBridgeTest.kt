// AndroidOnly: WP-403 Cold-start, permission, reconnect and single-owner request assertions.
package com.meshcoreone.android.platform.widgets

import android.content.Context
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class WidgetConnectionBridgeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun reset() {
        WidgetConnectionBridge.resetForTesting()
    }

    @Test
    fun preUnlockRequestDoesNotReachConnectionOwner() = runTest {
        val users = Shadows.shadowOf(context.getSystemService(UserManager::class.java))
        users.setUserUnlocked(false)
        var requests = 0
        WidgetConnectionBridge.install(context, backgroundScope, owner {
            requests++
            ConnectionRequestResult.Requested
        })

        assertEquals(
            ConnectionRequestResult.UserLocked,
            WidgetConnectionBridge.request(context, ConnectionRequestOrigin.QUICK_SETTINGS),
        )
        assertEquals(0, requests)
    }

    @Test
    fun coldStartWithoutInstalledOwnerIsTypedAndDoesNotCreateOne() = runTest {
        assertEquals(
            ConnectionRequestResult.OwnerUnavailable,
            WidgetConnectionBridge.request(context, ConnectionRequestOrigin.WIDGET),
        )
    }

    @Test
    fun permissionAndBackgroundRefusalsRemainTyped() = runTest {
        val results = ArrayDeque<ConnectionRequestResult>().apply {
            add(ConnectionRequestResult.PermissionDenied)
            add(ConnectionRequestResult.BackgroundRestricted)
        }
        WidgetConnectionBridge.install(context, backgroundScope, owner { results.removeFirst() })

        assertEquals(
            ConnectionRequestResult.PermissionDenied,
            WidgetConnectionBridge.request(context, ConnectionRequestOrigin.QUICK_SETTINGS),
        )
        assertEquals(
            ConnectionRequestResult.BackgroundRestricted,
            WidgetConnectionBridge.request(context, ConnectionRequestOrigin.QUICK_SETTINGS),
        )
    }

    @Test
    fun ownerStartupTimeoutIsTypedInsteadOfReportingSuccess() = runTest {
        WidgetConnectionBridge.install(context, backgroundScope, owner {
            delay(Long.MAX_VALUE)
            ConnectionRequestResult.Requested
        })

        assertEquals(
            ConnectionRequestResult.OwnerUnavailable,
            WidgetConnectionBridge.request(context, ConnectionRequestOrigin.WIDGET),
        )
    }

    @Test
    fun repeatedReconnectTapsAreSerializedThroughTheExistingOwner() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var active = 0
        var maximumActive = 0
        WidgetConnectionBridge.install(context, backgroundScope, owner {
            active++
            maximumActive = maxOf(maximumActive, active)
            firstEntered.complete(Unit)
            releaseFirst.await()
            active--
            ConnectionRequestResult.Requested
        })

        val first = async { WidgetConnectionBridge.request(context, ConnectionRequestOrigin.WIDGET) }
        firstEntered.await()
        val second = async { WidgetConnectionBridge.request(context, ConnectionRequestOrigin.QUICK_SETTINGS) }
        releaseFirst.complete(Unit)

        assertEquals(ConnectionRequestResult.Requested, first.await())
        assertEquals(ConnectionRequestResult.Requested, second.await())
        assertEquals(1, maximumActive)
    }

    private fun owner(
        request: suspend (ConnectionRequestOrigin) -> ConnectionRequestResult,
    ): WidgetConnectionOwner = object : WidgetConnectionOwner {
        override val status = MutableStateFlow(
            WidgetConnectionView(WidgetRadioStatus.DISCONNECTED, hasSavedDevice = true),
        )

        override suspend fun requestConnection(origin: ConnectionRequestOrigin) = request(origin)
    }
}
