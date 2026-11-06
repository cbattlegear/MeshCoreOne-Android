// AndroidOnly: WP-309 Incarnation-safe channel replay queue selection and original ordering controls.
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.contracts.domain.CapturedChannelReactionTarget
import com.meshcoreone.android.core.contracts.domain.ChannelReactionTarget
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.ReactionDTO
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

    private fun mixedLookup(boundFirst: Boolean, equalClock: Boolean) {
        val radio = RadioId(UUID.randomUUID())
        val clock = CacheClock()
        val cache = MessageLRUCache(clock = clock)
        val service = ReactionService(cache, clock)
        val bound = capture(radio)
        val unboundId = UUID.randomUUID()
        fun indexBound() = cache.indexChannelTarget(bound, "Alice")
        fun indexUnbound() = cache.index(unboundId, 3u, "Alice", "Hello", 100u)
        if (boundFirst) indexBound() else indexUnbound()
        if (!equalClock) clock.now = Instant.EPOCH.plusSeconds(1)
        if (boundFirst) indexUnbound() else indexBound()
        val parsed = ParsedReaction("X", "Alice", ReactionParser.generateMessageHash("Hello", 100u))
        val result = service.findChannelTarget(parsed, 3u, radio)
        val expectBound = if (equalClock) boundFirst else !boundFirst
        if (expectBound) assertSame(bound, assertIs<ChannelReactionCacheLookup.Bound>(result).captured)
        else assertEquals(unboundId, assertIs<ChannelReactionCacheLookup.Unbound>(result).messageID)
    }

    @Test fun newerUnboundCollisionParksInsteadOfMutatingAnOlderBoundCandidate() = mixedLookup(true, false)
    @Test fun newerBoundCollisionWinsOverAnOlderUnboundCandidate() = mixedLookup(false, false)
    @Test fun equalClockKeepsFirstBoundBeforeLaterUnboundCandidate() = mixedLookup(true, true)
    @Test fun equalClockKeepsFirstUnboundBeforeLaterBoundCandidate() = mixedLookup(false, true)

    @Test fun unboundSameIdReindexRetiresSelectionWithoutOverwritingStoredBoundAuthority() {
        val radio = RadioId(UUID.randomUUID())
        val clock = CacheClock()
        val cache = MessageLRUCache(clock = clock)
        val service = ReactionService(cache, clock)
        val original = capture(radio)
        val parsed = ParsedReaction("X", "Alice", ReactionParser.generateMessageHash("Hello", 100u))
        cache.indexChannelTarget(original, "Alice")
        cache.index(original.message.id, 3u, "Alice", "Hello", 100u)
        assertEquals(original.message.id, assertIs<ChannelReactionCacheLookup.Unbound>(
            service.findChannelTarget(parsed, 3u, radio),
        ).messageID)
        assertSame(original, cache.lookupChannelTargets(radio, 3u, "Alice", parsed.messageHash).single().channelTarget)
    }

    @Test fun mixedCategoriesShareTheOriginalFiveCandidateLimitWithoutResurrectingPrunedBindings() {
        val radio = RadioId(UUID.randomUUID())
        val cache = MessageLRUCache(clock = CacheClock())
        val targets = List(6) { capture(radio) }
        val hash = ReactionParser.generateMessageHash("Hello", 100u)
        targets.forEachIndexed { index, target ->
            if (index % 2 == 0) cache.indexChannelTarget(target, "Alice")
            else cache.index(target.message.id, 3u, "Alice", "Hello", 100u)
        }
        assertEquals(targets.drop(1).map { it.message.id }, cache.lookupChannelCandidates(
            radio, 3u, "Alice", hash,
        ).map { it.messageID })
        assertFalse(cache.lookupChannelTargets(radio, 3u, "Alice", hash).any {
            it.channelTarget?.target === targets.first().target
        })
        cache.evictChannelTarget(targets[2])
        cache.evictChannelTarget(targets[4])
        assertEquals(listOf(targets[1], targets[3], targets[5]).map { it.message.id },
            cache.lookupChannelCandidates(radio, 3u, "Alice", hash).map { it.messageID })
    }

    @Test fun pruningAShadowedOriginalRetiresBothCategoriesInsteadOfRestoringOldAuthority() {
        val radio = RadioId(UUID.randomUUID())
        val cache = MessageLRUCache(clock = CacheClock())
        val original = capture(radio)
        val hash = ReactionParser.generateMessageHash("Hello", 100u)
        cache.indexChannelTarget(original, "Alice")
        cache.index(original.message.id, 3u, "Alice", "Hello", 100u)
        val later = List(5) { capture(radio) }
        later.forEach { cache.indexChannelTarget(it, "Alice") }
        assertTrue(cache.lookup(3u, "Alice", hash).isEmpty())
        assertEquals(later.map { it.message.id }, cache.lookupChannelCandidates(
            radio, 3u, "Alice", hash,
        ).map { it.messageID })
        later.forEach { cache.evictChannelTarget(it) }
        assertTrue(cache.lookupChannelTargets(radio, 3u, "Alice", hash).isEmpty())
        assertTrue(cache.lookupChannelCandidates(radio, 3u, "Alice", hash).isEmpty())
        assertIs<ChannelReactionCacheLookup.Miss>(ReactionService(cache).findChannelTarget(
            ParsedReaction("X", "Alice", hash), 3u, radio,
        ))
    }

    @Test fun mixedSameIdReindexMovesToTheEndBeforeFiveCandidatePruningAndEqualClockSelection() {
        val radio = RadioId(UUID.randomUUID())
        val cache = MessageLRUCache(clock = CacheClock())
        val targets = List(6) { capture(radio) }
        val hash = ReactionParser.generateMessageHash("Hello", 100u)
        targets.take(5).forEach { cache.indexChannelTarget(it, "Alice") }
        cache.index(targets[0].message.id, 3u, "Alice", "Hello", 100u)
        assertEquals(listOf(1, 2, 3, 4, 0).map { targets[it].message.id },
            cache.lookupChannelCandidates(radio, 3u, "Alice", hash).map { it.messageID })
        cache.indexChannelTarget(targets[5], "Alice")
        assertEquals(listOf(2, 3, 4, 0, 5).map { targets[it].message.id },
            cache.lookupChannelCandidates(radio, 3u, "Alice", hash).map { it.messageID })
        assertSame(targets[2], assertIs<ChannelReactionCacheLookup.Bound>(
            ReactionService(cache).findChannelTarget(ParsedReaction("X", "Alice", hash), 3u, radio),
        ).captured)
        cache.evictChannelTarget(targets[2])
        cache.evictChannelTarget(targets[3])
        cache.evictChannelTarget(targets[4])
        assertEquals(targets[0].message.id, assertIs<ChannelReactionCacheLookup.Unbound>(
            ReactionService(cache).findChannelTarget(ParsedReaction("X", "Alice", hash), 3u, radio),
        ).messageID)
    }

    @Test fun mixedCategoriesConsumeOneOfTheOriginalFiveHundredKeysAndEvictTogetherAtFiveHundredOne() {
        val radio = RadioId(UUID.randomUUID())
        val cache = MessageLRUCache(clock = CacheClock())
        val target = capture(radio)
        val unboundId = UUID.randomUUID()
        val hash = ReactionParser.generateMessageHash("Hello", 100u)
        cache.indexChannelTarget(target, "Alice")
        cache.index(unboundId, 3u, "Alice", "Hello", 100u)
        repeat(499) { cache.index(UUID.randomUUID(), 3u, "Other$it", "Hello", 100u) }
        assertEquals(listOf(target.message.id, unboundId), cache.lookupChannelCandidates(
            radio, 3u, "Alice", hash,
        ).map { it.messageID })
        cache.index(UUID.randomUUID(), 3u, "Key501", "Hello", 100u)
        assertTrue(cache.lookup(3u, "Alice", hash).isEmpty())
        assertTrue(cache.lookupChannelTargets(radio, 3u, "Alice", hash).isEmpty())
        assertTrue(cache.lookupChannelCandidates(radio, 3u, "Alice", hash).isEmpty())
        assertEquals(1, cache.lookup(3u, "Other0", hash).size)
    }

    @Test fun legacyReindexRefreshesTheWholeMixedKeysEvictionAgeButBoundSuccessorDoesNot() {
        val radio = RadioId(UUID.randomUUID())
        val cache = MessageLRUCache(capacity = 2, clock = CacheClock())
        val first = capture(radio)
        val second = capture(radio)
        val hash = ReactionParser.generateMessageHash("Hello", 100u)
        cache.indexChannelTarget(first, "First")
        cache.indexChannelTarget(second, "Second")
        cache.index(first.message.id, 3u, "First", "Hello", 100u)
        cache.index(UUID.randomUUID(), 3u, "Third", "Hello", 100u)
        assertTrue(cache.lookupChannelTargets(radio, 3u, "Second", hash).isEmpty())
        assertSame(first, cache.lookupChannelTargets(radio, 3u, "First", hash).single().channelTarget)
        val successor = CapturedChannelReactionTarget(CacheMarker(), first.message.copy(reactionSummary = "X:1"))
        assertTrue(cache.advanceChannelTarget(first, successor))
        cache.index(UUID.randomUUID(), 3u, "Fourth", "Hello", 100u)
        assertTrue(cache.lookupChannelCandidates(radio, 3u, "First", hash).isEmpty())
        assertTrue(cache.lookupChannelTargets(radio, 3u, "First", hash).isEmpty())
        assertTrue(cache.lookup(3u, "First", hash).isEmpty())
        assertEquals(1, cache.lookup(3u, "Third", hash).size)
    }

    @Test fun retiringOneBoundKeyDoesNotDropAnotherBoundKeysEvictionTracking() {
        val radio = RadioId(UUID.randomUUID())
        val cache = MessageLRUCache(capacity = 2, clock = CacheClock())
        val first = capture(radio)
        val second = capture(radio)
        val hash = ReactionParser.generateMessageHash("Hello", 100u)
        cache.indexChannelTarget(first, "First")
        cache.indexChannelTarget(second, "Second")
        cache.evictChannelTarget(first)
        cache.index(UUID.randomUUID(), 3u, "Third", "Hello", 100u)
        cache.index(UUID.randomUUID(), 3u, "Fourth", "Hello", 100u)
        assertTrue(cache.lookupChannelTargets(radio, 3u, "Second", hash).isEmpty())
        assertEquals(1, cache.lookup(3u, "Third", hash).size)
        assertEquals(1, cache.lookup(3u, "Fourth", hash).size)
    }

    @Test fun mixedCacheClearRetiresBothCategoriesWithoutAffectingRadioFilteringOrDmIndependence() {
        val radio = RadioId(UUID.randomUUID())
        val foreign = RadioId(UUID.randomUUID())
        val cache = MessageLRUCache(capacity = 1, clock = CacheClock())
        val original = capture(radio)
        val foreignTarget = capture(foreign)
        val unboundId = UUID.randomUUID()
        val contact = UUID.randomUUID()
        val dmId = UUID.randomUUID()
        val hash = ReactionParser.generateMessageHash("Hello", 100u)
        cache.indexChannelTarget(original, "Alice")
        cache.indexChannelTarget(foreignTarget, "Alice")
        cache.index(unboundId, 3u, "Alice", "Hello", 100u)
        assertEquals(listOf(original.message.id, unboundId), cache.lookupChannelCandidates(
            radio, 3u, "Alice", hash,
        ).map { it.messageID })
        assertEquals(listOf(foreignTarget.message.id, unboundId), cache.lookupChannelCandidates(
            foreign, 3u, "Alice", hash,
        ).map { it.messageID })
        cache.indexDM(dmId, contact, "Hello", 100u)
        cache.index(UUID.randomUUID(), 3u, "Other", "Hello", 100u)
        assertTrue(cache.lookupChannelCandidates(radio, 3u, "Alice", hash).isEmpty())
        assertTrue(cache.lookupChannelCandidates(foreign, 3u, "Alice", hash).isEmpty())
        assertEquals(dmId, cache.lookupDM(contact, hash).single().messageID)
        cache.indexChannelTarget(original, "Alice")
        cache.index(unboundId, 3u, "Alice", "Hello", 100u)
        cache.clear()
        assertTrue(cache.lookup(3u, "Alice", hash).isEmpty())
        assertTrue(cache.lookupChannelTargets(radio, 3u, "Alice", hash).isEmpty())
        assertTrue(cache.lookupDM(contact, hash).isEmpty())
        cache.indexChannelTarget(foreignTarget, "Alice")
        assertTrue(cache.lookupChannelCandidates(radio, 3u, "Alice", hash).isEmpty())
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

    @Test fun parkedMcoFormatsKeepTheirExactOriginalWireAndTargetOutsideNativeReplay() {
        val radio = RadioId(UUID.randomUUID())
        val target = capture(radio)
        val service = ReactionService()
        val failure = IllegalStateException("unknown original return")
        val first = ParkedChannelReaction(
            target, ReactionDTO(
                radioId = radio, messageID = target.message.id, channelIndex = 3u,
                emoji = "X", senderName = "Bob", rawText = "original v1 wire", messageHash = "mco-v1-hash",
                receivedAt = Instant.EPOCH.plusSeconds(50),
            ), ParkedChannelReactionFormat.MCO_V1, ParkedChannelReactionFailure.Stale,
        )
        val second = ParkedChannelReaction(
            target, first.reaction.copy(
                id = UUID.randomUUID(), rawText = "original v3 wire", messageHash = "mco-v3-hash",
                receivedAt = Instant.EPOCH,
            ), ParkedChannelReactionFormat.MCO_V3, ParkedChannelReactionFailure.Unknown(failure),
        )
        service.parkChannelReaction(first)
        service.parkChannelReaction(second)
        assertTrue(service.indexMessage(target.message.id, 3u, "Alice", "Hello", 100u).isEmpty())
        val retained = service.retainedChannelReactionFailures(radio)
        assertSame(first, retained[0])
        assertSame(second, retained[1])
        assertSame(failure, assertIs<ParkedChannelReactionFailure.Unknown>(retained[1].failure).cause)
        assertEquals(listOf("mco-v1-hash", "mco-v3-hash"), retained.map { it.reaction.messageHash })
        assertTrue(service.retainedChannelReactionFailures(RadioId(UUID.randomUUID())).isEmpty())
    }

    @Test fun parkedMcoQueueReportsOverflowWithoutDisplacingAnyRetainedOriginal() {
        val radio = RadioId(UUID.randomUUID())
        val target = capture(radio)
        val service = ReactionService()
        val all = List(101) { ordinal ->
            ParkedChannelReaction(
                target, ReactionDTO(
                    radioId = radio, messageID = target.message.id, channelIndex = 3u,
                    emoji = "X", senderName = ordinal.toString(), rawText = "wire $ordinal",
                    messageHash = "format-specific", receivedAt = Instant.EPOCH.minusSeconds(ordinal.toLong()),
                ), ParkedChannelReactionFormat.MCO_V3,
                ParkedChannelReactionFailure.Rejected(
                    com.meshcoreone.android.core.contracts.domain.ChannelReactionRejection.CONFLICT,
                ),
            )
        }
        all.take(100).forEach { assertIs<ChannelReactionParkResult.Retained>(service.parkChannelReaction(it)) }
        val overflow = assertIs<ChannelReactionParkResult.Overflow>(service.parkChannelReaction(all.last()))
        assertSame(all.last(), overflow.unretained)
        assertEquals(all.take(100), service.retainedChannelReactionFailures(radio))
        assertEquals(100 to 0, service.pendingCounts)
        service.clearPendingReactions()
        assertTrue(service.retainedChannelReactionFailures(radio).isEmpty())
        assertEquals(0 to 0, service.pendingCounts)
    }

    @Test fun unselectedFormatsPreserveOriginalInputNamesTimesCausesAndOrderOutsideAllNativeMatching() {
        val radio = RadioId(UUID.randomUUID())
        val foreign = RadioId(UUID.randomUUID())
        val service = ReactionService()
        val wire = service.buildReactionText("X", "Alice", "Hello", 100u)
        val all = UnselectedChannelReactionFormat.entries.mapIndexed { index, format ->
            UnselectedChannelReaction(
                rawText = wire, channelIndex = 3u, radioId = radio, senderNodeName = "Sender $index",
                localNodeName = if (index == 1) null else "Self $index",
                receivedAt = Instant.EPOCH.minusSeconds(index.toLong()), format = format,
                failure = if (index == 2) kotlinx.coroutines.CancellationException("Original selection cancelled")
                    else IllegalStateException("Original selection failed $index"),
            )
        }
        all.forEach { assertIs<UnselectedChannelReactionParkResult.Retained>(service.parkUnselectedChannelReaction(it)) }
        val retained = service.retainedUnselectedChannelReactionFailures(radio)
        all.forEachIndexed { index, work ->
            assertSame(work, retained[index])
            assertSame(work.failure, retained[index].failure)
        }
        assertTrue(service.retainedUnselectedChannelReactionFailures(foreign).isEmpty())
        assertTrue(service.indexMessage(UUID.randomUUID(), 3u, "Alice", "Hello", 100u).isEmpty())
        assertTrue(service.indexDMMessage(UUID.randomUUID(), UUID.randomUUID(), "Hello", 100u).isEmpty())
        assertTrue(service.retainedChannelReactionFailures(radio).isEmpty())
        assertEquals(3 to 0, service.pendingCounts)
        assertEquals(all, service.retainedUnselectedChannelReactionFailures(radio))
    }

    @Test fun unselectedOverflowReturnsTheExactFailedOrCancelledInputWithoutDisplacingRetainedWork() {
        val radio = RadioId(UUID.randomUUID())
        val service = ReactionService()
        val first = UnselectedChannelReaction(
            rawText = "original raw input", channelIndex = 3u, radioId = radio,
            senderNodeName = "Bob", localNodeName = "Self", receivedAt = Instant.EPOCH,
            format = UnselectedChannelReactionFormat.NATIVE, failure = IllegalStateException("Selection unavailable"),
        )
        val retained = List(100) { first.copy(rawText = "original $it") }
        retained.forEach {
            assertIs<UnselectedChannelReactionParkResult.Retained>(service.parkUnselectedChannelReaction(it))
        }
        val cancelled = first.copy(failure = kotlinx.coroutines.CancellationException("Original cancellation"))
        for (unretained in listOf(first, cancelled)) {
            val overflow = assertIs<UnselectedChannelReactionParkResult.Overflow>(
                service.parkUnselectedChannelReaction(unretained),
            )
            assertSame(unretained, overflow.unretained)
            assertSame(unretained.failure, overflow.unretained.failure)
            val actual = service.retainedUnselectedChannelReactionFailures(radio)
            retained.forEachIndexed { index, work -> assertSame(work, actual[index]) }
            assertEquals(100 to 0, service.pendingCounts)
        }
        service.clearPendingReactions()
        assertTrue(service.retainedUnselectedChannelReactionFailures(radio).isEmpty())
        assertEquals(0 to 0, service.pendingCounts)
        assertIs<UnselectedChannelReactionParkResult.Retained>(service.parkUnselectedChannelReaction(cancelled))
        assertSame(cancelled, service.retainedUnselectedChannelReactionFailures(radio).single())
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
