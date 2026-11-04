// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChannelServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ChannelSyncError
import com.meshcoreone.android.core.contracts.domain.ChannelSyncErrorType
import com.meshcoreone.android.core.contracts.domain.ChannelSyncResult
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportError
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportException
import java.net.URI
import java.net.URLDecoder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ChannelServiceTest {
    @TestFactory
    fun secretsAndURIs(): List<DynamicTest> = channelsCases(
        SUITE,
        "hashSecret produces 16-byte output" to {
            assertEquals(ProtocolLimits.CHANNEL_SECRET_SIZE, ChannelService.hashSecret("test passphrase").size)
        },
        "hashSecret is deterministic" to {
            assertEquals(ChannelService.hashSecret("same passphrase"), ChannelService.hashSecret("same passphrase"))
        },
        "hashSecret differs for different inputs" to {
            assertNotEquals(ChannelService.hashSecret("passphrase one"), ChannelService.hashSecret("passphrase two"))
        },
        "hashSecret handles empty string" to {
            val secret = ChannelService.hashSecret("")
            assertEquals(ProtocolLimits.CHANNEL_SECRET_SIZE, secret.size)
            assertEquals(Bytes(ByteArray(ProtocolLimits.CHANNEL_SECRET_SIZE)), secret)
        },
        "hashSecret handles unicode" to {
            assertEquals(ProtocolLimits.CHANNEL_SECRET_SIZE, ChannelService.hashSecret("🔐 secure 密码").size)
        },
        "hashSecret matches golden hashtag channel vectors" to {
            listOf("#test" to "9cd8fcf22a47333b591d96a2b848b73f", "#avion-testing2" to "3976fbac9120f147576900ac90d41dd2")
                .forEach { (passphrase, hex) -> assertEquals(hex, ChannelService.hashSecret(passphrase).hexString, "hash mismatch for $passphrase") }
        },
        "exportChannelURI emits the well-known Public channel secret" to {
            val uri = ChannelService.exportChannelURI("Public", PUBLIC_SECRET)
            assertEquals("Public", queryValue(uri, "name"))
            assertEquals("8B3387E9C5CDEA6AC9E5EDBAA115CD72", queryValue(uri, "secret"))
        },
        "exportChannelURI always emits name and secret via URLComponents" to {
            val secret = Bytes.of(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF)
            val uri = ChannelService.exportChannelURI("a&b=c", secret)
            val parsed = URI(uri)
            assertEquals("meshcore", parsed.scheme)
            assertEquals("channel", parsed.host)
            assertEquals("/add", parsed.path)
            assertEquals("a&b=c", queryValue(uri, "name"))
            assertEquals(secret.uppercaseHexString(), queryValue(uri, "secret"))
            assertNull(queryValue(uri, "region_scope"))
        },
        "exportChannelURI emits region_scope only for region flood scope" to {
            val secret = channelsSecret(0xAB)
            val withRegion = ChannelService.exportChannelURI("#test", secret, ChannelFloodScope.Region("testregion"))
            assertEquals("testregion", queryValue(withRegion, "region_scope"))
            assertNull(queryValue(ChannelService.exportChannelURI("#test", secret, ChannelFloodScope.Inherit), "region_scope"))
            assertNull(queryValue(ChannelService.exportChannelURI("#test", secret, ChannelFloodScope.AllRegions), "region_scope"))
        },
        "validateSecret accepts 16-byte secrets" to {
            assertTrue(ChannelService.validateSecret(channelsSecret(0xAB)))
        },
        "validateSecret rejects wrong-sized secrets" to {
            assertFalse(ChannelService.validateSecret(Bytes(ByteArray(15) { 0xAB.toByte() })))
            assertFalse(ChannelService.validateSecret(Bytes(ByteArray(17) { 0xAB.toByte() })))
        },
    )

    @TestFactory
    fun syncErrorsAndResults(): List<DynamicTest> = channelsCases(
        SUITE,
        "ChannelSyncError timeout is retryable" to {
            assertTrue(ChannelSyncError(0u, ChannelSyncErrorType.Timeout, "Timeout").isRetryable)
        },
        "ChannelSyncError send timeout is retryable and counted separately" to {
            val error = ChannelSyncError(0u, ChannelSyncErrorType.SendTimeout, "Send timed out")
            val result = ChannelSyncResult(0, SnapshotList.of(error))
            assertTrue(error.isRetryable)
            assertEquals(0, result.requestTimeoutCount)
            assertEquals(1, result.sendTimeoutCount)
        },
        "ChannelSyncError circuit breaker is not retryable" to {
            assertFalse(ChannelSyncError(0u, ChannelSyncErrorType.CircuitBreaker, "Circuit open").isRetryable)
        },
        "ChannelSyncError deviceError is not retryable" to {
            assertFalse(ChannelSyncError(0u, ChannelSyncErrorType.DeviceError(0x02u), "Not found").isRetryable)
        },
        "ChannelSyncError databaseError is not retryable" to {
            assertFalse(ChannelSyncError(0u, ChannelSyncErrorType.DatabaseError, "Save failed").isRetryable)
        },
        "ChannelSyncError unknown is not retryable" to {
            assertFalse(ChannelSyncError(0u, ChannelSyncErrorType.Unknown, "Unknown error").isRetryable)
        },
        "ChannelSyncResult isComplete when no errors" to {
            assertTrue(ChannelSyncResult(8, SnapshotList.empty()).isComplete)
        },
        "ChannelSyncResult is not complete with errors" to {
            assertFalse(ChannelSyncResult(7, SnapshotList.of(ChannelSyncError(3u, ChannelSyncErrorType.Timeout, "Timeout"))).isComplete)
        },
        "ChannelSyncResult retryableIndices filters correctly" to {
            val result = ChannelSyncResult(
                5,
                SnapshotList.of(
                    ChannelSyncError(1u, ChannelSyncErrorType.Timeout, "Timeout"),
                    ChannelSyncError(2u, ChannelSyncErrorType.DeviceError(0x02u), "Not found"),
                    ChannelSyncError(5u, ChannelSyncErrorType.Timeout, "Timeout"),
                ),
            )
            assertEquals(listOf<UByte>(1u, 5u), result.retryableIndices.toList())
        },
        "ChannelService aborts early when transport send timeouts cascade" to {
            val radioId = channelsRadioId()
            val store = ChannelsInMemoryStore()
            val transport = ChannelsSendTimeoutTransport()
            // The protocol session must be started before any exchange; the source mock session
            // accepted unstarted sends. Every send after appStart throws a send timeout.
            val session = channelsStartedSession(
                transport, { transport.totalSends }, transport::answer,
                SessionConfiguration(defaultTimeout = 5.0, clientIdentifier = "MCTst"),
            )
            try {
                val service = channelServiceFor(session, store, radioId, clock = ChannelsRecordingClock())
                val result = service.syncChannels(radioId, 6u, usePipelinedRead = false)
                assertEquals(3, result.sendTimeoutCount)
                assertTrue(result.circuitBreakerAborted)
                assertEquals(3, transport.channelSendCount)
            } finally {
                session.stop()
            }
        },
        "ChannelSyncResult retryableIndices empty when no retryable errors" to {
            val result = ChannelSyncResult(
                6,
                SnapshotList.of(
                    ChannelSyncError(2u, ChannelSyncErrorType.DeviceError(0x02u), "Not found"),
                    ChannelSyncError(3u, ChannelSyncErrorType.DatabaseError, "Save failed"),
                ),
            )
            assertTrue(result.retryableIndices.isEmpty())
        },
        "isChannelConfigured returns true for empty name with non-zero secret" to {
            assertTrue(ChannelService.isChannelConfigured("", channelsSecret(0x42)))
        },
        "isChannelConfigured returns false for empty name with zero secret" to {
            assertFalse(ChannelService.isChannelConfigured("", channelsSecret(0)))
        },
        "isChannelConfigured returns true for named zero-secret channel" to {
            assertTrue(ChannelService.isChannelConfigured("Public", channelsSecret(0)))
        },
    )

    @TestFactory
    fun slotOccupantHandler(): List<DynamicTest> = channelsCases(
        SUITE,
        "clearChannel deletes the row and its messages, then fires the slot handler" to {
            val fixture = SlotFixture()
            val radioId = fixture.radioId
            fixture.store.saveChannel(radioId, ChannelInfo(5u, "Old", channelsSecret(0x11)))
            fixture.store.saveTestMessage(radioId, 5u, "old")

            fixture.service.clearChannel(radioId, 5u)

            assertNull(fixture.store.fetchChannel(radioId, 5u))
            assertTrue(fixture.store.fetchMessages(radioId, 5u).isEmpty())
            assertEquals(listOf(setOf<UByte>(5u)), fixture.capture.received)
        },
        "setChannel fires the slot handler on a new secret and not on a same-secret rename or first insert" to {
            val fixture = SlotFixture()
            val radioId = fixture.radioId
            fixture.service.setChannel(radioId, 5u, "First", "one")
            fixture.service.setChannel(radioId, 5u, "Renamed", "one")
            assertTrue(fixture.capture.received.isEmpty())

            fixture.service.setChannel(radioId, 5u, "Replaced", "two")
            assertEquals(listOf(setOf<UByte>(5u)), fixture.capture.received)
        },
        "syncChannels fires the slot handler for vacated and secret-changed slots, not first sightings" to {
            val fixture = SlotFixture()
            val radioId = fixture.radioId
            fixture.store.saveChannel(radioId, ChannelInfo(1u, "One", channelsSecret(0x11)))
            fixture.store.saveChannel(radioId, ChannelInfo(2u, "Two", channelsSecret(0x22)))
            fixture.session.setStubbedChannels(
                mapOf(1.toUByte() to ChannelInfo(1u, "OneB", channelsSecret(0x99)), 3.toUByte() to ChannelInfo(3u, "First", channelsSecret(0x33))),
            )

            fixture.service.syncChannels(radioId, 4u, usePipelinedRead = false)

            assertEquals(listOf(setOf<UByte>(1u, 2u)), fixture.capture.received)
        },
    )

    private class SlotFixture {
        val radioId = channelsRadioId()
        val store = ChannelsInMemoryStore()
        val session = ChannelsMockSession()
        val capture = ChannelsSlotCapture()
        val service = channelServiceFor(session, store, radioId, clock = ChannelsRecordingClock()).also {
            it.setSlotOccupantChangedHandler(capture.handler)
        }
    }

    private companion object {
        const val SUITE = "ChannelServiceTests"
        val PUBLIC_SECRET = Bytes.of(0x8B, 0x33, 0x87, 0xE9, 0xC5, 0xCD, 0xEA, 0x6A, 0xC9, 0xE5, 0xED, 0xBA, 0xA1, 0x15, 0xCD, 0x72)

        /** `URLComponents(string:).queryItems` lookup: first item with [name], percent-decoded. */
        fun queryValue(uri: String, name: String): String? = URI(uri).rawQuery.split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .firstOrNull { URLDecoder.decode(it.first, Charsets.UTF_8) == name }
            ?.let { URLDecoder.decode(it.second, Charsets.UTF_8) }
    }
}

/**
 * Source `SendTimeoutTransport`: accepts the session's appStart, then every send throws the
 * WiFi transport's send timeout and is counted.
 */
private class ChannelsSendTimeoutTransport : MeshTransport {
    private val lock = Any()
    private val incoming = Channel<Bytes>(Channel.UNLIMITED)
    private var sends = 0
    private var connected = true

    val totalSends: Int get() = synchronized(lock) { sends }
    val channelSendCount: Int get() = synchronized(lock) { sends - 1 }

    suspend fun answer(data: Bytes) { incoming.send(data) }

    override suspend fun connect() = Unit
    override suspend fun disconnect() {
        synchronized(lock) { connected = false }
        incoming.close()
    }
    override suspend fun isConnected(): Boolean = synchronized(lock) { connected }
    override suspend fun receivedData(): Flow<Bytes> = incoming.receiveAsFlow()
    override suspend fun send(data: Bytes) {
        val first = synchronized(lock) { sends += 1; sends == 1 }
        if (!first) throw WiFiTransportException(WiFiTransportError.SendTimeout)
    }
}
