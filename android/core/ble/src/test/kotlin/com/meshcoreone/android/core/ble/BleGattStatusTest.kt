// PortedFrom: MC1Services/Tests/MC1ServicesTests/Transport/BLEStateMachineDisconnectionMappingTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Transport/BLEStateMachineRestorationAndTeardownTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ble

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BleGattStatusTest(
    private val kind: GattOperationKind,
    private val status: Int,
    private val authentication: Boolean,
) {
    @Test fun `ATT status maps to source typed errors and retains operation metadata`() {
        val failure = gattFailure(kind, status)
        val expected = when {
            authentication -> BleError.AuthenticationFailed
            kind == GattOperationKind.Write -> BleError.WriteError("gatt.status.$status")
            else -> BleError.ConnectionFailed("gatt.status.$status")
        }
        assertEquals(expected, failure.error)
        assertEquals(kind, failure.operation)
        assertEquals(status, failure.status)
        assertEquals(GattStatusDomain.Att, failure.statusDomain)
        if (!authentication) assertFalse(failure.error == BleError.DeviceConnectedToOtherApp)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-status{1}")
        fun statuses(): List<Array<Any>> = listOf(
            GattOperationKind.DiscoverServices, GattOperationKind.Mtu, GattOperationKind.Subscribe, GattOperationKind.Write,
        ).flatMap { kind ->
            listOf(
                arrayOf<Any>(kind, 5, true), arrayOf<Any>(kind, 8, true), arrayOf<Any>(kind, 12, true), arrayOf<Any>(kind, 15, true),
                arrayOf<Any>(kind, 3, false), arrayOf<Any>(kind, 6, false), arrayOf<Any>(kind, 133, false),
            )
        }
    }
}
