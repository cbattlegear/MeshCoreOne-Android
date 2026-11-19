// AndroidOnly: WP-217 Source controls for immutable fixture identities and deferred builders.
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.services.simulator.SimulatorTestSupport.native
import com.meshcoreone.android.core.services.simulator.SimulatorTestSupport.oracleNow
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class SimulatorFixtureIdentityPlanTest {
    @TestFactory
    fun pinnedFixtureEquivalence(): List<DynamicTest> = listOf(
        native("every deferred family matches the pinned Kotlin fixture and its sidecars") {
            val instants = listOf(
                oracleNow,
                oracleNow.plusNanos(750_000_000),
                oracleNow.plusSeconds(86_400).plusNanos(123_456_789),
            )
            for (now in instants) {
                val plan = simulatorFixtureIdentityPlan(now)
                assertEquals(15, plan.families.size)
                assertEquals(11, plan.families.count { it.parent is SimulatorFixtureParent.Direct })
                assertEquals(4, plan.families.count { it.parent is SimulatorFixtureParent.Channel })
                assertEquals(23, plan.families.filter { it.parent is SimulatorFixtureParent.Direct }.sumOf { it.messages.size })
                assertEquals(91, plan.families.filter { it.parent is SimulatorFixtureParent.Channel }.sumOf { it.messages.size })
                assertEquals(6, plan.families.sumOf { it.reactions.size })
                assertEquals(9, plan.families.sumOf { it.repeats.size })
                assertEquals(1, plan.families.sumOf { it.previewTargets.size })
                assertEquals(3, plan.families.sumOf { it.summaries.size })

                val previews = MockDataProvider.pinnedLinkPreviewSeeds.associateBy { it.messageID }
                val summaries = MockDataProvider.pinnedReactedMessages.associateBy { it.messageID }
                for (family in plan.families) {
                    val raw = when (val parent = family.parent) {
                        is SimulatorFixtureParent.Direct -> MockDataProvider.pinnedMessages(parent.contact.id, now)
                        is SimulatorFixtureParent.Channel -> MockDataProvider.pinnedChannelMessages(parent.channel.index, now)
                    }
                    val expectedMessages = raw.map { message ->
                        val preview = previews[message.id]
                        message.copy(
                            linkPreviewURL = preview?.url ?: message.linkPreviewURL,
                            linkPreviewTitle = preview?.title ?: message.linkPreviewTitle,
                            linkPreviewImageData = preview?.imageData ?: message.linkPreviewImageData,
                            linkPreviewIconData = if (preview != null) null else message.linkPreviewIconData,
                            linkPreviewFetched = preview != null || message.linkPreviewFetched,
                            reactionSummary = summaries[message.id]?.summary ?: message.reactionSummary,
                        )
                    }
                    val expectedReactions = raw.flatMap { MockDataProvider.pinnedReactions(it.id, now) }
                    val expectedRepeats = raw.flatMap { MockDataProvider.pinnedMessageRepeats(it.id, now) }
                    val built = family.build()
                    assertEquals(family.parent, built.parent)
                    assertEquals(expectedMessages, built.messages, "messages for ${family.parent} at $now")
                    assertEquals(expectedReactions, built.reactions, "reactions for ${family.parent} at $now")
                    assertEquals(expectedRepeats, built.repeats, "repeats for ${family.parent} at $now")
                }
            }
        },
        native("descriptor-driven reference fixtures retain the independent Swift vectors") {
            val integral = SimulatorSeedDump.lines(oracleNow)
            assertEquals(SimulatorSeedSwiftOracleVectors.DUMP_AT_INTEGRAL_NOW, integral)
            assertEquals(SimulatorSeedSwiftOracleVectors.DUMP_SHA256_AT_INTEGRAL_NOW, SimulatorSeedDump.sha256(integral))
            val fractional = Instant.ofEpochSecond(
                SimulatorSeedSwiftOracleVectors.INTEGRAL_NOW_EPOCH_SECONDS,
                750_000_000,
            )
            assertEquals(
                SimulatorSeedSwiftOracleVectors.DUMP_SHA256_AT_FRACTIONAL_NOW,
                SimulatorSeedDump.sha256(SimulatorSeedDump.lines(fractional)),
            )
        },
        native("unknown parents and sidecar targets retain the pinned empty result") {
            val unknown = UUID.fromString("deadbeef-0000-0000-0000-000000000001")
            assertEquals(MockDataProvider.pinnedMessages(unknown, oracleNow), MockDataProvider.messages(unknown, oracleNow))
            assertEquals(MockDataProvider.pinnedChannelMessages(99u, oracleNow), MockDataProvider.channelMessages(99u, oracleNow))
            assertEquals(MockDataProvider.pinnedReactions(unknown, oracleNow), MockDataProvider.reactions(unknown, oracleNow))
            assertEquals(MockDataProvider.pinnedMessageRepeats(unknown, oracleNow), MockDataProvider.messageRepeats(unknown, oracleNow))
            assertEquals(
                MockDataProvider.pinnedIncomingPathRepeats(unknown, oracleNow),
                MockDataProvider.incomingPathRepeats(unknown, oracleNow),
            )
        },
    )

    @TestFactory
    fun metadataValidation(): List<DynamicTest> = listOf(
        native("validation snapshots metadata without invoking any family builder") {
            val plan = simulatorFixtureIdentityPlan(oracleNow)
            var calls = 0
            val mutableMessages = plan.families.first().messages.toMutableList()
            val mutableFamilies = plan.families.mapIndexed { index, original ->
                family(
                    original,
                    messages = if (index == 0) mutableMessages else original.messages,
                    reactions = original.reactions,
                    repeats = original.repeats,
                    previewTargets = original.previewTargets,
                    summaries = original.summaries,
                    repeatTargets = original.repeatTargets,
                ) {
                    calls++
                    throw AssertionError("validation must not construct children")
                }
            }.toMutableList()
            val validated = SimulatorFixtureIdentityPlan.validate(mutableFamilies)
            val firstCount = validated.families.first().messages.size
            mutableMessages.clear()
            mutableFamilies.clear()
            assertEquals(0, calls)
            assertEquals(15, validated.families.size)
            assertEquals(firstCount, validated.families.first().messages.size)
            assertTrue(firstCount > 0)
        },
        native("a later invalid fixture does not prevent independent plan validation") {
            val now = Instant.ofEpochSecond(100_000)
            val plan = simulatorFixtureIdentityPlan(now)
            val alice = plan.families.single { it.parent.contactID == MockDataProvider.aliceChenID }
            val bob = plan.families.single { it.parent.contactID == MockDataProvider.bobMartinezID }
            assertEquals(9, alice.build().messages.size)
            assertFailsWith<IllegalArgumentException> { bob.build() }
        },
        native("parent IDs keys and slots are unique within each radio") {
            val plan = simulatorFixtureIdentityPlan(oracleNow)
            val contacts = plan.families.filter { it.parent is SimulatorFixtureParent.Direct }
            val alice = contacts[0]
            val bob = contacts[1]
            val aliceContact = (alice.parent as SimulatorFixtureParent.Direct).contact
            val bobContact = (bob.parent as SimulatorFixtureParent.Direct).contact
            assertInvalid {
                SimulatorFixtureIdentityPlan.validate(listOf(alice, family(bob, parent = SimulatorFixtureParent.Direct(
                    bobContact.copy(id = aliceContact.id),
                ))))
            }
            assertInvalid {
                SimulatorFixtureIdentityPlan.validate(listOf(alice, family(bob, parent = SimulatorFixtureParent.Direct(
                    bobContact.copy(publicKey = aliceContact.publicKey),
                ))))
            }
            val channels = plan.families.filter { it.parent is SimulatorFixtureParent.Channel }
            val first = channels[0]
            val second = channels[1]
            val firstChannel = (first.parent as SimulatorFixtureParent.Channel).channel
            val secondChannel = (second.parent as SimulatorFixtureParent.Channel).channel
            assertInvalid {
                SimulatorFixtureIdentityPlan.validate(listOf(first, family(second, parent = SimulatorFixtureParent.Channel(
                    secondChannel.copy(id = firstChannel.id),
                ))))
            }
            assertInvalid {
                SimulatorFixtureIdentityPlan.validate(listOf(first, family(second, parent = SimulatorFixtureParent.Channel(
                    secondChannel.copy(index = firstChannel.index),
                ))))
            }
        },
        native("cross-family IDs and normalized deduplication identities include skipped families") {
            val (first, second) = distinctContacts()
            val firstMessage = message("a0000000-0000-0000-0000-000000000001", "key-\u00e9")
            val secondMessage = message("b0000000-0000-0000-0000-000000000001", "key-e\u0301")
            val skip = family(first, messages = listOf(firstMessage))
            val create = family(second, messages = listOf(secondMessage.copy(id = firstMessage.id, deduplicationKey = null)))
            assertInvalid { SimulatorFixtureIdentityPlan.validate(listOf(skip, create)) }
            assertInvalid {
                SimulatorFixtureIdentityPlan.validate(listOf(skip, family(second, messages = listOf(secondMessage))))
            }

            val reaction = reaction("c0000000-0000-0000-0000-000000000001", firstMessage.id)
            val otherReaction = reaction.copy(messageID = secondMessage.id)
            val firstWithReaction = family(
                first, messages = listOf(firstMessage.copy(deduplicationKey = null)),
                reactions = listOf(reaction), summaries = listOf(ReactedMessage(firstMessage.id, "👍:1")),
            )
            val secondWithReaction = family(
                second, messages = listOf(secondMessage.copy(deduplicationKey = null)),
                reactions = listOf(otherReaction), summaries = listOf(ReactedMessage(secondMessage.id, "👍:1")),
            )
            assertInvalid { SimulatorFixtureIdentityPlan.validate(listOf(firstWithReaction, secondWithReaction)) }

            val repeat = repeat("d0000000-0000-0000-0000-000000000001", firstMessage.id)
            val firstWithRepeat = family(
                first, messages = listOf(firstMessage.copy(deduplicationKey = null)),
                repeats = listOf(repeat), repeatTargets = listOf(firstMessage.id),
            )
            val secondWithRepeat = family(
                second, messages = listOf(secondMessage.copy(deduplicationKey = null)),
                repeats = listOf(repeat.copy(messageID = secondMessage.id)), repeatTargets = listOf(secondMessage.id),
            )
            assertInvalid { SimulatorFixtureIdentityPlan.validate(listOf(firstWithRepeat, secondWithRepeat)) }
        },
        native("equal row identities on distinct radios remain distinct") {
            val (first, second) = distinctContacts()
            val secondContact = (second.parent as SimulatorFixtureParent.Direct).contact
            val otherRadio = RadioId(UUID.fromString("f0000000-0000-0000-0000-000000000001"))
            val id = message("a0000000-0000-0000-0000-000000000001", "key")
            val left = family(first, messages = listOf(id))
            val right = family(
                second,
                parent = SimulatorFixtureParent.Direct(secondContact.copy(radioId = otherRadio)),
                messages = listOf(id),
            )
            assertEquals(2, SimulatorFixtureIdentityPlan.validate(listOf(left, right)).families.size)
        },
        native("orphan duplicated missing or malformed sidecar identities fail without builders") {
            val (first, _) = distinctContacts()
            val id = message("a0000000-0000-0000-0000-000000000001")
            val orphan = UUID.fromString("b0000000-0000-0000-0000-000000000001")
            val reaction = reaction("c0000000-0000-0000-0000-000000000001", id.id)
            val repeat = repeat("d0000000-0000-0000-0000-000000000001", id.id)
            val bad = listOf(
                family(first, messages = listOf(id.copy(replyToID = orphan))),
                family(first, messages = listOf(id), previewTargets = listOf(orphan)),
                family(first, messages = listOf(id), previewTargets = listOf(id.id, id.id)),
                family(first, messages = listOf(id), reactions = listOf(reaction.copy(messageID = orphan))),
                family(first, messages = listOf(id), summaries = listOf(ReactedMessage(id.id, "👍:1"))),
                family(first, messages = listOf(id), reactions = listOf(reaction)),
                family(first, messages = listOf(id), reactions = listOf(reaction), summaries = listOf(ReactedMessage(id.id, "👍:2"))),
                family(first, messages = listOf(id), repeats = listOf(repeat.copy(messageID = orphan))),
                family(first, messages = listOf(id), repeatTargets = listOf(id.id)),
                family(first, messages = listOf(id), repeats = listOf(repeat)),
                family(first, messages = listOf(id), repeats = listOf(repeat.copy(rxLogEntryID = orphan)), repeatTargets = listOf(id.id)),
            )
            for (candidate in bad) assertInvalid { SimulatorFixtureIdentityPlan.validate(listOf(candidate)) }

            val duplicateLogicalReaction = family(
                first,
                messages = listOf(id),
                reactions = listOf(
                    reaction.copy(senderName = "Cafe\u0301"),
                    reaction.copy(id = orphan, senderName = "Caf\u00e9"),
                ),
                summaries = listOf(ReactedMessage(id.id, "👍:2")),
            )
            assertInvalid { SimulatorFixtureIdentityPlan.validate(listOf(duplicateLogicalReaction)) }
        },
    )

    @TestFactory
    fun builtIdentityValidation(): List<DynamicTest> = listOf(
        native("a requested family builds exactly once and preserves its failures") {
            val original = simulatorFixtureIdentityPlan(oracleNow).families.first()
            val expected = original.build()
            var calls = 0
            val once = copyFamily(original) { calls++; expected }
            assertEquals(expected, once.build())
            assertEquals(1, calls)
            val failure = IllegalArgumentException("fixture")
            assertSame(failure, assertFailsWith<IllegalArgumentException> { copyFamily(original) { throw failure }.build() })
            val cancellation = CancellationException("fixture cancelled")
            assertSame(cancellation, assertFailsWith<CancellationException> { copyFamily(original) { throw cancellation }.build() })
        },
        native("built parent radio message identity and associations must match the plan") {
            val original = simulatorFixtureIdentityPlan(oracleNow).families.first()
            val built = original.build()
            val first = built.messages.first()
            val other = UUID.fromString("f0000000-0000-0000-0000-000000000001")
            val alteredMessages = listOf(
                first.copy(id = other),
                first.copy(radioId = RadioId(other)),
                first.copy(contactID = other),
                first.copy(channelIndex = 5u),
                first.copy(deduplicationKey = "unexpected"),
                first.copy(replyToID = other),
            )
            for (message in alteredMessages) {
                val candidate = built.copy(messages = (listOf(message) + built.messages.drop(1)).snapshot())
                assertInvalid { copyFamily(original) { candidate }.build() }
            }
            val changedParent = SimulatorFixtureParent.Direct(
                (original.parent as SimulatorFixtureParent.Direct).contact.copy(id = other),
            )
            assertInvalid { copyFamily(original) { built.copy(parent = changedParent) }.build() }
            assertInvalid { copyFamily(original) { built.copy(messages = built.messages.dropLast(1).snapshot()) }.build() }
        },
        native("built sidecars previews and summaries cannot change or disappear") {
            val original = simulatorFixtureIdentityPlan(oracleNow).families.first()
            val built = original.build()
            val other = UUID.fromString("f0000000-0000-0000-0000-000000000001")
            val reaction = built.reactions.first()
            val alteredReactions = listOf(
                reaction.copy(id = other),
                reaction.copy(messageID = other),
                reaction.copy(radioId = RadioId(other)),
                reaction.copy(contactID = other),
                reaction.copy(channelIndex = 5u),
                reaction.copy(senderName = "Other"),
                reaction.copy(emoji = "x"),
                reaction.copy(messageHash = "wrong"),
                reaction.copy(rawText = "wrong"),
            )
            for (row in alteredReactions) {
                val candidate = built.copy(reactions = (listOf(row) + built.reactions.drop(1)).snapshot())
                assertInvalid { copyFamily(original) { candidate }.build() }
            }
            assertInvalid { copyFamily(original) { built.copy(reactions = built.reactions.dropLast(1).snapshot()) }.build() }
            val repeat = built.repeats.first()
            for (row in listOf(repeat.copy(id = other), repeat.copy(messageID = other), repeat.copy(rxLogEntryID = other))) {
                val candidate = built.copy(repeats = (listOf(row) + built.repeats.drop(1)).snapshot())
                assertInvalid { copyFamily(original) { candidate }.build() }
            }
            assertInvalid { copyFamily(original) { built.copy(repeats = built.repeats.dropLast(1).snapshot()) }.build() }
            val previewID = original.previewTargets.single()
            val summaryID = original.summaries.single().messageID
            for (changed in listOf(
                built.messages.map { if (it.id == previewID) it.copy(linkPreviewFetched = false) else it },
                built.messages.map { if (it.id == summaryID) it.copy(reactionSummary = null) else it },
                built.messages.map { if (it.id == summaryID) it.copy(reactionSummary = "wrong") else it },
            )) {
                assertInvalid { copyFamily(original) { built.copy(messages = changed.snapshot()) }.build() }
            }
        },
    )

    private fun distinctContacts(): Pair<SimulatorFixtureFamily, SimulatorFixtureFamily> {
        val contacts = simulatorFixtureIdentityPlan(oracleNow).families.filter { it.parent is SimulatorFixtureParent.Direct }
        return contacts[0] to contacts[1]
    }

    private fun message(id: String, key: String? = null) =
        SimulatorFixtureMessageIdentity(UUID.fromString(id), key)

    private fun reaction(id: String, target: UUID) =
        SimulatorFixtureReactionIdentity(UUID.fromString(id), target, "Sender", "👍")

    private fun repeat(id: String, target: UUID) = SimulatorFixtureRepeatIdentity(UUID.fromString(id), target)

    private fun family(
        original: SimulatorFixtureFamily,
        parent: SimulatorFixtureParent = original.parent,
        messages: Iterable<SimulatorFixtureMessageIdentity> = emptyList(),
        reactions: Iterable<SimulatorFixtureReactionIdentity> = emptyList(),
        repeats: Iterable<SimulatorFixtureRepeatIdentity> = emptyList(),
        previewTargets: Iterable<UUID> = emptyList(),
        summaries: Iterable<ReactedMessage> = emptyList(),
        repeatTargets: Iterable<UUID> = emptyList(),
        build: () -> SimulatorFixtureConversation = { error("Unexpected family build") },
    ) = SimulatorFixtureFamily(parent, messages, reactions, repeats, previewTargets, summaries, repeatTargets, build)

    private fun copyFamily(original: SimulatorFixtureFamily, build: () -> SimulatorFixtureConversation) =
        family(
            original,
            messages = original.messages,
            reactions = original.reactions,
            repeats = original.repeats,
            previewTargets = original.previewTargets,
            summaries = original.summaries,
            repeatTargets = original.repeatTargets,
            build = build,
        )

    private fun assertInvalid(block: () -> Unit) {
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException>(block = block).error)
    }
}
