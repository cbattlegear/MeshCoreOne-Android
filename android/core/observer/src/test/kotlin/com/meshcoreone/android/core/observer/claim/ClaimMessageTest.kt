// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.claim

import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClaimMessageTest {
    private val key = Bytes(ByteArray(32) { it.toByte() })
    private val nonce = Bytes(ByteArray(32) { (0xf0 + it % 16).toByte() })

    @Test fun layoutIsExact() {
        val text = String(ClaimMessage.build(key, "123456789012345678", nonce).toByteArray(), Charsets.UTF_8)
        assertEquals(
            "prizm-claim:v1:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f:123456789012345678:" +
                "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff" + "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
            text,
        )
    }

    @Test fun realisticMessageNeedsExactlyTwoChunks() {
        val size = ClaimMessage.build(key, "123456789012345678", nonce).size
        assertTrue(size > 160 && size <= 320, "size=$size")
    }

    @Test fun rejectsMalformedInputs() {
        assertFailsWith<IllegalArgumentException> { ClaimMessage.build(Bytes(ByteArray(31)), "1", nonce) }
        assertFailsWith<IllegalArgumentException> { ClaimMessage.build(key, "1", Bytes(ByteArray(16))) }
        for (bad in listOf("", "12a", "1:2", "１２３", "123456789012345678901")) {
            assertFailsWith<IllegalArgumentException>(bad) { ClaimMessage.build(key, bad, nonce) }
        }
    }
}
