// AndroidOnly: WP-305 Actual legacy CDM gateway and launcher recreation/result ownership regressions.
@file:Suppress("DEPRECATION")
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.meshcoreone.android.app.container

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.meshcoreone.android.app.MainActivity
import com.meshcoreone.android.core.connectivity.ConnectivityDiagnostics
import com.meshcoreone.android.core.connectivity.SystemConnectivityClock
import com.meshcoreone.android.core.connectivity.pairing.CompanionChooserHost
import com.meshcoreone.android.core.connectivity.pairing.CompanionSetupError
import com.meshcoreone.android.core.connectivity.pairing.CompanionSetupService
import com.meshcoreone.android.core.connectivity.pairing.DeviceEndpointIdentity
import com.meshcoreone.android.core.connectivity.platform.AndroidCompanionDeviceGateway
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothAdapter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class, shadows = [ChooserCompanionManagerShadow::class])
class CompanionChooserOwnershipTest {
    @Test fun recreatedHostKeepsOldAndSuccessorResultsDistinctAndDuplicatesCannotRemoveTheirSharedAssociation() = runTest {
        val f = Fixture(this)
        try {
            f.setup.activateSession()
            val old = f.request()
            old.awaiting.cancelAndJoin()
            f.recreate()
            val successor = f.request()
            assertNotEquals(old.code, successor.code)
            assertNotEquals(old.id, successor.id)
            f.result(old)
            runCurrent()
            assertEquals(listOf(old.id), f.activity.delivered)
            assertFalse(successor.awaiting.isCompleted)
            assertTrue(f.cdm.removed.isEmpty())
            f.result(successor)
            assertEquals(DeviceEndpointIdentity.deviceId(ADDRESS), successor.awaiting.await())
            runCurrent()
            f.result(old)
            f.gateway.onChooserResult(old.id, Activity.RESULT_OK, f.resultIntent())
            f.gateway.onChooserResult(UUID.randomUUID(), Activity.RESULT_OK, f.resultIntent())
            runCurrent()
            assertEquals(listOf(old.id, successor.id), f.activity.delivered)
            assertTrue(f.cdm.removed.isEmpty())
            assertEquals(listOf(ADDRESS), f.cdm.associatedAddresses.toList())
            assertTrue("cdm.chooser.uncorrelatedResult" in f.diagnostics)
        } finally { f.close() }
    }

    @Test fun recreatedHostStillDeliversTrackedCancelledSelectionForGenuineOrphanCleanup() = runTest {
        val f = Fixture(this)
        try {
            f.setup.activateSession()
            val old = f.request()
            old.awaiting.cancelAndJoin()
            f.recreate()
            f.result(old)
            runCurrent()
            assertEquals(listOf(old.id), f.activity.delivered)
            assertEquals(listOf(ADDRESS), f.cdm.removed)
            assertTrue(f.gateway.associations().isEmpty())
            assertTrue(f.setup.pairedAccessories.isEmpty())
        } finally { f.close() }
    }

    @Test fun processRestoredHostNeverRoutesAnOldRequestToTheNewGatewayOwner() = runTest {
        val f = Fixture(this)
        try {
            f.setup.activateSession()
            val old = f.request()
            old.awaiting.cancelAndJoin()
            f.restartGatewayAndHost()
            f.setup.activateSession()
            val successor = f.request()
            assertNotEquals(old.id, successor.id)
            assertNotEquals(old.code, successor.code)
            f.result(old)
            runCurrent()
            assertEquals(listOf(old.id), f.activity.delivered)
            assertFalse(successor.awaiting.isCompleted)
            assertTrue(f.cdm.removed.isEmpty())
            assertTrue("cdm.chooser.uncorrelatedResult" in f.diagnostics)
            f.result(successor)
            assertEquals(DeviceEndpointIdentity.deviceId(ADDRESS), successor.awaiting.await())
            assertEquals(listOf(ADDRESS), f.cdm.associatedAddresses.toList())
        } finally { f.close() }
    }

    @Test fun evictedLegacyRequestCannotRemoveTheSameAddressClaimedByTheLastSuccessor() = runTest {
        val f = Fixture(this)
        try {
            f.setup.activateSession()
            val old = f.request()
            old.awaiting.cancelAndJoin()
            repeat(7) { f.request().awaiting.cancelAndJoin() }
            val successor = f.request()
            f.result(successor)
            successor.awaiting.await()
            f.result(old)
            runCurrent()
            assertTrue(f.cdm.removed.isEmpty())
            assertEquals(listOf(ADDRESS), f.cdm.associatedAddresses.toList())
            assertTrue("cdm.chooser.uncorrelatedResult" in f.diagnostics)
        } finally { f.close() }
    }

