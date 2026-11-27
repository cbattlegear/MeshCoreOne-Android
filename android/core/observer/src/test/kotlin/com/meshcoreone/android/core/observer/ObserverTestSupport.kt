// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Independent re-implementation of the firmware scheme (key zero-padded to 32 bytes) for test vectors. */
object ObserverTestSupport {
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun hex(text: String): Bytes = Bytes.fromHex(text)

    fun hashtagKey(name: String): Bytes = Bytes(sha256(name.toByteArray()).copyOf(16))

    /** Channel payload [hash][mac:2][ciphertext] encrypted under [key] (AES-128-ECB, zero padded plaintext). */
    fun channelPayload(key: Bytes, text: String = "hello world"): Bytes {
        val plain = byteArrayOf(1, 0, 0, 0, 0) + text.toByteArray()
        val padded = plain.copyOf((plain.size + 15) / 16 * 16)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"))
        }
        val ciphertext = cipher.doFinal(padded)
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key.toByteArray().copyOf(32), "HmacSHA256"))
        }.doFinal(ciphertext).copyOf(2)
        return Bytes(byteArrayOf(sha256(key.toByteArray())[0]) + mac + ciphertext)
    }

    /** Raw over-the-air packet: header, optional transport codes, path, payload. */
    fun packet(
        route: Int,
        type: Int,
        payload: Bytes,
        pathLenByte: Int = 0,
        path: ByteArray = ByteArray(0),
        version: Int = 0,
    ): Bytes {
        val header = (version shl 6) or (type shl 2) or route
        val codes = if (route == 0 || route == 3) byteArrayOf(1, 2, 3, 4) else ByteArray(0)
        return Bytes(byteArrayOf(header.toByte()) + codes + byteArrayOf(pathLenByte.toByte()) + path + payload.toByteArray())
    }

    fun logFrame(snr4: Int, rssi: Int, raw: Bytes): Bytes =
        Bytes(byteArrayOf(0x88.toByte(), snr4.toByte(), rssi.toByte()) + raw.toByteArray())

    fun capture(raw: Bytes): ObservedPacket =
        (PacketCapture.fromRaw(raw, 5.0, -80) as CaptureResult.Captured).packet

    fun slot(index: Int, name: String, secret: Bytes) = ChannelInfo(index.toUByte(), name, secret)
}
