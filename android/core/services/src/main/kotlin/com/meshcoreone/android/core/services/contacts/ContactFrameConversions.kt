// PortedFrom: MC1Services/Sources/MC1Services/Services/ContactService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.MeshContact
import java.time.Instant

/** Converts a [MeshContact] to a [ContactFrame] for persistence. */
fun MeshContact.toContactFrame(): ContactFrame = ContactFrame(
    publicKey = publicKey,
    type = type,
    typeRawValue = typeRawValue,
    flags = flags.rawValue,
    outPathLength = outPathLength,
    outPath = outPath,
    name = advertisedName,
    lastAdvertTimestamp = lastAdvertisement.toUInt32Seconds(),
    latitude = latitude,
    longitude = longitude,
    lastModified = lastModified.toUInt32Seconds(),
)

/** Converts a [ContactFrame] to a [MeshContact] for session operations. */
fun ContactFrame.toMeshContact(): MeshContact = MeshContact(
    id = publicKey.uppercaseHexString(),
    publicKey = publicKey,
    type = type,
    typeRawValue = typeRawValue,
    flags = ContactFlags(flags),
    outPathLength = outPathLength,
    outPath = outPath,
    advertisedName = name,
    lastAdvertisement = Instant.ofEpochSecond(lastAdvertTimestamp.toLong()),
    latitude = latitude,
    longitude = longitude,
    lastModified = Instant.ofEpochSecond(lastModified.toLong()),
)

/**
 * Swift `UInt32(date.timeIntervalSince1970)`: truncates toward zero. Swift traps when the value is
 * outside `UInt32`; this throws [IllegalArgumentException] instead of crashing the process.
 */
internal fun Instant.toUInt32Seconds(): UInt {
    val truncated = if (epochSecond < 0 && nano > 0) epochSecond + 1 else epochSecond
    require(truncated in 0L..UInt.MAX_VALUE.toLong()) { "Timestamp $this is outside the UInt32 seconds range" }
    return truncated.toUInt()
}
