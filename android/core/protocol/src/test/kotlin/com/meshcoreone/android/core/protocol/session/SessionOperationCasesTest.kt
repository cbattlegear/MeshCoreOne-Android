// AndroidOnly: WP-107 Execute every remaining public operation, compound signing/retry and explicit binary context.
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.crypto.Ed25519Crypto
import com.meshcoreone.android.core.protocol.crypto.CryptoFixtures
import com.meshcoreone.android.core.protocol.event.*
import com.meshcoreone.android.core.protocol.model.*
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SessionOperationCasesTest {
    @TestFactory
    fun completeOperations() = listOf(
        nativeCase("radio CLI sends CLI_COMMAND rather than admin CLI_DATA") {
            val f = fixture(); start(f)
            val prefix = hex("010203040506")
            val packet = hex("02030004030201010203040506") + Bytes.utf8("A0|ver")
            val query = com.meshcoreone.android.core.protocol.command.PacketBuilder.deviceQuery()
            assertFailsWith<MeshCoreException.DeviceError> {
                f.session.sendRadioCLICommand(prefix, "A0|ver")
            }
            command(f, query, hex("0d0d") + filled(0, 78)) { f.session.queryDevice() }
            assertFailsWith<MeshCoreException.DeviceError> {
                f.session.sendRadioCLICommand(prefix, "A0|ver")
            }
            command(f, query, hex("0d0e") + filled(0, 78)) { f.session.queryDevice() }
            command(f, packet, sentPacket()) {
                f.session.sendRadioCLICommand(prefix, "A0|ver", Instant.ofEpochSecond(0x01020304))
            }
            f.session.stop()
        },
        nativeCase("radio CLI permission edits fresh flags without changing other radio fields") {
            val f = fixture(); start(f)
            command(f, com.meshcoreone.android.core.protocol.command.PacketBuilder.deviceQuery(),
                hex("0d0e") + filled(0, 78)) { f.session.queryDevice() }
            val key = filled(0xaa, 32)
            for ((before, grant, after) in listOf(Triple(0xa5, true, 0xb5), Triple(0xb5, false, 0xa5))) {
                var committed = false
                val response = contactPacket(key)
                val radioContact = response.slice(0, 34) + Bytes.of(before) + response.slice(35, response.size)
                val expected = raw(0x09, key + Bytes.of(1, after, 255) + filled(0, 64) +
                    Bytes.utf8("Node").paddedOrTruncated(32) + filled(0, 15))
                f.transport.onSend = { frame ->
                    when (frame) {
                        raw(0x1e, key) -> f.transport.receive(radioContact)
                        expected -> f.transport.receive(hex("00"))
                        else -> fail("Unexpected permission frame ${frame.hexString}")
                    }
                }
                assertNotNull(f.session.setRemoteCLIAllowed(key, grant, ContactWriteDispatch(), { true }) { committed = true })
                assertTrue(committed)
            }
            f.session.stop()
        },
        nativeCase("remaining device config queries and unsigned setters") {
            val f = fixture(); start(f)
            val ranges = command(f, hex("3c"), raw(0x1a, little32(150_000) + little32(2_500_000))) { f.session.getRepeatFreq() }
            assertEquals(listOf(FrequencyRange(150_000u, 2_500_000u)), ranges)
            val auto = command(f, hex("3b"), hex("19ffff")) { f.session.getAutoAddConfig() }
            assertEquals(0xffu.toUByte(), auto.bitmask); assertEquals(0xffu.toUByte(), auto.maxHops)
            command(f, hex("25ffffffff"), hex("00")) { f.session.setDevicePin(UInt.MAX_VALUE) }
            command(f, hex("15ffffffffffffffff0000"), hex("00")) { f.session.setTuning(UInt.MAX_VALUE, UInt.MAX_VALUE) }
            command(f, hex("26013fff"), hex("00")) { f.session.setOtherParams(true, 255u, 255u, 255u, 255u) }
            command(f, hex("2601000000"), hex("00")) { f.session.setOtherParams(true, 0u, 0u, 0u, 0u, 0u) }
            val vars = command(f, hex("28"), raw(0x15, Bytes.utf8("key:value,x:y:z"))) { f.session.getCustomVars() }
            assertEquals(mapOf("key" to "value", "x" to "y:z"), vars)
            command(f, raw(0x29, Bytes.utf8("key:value")), hex("00")) { f.session.setCustomVar("key", "value") }
            command(f, hex("3d0002"), hex("00")) { f.session.setPathHashMode(2u) }
            f.session.stop()
        },
        nativeCase("device time caches only real currentTime replies") {
            val f = fixture(); start(f)
            assertNull(f.session.deviceTime)
            command(f, hex("0601000000"), hex("00")) { f.session.setTime(Instant.ofEpochSecond(1)) }
            assertNull(f.session.deviceTime)
            command(f, hex("05"), hex("09ffffffff")) { f.session.getTime() }
            assertEquals(Instant.ofEpochSecond(4_294_967_295), f.session.deviceTime)
            f.session.stop()
        },
        nativeCase("contact management helpers use actual caches and exact legacy/raw writes") {
            val f = fixture(); start(f)
            val key = filled(0xaa, 32)
            val contact = testContact(key, 4u)
            command(f, raw(0x09, key + hex("0400ff") + filled(0, 64) + Bytes.utf8("TestRepeater").paddedOrTruncated(32) +
                little32(1000) + filled(0, 11)), hex("00")) { f.session.addContact(contact) }
            command(f, raw(0x09, key + hex("040003") + hex("112233").paddedOrTruncated(64) +
                Bytes.utf8("TestRepeater").paddedOrTruncated(32) + little32(1000) + filled(0, 8)), hex("00")) {
                f.session.changeContactPath(contact, hex("112233"))
            }
            command(f, raw(0x09, key + hex("040e80") + filled(0, 64) + Bytes.utf8("TestRepeater").paddedOrTruncated(32) +
                little32(1000) + filled(0, 8)), hex("00")) {
                f.session.changeContactFlags(contact.copy(outPathLength = 0x80u), ContactFlags(0x0eu))
            }
            command(f, raw(0x09, key + hex("030000") + filled(0, 64) + Bytes.utf8("Room").paddedOrTruncated(32) +
                little32(0) + little32(90_000_000) + little32(-180_000_000)), hex("00")) {
                f.session.updateContact(key, ContactType.ROOM, ContactFlags(0u), 0u, Bytes.EMPTY, "Room",
                    Instant.ofEpochSecond(-1), 200.0, -200.0)
            }
            command(f, raw(0x10, key), hex("00")) { f.session.shareContact(key) }
            command(f, raw(0x0f, key), hex("00")) { f.session.removeContact(key) }
            command(f, raw(0x0d, key), hex("00")) { f.session.resetPath(key) }
            command(f, hex("12abcd"), hex("00")) { f.session.importContact(hex("abcd")) }
            assertEquals("meshcore://abcd", command(f, hex("11"), hex("0babcd")) { f.session.exportContact() })
            f.transport.receive(contactPacket(key, "Caf\u00e9"))
            f.transport.receive(raw(0x8a, contactPacket(filled(0xbb, 32), "Pending").slice(1, 148))); runCurrent()
            assertEquals(key, f.session.getContactByName("cafe")?.publicKey)
            assertEquals(key, f.session.getContactByName("CAF\u00c9", true)?.publicKey)
            assertEquals(key, f.session.getContactByKeyPrefix("AAAA")?.publicKey)
            assertEquals(key, f.session.getContactByKeyPrefix(filled(0xaa, 6))?.publicKey)
            assertEquals(1, f.session.cachedPendingContacts.size)
            assertNotNull(f.session.popPendingContact(filled(0xbb, 32).hexString))
            f.session.flushPendingContacts()
            assertTrue(f.session.cachedPendingContacts.isEmpty())
            f.transport.onSend = {
                assertEquals(hex("04"), it)
                f.transport.receive(contactsStart(1))
                f.transport.receive(contactPacket(key, "Caf\u00e9"))
                f.transport.receive(contactsEnd())
            }
            f.session.getContacts()
            f.transport.onSend = {}
            val before = f.transport.sent.size
            assertEquals(f.session.cachedContacts, f.session.ensureContacts())
            assertEquals(before, f.transport.sent.size)
            assertFailsWith<ClassCastException> { (f.session.cachedContacts as MutableList<MeshContact>).clear() }
            f.session.stop()
        },
        nativeCase("NOT_FOUND is absence but other contact device errors are not fake misses") {
            val f = fixture(); start(f)
            val key = filled(0x22, 32)
            assertNull(command(f, raw(0x1e, key), hex("0102")) { f.session.getContact(key) })
            assertEquals(3u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> {
                command(f, raw(0x1e, key), hex("0103")) { f.session.getContact(key) }
            }.code)
            f.session.stop()
        },
        nativeCase("destination overloads keep prefix messaging and full-key remote commands") {
            val f = fixture(); start(f)
            val key = filled(0xdd, 32)
            val target = Destination.Contact(testContact(key, 3u))
            command(f, hex("02000080009265") + key.prefix(6) + Bytes.utf8("Hi"), sentPacket()) {
                f.session.sendMessage(target, "Hi", testEpoch)
            }
            command(f, raw(0x1a, key + Bytes.utf8("password")), sentPacket()) { f.session.sendLogin(target, "password") }
            command(f, raw(0x27, filled(0, 3) + key), sentPacket()) { f.session.sendTelemetryRequest(key) }
            f.session.stop()
        },
        nativeCase("scope and control/discovery helpers preserve defaults explicit zero and derived keys") {
            val f = fixture(); start(f)
            command(f, raw(0x36, hex("00") + filled(0x5a, 16)), hex("00")) { f.session.setFloodScope(filled(0x5a, 32)) }
            command(f, raw(0x36, hex("00") + hex("efa1f375d76194fa51a3556a97e641e6")), hex("00")) {
                f.session.setFloodScope(FloodScope.ChannelName("public"))
            }
            command(f, hex("3601"), hex("00")) { f.session.setFloodScopeUnscoped() }
            command(f, hex("37010203"), hex("00")) { f.session.sendControlData(1u, hex("0203")) }
            assertEquals(0xaabbccddu, command(f, hex("3781ffddccbbaa"), hex("00")) { f.session.sendNodeDiscoverRequest(255u) })
            assertEquals(0u, command(f, hex("37800500000000ffffffff"), hex("00")) {
                f.session.sendNodeDiscoverRequest(5u, false, 0u, Instant.MAX)
            })
            f.session.stop()
        },
        nativeCase("owner-info binary exchange and line-split semantics") {
            val f = fixture(); start(f)
            val key = filled(0x22, 32)
            f.transport.onSend = {
                assertEquals(raw(0x32, key + hex("07")), it)
                f.transport.receive(sentPacket())
                f.transport.receive(binaryPacket(hex("aabbccdd"), Bytes.utf8("1.15\nTower\nOwner\nMore")))
            }
            assertEquals(OwnerInfoResponse("1.15", "Tower", "Owner\nMore"), f.session.requestOwnerInfo(key))
            f.session.stop()
        },
        nativeCase("MMA saturates request dates and parses real min max average data") {
            val f = fixture(); start(f)
            val key = filled(0x22, 32)
            f.transport.onSend = {
                assertEquals(raw(0x32, key + hex("0400000000ffffffff0000")), it)
                f.transport.receive(sentPacket())
                f.transport.receive(binaryPacket(hex("aabbccdd"), hex("0167ff9c00c80032")))
            }
            val response = f.session.requestMMA(key, Instant.MIN, Instant.MAX)
            assertEquals(key.prefix(6), response.publicKeyPrefix)
            assertEquals(hex("aabbccdd"), response.tag)
            assertEquals(-10.0, response.data.single().min)
            assertEquals(20.0, response.data.single().max)
            assertEquals(5.0, response.data.single().avg)
            f.session.stop()
        },
        nativeCase("ACL uses caller key tag and preserves unsigned permissions") {
            val f = fixture(); start(f)
            val key = filled(0x33, 32)
            f.transport.onSend = {
                assertEquals(raw(0x32, key + hex("050000")), it)
                f.transport.receive(sentPacket())
                f.transport.receive(binaryPacket(hex("aabbccdd"), hex("112233445566ff00000000000000")))
            }
            val response = f.session.requestACL(key)
            assertEquals(key.prefix(6), response.publicKeyPrefix)
            assertEquals(hex("aabbccdd"), response.tag)
            assertEquals(listOf(ACLEntry(hex("112233445566"), 255u)), response.entries)
            f.session.stop()
        },
        nativeCase("neighbours carry exact caller prefix width count offset order and random payload") {
            val f = fixture(); start(f)
            val key = filled(0x44, 32)
            f.transport.onSend = {
                assertEquals(raw(0x32, key + hex("0600ffffffff08ddccbbaa")), it)
                f.transport.receive(sentPacket())
                f.transport.receive(binaryPacket(hex("aabbccdd"), hex("010001001122334455667788ffffffff80")))
            }
            val response = f.session.requestNeighbours(key, 255u, 65535u, 255u, 8u)
            assertEquals(1L, response.totalCount)
            assertEquals(hex("1122334455667788"), response.neighbours.single().publicKeyPrefix)
            assertEquals(-1L, response.neighbours.single().secondsAgo)
            assertEquals(-32.0, response.neighbours.single().snr)
            f.session.stop()
        },
        nativeCase("neighbours pagination holds the whole operation and real one-second page cadence") {
            val f = fixture(); start(f)
            val key = filled(0x44, 32)
            var page = 0
            f.transport.onSend = { frame ->
                assertEquals(raw(0x32, key + hex("0600ff") + little16(page) + hex("0004ddccbbaa")), frame)
                page += 1
                val tag = little32(page.toLong())
                f.transport.receive(sentPacket(tag))
                f.transport.receive(binaryPacket(tag, hex("02000100") + filled(page, 4) + little32(11) + hex("04")))
            }
            val task = checkedRequest { f.session.fetchAllNeighbours(key) }; runCurrent()
            assertEquals(1, page)
            advanceTimeBy(999); runCurrent(); assertEquals(1, page)
            advanceTimeBy(1); runCurrent()
            assertEquals(2, page)
            assertEquals(listOf(filled(1, 4), filled(2, 4)), task.await().neighbours.map { it.publicKeyPrefix })
            assertEquals(2L, task.await().totalCount)
            f.session.stop()
        },
        nativeCase("explicit binary status context is not guessed from a cached contact type") {
            val f = fixture(); start(f)
            val key = filled(0x33, 32)
            f.transport.receive(contactPacket(key, "Cached room", 3)); runCurrent()
            f.transport.onSend = {
                assertEquals(raw(0x1b, key), it)
                f.transport.receive(sentPacket())
                f.transport.receive(binaryPacket(hex("aabbccdd"), statusPacket(key, 1234, 17, 9).slice(8, 60)))
            }
            val status = f.session.requestStatus(key, ContactType.REPEATER)
            assertEquals(StatusResponse.Layout.REPEATER, status.layout)
            assertEquals(589841u, status.rxAirtime)
            assertNull(status.roomServerPostedCount)
            f.session.stop()
        },
        nativeCase("direct region requests reverse raw path bytes without temporary routing writes") {
            val f = fixture(); start(f)
            val contact = testContact(pathLength = 0x42u, path = hex("11223344"))
            f.transport.onSend = {
                assertEquals(raw(0x39, contact.publicKey + hex("014244332211")), it)
                f.transport.receive(sentPacket())
                f.transport.receive(binaryPacket(hex("aabbccdd"), little32(1) + Bytes.utf8(" Europe,*,UK ")))
            }
            assertEquals(listOf("Europe", "UK"), f.session.requestRegions(contact))
            assertEquals(2, f.transport.sent.size)
            f.session.stop()
        },
        nativeCase("multi-step signing sends real RFC8032 message chunks and verifies the independent signature") {
            val f = fixture(); start(f, selfPacket(rfcThirdPublicKey))
            val writes = mutableListOf<Bytes>()
            f.transport.onSend = {
                writes += it
                when (it[0].toInt()) {
                    0x21 -> f.transport.receive(raw(0x13, hex("00") + little32(120)))
                    0x22 -> f.transport.ok()
                    0x23 -> f.transport.receive(raw(0x14, rfcThirdSignature))
                    else -> fail("Unexpected signing command")
                }
            }
            val signature = f.session.sign(hex("af82"), chunkSize = 1)
            assertEquals(listOf(hex("21"), hex("22af"), hex("2282"), hex("23")), writes)
            assertEquals(rfcThirdSignature, signature)
            assertTrue(Ed25519Crypto.verify(hex("af82"), signature, rfcThirdPublicKey))
            f.session.stop()
        },
        nativeCase("empty signing uses the independent RFC8032 empty-message signature") {
            val f = fixture(); start(f, selfPacket(SessionReferenceVectorsTest.rfcPublicKey))
            f.transport.onSend = {
                when (it[0].toInt()) {
                    0x21 -> f.transport.receive(raw(0x13, hex("00") + little32(0)))
                    0x23 -> f.transport.receive(raw(0x14, SessionReferenceVectorsTest.rfcSignature))
                    else -> fail("Empty signing must not emit signData")
                }
            }
            val signature = f.session.sign(Bytes.EMPTY)
            assertEquals(listOf(hex("21"), hex("23")), f.transport.sent.drop(1))
            assertTrue(Ed25519Crypto.verify(Bytes.EMPTY, signature, SessionReferenceVectorsTest.rfcPublicKey))
            f.session.stop()
        },
        nativeCase("signing max length and chunk arithmetic reject oversize and cannot overflow") {
            val f = fixture(); start(f)
            f.transport.onSend = {
                when (it[0].toInt()) {
                    0x21 -> f.transport.receive(raw(0x13, hex("00ffffffff")))
                    0x22 -> f.transport.ok()
                    0x23 -> f.transport.receive(raw(0x14, rfcThirdSignature))
                    else -> fail("Unexpected signing operation")
                }
            }
            assertEquals(rfcThirdSignature, f.session.sign(hex("af82"), Long.MAX_VALUE))
            assertEquals(listOf(hex("21"), hex("22af82"), hex("23")), f.transport.sent.drop(1))
            f.transport.onSend = { f.transport.receive(raw(0x13, hex("0001000000"))) }
            val failure = assertFailsWith<MeshCoreException.DataTooLarge> { f.session.sign(hex("af82")) }
            assertEquals(1L, failure.maxSize); assertEquals(2L, failure.actualSize)
            f.session.stop()
        },
        nativeCase("retry registers ACK before the send and admits a zero suggested timeout") {
            val f = fixture(); start(f)
            val key = filled(0x11, 32)
            f.transport.onSend = {
                assertEquals(hex("02000080009265") + key.prefix(6) + Bytes.utf8("hey"), it)
                f.transport.receive(ackPacket(hex("00000000")))
                f.transport.receive(sentPacket(hex("00000000"), 0))
            }
            val result = assertNotNull(f.session.sendMessageWithRetry(key, "hey", testEpoch))
            assertEquals(hex("00000000"), result.expectedAck)
            assertEquals(2, f.transport.sent.size)
            assertEquals(0, f.session.core.generation().pending.size)
            f.session.stop()
        },
        nativeCase("retry preserves firmware timeout multiplier direct/flood ordering and attempt limits") {
            val f = fixture(); start(f)
            val key = filled(0x11, 32)
            val attempts = mutableListOf<Int>()
            f.transport.onSend = {
                when (it[0].toInt()) {
                    0x02 -> {
                        val attempt = it[2].toInt(); attempts += attempt
                        assertEquals(hex("0200") + Bytes.of(attempt) + little32(testEpoch.epochSecond) + key.prefix(6) + Bytes.utf8("hey"), it)
                        f.transport.receive(sentPacket(little32((attempt + 1).toLong()), 100))
                    }
                    0x0d -> { assertEquals(raw(0x0d, key), it); f.transport.ok() }
                    else -> fail("Unexpected retry frame")
                }
            }
            val task = checkedRequest { f.session.sendMessageWithRetry(key, "hey", testEpoch, 99, 2, 1) }; runCurrent()
            advanceTimeBy(119); runCurrent(); assertEquals(listOf(0), attempts)
            advanceTimeBy(1); runCurrent(); assertEquals(listOf(0, 1), attempts)
            advanceTimeBy(120); runCurrent(); assertEquals(listOf(0, 1, 2), attempts)
            assertEquals(listOf(0x01, 0x02, 0x02, 0x0d, 0x02), f.transport.sent.map { it[0].toInt() })
            advanceTimeBy(120); runCurrent()
            assertNull(task.await())
            assertEquals(3, attempts.size)
            assertEquals(0, f.session.core.generation().pending.size)
            f.session.stop()
        },
        nativeCase("retry path rejection is diagnosed rather than mislabeled as flood success") {
            val f = fixture(); start(f)
            val key = filled(0x11, 32)
            f.transport.onSend = {
                if (it[0] == 0x0du.toUByte()) f.transport.error(6)
                else {
                    val attempt = it[2].toInt()
                    f.transport.receive(sentPacket(little32((attempt + 1).toLong()), 0))
                }
            }
            assertNull(f.session.sendMessageWithRetry(key, "hey", testEpoch, 3, 1, 1))
            assertEquals(3, f.transport.sent.count { it[0] == 2u.toUByte() })
            assertTrue(f.diagnostics.any { it is SessionDiagnostic.BackgroundFailure && it.operation == "retry-path-reset" })
            f.session.stop()
        },
        nativeCase("generic sendAndWait ignores unrelated errors and wait wrappers remove timeouts") {
            val f = fixture(); start(f)
            f.transport.onSend = { f.transport.error(9); f.transport.receive(batteryPacket(4018)) }
            assertEquals(4018L, f.session.sendAndWait(hex("14")) { (it as? MeshEvent.Battery)?.info?.level })
            val wait = checkedRequest { f.session.waitForEvent(EventFilter.anyAcknowledgement, 0.1) }; runCurrent()
            assertEquals(1, f.session.core.generation().dispatcher.subscriberCount)
            advanceTimeBy(100); runCurrent()
            assertNull(wait.await())
            assertEquals(0, f.session.core.generation().dispatcher.subscriberCount)
            val predicate = checkedRequest { f.session.waitForEvent(matching = { it is MeshEvent.Acknowledgement }) }; runCurrent()
            f.transport.receive(ackPacket(hex("11223344"))); runCurrent()
            assertIs<MeshEvent.Acknowledgement>(predicate.await())
            f.session.stop()
        },
    )

    @TestFactory
    fun granularConfiguration() = listOf("base", "location", "environment", "policy", "manual", "acks").map { field ->
        nativeCase("granular $field setter preserves all source fields") {
            val f = fixture(); start(f)
            val expected = when (field) {
                "base" -> hex("2600030000")
                "location" -> hex("26000c0000")
                "environment" -> hex("2600300000")
                "policy" -> hex("260000ff00")
                "manual" -> hex("2601000000")
                else -> hex("26000000ff")
            }
            f.transport.onSend = {
                if (it[0] == 0x26u.toUByte()) {
                    assertEquals(expected, it); f.transport.ok()
                } else {
                    assertEquals(hex("01032020202020204d436f7265"), it)
                    f.transport.receive(selfPacket(manual = field == "manual", multiAcks = if (field == "acks") 255 else 0,
                        telemetry = expected[2].toInt(), policy = expected[3].toInt()))
                }
            }
            when (field) {
                "base" -> f.session.setTelemetryModeBase(255u)
                "location" -> f.session.setTelemetryModeLocation(255u)
                "environment" -> f.session.setTelemetryModeEnvironment(255u)
                "policy" -> f.session.setAdvertisementLocationPolicy(255u)
                "manual" -> f.session.setManualAddContacts(true)
                else -> f.session.setMultiAcks(255u)
            }
            assertEquals(3, f.transport.sent.size)
            assertNotNull(f.session.currentSelfInfo)
            f.session.stop()
        }
    }

    companion object {
        private val rfcThirdVector = CryptoFixtures.signatures.single { it.id == "rfc8032-3" }
        private val rfcThirdPublicKey = rfcThirdVector.publicKey
        private val rfcThirdSignature = rfcThirdVector.signature
    }
}
