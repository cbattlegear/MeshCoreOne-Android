// AndroidOnly: WP-201 Radio-scoped neutral invalidation value controls, not consumer execution proof.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MessagePersistenceChangedContractTest {
    private val id = UUID(0, 1)
    private val radio = RadioId(UUID(0, 2))
    private val key = EntityKey(radio, id)

    @Test
    fun neutralEventRetainsTheCompleteRadioScopedKey() {
        val event = MessageStatusEvent.PersistenceChanged(key)

        assertEquals(key, event.key)
        assertEquals(radio, event.key.radioId)
        assertEquals(id, event.key.id)
    }

    @Test
    fun sameMessageIdOnDifferentRadiosIsNotTheSameInvalidation() {
        val first = MessageStatusEvent.PersistenceChanged(key)
        val second = MessageStatusEvent.PersistenceChanged(EntityKey(RadioId(UUID(0, 3)), id))

        assertNotEquals(first, second)
        assertNotEquals(first.key, second.key)
    }

    @Test
    fun invalidationCarriesNoStatusForARecreatedRowWithTheSameKey() {
        val before = MessageStatusEvent.PersistenceChanged(key)
        val after = MessageStatusEvent.PersistenceChanged(key.copy())

        assertEquals(before, after)
        assertEquals(key, after.component1())
    }

    @Test
    fun directStatusResolutionRetainsStatusAndRoundTripTime() {
        val event = MessageStatusEvent.StatusResolved(id, MessageStatus.DELIVERED, 42u)
        val withoutRoundTrip = MessageStatusEvent.StatusResolved(id, MessageStatus.SENT, null)

        assertEquals(id, event.messageID)
        assertEquals(MessageStatus.DELIVERED, event.status)
        assertEquals(42u, event.roundTripTime)
        assertEquals(MessageStatus.SENT, withoutRoundTrip.status)
        assertEquals(null, withoutRoundTrip.roundTripTime)
    }

    @Test
    fun otherExistingDirectEventPayloadsRemainUnchanged() {
        val resent = MessageStatusEvent.Resent(id)
        val retrying = MessageStatusEvent.Retrying(id, 2L, 3L)
        val routing = MessageStatusEvent.RoutingChanged(id, true)
        val failed = MessageStatusEvent.Failed(id)

        assertEquals(id, resent.messageID)
        assertEquals(id, retrying.messageID)
        assertEquals(2L, retrying.attempt)
        assertEquals(3L, retrying.maxAttempts)
        assertEquals(id, routing.contactID)
        assertEquals(true, routing.isFlood)
        assertEquals(id, failed.messageID)
    }
}
