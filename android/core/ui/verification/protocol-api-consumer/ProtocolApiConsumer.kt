// AndroidOnly: WP-304 Dependency-isolated Kotlin compile fixture; explicit imports replace Swift namespace re-export.
package com.meshcoreone.android.verification

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.parser.PacketParser

object ProtocolApiConsumer {
    @JvmStatic fun request(): ByteArray = PacketBuilder.getBattery().toByteArray()
    @JvmStatic fun decode(): MeshEvent = PacketParser.parse(Bytes.of(0))
}
