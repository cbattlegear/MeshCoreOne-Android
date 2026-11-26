// PortedFrom: MC1Services/Tests/MC1ServicesTests/BluetoothScanPairingServiceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.connectivity

import com.meshcoreone.android.core.connectivity.pairing.BluetoothScanPairingService
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingError
import com.meshcoreone.android.core.connectivity.support.OriginalCase
import com.meshcoreone.android.core.connectivity.support.scenario
import com.meshcoreone.android.core.connectivity.support.settle
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import org.junit.Test

class BluetoothScanPairingServiceTest {
    @Test @OriginalCase("BluetoothScanPairingServiceTests::discoverDevice presents the picker and resolves with the selected id()")
    fun `discoverDevice presents the picker and resolves with the selected id`() = scenario {
        val service = BluetoothScanPairingService()
        val expected = UUID.randomUUID()
        val task = scope.async { service.discoverDevice() }
        assertTrue(awaitCondition { service.isPresenting.value })
        service.select(expected)
        assertEquals(expected, task.await())
        assertFalse(service.isPresenting.value)
    }

    @Test @OriginalCase("BluetoothScanPairingServiceTests::cancel surfaces DevicePairingError.cancelled and lowers presentation()")
    fun `cancel surfaces DevicePairingError_cancelled and lowers presentation`() = scenario {
        val service = BluetoothScanPairingService()
        val task = scope.async { runCatching { service.discoverDevice() } }
        assertTrue(awaitCondition { service.isPresenting.value })
        service.cancel()
        assertIs<DevicePairingError.Cancelled>(task.await().exceptionOrNull())
        assertFalse(service.isPresenting.value)
    }

    @Test @OriginalCase("BluetoothScanPairingServiceTests::a new discovery resolves a stranded prior discovery()")
    fun `a new discovery resolves a stranded prior discovery`() = scenario {
        val service = BluetoothScanPairingService()
        val first = scope.async { runCatching { service.discoverDevice() } }
        assertTrue(awaitCondition { service.isPresenting.value })
        val second = scope.async { service.discoverDevice() }
        assertIs<DevicePairingError.Cancelled>(first.await().exceptionOrNull())
        assertTrue(awaitCondition { service.isPresenting.value })
        val expected = UUID.randomUUID()
        service.select(expected)
        assertEquals(expected, second.await())
    }

    @Test @OriginalCase("BluetoothScanPairingServiceTests::cancelling the discovery task resolves it and lowers presentation()", "platform-adaptation")
    fun `cancelling the discovery task resolves it and lowers presentation`() = scenario {
        val service = BluetoothScanPairingService()
        val task = scope.async { service.discoverDevice() }
        assertTrue(awaitCondition { service.isPresenting.value })
        task.cancel()
        // Structured cancellation (deviation A-08): the awaiting coroutine ends cancelled, not with a domain error.
        assertIs<CancellationException>(runCatching { task.await() }.exceptionOrNull())
        assertTrue(awaitCondition { !service.isPresenting.value })
        // The stranded continuation is gone: a late selection has nothing to resolve.
        service.select(UUID.randomUUID())
        settle()
        assertFalse(service.isPresenting.value)
    }

    @Test @OriginalCase("BluetoothScanPairingServiceTests::a selection racing a cancelled discovery task surfaces cancelled, not the selection()", "platform-adaptation")
    fun `a selection racing a cancelled discovery task surfaces cancelled, not the selection`() = scenario {
        val service = BluetoothScanPairingService()
        val task = scope.async { service.discoverDevice() }
        assertTrue(awaitCondition { service.isPresenting.value })
        task.cancel()
        service.select(UUID.randomUUID())
        assertIs<CancellationException>(runCatching { task.await() }.exceptionOrNull())
        assertTrue(awaitCondition { !service.isPresenting.value })
    }

    @Test @OriginalCase("BluetoothScanPairingServiceTests::system-registry operations are inert on the macOS path()")
    fun `system-registry operations are inert on the scan fallback path`() = scenario {
        val service = BluetoothScanPairingService()
        assertFalse(service.isSessionActive)
        assertFalse(service.hasSystemPairingRegistry)
        assertEquals(0, service.registeredDeviceCount)
        assertFalse(service.supportsSystemRename)
        assertTrue(service.isDeviceConnectable(UUID.randomUUID()))
        assertTrue(service.registeredDeviceInfos().isEmpty())
        service.activate()
        service.removeDevice(UUID.randomUUID())
        service.renameDevice(UUID.randomUUID())
        service.clearStaleRegistrations()
    }
}
