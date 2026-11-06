// AndroidOnly: WP-309 Original Room window bindings, guarded replay, replacement and cancellation controls.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.CapturedChannelReactionTarget
import com.meshcoreone.android.core.contracts.domain.ChannelReactionApplyResult
import com.meshcoreone.android.core.contracts.domain.ChannelReactionTarget
import com.meshcoreone.android.core.contracts.domain.ChannelReactionTargetAuthority
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.services.reactions.ChannelReactionReplayResult
import com.meshcoreone.android.core.services.reactions.PendingReaction
import com.meshcoreone.android.core.services.reactions.ReactionParser
import com.meshcoreone.android.core.services.reactions.ReactionService
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineDependencies
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelReactionTargetConsumerTest : RoomProcessTest() {
    private val stamp = 1_704_067_200u
    private val emoji = "\uD83D\uDC4D"
    private val receivedAt = Instant.ofEpochSecond(1_704_067_987)

    private data class Fixture(
        val harness: ContainerHarness,
        val channel: ChannelDTO,
        val service: ReactionService,
        val authority: ChannelReactionTargetAuthority,
        val conversation: TimelineConversation.Channel,
        val dependencies: ChatTimelineDependencies,
    )

    private suspend fun fixture(
        test: TestScope,
        beforeApply: suspend () -> Unit = {},
        afterApply: suspend (ChannelReactionApplyResult) -> Unit = {},
        roleAvailable: Boolean = true,
        body: suspend Fixture.() -> Unit,
    ) {
        val actual = store.channelReactionTargetAuthority
        val intercepted = object : PersistenceStoreProtocol by store, ChannelReactionTargetAuthority by actual {
            override suspend fun applyChannelReaction(
                target: ChannelReactionTarget,
                reaction: ReactionDTO,
            ): ChannelReactionApplyResult {
                beforeApply()
                return actual.applyChannelReaction(target, reaction).also { afterApply(it) }
            }
        }
        val harness = ContainerHarness(
            test, store, persistenceOverride = intercepted,
            channelReactionTargets = if (roleAvailable) intercepted else null,
        )
        try {
            harness.manager.connect(harness.target())
            harness.eventually("bound history session ready") {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            val live = assertNotNull(harness.sessions.current)
            if (roleAvailable) {
                assertSame(intercepted, harness.container.channelReactionTargets)
                assertSame(intercepted, live.channelReactionTargets)
            } else {
                assertNull(harness.container.channelReactionTargets)
                assertNull(live.channelReactionTargets)
            }
            store.saveChannel(live.token.radioId, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            val channel = assertNotNull(store.fetchChannel(live.token.radioId, 3u))
            val conversation = TimelineConversation.Channel(channel)
            Fixture(
                harness, channel, live.reactionService, intercepted, conversation,
                harness.container.chatsFeature.timeline(conversation),
            ).body()
        } finally {
            harness.container.close()
        }
    }

    private fun Fixture.message(
        ordinal: Int = 0,
        sender: String? = "Alice",
        corrected: Boolean = false,
    ) = MessageDTO(
        radioId = channel.radioId, channelIndex = channel.index, text = "Hello $ordinal",
        timestamp = stamp + ordinal.toUInt() + if (corrected) 100_000u else 0u,
        senderTimestamp = if (corrected) stamp + ordinal.toUInt() else null,
        timestampCorrected = corrected, createdAt = Instant.ofEpochSecond(ordinal.toLong()),
        sortDate = Instant.ofEpochSecond(ordinal.toLong()), senderNodeName = sender,
        direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
    )

    private fun Fixture.queue(message: MessageDTO, sender: String = "Bob", radio: RadioId = channel.radioId) {
        val wire = service.buildReactionText("Alice", emoji, message.text, message.reactionTimestamp)
        service.requeuePendingReaction(PendingReaction(
            assertNotNull(ReactionParser.parse(wire)), channel.index, sender, wire, radio, receivedAt,
        ))
    }

    private suspend fun Fixture.window(): List<MessageDTO> =
        dependencies.data.fetchMessages(conversation, 51, 0)

    @Test fun originalOffsetWindowReplaysAndReturnsTheActualCommittedSnapshot() = runTest {
        fixture(this) {
            val original = message()
            store.saveMessage(original)
            queue(original)
            val prepared = dependencies.prepareMessages(window())
            val saved = assertNotNull(store.fetchMessage(EntityKey(channel.radioId, original.id)))
            assertEquals(saved, prepared.single())
            assertEquals("$emoji:1", saved.reactionSummary)
            val reaction = store.fetchReactions(EntityKey(channel.radioId, original.id)).single()
            assertEquals(receivedAt, reaction.receivedAt)
            assertEquals(ReactionParser.generateMessageHash(original.text, original.reactionTimestamp), reaction.messageHash)
            assertEquals("Bob", reaction.senderName)
        }
    }

    @Test fun equalDtoCopyCannotRecoverALostOriginalWindowReceipt() = runTest {
        fixture(this) {
            val original = message()
            store.saveMessage(original)
            queue(original)
            val fetched = window()
            val failure = runCatching { dependencies.prepareMessages(fetched.map { it.copy() }) }.exceptionOrNull()
            assertIs<PersistenceStoreException>(failure)
            assertTrue(store.fetchReactions(EntityKey(channel.radioId, original.id)).isEmpty())
            assertEquals("$emoji:1", dependencies.prepareMessages(fetched).single().reactionSummary)
        }
    }

    @Test fun absentTypedAuthorityFailsClosedInsteadOfUsingGenericChannelPersistence() = runTest {
        fixture(this, roleAvailable = false) {
            val original = message()
            store.saveMessage(original)
            queue(original)
            assertIs<PersistenceStoreException>(runCatching { window() }.exceptionOrNull())
            assertTrue(store.fetchReactions(EntityKey(channel.radioId, original.id)).isEmpty())
            assertNull(store.fetchMessage(EntityKey(channel.radioId, original.id))?.reactionSummary)
        }
    }

    @Test fun neutralOffsetAndAnchoredReadsNeverDrainOrPersistPendingReactions() = runTest {
        fixture(this) {
            val original = message()
            store.saveMessage(original)
            queue(original)
            window()
            val anchored = dependencies.data.fetchMessageWindow(conversation, original.sortDate, 500)
            assertEquals(listOf(original.id), anchored.messages.map { it.id })
            assertFalse(anchored.hasMore)
            assertNull(store.fetchMessage(EntityKey(channel.radioId, original.id))?.reactionSummary)
            assertTrue(store.fetchReactions(EntityKey(channel.radioId, original.id)).isEmpty())
            assertEquals("$emoji:1", dependencies.prepareMessages(anchored.messages).single().reactionSummary)
        }
    }

    @Test fun actualOpenLoadOlderAndAnchoredRefreshPreserveOldCorrectedRowsBeyondTwoHundred() = runTest {
        fixture(this) {
            val all = List(235) { message(it, corrected = it == 0) }
            all.forEach { store.saveMessage(it) }
            queue(all.first())
            val viewModel = ChatViewModel(conversation, dependencies, CoroutineScope(currentCoroutineContext()))
            try {
                viewModel.open()
                assertNull(viewModel.state.value.loadError)
                repeat(4) { viewModel.loadOlder() }
                assertNull(viewModel.state.value.passiveError)
                assertEquals(235, viewModel.state.value.messages.size)
                assertEquals("$emoji:1", viewModel.state.value.messages.first().reactionSummary)
                assertFalse(viewModel.state.value.hasMoreMessages)
                queue(all.first(), "Carol")
                viewModel.refresh()
                assertNull(viewModel.state.value.passiveError)
                assertEquals(235, viewModel.state.value.messages.size)
                assertEquals("$emoji:2", viewModel.state.value.messages.first().reactionSummary)
                assertEquals(2, store.fetchReactions(EntityKey(channel.radioId, all.first().id)).size)
            } finally {
                viewModel.close()
            }
        }
    }

    @Test fun unknownSenderAndForeignRadioAreSkippedWithoutDroppingTheKnownSenderReplay() = runTest {
        fixture(this) {
            val known = message()
            val unknown = message(1, sender = null)
            store.saveMessage(known)
            store.saveMessage(unknown)
            queue(known)
            queue(known, "Foreign", RadioId(UUID.randomUUID()))
            queue(unknown, "Waiting")
            val prepared = dependencies.prepareMessages(window())
            assertEquals("$emoji:1", prepared.single { it.id == known.id }.reactionSummary)
            assertNull(prepared.single { it.id == unknown.id }.reactionSummary)
            assertTrue(store.fetchReactions(EntityKey(channel.radioId, unknown.id)).isEmpty())
            val foreign = service.indexMessage(known.id, channel.index, "Alice", known.text, known.reactionTimestamp)
            assertEquals(listOf("Foreign"), foreign.map { it.senderNodeName })
            assertEquals(receivedAt, foreign.single().receivedAt)
            val waiting = service.indexMessage(unknown.id, channel.index, "Alice", unknown.text, unknown.reactionTimestamp)
            assertEquals(listOf("Waiting"), waiting.map { it.senderNodeName })
        }
    }

    @Test fun identicalSameIdReplacementAfterOriginalCaptureNeverReceivesItsPendingReaction() = runTest {
        var replace: MessageDTO? = null
        fixture(this, beforeApply = {
            replace?.let { original ->
                replace = null
                store.deleteMessage(EntityKey(original.radioId, original.id))
                store.saveMessage(original.copy())
            }
        }) {
            val original = message()
            store.saveMessage(original)
            queue(original)
            replace = original
            val viewModel = ChatViewModel(conversation, dependencies, CoroutineScope(currentCoroutineContext()))
            try {
                viewModel.open()
                assertIs<PersistenceStoreException>(viewModel.state.value.loadError)
                assertTrue(viewModel.state.value.messages.isEmpty())
                assertEquals(original, store.fetchMessage(EntityKey(channel.radioId, original.id)))
                assertTrue(store.fetchReactions(EntityKey(channel.radioId, original.id)).isEmpty())
                viewModel.open()
                assertIs<PersistenceStoreException>(viewModel.state.value.loadError)
                assertTrue(store.fetchReactions(EntityKey(channel.radioId, original.id)).isEmpty())
            } finally {
                viewModel.close()
            }
        }
    }

    @Test fun equalGenericUpsertInvalidatesOriginalAuthorityWithoutRebindingOnRefresh() = runTest {
        var rewrite: MessageDTO? = null
        fixture(this, beforeApply = {
            rewrite?.let { rewrite = null; store.saveMessage(it.copy()) }
        }) {
            val original = message()
            store.saveMessage(original)
            queue(original)
            rewrite = original
            assertIs<PersistenceStoreException>(
                runCatching { dependencies.prepareMessages(window()) }.exceptionOrNull(),
            )
            assertIs<PersistenceStoreException>(
                runCatching { dependencies.prepareMessages(window()) }.exceptionOrNull(),
            )
            assertTrue(store.fetchReactions(EntityKey(channel.radioId, original.id)).isEmpty())
        }
    }

    @Test fun targetedBenignWritesPreserveOriginalAuthorityAndTheirActualPostWriteFields() = runTest {
        var change: MessageDTO? = null
        fixture(this, beforeApply = {
            change?.let {
                change = null
                store.markMessageAsRead(EntityKey(it.radioId, it.id))
                store.updateMessageHeardRepeats(EntityKey(it.radioId, it.id), 7)
            }
        }) {
            val original = message()
            store.saveMessage(original)
            queue(original)
            change = original
            val prepared = dependencies.prepareMessages(window()).single()
            assertTrue(prepared.isRead)
            assertEquals(7L, prepared.heardRepeats)
            assertEquals("$emoji:1", prepared.reactionSummary)
            assertEquals(store.fetchMessage(EntityKey(channel.radioId, original.id)), prepared)
        }
    }

    @Test fun duplicatePendingReactionAdvancesKnownSuccessorWithoutAnotherInsertion() = runTest {
        fixture(this) {
            val original = message()
            store.saveMessage(original)
            queue(original)
            queue(original)
            val captured = authority.fetchChannelReactionWindow(channel.radioId, channel.index, 10, 0).single()
            val inserted = mutableListOf<Boolean>()
            assertIs<ChannelReactionReplayResult.Completed>(
                service.indexChannelMessageAndReplay(captured, "Alice", authority) { inserted += it.inserted },
            )
            assertEquals(listOf(true, false), inserted)
            assertEquals(1, store.fetchReactions(EntityKey(channel.radioId, original.id)).size)
        }
    }

    @Test fun commitThenThrowRemainsUnknownAndNeverReplaysAgainstANewWindowBinding() = runTest {
        var throwOnce = true
        fixture(this, afterApply = {
            if (throwOnce && it is ChannelReactionApplyResult.Applied) {
                throwOnce = false
                throw IllegalStateException("Injected lost atomic return after real commit")
            }
        }) {
            val original = message()
            store.saveMessage(original)
            queue(original)
            queue(original, "Carol")
            assertIs<IllegalStateException>(
                runCatching { dependencies.prepareMessages(window()) }.exceptionOrNull(),
            )
            assertEquals(listOf("Bob"), store.fetchReactions(EntityKey(channel.radioId, original.id)).map { it.senderName })
            assertIs<IllegalStateException>(
                runCatching { dependencies.prepareMessages(window()) }.exceptionOrNull(),
            )
            assertEquals(1, store.fetchReactions(EntityKey(channel.radioId, original.id)).size)
        }
    }

    @Test fun committedCallbackPrecedesLaterCancellationAndTheUncertainTailRemainsBound() = runTest {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        fixture(this, beforeApply = {
            if (++calls == 2) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }) {
            val original = message()
            store.saveMessage(original)
            listOf("Bob", "Carol", "Dave").forEach { queue(original, it) }
            val captured = authority.fetchChannelReactionWindow(channel.radioId, channel.index, 10, 0).single()
            val observed = mutableListOf<String>()
            val attempt = launch {
                service.indexChannelMessageAndReplay(captured, "Alice", authority) { observed += it.summary }
            }
            entered.await()
            assertEquals(listOf("$emoji:1"), observed)
            attempt.cancelAndJoin()
            assertTrue(attempt.isCancelled)
            assertEquals(listOf("Bob"), store.fetchReactions(EntityKey(channel.radioId, original.id)).map { it.senderName })
            val fresh = authority.fetchChannelReactionWindow(channel.radioId, channel.index, 10, 0).single()
            assertIs<ChannelReactionReplayResult.Failed>(
                service.indexChannelMessageAndReplay(fresh, "Alice", authority),
            )
            assertEquals(2, calls, "UNKNOWN must not start another atomic mutation")
        }
    }

    @Test fun callbackFailureRequeuesOnlyTheTailWithTheKnownOwnWriteSuccessor() = runTest {
        fixture(this) {
            val original = message()
            store.saveMessage(original)
            listOf("Bob", "Carol", "Dave").forEach { queue(original, it) }
            val captured = authority.fetchChannelReactionWindow(channel.radioId, channel.index, 10, 0).single()
            val failure = assertIs<ChannelReactionReplayResult.Failed>(
                service.indexChannelMessageAndReplay(captured, "Alice", authority) {
                    throw IllegalStateException("Injected observer failure after known commit")
                },
            )
            assertFalse(failure.mutationUncertain)
            val first = store.fetchReactions(EntityKey(channel.radioId, original.id)).single()
            assertEquals("Bob", first.senderName)
            val observed = mutableListOf<String>()
            assertIs<ChannelReactionReplayResult.Completed>(
                service.indexChannelMessageAndReplay(failure.target, "Alice", authority) { observed += it.summary },
            )
            assertEquals(listOf("$emoji:2", "$emoji:3"), observed)
            val saved = store.fetchReactions(EntityKey(channel.radioId, original.id))
            assertEquals(3, saved.size)
            assertEquals(first, saved.single { it.senderName == "Bob" })
        }
    }
}
