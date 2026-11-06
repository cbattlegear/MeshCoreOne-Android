// AndroidOnly: WP-309 Incarnation-safe channel replay queue selection and original ordering controls.
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.model.RadioId
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.jupiter.api.Test

class ChannelReactionTargetServiceTest {
    private data class Entry(
        val radio: Int,
        val sender: String,
        val keyOrder: Long? = null,
        val entryOrder: Long? = null,
    )

    private fun stamped(value: Entry, keyOrder: Long, entryOrder: Long) =
        value.copy(keyOrder = keyOrder, entryOrder = entryOrder)

    @Test fun selectiveDrainLeavesOtherRadioInItsOriginalKeyAndEntryOrder() {
        val queue = PendingQueue.empty<String, Entry>()
            .enqueue("hash", Entry(1, "first"), 100)
            .enqueue("hash", Entry(2, "foreign"), 100)
            .enqueue("hash", Entry(1, "last"), 100)
        val (remaining, matches) = queue.takeMatching("hash", { it.radio == 1 }, ::stamped)
        assertEquals(listOf(Entry(1, "first", 0, 0), Entry(1, "last", 0, 2)), matches)
        assertEquals(1, remaining.count)
        val (empty, foreign) = remaining.take("hash", ::stamped)
        assertEquals(listOf(Entry(2, "foreign", 0, 1)), foreign)
        assertEquals(0, empty.count)
    }

    @Test fun noMatchingRadioDoesNotDrainOrRestampAnyPendingEntry() {
        val queue = PendingQueue.empty<String, Entry>().enqueue("hash", Entry(2, "foreign"), 100)
        val (remaining, matches) = queue.takeMatching("hash", { it.radio == 1 }) { _, _, _ ->
            error("A foreign-radio reaction must not be taken")
        }
        assertSame(queue, remaining)
        assertNull(matches)
        assertEquals(listOf(Entry(2, "foreign", 0, 0)), remaining.take("hash", ::stamped).second)
    }

    @Test fun unfinishedSelectiveBatchRestoresItsOriginalOrderAroundNewArrivals() {
        val queue = PendingQueue.empty<String, Entry>()
            .enqueue("hash", Entry(1, "first"), 100)
            .enqueue("hash", Entry(2, "foreign"), 100)
            .enqueue("hash", Entry(1, "last"), 100)
        val (remaining, matches) = queue.takeMatching("hash", { it.radio == 1 }, ::stamped)
        var restored = remaining.enqueue("hash", Entry(1, "new"), 100)
        for (entry in requireNotNull(matches).asReversed()) {
            restored = restored.enqueue("hash", entry, 100, entry.keyOrder, entry.entryOrder)
        }
        assertEquals(
            listOf("first", "foreign", "last", "new"),
            restored.take("hash", ::stamped).second?.map { it.sender },
        )
    }

    @Test fun selectiveDrainDoesNotRenewTheOldestKeysEvictionPriority() {
        val queue = PendingQueue.empty<String, Entry>()
            .enqueue("old", Entry(1, "taken"), 3)
            .enqueue("old", Entry(2, "old-foreign"), 3)
            .enqueue("later", Entry(1, "later"), 3)
        val (remaining, _) = queue.takeMatching("old", { it.radio == 1 }, ::stamped)
        val filled = remaining
            .enqueue("new", Entry(1, "new"), 3)
            .enqueue("newest", Entry(1, "newest"), 3)
        assertNull(filled.take("old", ::stamped).second)
        assertEquals("later", filled.take("later", ::stamped).second?.single()?.sender)
        assertEquals(3, filled.count)
    }

    @Test fun actualPendingValuesRetainWireTextRadioReceiveTimeAndOrderingAcrossSelectiveRestore() {
        val radio = RadioId(UUID(309, 1))
        val otherRadio = RadioId(UUID(309, 2))
        val original = PendingReaction(
            ParsedReaction("X", "Alpha", "1234abcd"), 7u, "Beta", "@[Alpha]X\n1234abcd",
            radio, Instant.ofEpochSecond(1_704_067_200),
        )
        val foreign = original.copy(radioId = otherRadio, senderNodeName = "Foreign")
        val queue = PendingQueue.empty<String, PendingReaction>()
            .enqueue("hash", original, 100)
            .enqueue("hash", foreign, 100)
        val (remaining, matches) = queue.takeMatching("hash", { it.radioId == radio }) { value, key, entry ->
            value.copy(queueKeyOrder = key, queueEntryOrder = entry)
        }
        val selected = requireNotNull(matches).single()
        assertEquals(original, selected.copy(queueKeyOrder = null, queueEntryOrder = null))
        val restored = remaining.enqueue(
            "hash", selected, 100, selected.queueKeyOrder, selected.queueEntryOrder,
        )
        val values = restored.take("hash") { value, _, _ -> value }.second
        assertEquals(listOf(selected, foreign), values)
        assertEquals(original.receivedAt, values?.first()?.receivedAt)
        assertEquals(original.rawText, values?.first()?.rawText)
    }

    @Test fun legacyTakeStillDrainsTheEntireKeyWithoutChangingItsEntryOrder() {
        val queue = PendingQueue.empty<String, Entry>()
            .enqueue("hash", Entry(1, "first"), 100)
            .enqueue("hash", Entry(2, "foreign"), 100)
            .enqueue("hash", Entry(1, "last"), 100)
        val (remaining, matches) = queue.take("hash", ::stamped)
        assertEquals(
            listOf(Entry(1, "first", 0, 0), Entry(2, "foreign", 0, 1), Entry(1, "last", 0, 2)),
            matches,
        )
        assertEquals(0, remaining.count)
        assertNull(remaining.take("hash", ::stamped).second)
    }
}
