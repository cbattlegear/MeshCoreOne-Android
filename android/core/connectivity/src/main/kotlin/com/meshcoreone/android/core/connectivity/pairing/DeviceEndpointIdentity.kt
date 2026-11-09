// AndroidOnly: WP-206 Stable endpoint ids for Bluetooth addresses and the rotating-address persistence boundary.
package com.meshcoreone.android.core.connectivity.pairing

import com.meshcoreone.android.core.contracts.domain.BluetoothAddress
import com.meshcoreone.android.core.contracts.domain.BluetoothPairingHandle
import java.util.Locale
import java.util.UUID

/** A connectable Bluetooth endpoint: address handle plus the companion association, when one exists. */
data class BluetoothEndpoint(val deviceId: UUID, val address: String, val associationId: Int?) {
    val handle: BluetoothPairingHandle get() = BluetoothPairingHandle(BluetoothAddress(address), associationId)
}

enum class EndpointStability {
    /** Public or static-random address, or a bonded device whose identity address Android resolves. */
    Stable,
    /** Unbonded resolvable/non-resolvable private address: it rotates and must not be persisted as identity. */
    Ephemeral,
}

/**
 * CoreBluetooth vends a per-install peripheral UUID; Android vends a MAC address. The endpoint id
 * is a name-based UUID of the canonical address so the runtime keeps a UUID-keyed handle. It is an
 * endpoint handle only: radio identity remains the public-key-derived radio id.
 */
object DeviceEndpointIdentity {
    private val ADDRESS = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
    private const val NAMESPACE = "meshcore-one/ble-endpoint/"

    fun canonicalAddress(raw: String): String {
        val canonical = raw.trim().uppercase(Locale.ROOT)
        require(ADDRESS.matches(canonical)) { "Malformed Bluetooth address" }
        return canonical
    }

    fun deviceId(address: String): UUID =
        UUID.nameUUIDFromBytes((NAMESPACE + canonicalAddress(address)).toByteArray(Charsets.UTF_8))

    fun endpoint(address: String, associationId: Int?): BluetoothEndpoint {
        val canonical = canonicalAddress(address)
        return BluetoothEndpoint(deviceId(canonical), canonical, associationId)
    }

    /**
     * LE random addresses carry their sub-type in the two most significant bits of the first octet:
     * `11` static, `01` resolvable private, `00` non-resolvable private. Android does not expose
     * the public/random address type before API 35, so a private-looking address is treated as
     * rotating until the bond lets the stack report the resolved identity address.
     */
    fun stability(address: String, bonded: Boolean): EndpointStability {
        if (bonded) return EndpointStability.Stable
        val firstOctet = canonicalAddress(address).substring(0, 2).toInt(16)
        return if (firstOctet and 0xC0 == 0xC0) EndpointStability.Stable else EndpointStability.Ephemeral
    }
}

/** Endpoint handles selected through scan fallback; stores keep rotating private addresses process-local. */
interface KnownEndpointStore {
    suspend fun endpoint(deviceId: UUID): BluetoothEndpoint?
    suspend fun remember(endpoint: BluetoothEndpoint)
    suspend fun remember(endpoint: BluetoothEndpoint, stability: EndpointStability) = remember(endpoint)
    suspend fun forget(deviceId: UUID)
}

class InMemoryKnownEndpointStore : KnownEndpointStore {
    private val lock = Any()
    private val endpoints = linkedMapOf<UUID, BluetoothEndpoint>()
    override suspend fun endpoint(deviceId: UUID): BluetoothEndpoint? = synchronized(lock) { endpoints[deviceId] }
    override suspend fun remember(endpoint: BluetoothEndpoint) { synchronized(lock) { endpoints[endpoint.deviceId] = endpoint } }
    override suspend fun forget(deviceId: UUID) { synchronized(lock) { endpoints.remove(deviceId) } }
}
