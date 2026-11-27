// AndroidOnly: WP-101 Prizm Mobile Observer claim signing. Layouts verified against meshcore-dev/MeshCore
// examples/companion_radio/MyMesh.cpp (CMD_SIGN_START 0x21 -> RESP_CODE_SIGN_START 0x13 [0x00, u32le max=8192];
// CMD_SIGN_DATA 0x22 -> OK or ERR(BAD_STATE | TABLE_FULL); CMD_SIGN_FINISH 0x23 -> RESP_CODE_SIGNATURE 0x14 + 64 bytes
// or ERR(BAD_STATE)). The node signs the raw bytes (no pre-hash).
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.ErrorCode

object SigningLimits {
    /** Largest CMD_SIGN_DATA payload (a companion frame is capped at 176 bytes including the command byte). */
    const val MAX_CHUNK_BYTES = 160
    const val SIGNATURE_BYTES = 64

    /** Firmware `MAX_SIGN_DATA_LEN`. */
    const val FIRMWARE_BUFFER_BYTES = 8L * 1024L

    const val REASON_BUFFER_FULL = "node signing buffer is full"
    const val REASON_NO_SESSION = "node has no open signing session"
}

/**
 * Signs [data] on the node in chunks of at most [SigningLimits.MAX_CHUNK_BYTES] and maps the firmware's signing
 * error replies (BAD_STATE, TABLE_FULL) to [MeshCoreException.SigningFailed]. Other errors pass through.
 */
internal suspend fun SessionCore.signBounded(data: Bytes, timeout: Double?): Bytes =
    try {
        sign(data, SigningLimits.MAX_CHUNK_BYTES.toLong(), timeout)
    } catch (error: MeshCoreException.DeviceError) {
        throw when (error.deviceErrorCode) {
            ErrorCode.TABLE_FULL -> MeshCoreException.SigningFailed(SigningLimits.REASON_BUFFER_FULL)
            ErrorCode.BAD_STATE -> MeshCoreException.SigningFailed(SigningLimits.REASON_NO_SESSION)
            else -> error
        }
    }