    @Test fun registryLaunchFailureRemainsATypedPickerFailureRatherThanUserDismissal() = runTest {
        val f = Fixture(this)
        try {
            f.setup.activateSession()
            f.activity.launchFailure = IntentSender.SendIntentException("Unavailable chooser")
            val request = f.request()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertFailsWith<CompanionSetupError.PickerRestricted> { request.awaiting.await() }
            assertTrue(f.cdm.removed.isEmpty())
        } finally { f.close() }
    }

    private data class Request(val id: UUID, val code: Int, val awaiting: Deferred<UUID>)

    private class Fixture(private val scope: TestScope) {
        private val app = RuntimeEnvironment.getApplication().also {
            Shadows.shadowOf(it.packageManager).setSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP, true)
            Shadows.shadowOf(it).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            ShadowBluetoothAdapter.setIsBluetoothSupported(true)
        }
        val cdm = Shadow.extract<ChooserCompanionManagerShadow>(app.getSystemService(CompanionDeviceManager::class.java))
        val diagnostics = mutableListOf<String>()
        private val reporter = ConnectivityDiagnostics { operation, _ -> diagnostics += operation }
        var gateway = AndroidCompanionDeviceGateway(app, reporter)
            private set
        var setup = newSetup()
            private set
        private var controller = Robolectric.buildActivity(ChooserOwnershipActivity::class.java).setup()
        val activity get() = controller.get()
        private val pending = mutableListOf<Deferred<UUID>>()
        private val launched = mutableListOf<UUID>()
        private val sender = PendingIntent.getActivity(
            app, 0, Intent("com.meshcoreone.android.TEST_CHOOSER").setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE,
        ).intentSender

        init {
            bindHost()
        }

        private fun newSetup() = CompanionSetupService(gateway, scope.backgroundScope, SystemConnectivityClock(), reporter)

        private fun bindHost() {
            activity.sink = gateway::onChooserResult
            setup.setChooserHost(CompanionChooserHost { chooser, id ->
                launched += id
                activity.launchChooser(chooser, id)
            })
        }

        suspend fun request(): Request {
            val awaiting = scope.backgroundScope.async { setup.showPicker() }
            pending += awaiting
            scope.runCurrent()
            cdm.callbacks.last().onDeviceFound(sender)
            return Request(launched.last(), activity.codes.last(), awaiting)
        }

        fun resultIntent(): Intent {
            val device = app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(ADDRESS)
            return Intent().putExtra(CompanionDeviceManager.EXTRA_DEVICE, device)
        }

        fun result(request: Request) {
            cdm.associatedAddresses += ADDRESS
            activity.result(request.code, Activity.RESULT_OK, resultIntent())
        }

        fun recreate() {
            controller.recreate()
            bindHost()
        }

        fun restartGatewayAndHost() {
            val saved = Bundle()
            controller.pause().saveInstanceState(saved).stop().destroy()
            setup.invalidateSession()
            gateway = AndroidCompanionDeviceGateway(app, reporter)
            setup = newSetup()
            controller = Robolectric.buildActivity(ChooserOwnershipActivity::class.java).create(saved).start().resume().visible()
            bindHost()
        }

        suspend fun close() {
            pending.forEach { it.cancelAndJoin() }
            setup.setChooserHost(null)
            setup.invalidateSession()
            controller.pause().stop().destroy()
        }
    }

    private companion object {
        const val ADDRESS = "C4:DE:E2:11:22:33"
    }
}

class ChooserOwnershipActivity : MainActivity() {
    val codes = mutableListOf<Int>()
    val delivered = mutableListOf<UUID>()
    var launchFailure: IntentSender.SendIntentException? = null
    var sink: ((UUID, Int, Intent?) -> Unit)? = null

    override fun startIntentSenderForResult(
        intent: IntentSender, requestCode: Int, fillInIntent: Intent?, flagsMask: Int,
        flagsValues: Int, extraFlags: Int, options: Bundle?,
    ) {
        codes += requestCode
        launchFailure?.let { throw it }
    }

    override fun deliverChooserResult(requestId: UUID, resultCode: Int, data: Intent?) {
        delivered += requestId
        checkNotNull(sink)(requestId, resultCode, data)
    }

    fun result(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    }
}

@Implements(CompanionDeviceManager::class)
class ChooserCompanionManagerShadow {
    val callbacks = mutableListOf<CompanionDeviceManager.Callback>()
    val associatedAddresses = linkedSetOf<String>()
    val removed = mutableListOf<String>()

    @Implementation fun associate(request: AssociationRequest, callback: CompanionDeviceManager.Callback, handler: Handler?) {
        callbacks += callback
    }
    @Implementation fun getAssociations(): List<String> = associatedAddresses.toList()
    @Implementation fun disassociate(address: String) {
        removed += address
        associatedAddresses -= address
    }
    @Implementation fun startObservingDevicePresence(address: String) = Unit
    @Implementation fun stopObservingDevicePresence(address: String) = Unit
}
