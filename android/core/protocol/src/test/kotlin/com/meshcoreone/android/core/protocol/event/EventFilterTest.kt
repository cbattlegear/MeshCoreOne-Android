// PortedFrom: MeshCore/Tests/MeshCoreTests/Events/EventFilterAnyAcknowledgementTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Tests/MeshCoreTests/Events/EventFilterFactoryTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/Events/EventFilter.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.event

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun contactMessage(prefix: Bytes = Bytes.of(1, 2)): ContactMessage =
    ContactMessage(prefix, 0u, 0u, Instant.EPOCH, null, "hello", null)

internal fun channelMessage(channel: UByte = 3u): ChannelMessage =
    ChannelMessage(channel, 0u, 0u, Instant.EPOCH, "hi", null)

class EventFilterTest {
    @Test
    fun `Any acknowledgement matches regardless of code`() {
        assertTrue(EventFilter.anyAcknowledgement.matches(MeshEvent.Acknowledgement(Bytes.of(1, 2, 3, 4), 100u)))
        assertTrue(EventFilter.anyAcknowledgement.matches(MeshEvent.Acknowledgement(Bytes.of(255, 238, 221, 204))))
    }

    @Test
    fun `Any acknowledgement rejects all unrelated cases`() {
        assertFalse(EventFilter.anyAcknowledgement.matches(MeshEvent.Ok(null)))
        assertFalse(EventFilter.anyAcknowledgement.matches(MeshEvent.Error(42u)))
        assertFalse(EventFilter.anyAcknowledgement.matches(MeshEvent.Advertisement(Bytes.of(0xaa))))
    }

    @Test
    fun `RF log filter matches only RF logs`() {
        val log = ParsedRxLogData(
            6.0, -72, Bytes.of(0), RouteType.FLOOD, PayloadType.TEXT_MESSAGE,
            0u, 2u, null, 0u, emptyList(), Bytes.EMPTY,
        )
        assertTrue(EventFilter.rxLogData.matches(MeshEvent.RxLogData(log)))
        assertFalse(EventFilter.rxLogData.matches(MeshEvent.Advertisement(Bytes.of(0xaa))))
        assertFalse(EventFilter.rxLogData.matches(MeshEvent.Ok(null)))
    }

    @Test
    fun `Any advertisement matches all senders and rejects unrelated events`() {
        assertTrue(EventFilter.anyAdvertisement.matches(MeshEvent.Advertisement(Bytes.of(0xaa, 0xbb))))
        assertTrue(EventFilter.anyAdvertisement.matches(MeshEvent.Advertisement(Bytes.of(0xff))))
        assertFalse(EventFilter.anyAdvertisement.matches(MeshEvent.Ok(null)))
        assertFalse(EventFilter.anyAdvertisement.matches(MeshEvent.Error(1u)))
    }

    @Test
    fun `Any contact message matches only direct receipts`() {
        assertTrue(EventFilter.anyContactMessage.matches(MeshEvent.ContactMessageReceived(contactMessage())))
        assertFalse(EventFilter.anyContactMessage.matches(MeshEvent.Advertisement(Bytes.of(0xaa))))
        assertFalse(EventFilter.anyContactMessage.matches(MeshEvent.Ok(null)))
    }

    @Test
    fun `Any channel message matches only channel text receipts`() {
        assertTrue(EventFilter.anyChannelMessage.matches(MeshEvent.ChannelMessageReceived(channelMessage())))
        assertFalse(EventFilter.anyChannelMessage.matches(MeshEvent.Advertisement(Bytes.of(0xaa))))
        assertFalse(EventFilter.anyChannelMessage.matches(MeshEvent.NoMoreMessages))
        assertFalse(EventFilter.anyChannelMessage.matches(MeshEvent.ChannelDataReceived(
            ChannelDatagram(3u, 0u, 0xffffu, Bytes.EMPTY, 1.0),
        )))
    }

    @Test
    fun `Any login success matches only successful login`() {
        assertTrue(EventFilter.anyLoginSuccess.matches(MeshEvent.LoginSuccess(LoginInfo(1u, false, Bytes.of(1)))))
        assertFalse(EventFilter.anyLoginSuccess.matches(MeshEvent.LoginFailed(null)))
        assertFalse(EventFilter.anyLoginSuccess.matches(MeshEvent.Ok(null)))
    }

    @Test
    fun `Any login failure matches absent and present prefix only`() {
        assertTrue(EventFilter.anyLoginFailed.matches(MeshEvent.LoginFailed(Bytes.of(0xaa, 0xbb))))
        assertTrue(EventFilter.anyLoginFailed.matches(MeshEvent.LoginFailed(null)))
        assertFalse(EventFilter.anyLoginFailed.matches(MeshEvent.LoginSuccess(LoginInfo(0u, false, Bytes.of(1)))))
        assertFalse(EventFilter.anyLoginFailed.matches(MeshEvent.Ok(null)))
    }

