// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionManagerBLEScanningTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.connectivity

import com.meshcoreone.android.core.connectivity.ble.BleScanCoordinator
import com.meshcoreone.android.core.connectivity.ble.DiscoveredDevice
import com.meshcoreone.android.core.connectivity.support.FakeScanGateway
import com.meshcoreone.android.core.connectivity.support.OriginalCase
import com.meshcoreone.android.core.connectivity.support.scenario
import com.meshcoreone.android.core.connectivity.support.settle
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Test

class ConnectionManagerBLEScanningTest {
    @Test @OriginalCase("ConnectionManagerBLEScanningTests::startBLEScanning starts scan and forwards discoveries()")
    fun `startBLEScanning starts scan and forwards discoveries`() = scenario {
        val gateway = FakeScanGateway()
        val scans = BleScanCoordinator(gateway)
        val expected = DiscoveredDevice(UUID.randomUUID(), "C1:00:00:00:00:01", "MeshCore-Test", -68)
        val received = scope.async { scans.startBleScanning().first() }
        assertTrue(awaitCondition { gateway.scanning })
        gateway.discover(expected)
        assertEquals(expected, received.await())
        assertEquals(1, gateway.startCount)
        scans.stopBleScanning()
        assertFalse(gateway.scanning)
    }

    @Test @OriginalCase("ConnectionManagerBLEScanningTests::terminated scan stream does not leave scanning active()")
    fun `terminated scan stream does not leave scanning active`() = scenario {
        val gateway = FakeScanGateway()
        val scans = BleScanCoordinator(gateway)
        val consumer = scope.launch { scans.startBleScanning().collect {} }
        assertTrue(awaitCondition { gateway.scanning })
        consumer.cancel()
        consumer.join()
        settle()
        assertFalse(gateway.scanning)
        assertTrue(gateway.stopCount >= 1)
        assertFalse(scans.isScanning)
    }

    @Test @OriginalCase("ConnectionManagerBLEScanningTests::older stream termination does not stop newer scan()")
    fun `older stream termination does not stop newer scan`() = scenario {
        val gateway = FakeScanGateway()
        val scans = BleScanCoordinator(gateway)
        val first = scope.launch { scans.startBleScanning().collect {} }
        assertTrue(awaitCondition { gateway.scanning })
        val second = scope.launch { scans.startBleScanning().collect {} }
        assertTrue(awaitCondition { gateway.startCount == 2 })
        val stopsBefore = gateway.stopCount
        first.cancel()
        first.join()
        settle()
        assertTrue(gateway.scanning, "Newer scan remains active")
        assertEquals(stopsBefore, gateway.stopCount)
        second.cancel()
        second.join()
        settle()
        assertFalse(gateway.scanning)
    }
}
