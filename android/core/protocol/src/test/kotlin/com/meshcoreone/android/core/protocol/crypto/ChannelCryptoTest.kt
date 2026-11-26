// PortedFrom: MeshCore/Tests/MeshCoreTests/ChannelCryptoTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.crypto

import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelCryptoTest {
    @Test
    fun `Decrypt success`() = CryptoFixtures.assertChannel(CryptoFixtures.message("channel-normal"))

    @Test
    fun `Decrypt wrong key`() {
        val vector = CryptoFixtures.message("channel-wrong-key")
        val wrongKey = Bytes.fromHex("00112233445566778899aabbccddeeff")
        assertEquals(ChannelCrypto.DecryptResult.HmacFailed, ChannelCrypto.decrypt(vector.packet, wrongKey))
    }

    @Test
    fun `Decrypt corrupted MAC`() {
        val vector = CryptoFixtures.message("channel-corrupted-mac")
        val payload = vector.packet.toByteArray()
        payload[0] = (payload[0].toInt() xor 0xff).toByte()
        payload[1] = (payload[1].toInt() xor 0xff).toByte()
        assertEquals(ChannelCrypto.DecryptResult.HmacFailed, ChannelCrypto.decrypt(Bytes(payload), vector.secret))
    }

    @Test
    fun `Decrypt payload too short`() {
        assertEquals(ChannelCrypto.DecryptResult.PayloadTooShort, ChannelCrypto.decrypt(
            Bytes.of(0, 1, 2, 3), CryptoFixtures.message("channel-normal").secret,
        ))
    }

    @Test
    fun `Decrypt empty message`() = CryptoFixtures.assertChannel(CryptoFixtures.message("channel-empty"))

    @Test
    fun `Decrypt long message`() = CryptoFixtures.assertChannel(CryptoFixtures.message("channel-long"))

    @Test
    fun `Decrypt unicode message`() = CryptoFixtures.assertChannel(CryptoFixtures.message("channel-unicode"))

    @Test
    fun constants() {
        assertEquals(2, ChannelCrypto.macSize)
        assertEquals(16, ChannelCrypto.keySize)
        assertEquals(4, ChannelCrypto.timestampSize)
        assertEquals(1, ChannelCrypto.txtTypeSize)
        assertEquals(5, ChannelCrypto.plaintextHeaderSize)
    }

    @Test
    fun `Decrypt with different txtTypes`() {
        for (type in 0..2) CryptoFixtures.assertChannel(CryptoFixtures.message("channel-type-$type"))
    }

    @Test
    fun `Decrypt with 32-byte secret uses first 16 bytes for AES`() {
        val vector = CryptoFixtures.message("channel-secret32")
        assertEquals(32, vector.secret.size)
        assertEquals(CryptoFixtures.message("channel-normal").secret, vector.secret.prefix(16))
        CryptoFixtures.assertChannel(vector)
        assertEquals(ChannelCrypto.DecryptResult.HmacFailed, ChannelCrypto.decrypt(vector.packet, vector.secret.prefix(16)))
    }
}
