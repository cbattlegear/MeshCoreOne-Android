// AndroidOnly: WP-217 Deferred simulator fixture metadata bridge controls.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.data.repository.SimulatorConversationParent
import com.meshcoreone.android.core.data.repository.SimulatorMessageIdentity
import com.meshcoreone.android.core.data.repository.SimulatorReactionIdentity
import com.meshcoreone.android.core.data.repository.SimulatorReactionSummary
import com.meshcoreone.android.core.data.repository.SimulatorRepeatIdentity
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.services.simulator.SimulatorDeferredFixtureFamily
import com.meshcoreone.android.core.services.simulator.SimulatorDeferredFixturePlan
import com.meshcoreone.android.core.services.simulator.SimulatorFixtureConversation
import com.meshcoreone.android.core.services.simulator.SimulatorFixtureParent
import com.meshcoreone.android.core.services.simulator.simulatorDeferredFixturePlan
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.junit.Test

class SimulatorDeferredFixtureBridgeTest {
    private val fixtureTime = Instant.parse("2025-01-01T12:00:00Z")

    @Test fun mapsAllFixtureClaimsWithoutConstructingChildren() {
        val source = simulatorDeferredFixturePlan(fixtureTime)
        var builds = 0
        val guarded = source.withFamilies(source.families.map { family ->
            family.withBuilder {
                builds++
                error("Mapping must not build fixture children")
            }
        })

        val mapped = guarded.toRoomSimulatorIdentityPlan()

        assertEquals(0, builds)
        assertEquals(15, mapped.families.size)
        for ((fixture, stored) in source.families.zip(mapped.families)) {
            val expectedParent = when (val parent = fixture.parent) {
                is SimulatorFixtureParent.Direct -> SimulatorConversationParent.Direct(parent.contact)
                is SimulatorFixtureParent.Channel -> SimulatorConversationParent.Channel(parent.channel)
            }
            assertEquals(expectedParent, stored.parent)
            assertEquals(
                fixture.messages.map { SimulatorMessageIdentity(it.id, it.deduplicationKey, it.replyToID) },
                stored.messages,
            )
            assertEquals(
                fixture.reactions.map { SimulatorReactionIdentity(it.id, it.messageID, it.senderName, it.emoji) },
                stored.reactions,
            )
            assertEquals(
                fixture.repeats.map { SimulatorRepeatIdentity(it.id, it.messageID, it.rxLogEntryID) },
                stored.repeats,
            )
            assertEquals(fixture.previewTargets, stored.previewTargets)
            assertEquals(
                fixture.summaries.map { SimulatorReactionSummary(it.messageID, it.summary) },
                stored.summaries,
            )
            assertEquals(fixture.repeatTargets, stored.repeatTargets)
        }
    }

    @Test fun dataPlanRejectsCrossFamilyClaimsWithoutBuildingEitherFamily() {
        val source = simulatorDeferredFixturePlan(fixtureTime)
        var builds = 0
        val guarded = source.families.map { family ->
            family.withBuilder {
                builds++
                error("Validation must not build fixture children")
            }
        }
        val first = guarded[0]
        val second = guarded[1]
        val duplicateMessage = object : SimulatorDeferredFixtureFamily by second {
            override val messages = second.messages.mapIndexed { index, message ->
                if (index == 0) message.copy(id = first.messages.first().id) else message
            }.snapshot()
        }
        val duplicateKeysFirst = object : SimulatorDeferredFixtureFamily by first {
            override val messages = first.messages.mapIndexed { index, message ->
                if (index == 0) message.copy(deduplicationKey = "caf\u00e9") else message
            }.snapshot()
        }
        val duplicateKeysSecond = object : SimulatorDeferredFixtureFamily by second {
            override val messages = second.messages.mapIndexed { index, message ->
                if (index == 0) message.copy(deduplicationKey = "cafe\u0301") else message
            }.snapshot()
        }
        val reactionFamilies = guarded.filter { it.reactions.isNotEmpty() }
        val duplicateReaction = object : SimulatorDeferredFixtureFamily by reactionFamilies[1] {
            override val reactions = reactionFamilies[1].reactions.mapIndexed { index, reaction ->
                if (index == 0) reaction.copy(id = reactionFamilies[0].reactions.first().id) else reaction
            }.snapshot()
        }
        val repeatFamilies = guarded.filter { it.repeats.isNotEmpty() }
        val duplicateRepeat = object : SimulatorDeferredFixtureFamily by repeatFamilies[1] {
            override val repeats = repeatFamilies[1].repeats.mapIndexed { index, repeat ->
                if (index == 0) repeat.copy(id = repeatFamilies[0].repeats.first().id) else repeat
            }.snapshot()
        }

        for (families in listOf(
            listOf(first, duplicateMessage),
            listOf(duplicateKeysFirst, duplicateKeysSecond),
            listOf(reactionFamilies[0], duplicateReaction),
            listOf(repeatFamilies[0], duplicateRepeat),
        )) {
            val failure = assertFailsWith<PersistenceStoreException> {
                source.withFamilies(families).toRoomSimulatorIdentityPlan()
            }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
        }
        assertEquals(0, builds)
    }

    @Test fun builtConversationConversionRetainsItsActualParentAndContents() {
        val source = simulatorDeferredFixturePlan(fixtureTime)
        for (family in source.families) {
            val built = family.build()
            val seed = built.toRoomSimulatorSeed()
            val expectedParent = when (val parent = built.parent) {
                is SimulatorFixtureParent.Direct -> SimulatorConversationParent.Direct(parent.contact)
                is SimulatorFixtureParent.Channel -> SimulatorConversationParent.Channel(parent.channel)
            }
            assertEquals(expectedParent, seed.parent)
            assertSame(built.messages, seed.messages)
            assertSame(built.reactions, seed.reactions)
            assertSame(built.repeats, seed.repeats)
        }

        val built = source.families.first().build()
        val alternateParent = source.families[1].parent
        val converted = built.copy(parent = alternateParent).toRoomSimulatorSeed()
        assertEquals(
            SimulatorConversationParent.Direct((alternateParent as SimulatorFixtureParent.Direct).contact),
            converted.parent,
        )
    }

    private fun SimulatorDeferredFixturePlan.withFamilies(
        entries: Iterable<SimulatorDeferredFixtureFamily>,
    ): SimulatorDeferredFixturePlan = object : SimulatorDeferredFixturePlan {
        override val families: SnapshotList<SimulatorDeferredFixtureFamily> = entries.snapshot()
    }

    private fun SimulatorDeferredFixtureFamily.withBuilder(
        builder: () -> SimulatorFixtureConversation,
    ): SimulatorDeferredFixtureFamily = object : SimulatorDeferredFixtureFamily by this {
        override fun build(): SimulatorFixtureConversation = builder()
    }
}
