// AndroidOnly: WP-101 Prizm Mobile Observer service logic (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.session

import com.meshcoreone.android.core.observer.CaptureResult
import com.meshcoreone.android.core.observer.ChannelAllowList
import com.meshcoreone.android.core.observer.ObserverConstants
import com.meshcoreone.android.core.observer.ObserverRecord
import com.meshcoreone.android.core.observer.PacketCapture
import com.meshcoreone.android.core.observer.PrivacyFilter
import com.meshcoreone.android.core.observer.PrivateTrafficPolicy
import com.meshcoreone.android.core.observer.geo.RegionGeofence
import com.meshcoreone.android.core.observer.geo.UploadGate
import com.meshcoreone.android.core.observer.health.HealthPoller
import com.meshcoreone.android.core.observer.health.HealthPublisher
import com.meshcoreone.android.core.observer.health.HealthReading
import com.meshcoreone.android.core.observer.health.HealthSnapshot
import com.meshcoreone.android.core.observer.health.HealthSource
import com.meshcoreone.android.core.observer.health.ProtocolHealthSource
import com.meshcoreone.android.core.observer.ingest.IngestOutcome
import com.meshcoreone.android.core.observer.ingest.ObserverIngest
import com.meshcoreone.android.core.observer.location.LocationFix
import com.meshcoreone.android.core.observer.location.LocationRedactor
import com.meshcoreone.android.core.observer.location.LocationSharing
import com.meshcoreone.android.core.observer.location.PositionResolver
import com.meshcoreone.android.core.observer.location.PrivacyZoneSource
import com.meshcoreone.android.core.observer.location.SamplingRatePolicy
import com.meshcoreone.android.core.observer.upload.InMemoryUploadQueueRepository
import com.meshcoreone.android.core.observer.upload.MqttPublisher
import com.meshcoreone.android.core.observer.upload.ObserverPresence
import com.meshcoreone.android.core.observer.upload.ObserverTopics
import com.meshcoreone.android.core.observer.upload.RegionalPipelines
import com.meshcoreone.android.core.observer.upload.UploadClock
import com.meshcoreone.android.core.observer.upload.UploadPipeline
import com.meshcoreone.android.core.observer.upload.UploadQueueRepository
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.event.MeshEvent
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One observing run against one connected node: 0x88 frames from the session feed go through [PacketCapture] and
 * [PrivacyFilter] into [ObserverIngest]; fixes feed the [PositionResolver] and [SamplingRatePolicy]; a timer drives the
 * pipelines and the 5 minute health poll; presence is announced per region in use. All real I/O sits behind
 * [ObserverNodeLink], [LocationUpdates] and [MqttPublisher], so the Android service stays a thin shell around this class.
 *
 * [stop] ends publishing at once (nothing more is offered, flushed or retried) and sends the offline status for every
 * region this run used. Records still buffered in memory are discarded: the user turned uploading off.
 */
