// AndroidOnly: WP-309 Mounted actual App chat consumer; deterministic observer fixture, not device evidence.
package com.meshcoreone.android.app.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.rendering.EnvInputs
import com.meshcoreone.android.core.services.rendering.NodeNameMatchKind
import com.meshcoreone.android.core.services.rendering.NodeNameResolution
import com.meshcoreone.android.feature.chats.actions.DirectAckConfirmationStatus
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID
import java.time.Instant

@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class AppDirectAckConfirmationComposeTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun actualDirectConversationShowsSomeMessageUncertaintyWithoutSendingOrBlockingComposer() =
        mount(direct = true, supported = true, recreate = false)

    @Test fun actualChannelConversationDoesNotAcquireADirectConfirmationObserver() =
        mount(direct = false, supported = true, recreate = false)

    @Test fun actualActivityRecreationRetainsOneGraphObserverAndItsPersistentNotice() =
        mount(direct = true, supported = true, recreate = true)

    @Test fun actualDirectConversationWithMissingCapabilityStaysUnavailableAndAllowsComposition() =
        mount(direct = true, supported = false, recreate = false)

    @Test fun mountedSameRadioNewTokenCannotKeepOldCheckedTextWhileNewReadIsGated() =
        mount(direct = true, supported = true, recreate = false, ownerChange = OwnerChange.SAME_RADIO)

    @Test fun mountedDifferentRadioCannotKeepOldCheckedTextWhileNewReadIsGated() =
        mount(direct = true, supported = true, recreate = false, ownerChange = OwnerChange.DIFFERENT_RADIO)

    @Test fun mountedDisconnectCannotKeepOldCheckedTextWhileOfflineReadIsGated() =
        mount(direct = true, supported = true, recreate = false, ownerChange = OwnerChange.DISCONNECT)

    @Test fun actualFailedDirectRowAndFallbackLabelStayUnconfirmedDespiteRadioNoWildcard() =
        mount(direct = true, supported = true, recreate = false, seedStatus = MessageStatus.FAILED)

    @Test fun actualDeliveredDirectRowIsNotDowngradedByConservativeDiagnostics() =
        mount(direct = true, supported = true, recreate = false, seedStatus = MessageStatus.DELIVERED)

    @Test fun actualFailedChannelRowKeepsItsOriginalFailureStatus() =
        mount(direct = false, supported = true, recreate = false, seedStatus = MessageStatus.FAILED)

    @Test fun actualUnconfirmedFailureAtDoubleFontKeepsComposerAndRetryReachable() =
        mount(direct = true, supported = true, recreate = false,
            seedStatus = MessageStatus.FAILED, fontScale = 2f)

    @Test fun identicalFieldDatabaseReplacementDuringPausedQueryCannotAcquireOldDiagnosticIdentity() =
        mount(direct = true, supported = true, recreate = false,
            seedStatus = MessageStatus.FAILED, identicalReplacement = true)

    @Test fun actualCachedFailedFooterIsReadOnlyAndCannotClaimDefiniteDirectFailure() {
        val message = MessageDTO(
            radioId = RadioId(UUID(309, 90)), contactID = UUID(309, 91), text = "cached fixture",
            timestamp = 1_700_000_000u, status = MessageStatus.FAILED,
        )
        val cached = messageBubbleAccessibilityItem(
            message, NodeNameResolution("fixture", NodeNameMatchKind.UNRESOLVED),
            EnvInputs.DEFAULT, host.get().resources,
        )
        assertEquals(MessageStatus.FAILED, cached.footer.status)
        val label = messageBubbleAccessibilityLabel(message, cached, false, host.get().resources)
        assertTrue(label.contains(host.get().getString(R.string.direct_ack_confirmation_message_uncertain)))
        assertFalse(label.contains(host.get().getString(AppChatsStrings.chatsMessageStatusFailed)))
        assertEquals(MessageStatus.FAILED, cached.footer.status)
        assertEquals(MessageStatus.FAILED, message.status)
        val delivered = messageBubbleAccessibilityLabel(
            message.copy(status = MessageStatus.DELIVERED), cached, false, host.get().resources,
        )
        assertTrue(delivered.contains(host.get().getString(AppChatsStrings.chatsMessageStatusDelivered)))
        assertFalse(delivered.contains(host.get().getString(AppChatsStrings.chatsMessageStatusFailed)))
    }

    @Test fun mountedPreauthorityHistoryWarningExplainsIndefiniteUncertaintyWithoutBlockingComposer() =
        mount(direct = true, supported = true, recreate = false,
            reason = DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN)

    @Test fun actualRoomConsumerShowsRetainedFailureAsUnconfirmedWithRealPrehistoryEvidence() =
        mount(direct = true, supported = true, recreate = false,
            seedStatus = MessageStatus.FAILED, realPersistence = true)

    @Test fun actualRoomConsumerKeepsDeliveredDespiteRealPrehistoryWarningAndActivityRecreation() =
        mount(direct = true, supported = true, recreate = true,
            seedStatus = MessageStatus.DELIVERED, realPersistence = true)

    @Test fun persistedDualKindFailedRowInActualDirectTimelineKeepsUnconfirmedVisibleAndAccessibleLabels() =
        mount(direct = true, supported = true, recreate = true,
            seedStatus = MessageStatus.FAILED, realPersistence = true, dualKind = true)

    private enum class OwnerChange { SAME_RADIO, DIFFERENT_RADIO, DISCONNECT }

    private fun mount(
        direct: Boolean, supported: Boolean, recreate: Boolean, ownerChange: OwnerChange? = null,
        seedStatus: MessageStatus? = null, identicalReplacement: Boolean = false,
        fontScale: Float = 1f,
        realPersistence: Boolean = false,
        dualKind: Boolean = false,
        reason: DirectAckRadioConfirmationWildcardReason =
            DirectAckRadioConfirmationWildcardReason.UNTRACKED_LEGACY_ANCESTRY,
    ) = runTest {
        var subscriptions = 0
        val replacementRead = CompletableDeferred<Unit>()
        val messageReadStarted = CompletableDeferred<Unit>()
        val releaseMessageRead = CompletableDeferred<Unit>()
        val observing = object : PersistenceStoreProtocol by store, DirectAckRadioConfirmationEvidenceObserving,
            DirectMessageConfirmationEvidenceInspecting {
            override suspend fun inspectDirectMessageConfirmationEvidence(key: EntityKey): DirectMessageConfirmationEvidence {
                val oldRow = requireNotNull(store.fetchMessage(key))
                val historical = DirectMessageConfirmationEvidence.Present(
                    key, DirectMessageConfirmationIdentity("a".repeat(64), 0), oldRow.status,
                    SnapshotSet(listOf(
                        DirectMessageConfirmationUncertaintyReason.TARGET_PROVENANCE_UNPROVEN,
                        DirectMessageConfirmationUncertaintyReason.KNOWN_OTHER_CLAIM_CODE,
                    )),
                )
                messageReadStarted.complete(Unit)
                if (identicalReplacement) releaseMessageRead.await()
                return historical
            }
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId) = flow {
                subscriptions++
                emit(DirectAckRadioConfirmationObservation.Inspecting(radioId))
                if (ownerChange != null && subscriptions > 1) {
                    replacementRead.complete(Unit)
                    awaitCancellation()
                }
                emit(DirectAckRadioConfirmationObservation.Snapshot(
                    if (ownerChange != null || seedStatus != null) DirectAckRadioConfirmationEvidence.NoPersistedWildcard(radioId)
                    else DirectAckRadioConfirmationEvidence.PersistedWildcard(
                        radioId, SnapshotSet(listOf(
                            reason,
                        )),
                    ),
                ))
                awaitCancellation()
            }
        }
        val persistence: PersistenceStoreProtocol = when {
            realPersistence -> store.also {
                val candidate: PersistenceStoreProtocol = it
                require(candidate is DirectMessageConfirmationEvidenceInspecting) {
                    "Compose the genuine canonical message-evidence producer"
                }
                require(candidate is DirectAckRadioConfirmationEvidenceObserving) {
                    "Compose the genuine canonical radio-evidence observer"
                }
            }
            supported -> observing
            else -> object : PersistenceStoreProtocol by store {}
        }
        val ownerScheduler = TestCoroutineScheduler()
        val ownerScope = CoroutineScope(SupervisorJob(backgroundScope.coroutineContext[Job]) +
            StandardTestDispatcher(ownerScheduler))
        val pairing = UUID.randomUUID()
        val h = ContainerHarness(
            this, store, persistenceOverride = persistence, mainScope = ownerScope,
            identity = { target ->
                val value = if ((target as ConnectionTarget.Bluetooth).deviceId == pairing) 7 else 8
                Bytes(ByteArray(32) { value.toByte() })
            },
        )
        fun pump() {
            h.settle()
            ownerScheduler.runCurrent()
            h.settle()
        }
        try {
            h.manager.connect(h.target(pairing))
            h.eventually("mounted App initial owner ready") {
                ownerScheduler.runCurrent()
                h.manager.connectionState == DeviceConnectionState.READY && h.appState.currentRadioId != null
            }
            pump()
            h.assertReady()
            val radio = requireNotNull(h.appState.currentRadioId)
            var seeded: MessageDTO? = null
            if (direct) {
                val contact = ContactDTO(
                    radioId = radio, publicKey = Bytes(ByteArray(32) { 5 }), name = "Alice",
                    lastHeardTimestamp = null,
                )
                store.saveContact(contact)
                if (dualKind) store.saveChannel(ChannelDTO(radioId = radio, index = 3u, name = "Ambiguous ancestry"))
                if (seedStatus != null) {
                    seeded = MessageDTO(
                        radioId = radio, contactID = contact.id, channelIndex = if (dualKind) 3u else null,
                        text = "Retained history fixture",
                        timestamp = 1_700_000_000u, createdAt = Instant.ofEpochSecond(1_700_000_000),
                        status = seedStatus,
                    )
                    store.saveMessage(requireNotNull(seeded))
                }
                h.container.navigation.navigateToChat(contact)
            } else {
                val channel = ChannelDTO(radioId = radio, index = 3u, name = "General")
                store.saveChannel(channel)
                if (seedStatus != null) {
                    seeded = MessageDTO(
                        radioId = radio, channelIndex = channel.index, text = "Retained history fixture",
                        timestamp = 1_700_000_000u, createdAt = Instant.ofEpochSecond(1_700_000_000),
                        status = seedStatus,
                    )
                    store.saveMessage(requireNotNull(seeded))
                }
                h.container.navigation.navigateToChannel(channel)
            }
            val destination = h.container.navigation.state.value.activeStack.last().destination
            fun render() {
                compose.runOnUiThread {
                    host.get().setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                            MeshCoreTheme(motionScale = 0f) {
                                AppChatsContent(h.container.chatsFeature, destination, h.container.navigation)
                            }
                        }
                    }
                }
            }
            render()
            val resources = host.get().resources
            val prompt = resources.getString(if (direct) AppChatsStrings.chatsInputPlaceholderDirectMessage
                else AppChatsStrings.chatsChannelTypePrivate)
            compose.waitUntil(30_000) {
                pump()
                compose.onAllNodesWithText(prompt, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(prompt, useUnmergedTree = true).assertIsDisplayed()
            if (direct) {
                val monitorBefore = h.container.chatsFeature.directAckConfirmation
                val expected = resources.getString(if (realPersistence) R.string.direct_ack_confirmation_uncertain
                    else if (ownerChange != null ||
                    seedStatus == MessageStatus.DELIVERED || identicalReplacement) R.string.direct_ack_confirmation_checked
                    else if (supported) R.string.direct_ack_confirmation_uncertain
                    else R.string.direct_ack_confirmation_missing)
                compose.waitUntil(30_000) {
                    pump()
                    compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(expected).assertIsDisplayed()
                assertEquals(if (supported && !realPersistence) 1 else 0, subscriptions)
                val editor = compose.onNodeWithContentDescription(
                    resources.getString(AppChatsStrings.chatsInputAccessibilityLabel) + ", " +
                        resources.getString(AppChatsStrings.chatsInputEncrypted),
                )
                editor.performTextInput("Still composing")
                editor.assertTextEquals("Still composing")
                if (identicalReplacement) {
                    compose.waitUntil(30_000) { pump(); messageReadStarted.isCompleted }
                    val key = EntityKey(radio, requireNotNull(seeded).id)
                    val original = requireNotNull(store.fetchMessage(key))
                    store.deleteMessage(key)
                    store.saveMessage(original.copy())
                    assertEquals(original, store.fetchMessage(key))
                    releaseMessageRead.complete(Unit)
                    compose.waitUntil(30_000) {
                        pump()
                        h.container.chatsFeature.directAckConfirmation.messages.state.value.hasHistoricalUncertainty
                    }
                    assertTrue(h.container.chatsFeature.directAckConfirmation.messages.state.value.rowDiagnosticsUnavailable)
                    compose.onNodeWithText(resources.getString(R.string.direct_ack_confirmation_uncertain)).assertIsDisplayed()
                    compose.onNodeWithText(resources.getString(R.string.direct_ack_confirmation_message_evidence_unavailable))
                        .performScrollTo().assertIsDisplayed()
                    compose.onAllNodesWithContentDescription("a".repeat(64), substring = true).assertCountEquals(0)
                }
                if (ownerChange != null) {
                    val oldToken = requireNotNull(h.sessions.current).token
                    compose.mainClock.autoAdvance = false
                    h.manager.disconnect()
                    h.eventually("mounted owner disconnected") { h.sessions.current == null }
                    if (ownerChange != OwnerChange.DISCONNECT) {
                        val nextPairing = if (ownerChange == OwnerChange.SAME_RADIO) pairing else UUID.randomUUID()
                        h.manager.connect(h.target(nextPairing))
                        h.eventually("mounted replacement owner ready without monitor progress") {
                            h.manager.connectionState == DeviceConnectionState.READY &&
                                h.sessions.current?.token != null && h.sessions.current?.token != oldToken
                        }
                        h.assertReady()
                        assertNotEquals(oldToken, requireNotNull(h.sessions.current).token)
                        if (ownerChange == OwnerChange.SAME_RADIO) assertEquals(radio, h.sessions.current?.token?.radioId)
                        else assertNotEquals(radio, h.sessions.current?.token?.radioId)
                    }
                    // Skip the intermediate disconnected frame for replacements, and keep the
                    // monitor dispatcher frozen until the actual mounted owner fence removes it.
                    compose.waitUntil(30_000) {
                        compose.mainClock.advanceTimeByFrame()
                        compose.onAllNodesWithText(expected).fetchSemanticsNodes().isEmpty()
                    }
                    assertEquals(if (realPersistence) 0 else 1, subscriptions)
                    assertSame(monitorBefore, h.container.chatsFeature.directAckConfirmation)
                    assertEquals(oldToken, h.container.chatsFeature.directAckConfirmation.state.value.observation?.sessionToken)
                    compose.mainClock.autoAdvance = true
                    compose.waitUntil(30_000) {
                        pump()
                        replacementRead.isCompleted &&
                            compose.onAllNodesWithText(expected).fetchSemanticsNodes().isEmpty()
                    }
                    compose.onNodeWithText(expected).assertDoesNotExist()
                    compose.onNodeWithText(resources.getString(R.string.direct_ack_confirmation_title)).assertIsDisplayed()
                    editor.assertTextEquals("Still composing")
                }
                if (recreate) {
                    compose.runOnUiThread {
                        host.pause().stop().destroy()
                        createHost()
                    }
                    render()
                    compose.onNodeWithText(expected).assertIsDisplayed()
                    assertEquals(if (realPersistence) 0 else 1, subscriptions)
                    assertSame(monitorBefore, h.container.chatsFeature.directAckConfirmation)
                }
            } else {
                compose.onNodeWithText(resources.getString(R.string.direct_ack_confirmation_title)).assertDoesNotExist()
                assertEquals(0, subscriptions)
            }
            if (seedStatus != null) {
                val label = resources.getString(when {
                    seedStatus == MessageStatus.DELIVERED -> AppChatsStrings.chatsMessageStatusDelivered
                    direct -> R.string.direct_ack_confirmation_message_uncertain
                    else -> AppChatsStrings.chatsMessageStatusFailed
                })
                compose.waitUntil(30_000) {
                    pump()
                    compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                compose.onAllNodesWithContentDescription(label, substring = true).assertCountEquals(1)
                if (direct && seedStatus == MessageStatus.FAILED) {
                    compose.onAllNodesWithText(resources.getString(AppChatsStrings.chatsMessageStatusFailed))
                        .assertCountEquals(0)
                    compose.onAllNodesWithContentDescription(
                        resources.getString(AppChatsStrings.chatsMessageStatusFailed), substring = true,
                    ).assertCountEquals(0)
                    if (realPersistence) {
                        val evidence = (h.container.chatsFeature.directAckConfirmation.state.value.status as
                            DirectAckConfirmationStatus.Inspected).evidence as
                            DirectAckRadioConfirmationEvidence.PersistedWildcard
                        assertTrue(evidence.reasons.contains(
                            DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN,
                        ))
                        compose.waitUntil(30_000) {
                            pump()
                            val actual = h.container.chatsFeature.directAckConfirmation.messages.state.value
                            if (dualKind) actual.status ==
                                com.meshcoreone.android.feature.chats.actions.DirectMessageConfirmationReadStatus.HistoricalRead
                            else actual.hasHistoricalUncertainty
                        }
                        assertTrue(h.container.chatsFeature.directAckConfirmation.messages.state.value.rowDiagnosticsUnavailable)
                    } else {
                        assertTrue((h.container.chatsFeature.directAckConfirmation.state.value.status as
                            DirectAckConfirmationStatus.Inspected).evidence is DirectAckRadioConfirmationEvidence.NoPersistedWildcard)
                    }
                    assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radio, requireNotNull(seeded).id))?.status)
                    compose.onNodeWithText(resources.getString(AppChatsStrings.chatsMessageStatusRetry),
                        useUnmergedTree = true).assertIsEnabled().assertIsDisplayed()
                }
            }
            assertEquals(0, h.links.radios.sumOf { device -> device.sent.count { it[0].toInt() == 2 } })
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { host.get().setContent {} }
            compose.waitForIdle()
            try { h.container.close() } finally { ownerScope.cancel() }
        }
    }
}
