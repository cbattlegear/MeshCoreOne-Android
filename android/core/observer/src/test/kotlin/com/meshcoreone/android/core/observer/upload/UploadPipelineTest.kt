// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import com.meshcoreone.android.core.observer.ObserverRecord
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadPipelineTest {
    private val clock = MutableUploadClock()
    private val publisher = FakePublisher()
    private val repository = InMemoryUploadQueueRepository()
    private val policy = UploadPolicy()
    private val pipeline = UploadPipeline(publisher, repository, testTopics(), TEST_ORIGIN, clock, policy)

    private fun offer(n: Int, at: java.time.Instant = clock.now()) = runBlocking { pipeline.offer(fullRecord(n, at)) }
    private fun tick() = runBlocking { pipeline.tick() }
    private fun records(m: MqttMessage) = Regex("\"mode\"").findAll(m.payload).count()

    @Test fun fiftyRecordsFlushImmediately() {
        repeat(49) { offer(it) }
        assertTrue(publisher.acked.isEmpty())
        offer(49)
        val batch = publisher.ackedPackets.single()
        assertEquals(50, records(batch))
        assertEquals(1, batch.qos); assertFalse(batch.retained)
        assertTrue(batch.payload.startsWith("{\"origin\":\"Test Node\",\"origin_id\":\"${testNodeKey.hexString}\",\"direction\":\"rx\",\"records\":["))
        assertEquals(testTopics().packets, batch.topic)
    }

    @Test fun fifteenSecondsFlushesAFewRecords() {
        offer(1); offer(2)
        clock.advance(Duration.ofSeconds(14)); tick()
        assertTrue(publisher.acked.isEmpty())
        clock.advance(Duration.ofSeconds(1)); tick()
        assertEquals(2, records(publisher.ackedPackets.single()))
        assertNull(runBlocking { pipeline.nextDueAt() })
    }

    @Test fun nextDueAtIsTheOldestRecordPlusInterval() {
        val start = clock.now()
        offer(1); clock.advance(Duration.ofSeconds(10)); offer(2)
        assertEquals(start.plusSeconds(15), runBlocking { pipeline.nextDueAt() })
    }

    @Test fun screenOffAndStillStretchesToSixtySecondsButFiftyStillFlushes() {
        pipeline.setLowActivity(true)
        offer(1)
        clock.advance(Duration.ofSeconds(59)); tick()
        assertTrue(publisher.acked.isEmpty())
        clock.advance(Duration.ofSeconds(1)); tick()
        assertEquals(1, publisher.ackedPackets.size)
        repeat(50) { offer(100 + it) }
        assertEquals(2, publisher.ackedPackets.size)
        pipeline.setLowActivity(false)
        offer(7); clock.advance(Duration.ofSeconds(15)); tick()
        assertEquals(3, publisher.ackedPackets.size)
    }

    @Test fun droppedRecordsAreNeverBufferedOrQueued() {
        assertFalse(runBlocking { pipeline.offer(TimedRecord(ObserverRecord.Dropped, clock.now(), testLocation)) })
        assertEquals(0, runBlocking { pipeline.pendingCount() })
        runBlocking { pipeline.flush() }
        assertTrue(publisher.attempts.isEmpty())
    }

    @Test fun failedLiveBatchIsQueuedAndNotLost() {
        publisher.connected = false
        offer(1); offer(2); runBlocking { pipeline.flush() }
        assertEquals(2, runBlocking { pipeline.queuedCount() })
        assertEquals(0, runBlocking { pipeline.pendingCount() })
    }

    @Test fun rowsAreDeletedOnlyAfterTheBrokerAcks() {
        publisher.connected = false
        offer(1); runBlocking { pipeline.flush() }
        publisher.connected = true; publisher.failNext = 1 // reconnect but the first publish is not acked
        runBlocking { pipeline.onConnected() }
        assertEquals(1, runBlocking { pipeline.queuedCount() }, "no ack, row must stay")
        runBlocking { pipeline.onConnected() }
        assertEquals(0, runBlocking { pipeline.queuedCount() })
        assertEquals(listOf(rawOf(1)), publisher.deliveries())
    }

    @Test fun aThrowingPublisherIsTreatedAsNotAcked() {
        offer(1); publisher.throwNext = true
        runBlocking { pipeline.flush() }
        assertEquals(1, runBlocking { pipeline.queuedCount() })
    }

    @Test fun afterReconnectQueuedBatchesGoOldestFirstBeforeLive() {
        publisher.connected = false
        repeat(120) { offer(it) }            // flushes at 50 and 100, all queued (offline)
        runBlocking { pipeline.flush() }
        assertEquals(120, runBlocking { pipeline.queuedCount() })
        publisher.connected = true
        offer(1000)                          // live record buffered during reconnect
        runBlocking { pipeline.onConnected() }
        val deliveries = publisher.deliveries()
        assertEquals((0 until 120).map { rawOf(it) } + rawOf(1000), deliveries)
        assertEquals(4, publisher.ackedPackets.size)
        assertTrue(publisher.ackedPackets.none { it.payload.contains("delayed") || it.payload.contains("session") })
        assertEquals(0, runBlocking { pipeline.queuedCount() })
    }

    @Test fun offlineThirtyMinutesThenReconnectDeliversEveryRecordExactlyOnceWithOriginalRxTime() {
        publisher.connected = false
        val start = clock.now()
        val sent = mutableListOf<Pair<Int, java.time.Instant>>()
        for (second in 0 until 30 * 60 step 3) {
            clock.current = start.plusSeconds(second.toLong())
            offer(second, clock.current); sent += second to clock.current
            tick()
        }
        runBlocking { pipeline.flush() }
        clock.current = start.plus(Duration.ofMinutes(30))
        publisher.connected = true
        runBlocking { pipeline.onConnected() }
        // a second reconnect and extra ticks must not duplicate anything
        runBlocking { pipeline.onConnected() }; tick()
        val delivered = publisher.ackedPackets.flatMap { m ->
            Regex("\"raw\":\"([0-9a-f]+)\".*?\"timestamp\":\"([^\"]+)\"").findAll(m.payload).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }
        assertEquals(sent.size, delivered.size)
        assertEquals(sent.map { rawOf(it.first) to WireFormat.timestamp(it.second) }, delivered)
        assertEquals(delivered.size, delivered.map { it.first }.toSet().size)
        assertEquals(0, runBlocking { pipeline.queuedCount() })
    }

    @Test fun tickRetriesTheBacklogWithBackoff() {
        publisher.connected = false
        offer(1); runBlocking { pipeline.flush() }
        publisher.attempts.clear()
        publisher.connected = true
        tick()                                      // retryAt is a full interval after the failed flush
        assertTrue(publisher.attempts.isEmpty())
        clock.advance(Duration.ofSeconds(15)); tick()
        assertEquals(1, publisher.ackedPackets.size)
    }

    @Test fun queueIsCappedAtFiftyThousandDroppingTheOldest() {
        val small = UploadPolicy(maxQueueRecords = 120)
        val p = UploadPipeline(publisher, repository, testTopics(), TEST_ORIGIN, clock, small)
        publisher.connected = false
        runBlocking { repeat(200) { p.offer(fullRecord(it, clock.now())) }; p.flush() }
        assertEquals(120, runBlocking { repository.count() })
        publisher.connected = true
        runBlocking { p.onConnected() }
        assertEquals((80 until 200).map { rawOf(it) }, publisher.deliveries())
        assertEquals(50_000, UploadPolicy().maxQueueRecords)
        assertEquals(Duration.ofDays(7), UploadPolicy().maxQueueAge)
    }

    @Test fun rowsOlderThanSevenDaysAreDroppedByRxTime() {
        publisher.connected = false
        val old = clock.now().minus(Duration.ofDays(7)).minusSeconds(1)
        val edge = clock.now().minus(Duration.ofDays(7)).plusSeconds(1)
        runBlocking { pipeline.offer(fullRecord(1, old)); pipeline.offer(fullRecord(2, edge)); pipeline.offer(fullRecord(3, clock.now())); pipeline.flush() }
        assertEquals(2, runBlocking { repository.count() })
        publisher.connected = true; runBlocking { pipeline.onConnected() }
        assertEquals(listOf(rawOf(2), rawOf(3)), publisher.deliveries())
    }

    @Test fun queuedRowsExpireWhileSittingOffline() {
        publisher.connected = false
        offer(1); runBlocking { pipeline.flush() }
        clock.advance(Duration.ofDays(8)); tick()
        assertEquals(0, runBlocking { repository.count() })
    }

    @Test fun rfSamplesGoToTheRfTopicAtQos1NotRetainedAndAreNeverQueued() {
        assertTrue(runBlocking { pipeline.publishRf("{\"x\":1}") })
        val m = publisher.acked.single()
        assertEquals(testTopics().rf, m.topic); assertEquals(1, m.qos); assertFalse(m.retained)
        publisher.connected = false
        assertFalse(runBlocking { pipeline.publishRf("{\"x\":2}") })
        assertEquals(0, runBlocking { pipeline.queuedCount() })
    }

    @Test fun onlyPacketsAndRfTopicsAreEverPublishedTo() {
        offer(1); runBlocking { pipeline.flush(); pipeline.publishRf("{}") }
        val key = testNodeKey.hexString
        assertEquals(setOf("meshcore/client/$key/packets", "meshcore/client/$key/rf"), publisher.attempts.map { it.topic }.toSet())
    }

    @Test fun connectionConfigUsesTheBrokerEndpointAndNeverPrintsThePassword() = runBlocking<Unit> {
        val signer = com.meshcoreone.android.core.observer.claim.NodeSigner { Bytes(ByteArray(64) { 7 }) }
        val logins = com.meshcoreone.android.core.observer.broker.BrokerLoginProvider(testNodeKey, signer, { clock.now() })
        val config = MqttConnectionConfig.forNode(logins, "client-1")
        assertEquals("mqtt.nodakmesh.org", config.host); assertEquals(443, config.port)
        assertEquals("v1_" + testNodeKey.hexString.uppercase(), config.username)
        val password = config.password()
        assertTrue(password.split(".").size == 3)
        assertFalse(config.toString().contains(password))
    }
}
