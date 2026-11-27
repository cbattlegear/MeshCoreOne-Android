// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.claim

import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaimMessageTest {
    private val key = Bytes(ByteArray(32) { it.toByte() })
    private val pk = key.hexString
    private val nonce = "AbCdEf0123456789_-AbCdEf0123456789"
    private val good = "prizm-claim:v1:$pk:123456789012345678:$nonce"

    @Test fun acceptsTheServersShapeForThisNodeAndNonce() = assertTrue(ClaimMessage.validate(good, key, nonce))

    @Test fun uppercaseKeyOrAnotherNodeOrAnotherNonceIsRefused() {
        assertFalse(ClaimMessage.validate(good.replace(pk, pk.uppercase()), key, nonce))
        assertFalse(ClaimMessage.validate(good, Bytes(ByteArray(32) { 9 }), nonce))
        assertFalse(ClaimMessage.validate(good, key, nonce.reversed()))
    }

    @Test fun anythingElseTheNodeCouldBeAskedToSignIsRefused() {
        val token = "eyJhbGciOiJFZDI1NTE5IiwidHlwIjoiSldUIn0.eyJwdWJsaWNLZXkiOiJ4In0"
        for (bad in listOf(
            token, "", "prizm-claim:v2:$pk:1:$nonce", "xprizm-claim:v1:$pk:1:$nonce", "$good\n", "$good ", " $good",
            "prizm-claim:v1:$pk::$nonce", "prizm-claim:v1:$pk:12a:$nonce", "prizm-claim:v1:$pk:1:2:$nonce",
            "prizm-claim:v1:$pk:${"9".repeat(33)}:$nonce", "prizm-claim:v1:$pk:1:$nonce:extra", good + "x".repeat(400),
        )) assertFalse(ClaimMessage.validate(bad, key, nonce), bad)
    }

    @Test fun weakNoncesAreRefused() {
        for (n in listOf("", "short", "has space 12345678", "semi;colon1234567", "a".repeat(129))) {
            assertFalse(ClaimMessage.validate("prizm-claim:v1:$pk:1:$n", key, n), n)
        }
    }

    @Test fun signedBytesAreTheUtf8OfTheMessageUntouched() {
        assertEquals(good, String(ClaimMessage.bytes(good).toByteArray(), Charsets.UTF_8))
        assertTrue(ClaimMessage.bytes(good).size > 100)
    }
}
