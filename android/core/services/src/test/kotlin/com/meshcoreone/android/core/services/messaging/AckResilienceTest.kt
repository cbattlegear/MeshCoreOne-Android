// AndroidOnly: WP-208 PR45 terminal-ACK and monitor/expiry regressions over the real protocol session.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AckResilienceTest {
    private val other = Bytes(ByteArray(32) { 0x33 })

    @TestFactory fun ackResilience() = listOf(
        native("an ACK for a deleted DM retires its entry and later DMs still expire") {
            val h = Harness(this); h.start()
            val deleted = h.message(); val later = h.message()
            val codeA = Bytes.of(1, 2, 3, 4); val codeB = Bytes.of(5, 6, 7, 8)
            h.installAcceptedPendingAck(h.tracking(deleted, codeA))
            h.installAcceptedPendingAck(h.tracking(later, codeB, age = 60))
            h.store.deleteMessage(EntityKey(RADIO, deleted.id))
            assertEquals(PersistenceStoreError.MessageNotFound,
                assertFailsWith<PersistenceStoreException> { h.service.handleAcknowledgement(codeA, null) }.error)
            assertNull(h.service.pendingAck(deleted.id))
            h.service.checkExpiredAcks()
            assertEquals(MessageStatus.FAILED, h.stored(later.id).status)
            assertEquals(0, h.service.pendingAckCount); h.close()
        },
        native("the expiry sweep isolates an entry whose contact identity changed and still fails later DMs") {
            val h = Harness(this); h.start()
            val stuck = h.message(); val later = h.message(); val codeA = Bytes.of(1, 2, 3, 4)
            h.installAcceptedPendingAck(h.tracking(stuck, codeA).copy(
                publicKey = other, isDelivered = true, acknowledgement = MeshAcknowledgement(codeA, null),
            ))
            h.installAcceptedPendingAck(h.tracking(later, Bytes.of(5, 6, 7, 8), age = 60))
            h.service.checkExpiredAcks()
            assertEquals(MessageStatus.FAILED, h.stored(later.id).status)
            assertNull(h.service.pendingAck(stuck.id)); assertEquals(0, h.service.pendingAckCount)
            assertTrue(h.diagnostics.any { it is MessagingDiagnostic.Failure &&
                (it.cause as? MessageServiceException)?.error == MessageServiceError.ContactNotFound }); h.close()
        },
        native("the ACK monitor survives a non-persistence reconcile failure and delivers the next ACK") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring()
            val first = h.message(); val second = h.message()
            val codeA = Bytes.of(1, 2, 3, 4); val codeB = Bytes.of(5, 6, 7, 8)
            h.installAcceptedPendingAck(h.tracking(first, codeA).copy(publicKey = other))
            h.transport.pushAck(codeA); runCurrent()
            assertEquals(MessageStatus.SENT, h.stored(first.id).status)
            assertNull(h.service.pendingAck(first.id)); assertTrue(h.service.isEventMonitoringActive)
            h.installAcceptedPendingAck(h.tracking(second, codeB))
            h.transport.pushAck(codeB, 250u); runCurrent()
            assertEquals(MessageStatus.DELIVERED, h.stored(second.id).status)
            assertEquals(250u, h.stored(second.id).roundTripTime); h.close()
        },
        native("real deleted-row ACK cannot stop the periodic checker for later accepted DMs") {
            val h = Harness(this); h.start(); h.service.startEventMonitoring(); h.service.startAckExpiryChecking()
            val deleted = h.service.sendDirectMessage("deleted after acceptance", h.contact)
            val code = assertNotNull(h.service.pendingAck(deleted.id)).ackCodes.first()
            val later = h.service.sendDirectMessage("later accepted", h.contact)
            h.store.deleteMessage(EntityKey(RADIO, deleted.id)); h.transport.pushAck(code); runCurrent()
            assertNull(h.service.pendingAck(deleted.id)); assertEquals(1, h.service.pendingAckCount)
            advanceTimeBy(35_001); runCurrent()
            assertEquals(MessageStatus.FAILED, h.stored(later.id).status)
            assertTrue(h.service.isAckExpiryCheckingActive); assertTrue(h.service.isEventMonitoringActive)
            assertEquals(0, h.service.pendingAckCount); h.close()
        },
    )
}
