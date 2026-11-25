// PortedFrom: MC1Services/Sources/MC1Services/Services/LoginResult.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant

data class LoginResult(
    val success: Boolean,
    val isAdmin: Boolean,
    val aclPermissions: UByte?,
    val publicKeyPrefix: Bytes,
    /** The remote node's RTC reading from the login response, if carried. */
    val serverTime: Instant? = null,
) {
    val permissionLevel: RoomPermissionLevel
        get() = if (isAdmin) RoomPermissionLevel.ADMIN
        else RoomPermissionLevel.fromRawValue(aclPermissions ?: 0u) ?: RoomPermissionLevel.GUEST
}
