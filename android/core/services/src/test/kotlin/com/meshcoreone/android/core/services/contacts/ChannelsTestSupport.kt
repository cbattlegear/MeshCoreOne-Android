// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChannelServicePipelineTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DynamicTest

/** Hard ceiling per case so a regression hangs neither the suite nor CI. */
private val CHANNELS_CASE_TIMEOUT = 60.seconds

internal typealias ChannelsCaseBody = suspend CoroutineScope.() -> Unit

/** One dynamic test per source case, named `Suite::name()` as in the frozen Swift sources. */
internal fun channelsCases(suite: String, vararg cases: Pair<String, ChannelsCaseBody>): List<DynamicTest> =
    cases.map { (name, body) -> channelsCase("$suite::$name()", body) }

/** A source parameterized case expanded to one dynamic test per argument. */
internal fun <T> channelsParameterized(id: String, arguments: List<T>, body: suspend CoroutineScope.(T) -> Unit): List<DynamicTest> =
    arguments.mapIndexed { position, argument -> channelsCase("$id[${position + 1}] $argument") { body(argument) } }

/** Android-native cases for cancellation, concurrency and platform deviations. */
internal fun channelsNativeCases(vararg cases: Pair<String, ChannelsCaseBody>): List<DynamicTest> =
    cases.map { (name, body) -> channelsCase("WP-209::$name", body) }

private fun channelsCase(name: String, body: ChannelsCaseBody): DynamicTest =
    DynamicTest.dynamicTest(name) { runBlocking { withTimeout(CHANNELS_CASE_TIMEOUT) { body() } } }

internal fun channelsRadioId(): RadioId = RadioId(UUID.randomUUID())

internal fun channelsSecret(byte: Int): Bytes = Bytes(ByteArray(16) { byte.toByte() })

/** Records requested sleeps and returns immediately. */
internal class ChannelsRecordingClock : ChannelServiceClock {
    private val lock = Any()
    private var recorded: List<Duration> = emptyList()
    val sleeps: List<Duration> get() = synchronized(lock) { recorded }
    override suspend fun sleep(duration: Duration) = synchronized(lock) { recorded = recorded + duration }
}

/** Records slot-occupant-changed notifications in order. */
internal class ChannelsSlotCapture {
    private val lock = Any()
    private var calls: List<Set<UByte>> = emptyList()
    val received: List<Set<UByte>> get() = synchronized(lock) { calls }
    val handler: SlotOccupantChangedHandler = { _, indices -> synchronized(lock) { calls = calls + setOf(indices.toSet()) } }
}

/** Records every channel list forwarded to the decryption cache. */
internal class ChannelsDecryptionCapture : ChannelDecryptionCache {
    private val lock = Any()
    private var updates: List<List<UByte>> = emptyList()
    val indexUpdates: List<List<UByte>> get() = synchronized(lock) { updates }
    override suspend fun updateChannels(channels: List<com.meshcoreone.android.core.model.ChannelDTO>) =
        synchronized(lock) { updates = updates + listOf(channels.map { it.index }) }
}

/** Polls [condition] until true, failing with [message] after [timeout]. */
internal suspend fun channelsWaitUntil(message: String, timeout: Duration = 10.seconds, condition: suspend () -> Boolean) {
    val start = TimeSource.Monotonic.markNow()
    while (!condition()) {
        check(start.elapsedNow() < timeout) { message }
        delay(5)
    }
}

/**
 * Generous timeouts keep appStart and acknowledged reads from flaking under parallel load; the
 * idle timeout is above the gap between back-to-back simulated responses but short enough that
 * dropped-write tests wait on the watchdog rather than hanging.
 */
internal val CHANNELS_PIPELINE_CONFIGURATION = SessionConfiguration(
    defaultTimeout = 30.0,
    clientIdentifier = "MCTst",
    channelPipelineWindow = 8,
    channelPipelineIdleTimeout = 2.0,
    channelPipelineHardTimeout = 30.0,
    channelPipelinePostDrainGrace = 0.2,
)

/** Starts a real session, answering its appStart through [answer] once [sentCount] reaches 1. */
internal suspend fun CoroutineScope.channelsStartedSession(
    transport: MeshTransport,
    sentCount: suspend () -> Int,
    answer: suspend (Bytes) -> Unit,
    configuration: SessionConfiguration = CHANNELS_PIPELINE_CONFIGURATION,
): MeshCoreSession {
    val session = MeshCoreSession(transport, configuration)
    val start = async { session.start() }
    channelsWaitUntil("session should send app start") { sentCount() == 1 }
    answer(channelsSelfInfoPacket())
    start.await()
    return session
}

/** Source `makeSelfInfoPacket()` bytes, verbatim. */
internal fun channelsSelfInfoPacket(): Bytes {
    val out = ByteArrayOutputStream()
    out.write(ResponseCode.SELF_INFO.rawValue.toInt())
    out.write(1); out.write(22); out.write(22)
    out.write(ByteArray(32) { 0x01 })
    out.write(littleEndian32(0)); out.write(littleEndian32(0))
    out.write(0); out.write(0); out.write(0)
    out.write(littleEndian32(915_000)); out.write(littleEndian32(125_000))
    out.write(7); out.write(5)
    out.write("Test".toByteArray(Charsets.UTF_8))
    return Bytes(out.toByteArray())
}

/** Source `makeChannelInfoPacket(index:name:secret:)`: 32-byte NUL-padded name then the secret. */
internal fun channelsChannelInfoPacket(index: Int, name: String, secret: Bytes): Bytes {
    val out = ByteArrayOutputStream()
    out.write(ResponseCode.CHANNEL_INFO.rawValue.toInt())
    out.write(index)
    val nameBytes = name.toByteArray(Charsets.UTF_8).take(31).toByteArray()
    out.write(nameBytes)
    out.write(0)
    if (nameBytes.size < 31) out.write(ByteArray(31 - nameBytes.size))
    out.write(secret.toByteArray())
    return Bytes(out.toByteArray())
}

private fun littleEndian32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