    @Test
    fun `Acknowledgement code matching is exact and not prefix or object identity`() {
        val filter = EventFilter.acknowledgement(Bytes.of(1, 2))
        assertTrue(filter.matches(MeshEvent.Acknowledgement(Bytes.of(1, 2))))
        assertFalse(filter.matches(MeshEvent.Acknowledgement(Bytes.of(1, 2, 3))))
        assertFalse(filter.matches(MeshEvent.Acknowledgement(Bytes.of(1))))
        assertFalse(filter.matches(MeshEvent.ContactDeleted(Bytes.of(1, 2))))
    }

    @Test
    fun `Contact prefix matching preserves source starts-with behavior including empty prefix`() {
        val event = MeshEvent.ContactMessageReceived(contactMessage(Bytes.of(0xff, 0x80, 1)))
        assertTrue(EventFilter.contactMessage(Bytes.of(0xff, 0x80)).matches(event))
        assertTrue(EventFilter.contactMessage(Bytes.EMPTY).matches(event))
        assertFalse(EventFilter.contactMessage(Bytes.of(0xff, 0x80, 1, 2)).matches(event))
        assertFalse(EventFilter.contactMessage(Bytes.of(0xff, 0x81)).matches(event))
        assertFalse(EventFilter.contactMessage(Bytes.EMPTY).matches(MeshEvent.Ok(null)))
    }

    @Test
    fun `Channel index matching includes the full unsigned byte range`() {
        for (channel in 0..255) {
            val event = MeshEvent.ChannelMessageReceived(channelMessage(channel.toUByte()))
            assertTrue(EventFilter.channelMessage(channel.toUByte()).matches(event))
            assertFalse(EventFilter.channelMessage((channel xor 1).toUByte()).matches(event))
        }
    }

    @Test
    fun `Advertisement and path update filters preserve prefix and event type`() {
        val key = Bytes.of(0xff, 0x80, 1)
        assertTrue(EventFilter.advertisement(key.prefix(2)).matches(MeshEvent.Advertisement(key)))
        assertTrue(EventFilter.pathUpdate(key.prefix(2)).matches(MeshEvent.PathUpdate(key)))
        assertFalse(EventFilter.pathUpdate(key).matches(MeshEvent.Advertisement(key)))
        assertFalse(EventFilter.advertisement(Bytes.of(2)).matches(MeshEvent.Advertisement(key)))
        assertTrue(EventFilter.advertisement(Bytes.EMPTY).matches(MeshEvent.Advertisement(Bytes.EMPTY)))
    }

    @Test
    fun `Telemetry prefix filter does not require successful LPP parsing`() {
        val event = MeshEvent.TelemetryResponse(TelemetryResponse(Bytes.of(1, 2), null, Bytes.of(0xff)))
        assertTrue(EventFilter.telemetryResponse(Bytes.of(1)).matches(event))
        assertTrue(EventFilter.telemetryResponse(Bytes.EMPTY).matches(event))
        assertFalse(EventFilter.telemetryResponse(Bytes.of(2)).matches(event))
        assertFalse(EventFilter.telemetryResponse(Bytes.EMPTY).matches(MeshEvent.Ok(null)))
    }

    @Test
    fun `Status response prefix matching uses original prefix without arbitrary padding`() {
        val event = MeshEvent.StatusResponse(statusResponse(key = Bytes.of(1, 2, 3)))
        assertTrue(EventFilter.statusResponse(Bytes.of(1, 2)).matches(event))
        assertTrue(EventFilter.statusResponse(Bytes.EMPTY).matches(event))
        assertFalse(EventFilter.statusResponse(Bytes.of(1, 2, 3, 4)).matches(event))
        assertFalse(EventFilter.statusResponse(Bytes.EMPTY).matches(MeshEvent.Ok(null)))
    }

    @Test
    fun `Response and indicator filters never mix event variants`() {
        assertTrue(EventFilter.ok.matches(MeshEvent.Ok(0u)))
        assertTrue(EventFilter.error.matches(MeshEvent.Error(null)))
        assertTrue(EventFilter.noMoreMessages.matches(MeshEvent.NoMoreMessages))
        assertTrue(EventFilter.messagesWaiting.matches(MeshEvent.MessagesWaiting))
        assertFalse(EventFilter.ok.matches(MeshEvent.Error(null)))
        assertFalse(EventFilter.error.matches(MeshEvent.Ok(null)))
        assertFalse(EventFilter.noMoreMessages.matches(MeshEvent.MessagesWaiting))
        assertFalse(EventFilter.messagesWaiting.matches(MeshEvent.NoMoreMessages))
    }

    @Test
    fun `Combinators preserve short-circuit and negation`() {
        var calls = 0
        val probe = EventFilter { calls++; true }
        assertTrue((EventFilter.ok or probe).matches(MeshEvent.Ok(null)))
        assertFalse((EventFilter.error and probe).matches(MeshEvent.Ok(null)))
        kotlin.test.assertEquals(0, calls)
        assertTrue(EventFilter.error.negated.matches(MeshEvent.Ok(null)))
        assertFalse(EventFilter.ok.negated.matches(MeshEvent.Ok(null)))
        assertTrue(EventFilter.eventType { it is MeshEvent.Signature }.matches(MeshEvent.Signature(Bytes.EMPTY)))
    }
}
