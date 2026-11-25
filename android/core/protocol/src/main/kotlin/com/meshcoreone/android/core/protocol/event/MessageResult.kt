// PortedFrom: MeshCore/Sources/MeshCore/Session/SessionConfiguration.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.event

sealed interface MessageResult {
    data class ContactMessage(val message: com.meshcoreone.android.core.protocol.event.ContactMessage) : MessageResult
    data class ChannelMessage(val message: com.meshcoreone.android.core.protocol.event.ChannelMessage) : MessageResult
    data class ChannelDatagram(val datagram: com.meshcoreone.android.core.protocol.event.ChannelDatagram) : MessageResult
    data object NoMoreMessages : MessageResult
}
