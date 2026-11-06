// AndroidOnly: WP-309 Incarnation-safe channel replay queue selection and original ordering controls.
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.contracts.domain.CapturedChannelReactionTarget
import com.meshcoreone.android.core.contracts.domain.ChannelReactionTarget
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.services.rendering.MessageLRUCache
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

class ChannelReactionTargetServiceTest {
    // Opaque test markers exercise cache ownership only, never storage issuance or validation.
    private class CacheMarker : ChannelReactionTarget
    private class CacheClock(var now: Instant = Instant.EPOCH) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private fun capture(radio: RadioId, text: String = "Hello", id: UUID = UUID.randomUUID()) =
        CapturedChannelReactionTarget(CacheMarker(), MessageDTO(
            id = id, radioId = radio, channelIndex = 3u, text = text, timestamp = 100u,
        ))

    @Test fun boundCacheIsRadioScopedAndLegacyEntryNeverProvidesAuthority() {
        val radio = RadioId(UUID.randomUUID())
        val foreign = RadioId(UUID.randomUUID())
        val target = capture(radio)
        val service = ReactionService()
        val parsed = ParsedReaction("X", "Alice", ReactionParser.generateMessageHash("Hello", 100u))
        service.indexMessage(target.message.id, 3u, "Alice", "Hello", 100u)
        assertIs<ChannelReactionCacheLookup.Unbound>(service.findChannelTarget(parsed, 3u, radio))
        val cache = MessageLRUCache()
        cache.indexChannelTarget(target, "Alice")
        assertSame(target, cache.lookupChannelTargets(radio, 3u, "Alice", parsed.messageHash).single().channelTarget)
        assertTrue(cache.lookupChannelTargets(foreign, 3u, "Alice", parsed.messageHash).isEmpty())
    }

    @Test fun identicalSameIdReplacementCannotBeAdvancedOrEvictedByItsPredecessor() {
        val radio = RadioId(UUID.randomUUID())
        val first = capture(radio)
        val replacement = CapturedChannelReactionTarget(CacheMarker(), first.message.copy())
        val successor = CapturedChannelReactionTarget(CacheMarker(), first.message.copy(reactionSummary = "X:1"))
        val cache = MessageLRUCache()
        cache.indexChannelTarget(first, "Alice")
        cache.indexChannelTarget(replacement, "Alice")
        assertFalse(cache.advanceChannelTarget(first, successor))
        cache.evictChannelTarget(first)
        assertSame(replacement, cache.lookupChannelTargets(
            radio, 3u, "Alice", ReactionParser.generateMessageHash("Hello", 100u),
        ).single().channelTarget)
    }

    @Test fun successorAdvancesOnlyItsExactPredecessorWithoutRefreshingArrivalOrEvictionAge() {
        val radio = RadioId(UUID.randomUUID())
        val clock = CacheClock()
        val cache = MessageLRUCache(capacity = 2, clock = clock)
        val first = capture(radio)
        val second = capture(radio, "second")
        cache.indexChannelTarget(first, "Alice")
        clock.now = Instant.EPOCH.plusSeconds(1)
        cache.indexChannelTarget(second, "Alice")
        val successor = CapturedChannelReactionTarget(CacheMarker(), first.message.copy(reactionSummary = "X:1"))
        clock.now = Instant.EPOCH.plusSeconds(2)
        assertTrue(cache.advanceChannelTarget(first, successor))
        val hash = ReactionParser.generateMessageHash("Hello", 100u)
        assertEquals(Instant.EPOCH, cache.lookupChannelTargets(radio, 3u, "Alice", hash).single().indexedAt)
        cache.indexChannelTarget(capture(radio, "third"), "Alice")
        assertTrue(cache.lookupChannelTargets(radio, 3u, "Alice", hash).isEmpty())
        assertSame(second, cache.lookupChannelTargets(
            radio, 3u, "Alice", ReactionParser.generateMessageHash("second", 100u),
        ).single().channelTarget)
    }

    @Test fun boundCollisionSelectionPreservesMostRecentIndexAndFirstEqualClockTie() {
        val radio = RadioId(UUID.randomUUID())
        val clock = CacheClock()
        val cache = MessageLRUCache(clock = clock)
        val service = ReactionService(cache, clock)
        val first = capture(radio)
        val second = capture(radio)
        cache.indexChannelTarget(first, "Alice")
        cache.indexChannelTarget(second, "Alice")
        val parsed = ParsedReaction("X", "Alice", ReactionParser.generateMessageHash("Hello", 100u))
        assertSame(first, assertIs<ChannelReactionCacheLookup.Bound>(
            service.findChannelTarget(parsed, 3u, radio),
        ).captured)
        clock.now = Instant.EPOCH.plusSeconds(1)
        cache.indexChannelTarget(second, "Alice")
        assertSame(second, assertIs<ChannelReactionCacheLookup.Bound>(
            service.findChannelTarget(parsed, 3u, radio),
        ).captured)
    }

    @Test fun parkedUnboundOriginalNeverBecomesLegacyEligibleAfterAnotherArrival() {
        val radio = RadioId(UUID.randomUUID())
        val service = ReactionService()
        val parsed = ParsedReaction("X", "Alice", ReactionParser.generateMessageHash("Hello", 100u))
        service.requeuePendingReaction(PendingReaction(
            parsed, 3u, "Bob", "original", radio, Instant.EPOCH, originalAuthorityUnavailable = true,
        ))
        assertTrue(service.indexMessage(UUID.randomUUID(), 3u, "Alice", "Hello", 100u).isEmpty())
        assertEquals(1 to 0, service.pendingCounts)
    }

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