class ObserverSession(
    private val link: ObserverNodeLink,
    private val location: LocationUpdates,
    private val publisher: MqttPublisher,
    sharing: LocationSharing,
    zones: PrivacyZoneSource,
    geofence: RegionGeofence,
    private val clock: UploadClock,
    private val elapsedMs: () -> Long,
    private val sessionId: String,
    private val queueFactory: (region: String) -> UploadQueueRepository = { InMemoryUploadQueueRepository() },
    private val privateTraffic: PrivateTrafficPolicy = ObserverConstants.DEFAULT_PRIVATE_TRAFFIC_POLICY,
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
    private val log: (String) -> Unit = {},
) {
    private val resolver = PositionResolver()
    private val gate = UploadGate(geofence)
    private val lock = Any()
    private val regions = LinkedHashSet<String>()
    private val announced = HashSet<String>()
    private val slots = HashMap<Int, ChannelInfo>()
    @Volatile private var filter = PrivacyFilter(ChannelAllowList.publicOnly, privateTraffic)
    @Volatile private var active = false
    @Volatile private var lowActivity = false
    @Volatile private var screenOn = true
    private var requestedIntervalMs = SamplingRatePolicy.MOVING_INTERVAL_MS
    private var lastChannelRefreshAtMs = 0L
    private var jobs: List<Job> = emptyList()

    private val pipelines = RegionalPipelines { region ->
        synchronized(lock) { regions += region }
        UploadPipeline(publisher, queueFactory(region), ObserverTopics(region, link.nodeKey), sessionId, clock)
            .also { it.setLowActivity(lowActivity) }
    }
    private val ingest = ObserverIngest(resolver, gate, LocationRedactor(sharing, zones), pipelines)
    private val health = HealthPublisher(healthSource(), resolver, gate, LocationRedactor(sharing, zones), pipelines, link::isConnected)
    private val poller = HealthPoller(clock)

    private val counterState = MutableStateFlow(ObserverCounters())
    private val gateTextState = MutableStateFlow<String?>(null)
    val counters: StateFlow<ObserverCounters> = counterState.asStateFlow()

    /** Notification text while paused (outside ND/MN, waiting for a fix); null while uploading. */
    val gateText: StateFlow<String?> = gateTextState.asStateFlow()
    val isActive: Boolean get() = active

    /** Channel names currently allowed to upload in full (always includes Public). */
    val allowedChannelNames: List<String> get() = allowListNames

    @Volatile private var allowListNames: List<String> = ChannelAllowList.publicOnly.names

    /** Starts requesting fixes and launches the event and timer loops in [scope]. */
    suspend fun start(scope: CoroutineScope) {
        active = true
        location.request(requestedIntervalMs)
        refreshChannels()
        jobs = listOf(
            scope.launch { link.events().catch { log("event feed ended: ${it.message}") }.collect { handle(it) } },
            scope.launch { while (active) { delay(tickIntervalMs); tick() } },
        )
    }

    fun onFix(fix: LocationFix) {
        if (!active || !resolver.add(fix)) return
        val interval = SamplingRatePolicy.intervalMs(resolver.buffer.snapshot(), elapsedMs())
        if (interval != requestedIntervalMs) {
            requestedIntervalMs = interval
            location.request(interval)
        }
        applyLowActivity()
    }

    /** Screen on/off from the service; the stretch to 60 s batching applies only while off and still. */
    fun setScreenOn(on: Boolean) {
        screenOn = on
        applyLowActivity()
    }

    suspend fun handle(event: MeshEvent) {
        if (!active) return
        when (event) {
            is MeshEvent.RxLogData -> onPacket(event)
            is MeshEvent.ChannelInfo -> updateSlot(event.info)
            else -> Unit
        }
    }

    /** Timer entry: pipelines, presence, health, channel refresh and the published status. */
    suspend fun tick() {
        if (!active) return
        guarded("tick") {
            pipelines.tick()
            announcePending()
            poller.runIfDue { health.pollOnce(elapsedMs(), clock.now()) }
            if (elapsedMs() - lastChannelRefreshAtMs >= CHANNEL_REFRESH_MS) refreshChannels()
        }
        publishStatus()
    }

    /** The node link came back: rebuild the allow list, resend everything, re-announce presence, poll health soon. */
    suspend fun onReconnected() {
        if (!active) return
        refreshChannels()
        synchronized(lock) { announced.clear() }
        poller.reset()
        guarded("reconnect") { pipelines.onConnected(); announcePending() }
        publishStatus()
    }

    /** Turns publishing off at once and tells Prizm the observer is offline on every region used. */
    suspend fun stop() {
        if (!active) return
        active = false
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        location.stop()
        val used = synchronized(lock) { regions.toList().also { announced.clear() } }
        used.forEach { region ->
            guarded("offline $region") { publisher.publish(ObserverPresence.offline(ObserverTopics(region, link.nodeKey))) }
        }
        counterState.update { it.copy(queued = 0) }
    }

    private suspend fun onPacket(event: MeshEvent.RxLogData) {
        val captured = PacketCapture.fromParsed(event.data) as? CaptureResult.Captured ?: return
        counterState.update { it.copy(heard = it.heard + 1) }
        val record = filter.filter(captured.packet)
        val outcome = guarded("ingest") { ingest.onRecord(record, elapsedMs(), clock.now()) }
        if (outcome is IngestOutcome.Uploaded) {
            counterState.update {
                when (record) {
                    is ObserverRecord.Full -> it.copy(uploaded = it.uploaded + 1)
                    is ObserverRecord.MetaOnly -> it.copy(metadataOnly = it.metadataOnly + 1)
                    ObserverRecord.Dropped -> it
                }
            }
        }
        gateTextState.value = gate.notificationText
    }

    private suspend fun refreshChannels() {
        lastChannelRefreshAtMs = elapsedMs()
        val fetched = guarded("channels") { link.fetchChannels() } ?: return
        synchronized(lock) { slots.clear(); fetched.forEach { slots[it.index.toInt()] = it } }
        rebuildFilter()
    }

    private fun updateSlot(info: ChannelInfo) {
        synchronized(lock) { slots[info.index.toInt()] = info }
        rebuildFilter()
    }

    private fun rebuildFilter() {
        val list = ChannelAllowList.from(synchronized(lock) { slots.values.toList() })
        filter = PrivacyFilter(list, privateTraffic)
        allowListNames = list.names
    }

    private suspend fun announcePending() {
        val todo = synchronized(lock) { regions.filter { it !in announced } }
        for (region in todo) {
            val ok = ObserverPresence.announce(publisher, ObserverTopics(region, link.nodeKey), link.deviceInfo)
            if (ok) synchronized(lock) { announced += region }
        }
    }

    private suspend fun publishStatus() {
        val queued = guarded("queue count") { pipelines.queuedCount() } ?: counterState.value.queued
        counterState.update { it.copy(queued = queued) }
        gateTextState.value = gate.notificationText
    }

    private fun applyLowActivity() {
        val still = SamplingRatePolicy.intervalMs(resolver.buffer.snapshot(), elapsedMs()) == SamplingRatePolicy.STILL_INTERVAL_MS
        val low = !screenOn && still
        if (low != lowActivity) { lowActivity = low; pipelines.setLowActivity(low) }
    }

    private fun healthSource(): HealthSource {
        val reader = link.statsReader() ?: return HealthSource { HealthReading(HealthSnapshot(), emptyList()) }
        return ProtocolHealthSource(reader)
    }

    private suspend fun <T> guarded(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        log("$what failed: ${error.message ?: error.javaClass.simpleName}")
        null
    }

    companion object {
        val TICK_INTERVAL_MS: Long = Duration.ofSeconds(5).toMillis()
        val CHANNEL_REFRESH_MS: Long = Duration.ofMinutes(10).toMillis()
    }
}
