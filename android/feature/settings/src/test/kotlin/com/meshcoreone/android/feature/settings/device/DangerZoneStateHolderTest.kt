// PortedFrom: MC1Tests/Views/Settings/Sections/DangerZoneViewModelForgetTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.RemoveUnfavoritedResult
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.settings.device.support.FakeConnection
import com.meshcoreone.android.feature.settings.device.support.FakeMaintenance
import com.meshcoreone.android.feature.settings.device.support.FakeSettingsPort
import com.meshcoreone.android.feature.settings.device.support.OriginalCase
import com.meshcoreone.android.feature.settings.device.support.TestScheduler
import com.meshcoreone.android.feature.settings.device.support.device
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class DangerZoneStateHolderTest {
    private val scheduler = TestScheduler()
    private val dev = device()
    private val connection = FakeConnection(dev)
    private val service = FakeSettingsPort()
    private val maintenance = FakeMaintenance()
    private fun holder(
        port: SettingsRadioPort? = service, deviceProvider: () -> com.meshcoreone.android.core.model.DeviceDTO? = { dev },
        maintenancePort: DeviceMaintenancePort? = maintenance,
    ) = DangerZoneStateHolder(scheduler.environment(), { port }, deviceProvider, maintenancePort)

    @Test @OriginalCase("DangerZoneViewModelForgetTests::declining Remove Accessory does not set errorMessage()", "adapted-service-fake")
    fun `declining the system removal prompt does not set an error`() {
        maintenance.forgetError = PairingCancelledException()
        val holder = holder()
        val dismissed = scheduler.run { holder.forgetDevice(deleteData = false) }
        assertFalse(dismissed)
        assertNull(holder.state.value.errorMessage)
        assertEquals(DeviceConnectionState.READY, connection.stateFlow.value)
        assertEquals(dev, connection.deviceFlow.value)
        assertEquals(listOf("forget(false)"), maintenance.calls)
    }

    @Test
    fun `forget failure other than a declined prompt shows the described error`() {
        maintenance.forgetError = IllegalStateException("boom")
        val holder = holder()
        assertFalse(scheduler.run { holder.forgetDevice(true) })
        assertEquals(UiText.Verbatim("boom"), holder.state.value.errorMessage)
    }

    @Test
    fun `forget success tells the page to dismiss and forget without a maintenance port is a no-op`() {
        assertTrue(scheduler.run { holder().forgetDevice(true) })
        assertFalse(scheduler.run { holder(maintenancePort = null).forgetDevice(true) })
    }

    @Test
    fun `forget never swallows cancellation`() {
        maintenance.forgetError = CancellationException("cancelled")
        val holder = holder()
        var thrown: Throwable? = null
        scheduler.scope.launch { try { holder.forgetDevice(false) } catch (e: CancellationException) { thrown = e } }
        scheduler.runCurrent()
        assertTrue(thrown is CancellationException)
        assertNull(holder.state.value.errorMessage)
    }

    @Test
    fun `factory reset waits the grace period then forgets the device`() {
        val holder = holder()
        var result: Boolean? = null
        scheduler.scope.launch { result = holder.factoryReset() }
        scheduler.runCurrent()
        assertTrue(holder.state.value.isResetting)
        assertEquals(listOf("factoryReset"), service.calls)
        scheduler.advanceBy(1.seconds)
        assertEquals(true, result)
        assertFalse(holder.state.value.isResetting)
        assertEquals(listOf("forgetById(${dev.id})"), maintenance.calls)
    }

    @Test
    fun `factory reset treats a reset failure as the expected reboot and still cleans up`() {
        service.failNext("factoryReset", SettingsOperationTimeoutException())
        val holder = holder()
        var result: Boolean? = null
        scheduler.scope.launch { result = holder.factoryReset() }
        scheduler.advanceBy(1.seconds)
        assertEquals(true, result)
        assertEquals(listOf("forgetById(${dev.id})"), maintenance.calls)
        assertNull(holder.state.value.errorMessage)
    }

    @Test
    fun `factory reset without services reports unavailable and touches nothing`() {
        for (holder in listOf(holder(port = null), holder(deviceProvider = { null }), holder(maintenancePort = null))) {
            assertFalse(scheduler.run { holder.factoryReset() })
            assertEquals(UiText.Resource(AppSettingsStrings.dangerZoneErrorServicesUnavailable), holder.state.value.errorMessage)
            assertFalse(holder.state.value.isResetting)
        }
        assertTrue(service.calls.isEmpty() && maintenance.calls.isEmpty())
    }

    @Test
    fun `cancelling factory reset mid-grace still removes the device then propagates`() {
        val holder = holder()
        val job = scheduler.scope.launch { holder.factoryReset() }
        scheduler.runCurrent()
        job.cancel()
        scheduler.runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(listOf("forgetById(${dev.id})"), maintenance.calls)
        assertFalse(holder.state.value.isResetting)
    }

    @Test
    fun `zero unfavorited nodes reports none found and any other count asks for confirmation`() {
        val holder = holder()
        scheduler.run { holder.fetchUnfavoritedCount() }
        assertTrue(holder.state.value.showRemoveResult)
        assertFalse(holder.state.value.showingRemoveUnfavoritedAlert)
        assertEquals(RemoveOutcome.NoneFound, holder.state.value.removeResult)
        holder.dismissRemoveResult()
        maintenance.count = 4
        scheduler.run { holder.fetchUnfavoritedCount() }
        assertTrue(holder.state.value.showingRemoveUnfavoritedAlert)
        assertEquals(4, holder.state.value.unfavoritedCount)
        maintenance.count_error = IllegalStateException("db")
        scheduler.run { holder.fetchUnfavoritedCount() }
        assertEquals(UiText.Verbatim("db"), holder.state.value.errorMessage)
    }

    @Test
    fun `full removal flashes the success label for 1_5 seconds`() {
        maintenance.removal = RemoveUnfavoritedResult(3, 3)
        val holder = holder()
        holder.removeUnfavoritedNodes()
        scheduler.runCurrent()
        assertTrue(holder.state.value.showRemoveSuccess)
        assertFalse(holder.state.value.isRemovingUnfavorited)
        scheduler.advanceBy(1499.milliseconds)
        assertTrue(holder.state.value.showRemoveSuccess)
        scheduler.advanceBy(1.milliseconds)
        assertFalse(holder.state.value.showRemoveSuccess)
    }

    @Test
    fun `partial removal reports removed of total`() {
        maintenance.removal = RemoveUnfavoritedResult(2, 5)
        val holder = holder()
        holder.removeUnfavoritedNodes()
        scheduler.runCurrent()
        assertEquals(RemoveOutcome.Partial(2, 5), holder.state.value.removeResult)
        assertTrue(holder.state.value.showRemoveResult)
    }

    @Test
    fun `leaving the screen cancels removal quietly`() {
        maintenance.removal = RemoveUnfavoritedResult(1, 1)
        val holder = holder()
        holder.removeUnfavoritedNodes()
        scheduler.runCurrent()
        holder.cancelPendingRemoval()
        scheduler.runCurrent()
        assertNull(holder.state.value.errorMessage)
        assertFalse(holder.state.value.showRemoveSuccess)
        assertFalse(holder.state.value.isRemovingUnfavorited)
    }

    @Test
    fun `confirmation flags gate the destructive actions`() {
        val holder = holder()
        holder.requestForget(); holder.requestReset()
        assertTrue(holder.state.value.showingForgetConfirmation && holder.state.value.showingResetAlert)
        holder.dismissForget(); holder.dismissReset()
        assertFalse(holder.state.value.showingForgetConfirmation || holder.state.value.showingResetAlert)
        assertTrue(service.calls.isEmpty() && maintenance.calls.isEmpty())
    }
}
