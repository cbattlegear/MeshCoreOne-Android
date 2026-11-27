// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.protocol.bytes.Bytes

/**
 * Topics the Prizm broker lets a claimed node publish to (contract "Broker login"): `meshcore/client/<pk>/packets` and
 * `meshcore/client/<pk>/rf`, with `<pk>` lowercase hex. There is no region segment (the server decides ND or MN from the
 * position) and no status or device topic: nothing else is allowed and nothing is subscribed.
 */
class ObserverTopics(nodeKey: Bytes) {
    init { require(nodeKey.size == NODE_KEY_BYTES) { "node key must be $NODE_KEY_BYTES bytes" } }

    val nodeKeyHex: String = nodeKey.hexString.lowercase()
    private val base = "meshcore/client/$nodeKeyHex"
    val packets: String = "$base/packets"
    val rf: String = "$base/rf"

    companion object { const val NODE_KEY_BYTES = 32 }
}
