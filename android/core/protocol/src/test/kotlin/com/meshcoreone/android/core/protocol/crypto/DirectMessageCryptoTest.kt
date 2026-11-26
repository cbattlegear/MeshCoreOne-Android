// PortedFrom: MeshCore/Tests/MeshCoreTests/DirectMessageCryptoTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.crypto

import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals

class DirectMessageCryptoTest {
    @Test
    fun `Decrypt success`() = CryptoFixtures.assertDirect(CryptoFixtures.message("direct-normal"))

    @Test
    fun `Decrypt wrong key`() {
        val vector = CryptoFixtures.message("direct-wrong-key")
        assertEquals(DirectMessageCrypto.DecryptResult.MacMismatch, DirectMessageCrypto.decrypt(
            vector.packet, Bytes(ByteArray(32) { it.toByte() }), requireNotNull(vector.publicKey),
        ))
    }

    @Test
    fun `Decrypt corrupted MAC`() {
        val vector = CryptoFixtures.message("direct-corrupted-mac")
        val payload = vector.packet.toByteArray()
        payload[2] = (payload[2].toInt() xor 0xff).toByte()
        payload[3] = (payload[3].toInt() xor 0xff).toByte()
        assertEquals(DirectMessageCrypto.DecryptResult.MacMismatch, DirectMessageCrypto.decrypt(
            Bytes(payload), requireNotNull(vector.privateKey), requireNotNull(vector.publicKey),
        ))
    }

    @Test
    fun `Decrypt payload too short`() {
        val vector = CryptoFixtures.message("direct-normal")
        assertEquals(DirectMessageCrypto.DecryptResult.InvalidPayload, DirectMessageCrypto.decrypt(
            Bytes.of(0, 1, 2, 3), requireNotNull(vector.privateKey), requireNotNull(vector.publicKey),
        ))
    }

    @Test
    fun `Decrypt empty message`() = CryptoFixtures.assertDirect(CryptoFixtures.message("direct-empty"))

    @Test
    fun `Decrypt unicode message`() = CryptoFixtures.assertDirect(CryptoFixtures.message("direct-unicode"))

    @Test
    fun `Extract timestamp`() {
        val vector = CryptoFixtures.message("direct-extract")
        assertEquals(vector.timestamp, DirectMessageCrypto.extractTimestamp(
            vector.packet, requireNotNull(vector.privateKey), requireNotNull(vector.publicKey),
        ))
    }

    @Test
    fun constants() {
        assertEquals(2, DirectMessageCrypto.macSize)
        assertEquals(2, DirectMessageCrypto.headerSize)
        assertEquals(4, DirectMessageCrypto.timestampSize)
        assertEquals(1, DirectMessageCrypto.typeAttemptSize)
        assertEquals(16, DirectMessageCrypto.minCiphertextSize)
        assertEquals(20, DirectMessageCrypto.minPacketSize)
    }

    @Test
    fun `Invalid key length`() {
        assertEquals(DirectMessageCrypto.DecryptResult.KeyError, DirectMessageCrypto.decrypt(
            Bytes.EMPTY.paddedOrTruncated(24), Bytes.of(1, 2), Bytes.EMPTY.paddedOrTruncated(32),
        ))
    }
}
