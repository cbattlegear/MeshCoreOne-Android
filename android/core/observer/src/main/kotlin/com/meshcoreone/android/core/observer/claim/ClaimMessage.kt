// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.claim

import com.meshcoreone.android.core.protocol.bytes.Bytes

/** The exact bytes the node signs to prove key ownership: UTF-8 `prizm-claim:v1:<node_key hex>:<discord id>:<nonce hex>`. */
object ClaimMessage {
    const val PREFIX = "prizm-claim:v1:"
    const val NODE_KEY_BYTES = 32
    const val NONCE_BYTES = 32
    private const val MAX_DISCORD_ID_LENGTH = 20

    /** @throws IllegalArgumentException on a malformed key, id or nonce (never signs a malformed claim). */
    fun build(nodeKey: Bytes, discordUserId: String, nonce: Bytes): Bytes {
        require(nodeKey.size == NODE_KEY_BYTES) { "node key must be $NODE_KEY_BYTES bytes" }
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
        require(isDiscordSnowflake(discordUserId)) { "discord user id must be 1-$MAX_DISCORD_ID_LENGTH ASCII digits" }
        return Bytes.utf8("$PREFIX${nodeKey.hexString}:$discordUserId:${nonce.hexString}")
    }

    fun isDiscordSnowflake(value: String): Boolean =
        value.length in 1..MAX_DISCORD_ID_LENGTH && value.all { it in '0'..'9' }
}
