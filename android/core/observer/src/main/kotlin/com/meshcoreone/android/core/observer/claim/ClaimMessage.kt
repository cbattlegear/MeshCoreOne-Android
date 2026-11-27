// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.claim

import com.meshcoreone.android.core.protocol.bytes.Bytes

/**
 * The claim message is built by the server (`prizm-claim:v1:<pk>:<discord id>:<nonce>`, it knows the Discord id) and signed
 * by the node exactly as returned. The app never builds it. Because the node signs arbitrary bytes, [validate] refuses to sign
 * anything that is not that exact shape for this node and this nonce (for example a broker token or any other text a faulty or hostile
 * server might send).
 */
object ClaimMessage {
    const val PREFIX = "prizm-claim:v1:"
    private const val MAX_LENGTH = 320
    private val DISCORD_ID = Regex("^[0-9]{1,32}$")
    private val NONCE = Regex("^[A-Za-z0-9_-]{8,128}$")

    fun isValidNonce(nonce: String): Boolean = NONCE.matches(nonce)

    /** True only for `prizm-claim:v1:<this node's pk>:<digits>:<this nonce>` with nothing before or after. */
    fun validate(message: String, nodeKey: Bytes, nonce: String): Boolean {
        if (message.length > MAX_LENGTH || !isValidNonce(nonce)) return false
        val prefix = PREFIX + nodeKey.hexString.lowercase() + ":"
        if (!message.startsWith(prefix, ignoreCase = false) || !message.endsWith(":$nonce")) return false
        val id = message.substring(prefix.length, message.length - nonce.length - 1)
        return DISCORD_ID.matches(id)
    }

    /** The bytes the node signs: the UTF-8 of [message], untouched. */
    fun bytes(message: String): Bytes = Bytes.utf8(message)
}
