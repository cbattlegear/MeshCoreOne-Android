// AndroidOnly: WP-303 Controlled framework GATT peer for production-composed BLE recovery tests.
package com.meshcoreone.android.app.container

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowBluetoothGatt

@Implements(BluetoothGatt::class, minSdk = 31, maxSdk = 32)
class BleRecoveryGattShadow : ShadowBluetoothGatt() {
    @RealObject private lateinit var actual: BluetoothGatt
    internal var onCommand: (ByteArray) -> Unit = { error("Firmware peer is not installed") }
    internal var onClosed: () -> Unit = {}
    internal var closes = 0
    internal var disconnects = 0
    internal val writes = mutableListOf<ByteArray>()

    @Implementation
    override fun requestMtu(mtu: Int): Boolean {
        requireNotNull(gattCallback).onMtuChanged(actual, 247, BluetoothGatt.GATT_SUCCESS)
        return true
    }

    @Suppress("DEPRECATION")
    @Implementation
    override fun writeDescriptor(descriptor: BluetoothGattDescriptor): Boolean {
        requireNotNull(gattCallback).onDescriptorWrite(actual, descriptor, BluetoothGatt.GATT_SUCCESS)
        return true
    }

    @Suppress("DEPRECATION")
    @Implementation
    override fun writeCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        val bytes = requireNotNull(characteristic.value).copyOf()
        writes += bytes
        requireNotNull(gattCallback).onCharacteristicWrite(actual, characteristic, BluetoothGatt.GATT_SUCCESS)
        onCommand(bytes)
        return true
    }

    @Implementation
    override fun disconnect() {
        disconnects++
        super.disconnect()
    }

    @Implementation
    override fun close() {
        closes++
        onClosed()
        super.close()
    }

    internal fun lose(status: Int = BluetoothGatt.GATT_SUCCESS) {
        requireNotNull(gattCallback).onConnectionStateChange(actual, status, BluetoothProfile.STATE_DISCONNECTED)
    }

    @Suppress("DEPRECATION")
    internal fun notify(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        characteristic.value = bytes.copyOf()
        requireNotNull(gattCallback).onCharacteristicChanged(actual, characteristic)
    }

    internal fun lateConnected() {
        requireNotNull(gattCallback).onConnectionStateChange(actual, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    }
}
