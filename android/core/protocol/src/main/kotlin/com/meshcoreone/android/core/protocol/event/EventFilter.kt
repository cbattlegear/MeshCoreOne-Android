// PortedFrom: MeshCore/Sources/MeshCore/Events/EventFilter.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.event

import com.meshcoreone.android.core.protocol.bytes.Bytes

class EventFilter(private val predicate: (MeshEvent) -> Boolean) {
    fun matches(event: MeshEvent): Boolean = predicate(event)
    infix fun or(other: EventFilter): EventFilter = EventFilter { matches(it) || other.matches(it) }
    infix fun and(other: EventFilter): EventFilter = EventFilter { matches(it) && other.matches(it) }
    val negated: EventFilter get() = EventFilter { !matches(it) }

    companion object {
        val anyAcknowledgement = EventFilter { it is MeshEvent.Acknowledgement }
        val anyContactMessage = EventFilter { it is MeshEvent.ContactMessageReceived }
        val anyChannelMessage = EventFilter { it is MeshEvent.ChannelMessageReceived }
        val rxLogData = EventFilter { it is MeshEvent.RxLogData }
        val anyAdvertisement = EventFilter { it is MeshEvent.Advertisement }
        val ok = EventFilter { it is MeshEvent.Ok }
        val error = EventFilter { it is MeshEvent.Error }
        val noMoreMessages = EventFilter { it is MeshEvent.NoMoreMessages }
        val messagesWaiting = EventFilter { it is MeshEvent.MessagesWaiting }
        val anyLoginSuccess = EventFilter { it is MeshEvent.LoginSuccess }
        val anyLoginFailed = EventFilter { it is MeshEvent.LoginFailed }

        fun acknowledgement(code: Bytes): EventFilter =
            EventFilter { it is MeshEvent.Acknowledgement && it.code == code }

        fun contactMessage(publicKeyPrefix: Bytes): EventFilter = EventFilter {
            it is MeshEvent.ContactMessageReceived && it.message.senderPublicKeyPrefix.startsWith(publicKeyPrefix)
        }

        fun channelMessage(channel: UByte): EventFilter =
            EventFilter { it is MeshEvent.ChannelMessageReceived && it.message.channelIndex == channel }

        fun statusResponse(publicKeyPrefix: Bytes): EventFilter = EventFilter {
            it is MeshEvent.StatusResponse && it.response.publicKeyPrefix.startsWith(publicKeyPrefix)
        }

        fun telemetryResponse(publicKeyPrefix: Bytes): EventFilter = EventFilter {
            it is MeshEvent.TelemetryResponse && it.response.publicKeyPrefix.startsWith(publicKeyPrefix)
        }

        fun advertisement(publicKeyPrefix: Bytes): EventFilter = EventFilter {
            it is MeshEvent.Advertisement && it.publicKey.startsWith(publicKeyPrefix)
        }

        fun pathUpdate(publicKeyPrefix: Bytes): EventFilter = EventFilter {
            it is MeshEvent.PathUpdate && it.publicKey.startsWith(publicKeyPrefix)
        }

        fun eventType(matcher: (MeshEvent) -> Boolean): EventFilter = EventFilter(matcher)
        private fun Bytes.startsWith(prefix: Bytes): Boolean = this.prefix(prefix.size) == prefix
    }
}
