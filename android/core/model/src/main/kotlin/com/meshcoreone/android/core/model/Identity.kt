// PortedFrom: MC1Services/Sources/MC1Services/Utilities/DeviceIdentity.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Extensions/String+StableUUID.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Utilities/VContactIdentity.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Utilities/Sequence+IndexByID.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Utilities/PersistenceKeys.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.sha256
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID

@JvmInline
value class RadioId(val value: UUID) {
    val canonicalString: String get() = value.canonicalString()
}

fun UUID.canonicalString(): String = toString().uppercase(Locale.ROOT)

fun Bytes.toUUID(): UUID {
    require(size == 16) { "UUID requires exactly 16 bytes" }
    val buffer = ByteBuffer.wrap(toByteArray())
    return UUID(buffer.long, buffer.long)
}

object DeviceIdentity {
    fun deriveUUID(publicKey: Bytes): UUID = sha256(publicKey).prefix(16).toUUID()
}

val String.stableUUID: UUID
    get() {
        var even = 5381L
        var odd = 5381L
        toByteArray(Charsets.UTF_8).forEachIndexed { index, byte ->
            if (index % 2 == 0) even = even * 33 + (byte.toLong() and 255)
            else odd = odd * 33 + (byte.toLong() and 255)
        }
        return Bytes(ByteArray(16) { index ->
            val stream = if (index < 8) even else odd
            (stream ushr ((index % 8) * 8)).toByte()
        }).toUUID()
    }

object VContactIdentity {
    val salt: Bytes = Bytes.utf8("zc-vcontact")

    fun publicKey(selfPublicKey: Bytes): Bytes? =
        if (selfPublicKey.size == ProtocolLimits.PUBLIC_KEY_SIZE) sha256(salt + selfPublicKey) else null

    fun isVContact(publicKey: Bytes, selfPublicKey: Bytes): Boolean =
        publicKey.size == ProtocolLimits.PUBLIC_KEY_SIZE && publicKey == VContactIdentity.publicKey(selfPublicKey)
}

fun <T, ID> Iterable<T>.indexByID(id: (T) -> ID): SnapshotMap<ID, Long> {
    val result = LinkedHashMap<ID, Long>()
    var offset = 0L
    for (element in this) {
        result[id(element)] = offset
        offset = Math.incrementExact(offset)
    }
    return result.snapshotMap()
}

object PersistenceKeys {
    const val LAST_CONNECTED_DEVICE_ID = "com.pocketmesh.lastConnectedDeviceID"
    const val LAST_CONNECTED_DEVICE_NAME = "com.pocketmesh.lastConnectedDeviceName"
    const val LAST_CONNECTED_RADIO_ID = "com.pocketmesh.lastConnectedRadioID"
    const val LAST_DISCONNECT_DIAGNOSTIC = "com.pocketmesh.lastDisconnectDiagnostic"
    const val LAST_BOND_VERIFIED_DEVICE_ID = "com.pocketmesh.lastBondVerifiedDeviceID"
    const val LAST_BOND_VERIFIED_DATE = "com.pocketmesh.lastBondVerifiedDate"
    const val USER_EXPLICITLY_DISCONNECTED = "com.pocketmesh.userExplicitlyDisconnected"
    const val SELECTED_THEME_ID = "selectedThemeID"
    const val APP_COLOR_SCHEME_PREFERENCE = "appColorSchemePreference"
}
