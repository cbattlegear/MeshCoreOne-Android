// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.protocol.bytes.Bytes

/**
 * Topic layout `meshcore/<region>/mobile/<node_key>/...`. [region] is `nd` or `mn` for now (a plain parameter).
 * OPEN QUESTION: node_key is rendered as lowercase hex; MQTT topics are case sensitive, so confirm with Prizm.
 */
class ObserverTopics(val region: String, nodeKey: Bytes) {
    init {
        require(region.isNotEmpty() && region.all { it in 'a'..'z' || it in '0'..'9' }) { "invalid region segment" }
        require(nodeKey.size == NODE_KEY_BYTES) { "node key must be $NODE_KEY_BYTES bytes" }
    }

    val nodeKeyHex: String = nodeKey.hexString
    private val base = "meshcore/$region/mobile/$nodeKeyHex"
    val packets: String = "$base/packets"
    val status: String = "$base/status"
    val device: String = "$base/device"
    val health: String = "$base/health"

    companion object {
        const val REGION_NORTH_DAKOTA = "nd"
        const val REGION_MINNESOTA = "mn"
        const val NODE_KEY_BYTES = 32
    }
}
