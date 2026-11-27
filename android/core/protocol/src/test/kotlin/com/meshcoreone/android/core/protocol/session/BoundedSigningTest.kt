// AndroidOnly: WP-101 Prizm Mobile Observer claim signing; layouts verified against meshcore-dev/MeshCore companion_radio MyMesh.cpp.
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.ErrorCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedSigningTest {
    private val signature = filled(0x5a, SigningLimits.SIGNATURE_BYTES)

    @TestFactory
    fun boundedSigning() = listOf(
        nativeCase("constants match the firmware") {
            assertEquals(160, SigningLimits.MAX_CHUNK_BYTES)
            assertEquals(64, SigningLimits.SIGNATURE_BYTES)
            assertEquals(8 * 1024L, SigningLimits.FIRMWARE_BUFFER_BYTES)
        },
        nativeCase("a 165 byte claim message is sent as a 160 and a 5 byte chunk then finish") {
            val f = fixture(); start(f)
            f.transport.onSend = {
                when (it[0].toInt()) {
                    0x21 -> f.transport.receive(raw(0x13, hex("00") + little32(8192)))
                    0x22 -> f.transport.ok()
                    0x23 -> f.transport.receive(raw(0x14, signature))
                    else -> fail("Unexpected command")
                }
            }
            val message = Bytes(ByteArray(165) { it.toByte() })
            assertEquals(signature, f.session.signBounded(message))
            val writes = f.transport.sent.drop(1)
            assertEquals(listOf(1, 161, 6, 1), writes.map { it.size })
            assertEquals(hex("21"), writes[0])
            assertEquals(Bytes.of(0x22) + message.slice(0, 160), writes[1])
            assertEquals(Bytes.of(0x22) + message.slice(160, 165), writes[2])
            assertEquals(hex("23"), writes[3])
            f.session.stop()
        },
        nativeCase("a message over the firmware buffer is rejected before any data is sent") {
            val f = fixture(); start(f)
            f.transport.onSend = { f.transport.receive(raw(0x13, hex("00") + little32(8192))) }
            val failure = assertFailsWith<MeshCoreException.DataTooLarge> {
                f.session.signBounded(Bytes(ByteArray(8193)))
            }
            assertEquals(8192L, failure.maxSize)
            assertEquals(listOf(hex("21")), f.transport.sent.drop(1))
            f.session.stop()
        },
        nativeCase("TABLE_FULL on a data chunk is signing failure, buffer exceeded") {
            val f = fixture(); start(f)
            f.transport.onSend = {
                when (it[0].toInt()) {
                    0x21 -> f.transport.receive(raw(0x13, hex("00") + little32(8192)))
                    else -> f.transport.error(ErrorCode.TABLE_FULL.rawValue.toInt())
                }
            }
            val failure = assertFailsWith<MeshCoreException.SigningFailed> { f.session.signBounded(hex("aabb")) }
            assertEquals(SigningLimits.REASON_BUFFER_FULL, failure.reason)
            f.session.stop()
        },
        nativeCase("BAD_STATE on finish is a signing failure, no session open") {
            val f = fixture(); start(f)
            f.transport.onSend = {
                when (it[0].toInt()) {
                    0x21 -> f.transport.receive(raw(0x13, hex("00") + little32(8192)))
                    0x22 -> f.transport.ok()
                    else -> f.transport.error(ErrorCode.BAD_STATE.rawValue.toInt())
                }
            }
            val failure = assertFailsWith<MeshCoreException.SigningFailed> { f.session.signBounded(hex("aabb")) }
            assertEquals(SigningLimits.REASON_NO_SESSION, failure.reason)
            f.session.stop()
        },
        nativeCase("other device errors pass through unchanged") {
            val f = fixture(); start(f)
            f.transport.onSend = { f.transport.error(ErrorCode.UNSUPPORTED_COMMAND.rawValue.toInt()) }
            val failure = assertFailsWith<MeshCoreException.DeviceError> { f.session.signBounded(hex("aa")) }
            assertEquals(ErrorCode.UNSUPPORTED_COMMAND.rawValue, failure.code)
            f.session.stop()
        },
    )
}
