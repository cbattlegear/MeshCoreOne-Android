// PortedFrom: MC1Services/Tests/MC1ServicesTests/Mocks/MockMeshCoreSession.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: recording role consumers delegate to the real session; no no-op or default-success mock enters production.
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import java.time.Instant
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

private class RecordingSession(private val real: MeshCoreSession) : MeshCoreSessionProtocol by real {
    data class MessageInvocation(val destination: Bytes, val text: String, val timestamp: Instant, val attempt: UByte)
    val messages = mutableListOf<MessageInvocation>()
    override suspend fun sendMessage(destination: Bytes, text: String, timestamp: Instant, attempt: UByte): MessageSentInfo {
        messages += MessageInvocation(destination, text, timestamp, attempt)
        return real.sendMessage(destination, text, timestamp, attempt)
    }
}

class SessionRoleConsumerTest {
    @TestFactory
    fun roleContracts() = listOf(
        nativeCase("source mock recording seam is exercised through a real role-backed session") {
            val f = fixture(); start(f)
            val recorder = RecordingSession(f.session)
            val role: MessagingSessionOps = recorder
            val key = filled(0x11, 32)
            val result = command(f, hex("02000080009265") + key.prefix(6) + Bytes.utf8("Hi"), sentPacket()) {
                role.sendMessage(key, "Hi", testEpoch)
            }
            assertEquals(hex("aabbccdd"), result.expectedAck)
            assertEquals(listOf(RecordingSession.MessageInvocation(key, "Hi", testEpoch, 0u)), recorder.messages)
            val broad: MeshCoreSessionProtocol = recorder
            assertEquals(f.session.currentSelfInfo, broad.currentSelfInfo)
            f.session.stop()
        },
        nativeCase("all source role interfaces are actual API consumers of the concrete component") {
            val f = fixture(); start(f)
            val configuration: ConfigurationSessionOps = f.session
            val advertising: AdvertisingSessionOps = f.session
            val channels: ChannelSessionOps = f.session
            val contacts: ContactSessionOps = f.session
            val messages: MessageFetchSessionOps = f.session
            val diagnostics: DiagnosticsSessionOps = f.session
            val remote: RemoteAccessSessionOps = f.session
            val events: SessionEventStreaming = f.session
            assertEquals(4018L, command(f, hex("14"), batteryPacket(4018)) { configuration.getBattery() }.level)
            assertEquals("> 1", command(f, raw(0x42, Bytes.utf8("00|get wifi.enabled")), raw(0x1d, Bytes.utf8("00|> 1"))) {
                configuration.runCorrelatedCliCommand("get wifi.enabled")
            })
            command(f, hex("07"), hex("00")) { advertising.sendAdvertisement() }
            assertEquals(0u.toUByte(), command(f, hex("1f00"), channelPacket(0)) { channels.getChannel(0u) }.index)
            assertNull(command(f, raw(0x1e, filled(1, 32)), hex("0102")) { contacts.getContact(filled(1, 32)) })
            assertEquals(com.meshcoreone.android.core.protocol.event.MessageResult.NoMoreMessages,
                command(f, hex("0a"), hex("0a")) { messages.getMessage() })
            assertFailsWith<com.meshcoreone.android.core.protocol.config.MeshCoreException.InvalidInput> { diagnostics.requestStatus(filled(1, 31)) }
            assertFailsWith<com.meshcoreone.android.core.protocol.config.MeshCoreException.InvalidInput> { remote.sendLogin(filled(1, 31), "password") }
            f.session.stop()
            assertTrue(events.events().toList().isEmpty())
        },
    )
}
