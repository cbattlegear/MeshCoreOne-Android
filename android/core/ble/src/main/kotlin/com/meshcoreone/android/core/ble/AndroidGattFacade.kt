// PortedFrom: MC1Services/Sources/MC1Services/Transport/BLEStateMachine+CBDelegate.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.IdentityHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

class AndroidGattFacade internal constructor(
    private val context: Context,
    override val handle: BleDeviceHandle,
    private val api: AndroidGattApi,
    private val callbackExecutor: GattCallbackExecutor,
    private val apiLevel: Int,
) : GattFacade {
    constructor(
        context: Context,
        handle: BleDeviceHandle,
        callbackHandler: Handler = Handler(Looper.getMainLooper()),
    ) : this(context.applicationContext, handle, PlatformGattApi, HandlerGattExecutor(callbackHandler), Build.VERSION.SDK_INT)

    override fun availability(): BluetoothAvailability = api.availability(context)
    override fun create(events: GattEvents): GattConnection = AndroidGattConnection(
        context, handle, api, callbackExecutor, apiLevel, events,
    )
}

internal class AndroidGattConnection(
    private val context: Context,
    private val handle: BleDeviceHandle,
    private val api: AndroidGattApi,
    private val executor: GattCallbackExecutor,
    private val apiLevel: Int,
    private val events: GattEvents,
) : GattConnection {
    private val closeRequested = AtomicBoolean(false)
    private val closeReceipt = CompletableDeferred<Unit>()
    private var cleaned = false
    private var issuing = false
    private var gatt: BluetoothGatt? = null
    private var pending: GattOperation? = null
    private var registered = false
    private var rx: BluetoothGattCharacteristic? = null
    private val earlyCallbacks = ArrayDeque<() -> Unit>()
    private val nativeCharacteristics = IdentityHashMap<GattCharacteristic, BluetoothGattCharacteristic>()
    private val domainCharacteristics = IdentityHashMap<BluetoothGattCharacteristic, GattCharacteristic>()
    private val nativeDescriptors = IdentityHashMap<GattDescriptor, BluetoothGattDescriptor>()

    override fun submit(operation: GattOperation) {
        if (!executor.execute { submitOnLooper(operation) }) {
            events.onFailure(this, operation.key, BleTransportException(BleError.GattRejected(operation.kind, null)))
        }
    }

    private fun submitOnLooper(operation: GattOperation) {
        if (closeRequested.get()) {
            events.onFailure(this, operation.key, BleTransportException(BleError.NotConnected, operation.kind))
            return
        }
        if (pending != null) {
            events.onFailure(this, operation.key, BleTransportException(BleError.GattOperationInProgress(operation.kind), operation.kind))
            return
        }
        pending = operation
        issuing = true
        try {
            val available = api.availability(context)
            if (available != BluetoothAvailability.Ready) {
                events.onUnavailable(this, available)
                return
            }
            when (operation) {
                is GattOperation.Connect -> {
                    if (gatt != null) {
                        fail(operation, BleTransportException(BleError.ConnectionFailed("gatt.alreadyOpened")))
                        return
                    }
                    registerStateReceiver()
                    val bond = api.bondState(context, handle)
                    events.onBondChanged(this, bond)
                    if (operation.requireBond && bond != BondState.Bonded) {
                        fail(operation, BleTransportException(BleError.BondRequired(bond), operation.kind))
                        return
                    }
                    gatt = if (apiLevel >= 37) api.connectModern(context, handle, callback, executor.executor)
                    else api.connectLegacy(context, handle, callback, executor.handler)
                    if (gatt == null) fail(operation, BleTransportException(BleError.ConnectionFailed("gatt.nullHandle"), operation.kind))
                }
                is GattOperation.DiscoverServices -> requireAccepted(operation, api.discoverServices(requireGatt()))
                is GattOperation.Mtu -> requireAccepted(operation, api.requestMtu(requireGatt(), operation.requested))
                is GattOperation.Rssi -> requireAccepted(operation, api.readRssi(requireGatt()))
                is GattOperation.Subscribe -> {
                    val characteristic = nativeCharacteristics[operation.characteristic]
                        ?: throw BleTransportException(BleError.CharacteristicNotFound, operation.kind)
                    val descriptor = nativeDescriptors[operation.cccd]
                        ?: throw BleTransportException(BleError.DescriptorNotFound, operation.kind)
                    if (descriptor.characteristic !== characteristic) {
                        throw BleTransportException(BleError.DescriptorNotFound, operation.kind)
                    }
                    rx = characteristic
                    if (!api.setNotifications(requireGatt(), characteristic)) {
                        fail(operation, immediateFailure(operation.kind, null))
                        return
                    }
                    val value = byteArrayOf(0x01, 0x00)
                    if (apiLevel >= 33) requireAccepted(operation, api.descriptorModern(requireGatt(), descriptor, value))
                    else requireAccepted(operation, api.descriptorLegacy(requireGatt(), descriptor, value))
                }
                is GattOperation.Write -> {
                    val characteristic = nativeCharacteristics[operation.characteristic]
                        ?: throw BleTransportException(BleError.CharacteristicNotFound, operation.kind)
                    val writeType = when (operation.mode) {
                        GattWriteMode.WithResponse -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        GattWriteMode.WithoutResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    }
                    val value = operation.data.toByteArray()
                    if (apiLevel >= 33) requireAccepted(operation, api.writeModern(requireGatt(), characteristic, value, writeType))
                    else requireAccepted(operation, api.writeLegacy(requireGatt(), characteristic, value, writeType))
                }
            }
        } catch (failure: BleTransportException) {
            fail(operation, failure)
        } catch (denied: SecurityException) {
            fail(operation, BleTransportException(BleError.BluetoothUnauthorized, operation.kind, cause = denied))
        } catch (invalid: IllegalArgumentException) {
            fail(operation, BleTransportException(BleError.GattRejected(operation.kind, null), operation.kind, cause = invalid))
        } catch (rejected: RejectedExecutionException) {
            fail(operation, BleTransportException(BleError.GattRejected(operation.kind, null), operation.kind, cause = rejected))
        } finally {
            issuing = false
            if (closeRequested.get()) closeOnLooper()
            while (earlyCallbacks.isNotEmpty()) earlyCallbacks.removeFirst().invoke()
        }
    }

    private fun requireGatt(): BluetoothGatt =
        gatt ?: throw BleTransportException(BleError.NotConnected)

    private fun requireAccepted(operation: GattOperation, accepted: Boolean) {
        if (!accepted) fail(operation, immediateFailure(operation.kind, null))
    }

    private fun requireAccepted(operation: GattOperation, status: Int) {
        if (status != BluetoothStatusCodes.SUCCESS) fail(operation, immediateFailure(operation.kind, status))
    }

    private fun fail(operation: GattOperation, failure: BleTransportException) {
        if (pending !== operation) {
            reject(RejectedCallback.Operation)
            return
        }
        pending = null
        events.onFailure(this, operation.key, failure)
    }

    private fun finish(operation: GattOperation, status: Int, reply: GattReply) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail(operation, gattFailure(operation.kind, status))
            return
        }
        if (pending !== operation) {
            reject(RejectedCallback.Operation)
            return
        }
        pending = null
        events.onReply(this, operation.key, reply)
    }

    private fun dispatch(source: BluetoothGatt, action: () -> Unit) {
        if (!executor.execute {
                val deliver = {
                    if (closeRequested.get()) reject(RejectedCallback.Closed)
                    else if (gatt !== source) reject(RejectedCallback.Connection)
                    else {
                        try {
                            val available = api.availability(context)
                            if (available != BluetoothAvailability.Ready) events.onUnavailable(this, available)
                            else action()
                        } catch (denied: SecurityException) {
                            events.onLinkFailure(this, BleTransportException(BleError.BluetoothUnauthorized, cause = denied))
                        } catch (failure: BleTransportException) {
                            events.onLinkFailure(this, failure)
                        }
                    }
                }
                if (issuing) earlyCallbacks.addLast(deliver) else deliver()
            }
        ) {
            events.onLinkFailure(this, BleTransportException(BleError.CleanupFailed("callback.executor")))
        }
    }

    private fun reject(reason: RejectedCallback) { events.onRejectedCallback(this, reason) }

    internal val callback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            dispatch(gatt) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    events.onDisconnected(this@AndroidGattConnection, status)
                    return@dispatch
                }
                val operation = pending as? GattOperation.Connect
                if (operation == null) return@dispatch reject(RejectedCallback.Kind)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail(operation, connectionStateFailure(operation.kind, status))
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        finish(operation, status, GattReply.Connected(api.bondState(context, handle)))
                    } catch (denied: SecurityException) {
                        fail(operation, BleTransportException(BleError.BluetoothUnauthorized, operation.kind, cause = denied))
                    } catch (failure: BleTransportException) {
                        fail(operation, failure)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            dispatch(gatt) {
                val operation = pending as? GattOperation.DiscoverServices
                    ?: return@dispatch reject(RejectedCallback.Kind)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail(operation, gattFailure(operation.kind, status))
                    return@dispatch
                }
                nativeCharacteristics.clear()
                domainCharacteristics.clear()
                nativeDescriptors.clear()
                val services = gatt.services.map { service ->
                    GattService(service.uuid, service.characteristics.map { characteristic ->
                        val descriptors = characteristic.descriptors.map {
                            GattDescriptor(it.uuid).also { reference -> nativeDescriptors[reference] = it }
                        }
                        val properties = buildSet {
                            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add(GattProperty.Write)
                            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add(GattProperty.WriteWithoutResponse)
                            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add(GattProperty.Notify)
                        }
                        GattCharacteristic(characteristic.uuid, properties, descriptors).also {
                            nativeCharacteristics[it] = characteristic
                            domainCharacteristics[characteristic] = it
                        }
                    })
                }
                finish(operation, status, GattReply.Services(services))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            dispatch(gatt) {
                val operation = pending as? GattOperation.Mtu
                if (operation == null) {
                    events.onLinkFailure(this@AndroidGattConnection, BleTransportException(BleError.InvalidResponse, GattOperationKind.Mtu, status))
                    return@dispatch
                }
                finish(operation, status, GattReply.Mtu(mtu))
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            dispatch(gatt) {
                val operation = pending as? GattOperation.Subscribe
                    ?: return@dispatch reject(RejectedCallback.Kind)
                if (nativeDescriptors[operation.cccd] !== descriptor ||
                    nativeCharacteristics[operation.characteristic] !== descriptor.characteristic
                ) return@dispatch reject(RejectedCallback.Attribute)
                finish(operation, status, GattReply.Subscribed)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            dispatch(gatt) {
                val operation = pending as? GattOperation.Write
                    ?: return@dispatch reject(RejectedCallback.Kind)
                if (nativeCharacteristics[operation.characteristic] !== characteristic) {
                    return@dispatch reject(RejectedCallback.Attribute)
                }
                finish(operation, status, GattReply.Written)
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            dispatch(gatt) {
                val operation = pending as? GattOperation.Rssi
                    ?: return@dispatch reject(RejectedCallback.Kind)
                finish(operation, BluetoothGatt.GATT_SUCCESS, GattReply.Rssi(rssi, status))
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (apiLevel >= 33) return
            val data = characteristic.value?.let(::Bytes)
            notification(gatt, characteristic, data)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            if (apiLevel < 33) return
            notification(gatt, characteristic, Bytes(value))
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            dispatch(gatt) {
                events.onLinkFailure(this@AndroidGattConnection, BleTransportException(BleError.ConnectionFailed("gatt.serviceChanged")))
            }
        }
    }

    private fun notification(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: Bytes?) {
        dispatch(source) {
            val reference = domainCharacteristics[characteristic]
            if (characteristic !== rx || reference == null) return@dispatch reject(RejectedCallback.Attribute)
            if (data == null) {
                events.onLinkFailure(this, BleTransportException(BleError.InvalidResponse))
                return@dispatch
            }
            events.onNotification(this, reference, data)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action !in setOf(BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothDevice.ACTION_BOND_STATE_CHANGED)) return
            if (!executor.execute {
                    if (!closeRequested.get()) {
                        val available = api.availability(this@AndroidGattConnection.context)
                        if (available != BluetoothAvailability.Ready) {
                            events.onUnavailable(this@AndroidGattConnection, available)
                        } else if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                            try {
                                events.onBondChanged(this@AndroidGattConnection, api.bondState(this@AndroidGattConnection.context, handle))
                            } catch (denied: SecurityException) {
                                events.onLinkFailure(this@AndroidGattConnection, BleTransportException(BleError.BluetoothUnauthorized, cause = denied))
                            } catch (failure: BleTransportException) {
                                events.onLinkFailure(this@AndroidGattConnection, failure)
                            }
                        }
                    }
                }
            ) events.onLinkFailure(this@AndroidGattConnection, BleTransportException(BleError.CleanupFailed("state.executor")))
        }
    }

    private fun registerStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // Bluetooth broadcasts can originate from a privileged non-system UID. Both actions are protected;
        // recovery reads current platform state instead of trusting broadcast extras.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        registered = true
    }

    override fun close(): Deferred<Unit> {
        if (closeRequested.compareAndSet(false, true)) {
            if (!executor.execute { if (!issuing) closeOnLooper() }) {
                closeReceipt.completeExceptionally(BleTransportException(BleError.CleanupFailed("close.executor")))
            }
        }
        return closeReceipt
    }

    private fun closeOnLooper() {
        if (cleaned) return
        cleaned = true
        pending = null
        var failure: BleTransportException? = null
        fun record(stage: String, cause: Throwable) {
            val next = BleTransportException(BleError.CleanupFailed(stage), cause = cause)
            val previous = failure
            if (previous == null) failure = next else previous.addSuppressed(next)
        }
        if (registered) {
            registered = false
            try {
                context.unregisterReceiver(receiver)
            } catch (denied: SecurityException) {
                record("unregisterReceiver", denied)
            } catch (invalid: IllegalArgumentException) {
                record("unregisterReceiver", invalid)
            }
        }
        gatt?.let {
            try {
                api.disconnect(it)
            } catch (denied: SecurityException) {
                record("gatt.disconnect", denied)
            } catch (invalid: IllegalStateException) {
                record("gatt.disconnect", invalid)
            }
            try {
                api.close(it)
            } catch (denied: SecurityException) {
                record("gatt.close", denied)
            } catch (invalid: IllegalStateException) {
                record("gatt.close", invalid)
            }
        }
        gatt = null
        rx = null
        nativeCharacteristics.clear()
        domainCharacteristics.clear()
        nativeDescriptors.clear()
        val actualFailure = failure
        if (actualFailure == null) closeReceipt.complete(Unit)
        else closeReceipt.completeExceptionally(actualFailure)
    }
}
