// AndroidOnly: WP-308 Actual App recovery adapter reads retained Room rows without send or origin adoption.
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.feature.chats.composer.ComposerTarget
import com.meshcoreone.android.feature.chats.composer.RecoveredDraftStateHolder
import com.meshcoreone.android.feature.chats.composer.ui.recoveredDraftLabels
import com.meshcoreone.android.feature.chats.composer.ui.RecoveredDraftList
import com.meshcoreone.android.feature.chats.composer.ui.RecoveryContinuityLabels
import com.meshcoreone.android.feature.chats.composer.ui.RecoveryContinuityNotice
import com.meshcoreone.android.feature.chats.composer.ui.RecoveryContinuityInteraction
import com.meshcoreone.android.feature.chats.composer.ui.RecoveryContinuityBinding
import com.meshcoreone.android.feature.chats.composer.ui.ForegroundRecoveryAction
import com.meshcoreone.android.feature.chats.composer.ui.UnboundRecoveryKey
import com.meshcoreone.android.feature.chats.composer.ui.UnboundRecoveryText
import com.meshcoreone.android.feature.chats.composer.ui.UnboundRecoveryList
import com.meshcoreone.android.feature.chats.composer.ui.UnboundRecoveryCopy
import com.meshcoreone.android.feature.chats.composer.ui.UnboundRecoveryCopyException
import com.meshcoreone.android.feature.chats.composer.ui.TransientRecoveryLabels
import com.meshcoreone.android.feature.chats.composer.ui.TransientRecoveryNotice
import com.meshcoreone.android.feature.chats.composer.ui.TransientRecoveryText
import com.meshcoreone.android.feature.chats.composer.ui.TransientRetention
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.ui.UiText
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31, 37], qualifiers = "en-rUS-w390dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChannelDraftRecoveryAdapterTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private val radio = RadioId(UUID(0, 1))
    private fun channel(secret: Int) = ChannelInfo(2u, "Channel", Bytes(ByteArray(16) { secret.toByte() }))
    private fun row(id: Long, origin: UUID?) = MessageDTO(
        id = UUID(0, id), radioId = radio, channelIndex = 2u,
        text = "  original \u4e2d\uD83D\uDE80 $id  ", timestamp = 100u,
        createdAt = Instant.ofEpochSecond(id), channelOccupantID = origin, status = MessageStatus.FAILED,
    )

    private enum class UnboundCopyOutcome {
        EXACT, SOURCE_CHANGED, NEWER_DRAFT, TYPED_THEN_CLEARED, SLOT_REPLACED, SLOT_REPLACED_BACK,
    }

    @Test fun mountedCopyConfirmationIsVisibleWithoutFailureAndClearsWithNextSelection() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        val source = row(701, null)
        val key = EntityKey(radio, source.id)
        val state = mutableStateOf(com.meshcoreone.android.feature.chats.composer.RecoveredDraftState(
            messages = listOf(source), hasMore = false,
        ))
        val feedback = host.get().getString(com.meshcoreone.android.core.l10n.R.string.wp308_recovery_copied)
        try {
            host.get().setContent {
                MeshCoreTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        RecoveredDraftList(
                            state = state.value, labels = recoveredDraftLabels(), destinationLabel = null,
                            originLabel = { UiText.Verbatim("Unknown origin") },
                            failureText = { error("Successful copying must not render a failure") },
                            onSelect = { state.value = state.value.copy(selected = it, copied = false) },
                            onRefresh = {}, onLoadMore = {}, onChooseChat = {}, onCopy = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            compose.onNodeWithText(feedback).assertDoesNotExist()
            compose.runOnIdle { state.value = state.value.copy(copied = true, failure = null) }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(feedback))
            compose.onNodeWithText(feedback).assertIsDisplayed().assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
                androidx.compose.ui.semantics.LiveRegionMode.Polite,
            ))
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(source.text))
            compose.onNodeWithText(source.text).performClick()
            compose.runOnIdle {
                assertEquals(key, state.value.selected)
                assertNull(state.value.failure)
            }
            compose.onNodeWithText(feedback).assertDoesNotExist()
        } finally {
            host.pause().stop().destroy()
        }
    }

    private data class WarningOperation(val operationId: UUID, val storeId: UUID, val creationNonce: UUID)
    private data class WarningObservation(val revision: Long, val retainedHistory: String)
    private data class WarningPresentation(val fixtureId: Int)
    private data class WarningRequest(
        val operation: WarningOperation,
        val acknowledgmentId: UUID,
        val expectedCurrent: WarningObservation?,
    )

    @Test fun mountedForegroundActionBindsRenderedWarningCompleteRequestAndObservationOnce() {
        mountedForegroundInteraction(changeEvidence = false)
    }

    @Test fun mountedForegroundActionResetsForEvidenceOperationVersionAndRenderedCopyChanges() {
        mountedForegroundInteraction(changeEvidence = true)
    }

    @Test fun mountedHeldForegroundDispatchIsRetiredBeforePauseInvalidationCanRun() {
        mountedForegroundInteraction(changeEvidence = false, holdDispatch = true)
    }

    @Test fun mountedHeldForegroundDispatchIsRetiredBeforeDisposeInvalidationCanRun() {
        mountedForegroundInteraction(changeEvidence = false, holdDispatch = true, disposeHeld = true)
    }

    private fun mountedForegroundInteraction(
        changeEvidence: Boolean, holdDispatch: Boolean = false, disposeHeld: Boolean = false,
    ) {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        val observation = WarningObservation(3, "complete prior record fixture")
        val request = WarningRequest(
            WarningOperation(UUID(0, 601), UUID(0, 602), UUID(0, 603)), UUID(0, 604), observation,
        )
        var nextPresentation = 1
        val binding = mutableStateOf(RecoveryContinuityBinding(
            request, observation, "loss-warning-v1", WarningPresentation(nextPresentation++),
        ))
        val warningText = mutableStateOf("Physically lost text cannot be recovered. Unsaved text can be lost when the process stops.")
        val working = mutableStateOf(false)
        val ready = mutableStateOf(false)
        val mounted = mutableStateOf(true)
        val actions = mutableListOf<ForegroundRecoveryAction<WarningRequest, WarningObservation, WarningPresentation>>()
        val invalidated = mutableListOf<WarningPresentation>()
        val retired = mutableSetOf<WarningPresentation>()
        val heldInvalidations = mutableListOf<() -> Unit>()
        val heldDispatch = mutableListOf<() -> Unit>()
        val dispatched = mutableListOf<WarningPresentation>()
        var inspections = 0
        val acknowledgment = "I understand that a new generation does not recover lost text."
        val create = "Create a new notification action generation"
        val inspect = "Inspect readable text for manual copy"
        try {
            host.get().setContent {
                MeshCoreTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        if (mounted.value) {
                            RecoveryContinuityInteraction(
                                binding = binding.value,
                                labels = RecoveryContinuityLabels(
                                    UiText.Verbatim("Notification continuity unavailable"),
                                    UiText.Verbatim("Previous installation continuity cannot be established."),
                                    UiText.Verbatim(warningText.value),
                                    UiText.Verbatim("Readable text remains unbound and copy-only. Nothing is sent."),
                                    UiText.Verbatim(acknowledgment), UiText.Verbatim(create), UiText.Verbatim(inspect),
                                ),
                                working = working.value, creationAvailable = ready.value, failure = null,
                                onForegroundAction = { action ->
                                    actions += action
                                    if (holdDispatch) heldDispatch += {
                                        if (action.isCurrentForegroundInteraction() &&
                                            action.binding.presentation !in retired
                                        ) dispatched += action.binding.presentation
                                    }
                                },
                                onRetirePresentation = { retired += it },
                                onInvalidatePresentation = { presentation ->
                                    assertTrue(presentation in retired)
                                    if (holdDispatch) heldInvalidations += { invalidated += presentation }
                                    else invalidated += presentation
                                },
                                onInspectRecoveredText = { inspections++ },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            fun node(text: String): SemanticsNodeInteraction {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
                return compose.onNodeWithText(text)
            }
            node(create).assertIsNotEnabled()
            node(inspect).performClick()
            assertEquals(1, inspections)
            assertTrue(actions.isEmpty())
            node(acknowledgment).performClick()
            node(create).assertIsNotEnabled()
            compose.runOnIdle { ready.value = true }
            node(create).assertIsEnabled()
            if (changeEvidence) {
                val changed = binding.value.observation.copy(revision = 4, retainedHistory = "changed record fixture")
                val stalePresentation = binding.value.presentation
                compose.runOnIdle {
                    binding.value = binding.value.copy(
                        observation = changed, request = request.copy(expectedCurrent = changed),
                    )
                }
                node(create).assertIsNotEnabled()
                assertEquals(1, invalidated.count { it == stalePresentation })
                // Changing evidence must not turn the same invalidated handle into a new interaction.
                compose.waitForIdle()
                node(create).assertIsNotEnabled()
                assertTrue(actions.isEmpty())
                compose.runOnIdle {
                    binding.value = binding.value.copy(
                        presentation = WarningPresentation(nextPresentation++),
                    )
                }
                node(create).assertIsNotEnabled()
                node(acknowledgment).performClick()
                compose.runOnIdle {
                    binding.value = binding.value.copy(request = binding.value.request.copy(
                        operation = request.operation.copy(creationNonce = UUID(0, 605)),
                    ), presentation = WarningPresentation(nextPresentation++))
                }
                node(create).assertIsNotEnabled()
                node(acknowledgment).performClick()
                compose.runOnIdle {
                    binding.value = binding.value.copy(
                        warningVersion = "loss-warning-v2", presentation = WarningPresentation(nextPresentation++),
                    )
                }
                node(create).assertIsNotEnabled()
                node(acknowledgment).performClick()
                compose.runOnIdle {
                    warningText.value = "Changed loss warning: physically lost text cannot be recovered."
                    binding.value = binding.value.copy(presentation = WarningPresentation(nextPresentation++))
                }
                node(create).assertIsNotEnabled()
                node(acknowledgment).performClick()
            }
            compose.runOnIdle { working.value = true }
            node(create).assertIsNotEnabled()
            compose.runOnIdle { working.value = false }
            node(create).assertIsEnabled()
            assertTrue(actions.isEmpty())
            compose.runOnIdle { host.pause() }
            val beforePause = binding.value.presentation
            compose.runOnIdle { host.resume().visible().windowFocusChanged(true) }
            node(create).assertIsNotEnabled()
            assertTrue(beforePause in retired)
            if (!holdDispatch) assertEquals(1, invalidated.count { it == beforePause })
            compose.runOnIdle {
                binding.value = binding.value.copy(presentation = WarningPresentation(nextPresentation++))
            }
            node(create).assertIsNotEnabled()
            node(acknowledgment).performClick()
            node(create).performClick()
            assertEquals(1, actions.size)
            val action = actions.single()
            assertSame(binding.value.request, action.binding.request)
            assertEquals(binding.value, action.binding)
            assertEquals(warningText.value, action.warning.lostTextWarning)
            assertEquals(acknowledgment, action.warning.acknowledgment)
            assertEquals(create, action.warning.createNewGeneration)
            assertTrue(action.isCurrentForegroundInteraction())
            node(create).assertIsNotEnabled()
            compose.waitForIdle()
            assertEquals(1, actions.size)
            if (holdDispatch) {
                assertEquals(1, heldDispatch.size)
                assertTrue(dispatched.isEmpty())
                compose.runOnIdle {
                    if (disposeHeld) mounted.value = false else host.pause()
                }
                compose.waitForIdle()
                assertFalse(action.isCurrentForegroundInteraction())
                assertTrue(action.binding.presentation in retired)
                assertTrue(action.binding.presentation !in invalidated)
                // Deliberately dispatch before allowing asynchronous ledger invalidation.
                heldDispatch.single().invoke()
                assertTrue(dispatched.isEmpty())
                assertTrue(action.binding.presentation !in invalidated)
                heldInvalidations.forEach { it() }
                assertEquals(1, invalidated.count { it == action.binding.presentation })
                return
            }
            if (changeEvidence) {
                compose.runOnIdle {
                    binding.value = binding.value.copy(
                        warningVersion = "loss-warning-v3", presentation = WarningPresentation(nextPresentation++),
                    )
                }
                compose.waitForIdle()
                assertFalse(action.isCurrentForegroundInteraction())
                node(create).assertIsNotEnabled()
                node(acknowledgment).performClick()
                node(create).performClick()
                assertEquals(2, actions.size)
            } else {
                compose.runOnIdle { host.pause() }
                assertFalse(action.isCurrentForegroundInteraction())
                compose.runOnIdle { host.resume().visible().windowFocusChanged(true) }
            }
            val last = actions.last()
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            assertFalse(last.isCurrentForegroundInteraction())
            assertEquals(1, invalidated.count { it == last.binding.presentation })
            compose.runOnIdle { mounted.value = true }
            node(create).assertIsNotEnabled()
            assertEquals(if (changeEvidence) 2 else 1, actions.size)
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test fun mountedNotRetainedNoticeCopiesOnlyExplicitLiveTextWithoutClaimingRetention() =
        mountedTransientRecovery(TransientRetention.NOT_RETAINED)

    @Test fun mountedIndeterminateNoticeKeepsUncertaintyAfterAcknowledgmentAndExplicitCopy() =
        mountedTransientRecovery(TransientRetention.INDETERMINATE)

    @Test fun mountedTransientCopyRefusesReplacedDestinationUntilExplicitReselection() =
        mountedTransientRecovery(TransientRetention.NOT_RETAINED, replaceSlot = true)

    @Test fun mountedTransientCopyRefusesReplacedBackDestinationUntilExplicitReselection() =
        mountedTransientRecovery(TransientRetention.INDETERMINATE, replaceSlot = true, replaceBack = true)

    private fun mountedTransientRecovery(
        retention: TransientRetention, replaceSlot: Boolean = false, replaceBack: Boolean = false,
    ) = runTest {
        store.saveChannel(radio, channel(1))
        val target = ComposerTarget.Channel(assertNotNull(store.fetchChannel(radio, 2u)))
        val rig = ContainerHarness(this, store)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        val source = TransientRecoveryText(
            UUID(0, 500), "  \u4e2d\uD83D\uDE80 e\u0301 \uD800 x \uDC00\r\n  ", retention,
        )
        val acknowledged = mutableStateOf(false)
        val working = mutableStateOf(false)
        val destination = mutableStateOf<UnboundRecoveryCopy.Destination?>(null)
        val failure = mutableStateOf<UiText?>(null)
        val acknowledgments = mutableListOf<TransientRecoveryText>()
        val copies = mutableListOf<TransientRecoveryText>()
        val completed = mutableListOf<TransientRecoveryText>()
        val notSaved = "This reply was not saved."
        val uncertain = "Saving this reply could not be confirmed."
        val memoryWarning = "This captured text may be lost if the process stops. Android does not guarantee its survival."
        val sendWarning = "Delivery may be uncertain. Manual copy does not send, retry or remove the original."
        val acknowledge = "Acknowledge this notice"
        val conflict = "A newer destination draft was preserved."
        try {
            val drafts = rig.container.chatsFeature.drafts
            val copier = UnboundRecoveryCopy(
                readInventory = { fail("Transient copy must not fabricate or read a durable record") },
                drafts = drafts,
            )
            host.get().setContent {
                MeshCoreTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        val scope = rememberCoroutineScope()
                        TransientRecoveryNotice(
                            text = source,
                            labels = TransientRecoveryLabels(
                                UiText.Verbatim("Reply text recovery"),
                                UiText.Verbatim(notSaved), UiText.Verbatim(uncertain),
                                UiText.Verbatim(memoryWarning), UiText.Verbatim(sendWarning),
                                UiText.Verbatim("Choose chat"), UiText.Verbatim("Copy to selected chat"),
                                UiText.Verbatim(acknowledge),
                            ),
                            destinationLabel = destination.value?.let { UiText.Verbatim("Selected chat") },
                            acknowledged = acknowledged.value, working = working.value, failure = failure.value,
                            onChooseChat = { destination.value = copier.selectDestination(target) },
                            onCopy = { displayed ->
                                val chosen = checkNotNull(destination.value)
                                copies += displayed
                                scope.launch {
                                    working.value = true
                                    try {
                                        copier.copyTransient(displayed, chosen)
                                        completed += displayed
                                    } catch (rejected: UnboundRecoveryCopyException) {
                                        assertEquals(UnboundRecoveryCopyException.Failure.DESTINATION_CHANGED,
                                            rejected.failure)
                                        failure.value = UiText.Verbatim(conflict)
                                    } finally {
                                        working.value = false
                                    }
                                }
                            },
                            onAcknowledge = {
                                acknowledgments += it
                                acknowledged.value = true
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            fun node(text: String): SemanticsNodeInteraction {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
                return compose.onNodeWithText(text)
            }
            val status = if (retention == TransientRetention.NOT_RETAINED) notSaved else uncertain
            node(status).assertIsDisplayed()
            node(memoryWarning).assertIsDisplayed()
            node(sendWarning).assertIsDisplayed()
            node("Copy to selected chat").assertIsNotEnabled()
            assertTrue(copies.isEmpty())
            node(acknowledge).performClick()
            assertEquals(listOf(source), acknowledgments)
            node(status).assertIsDisplayed()
            node(source.exactText).assertIsDisplayed()
            assertNull(drafts.get(target.conversationId))
            node("Choose chat").performClick()
            val newer = "newer destination draft"
            if (replaceSlot) {
                store.saveChannel(radio, channel(2))
                rig.appState.handleChannelSlotOccupantChanged(radio, setOf(2u))
                if (replaceBack) {
                    store.saveChannel(radio, channel(1))
                    rig.appState.handleChannelSlotOccupantChanged(radio, setOf(2u))
                }
            } else drafts.set(target.conversationId, newer)
            node("Copy to selected chat").performClick()
            compose.waitUntil(5_000) { failure.value != null }
            node(conflict).assertIsDisplayed()
            assertEquals(if (replaceSlot) null else newer, drafts.get(target.conversationId))
            assertTrue(completed.isEmpty())
            compose.runOnIdle {
                drafts.set(target.conversationId, null)
                failure.value = null
            }
            node("Choose chat").performClick()
            assertNull(drafts.get(target.conversationId))
            node("Copy to selected chat").performClick()
            compose.waitUntil(5_000) { completed.isNotEmpty() }
            assertEquals(listOf(source), completed)
            assertEquals(listOf(source, source), copies)
            assertEquals(source.exactText.toCharArray().toList(),
                assertNotNull(drafts.get(target.conversationId)).toCharArray().toList())
            node(status).assertIsDisplayed()
            node(acknowledge).assertIsNotEnabled()
            compose.runOnIdle { working.value = true }
            node("Choose chat").assertIsNotEnabled()
            node("Copy to selected chat").assertIsNotEnabled()
            assertEquals(listOf(source), completed)
            assertTrue(store.fetchMessages(radio, target.channel.index, 20, 0).isEmpty())
            assertTrue(store.fetchPendingSends(radio).isEmpty())
            assertTrue(rig.links.links.isEmpty())
        } finally {
            host.pause().stop().destroy()
            rig.container.close()
        }
    }

    @Test fun mountedUnboundCopyTransfersExactUtf16IntoActualAppDraft() =
        mountedUnboundCopy(UnboundCopyOutcome.EXACT)

    @Test fun mountedUnboundCopyRefusesChangedTextUnderSameSourceCoordinates() =
        mountedUnboundCopy(UnboundCopyOutcome.SOURCE_CHANGED)

    @Test fun mountedUnboundCopyPreservesDraftChangedDuringSourceRead() =
        mountedUnboundCopy(UnboundCopyOutcome.NEWER_DRAFT)

    @Test fun mountedUnboundCopyRefusesTypingThenClearingAfterDestinationSelection() =
        mountedUnboundCopy(UnboundCopyOutcome.TYPED_THEN_CLEARED)

    @Test fun mountedUnboundCopyRefusesSlotReplacementDuringHeldSourceRead() =
        mountedUnboundCopy(UnboundCopyOutcome.SLOT_REPLACED)

    @Test fun mountedUnboundCopyRefusesSlotReplacementBackDuringHeldSourceRead() =
        mountedUnboundCopy(UnboundCopyOutcome.SLOT_REPLACED_BACK)

    private fun mountedUnboundCopy(outcome: UnboundCopyOutcome) = runTest {
        store.saveChannel(radio, channel(1))
        val target = ComposerTarget.Channel(assertNotNull(store.fetchChannel(radio, 2u)))
        val rig = ContainerHarness(this, store)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        val source = UnboundRecoveryText(
            UnboundRecoveryKey(UUID(0, 100), UUID(0, 101), 1),
            "  \u4e2d\uD83D\uDE80 e\u0301 \uD800 x \uDC00\r\n  ",
        )
        val records = listOf(source)
        var inventory = records
        val selected = mutableStateOf<UnboundRecoveryKey?>(null)
        val destination = mutableStateOf<UnboundRecoveryCopy.Destination?>(null)
        val failure = mutableStateOf<UiText?>(null)
        val working = mutableStateOf(false)
        val readStarted = CompletableDeferred<Unit>()
        val readGate = CompletableDeferred<Unit>()
        val failures = mutableListOf<UnboundRecoveryCopyException.Failure>()
        var completed = 0
        var reads = 0
        try {
            val drafts = rig.container.chatsFeature.drafts
            val copier = UnboundRecoveryCopy(
                readInventory = {
                    reads++
                    readStarted.complete(Unit)
                    readGate.await()
                    inventory
                },
                drafts = drafts,
            )
            host.get().setContent {
                MeshCoreTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        val scope = rememberCoroutineScope()
                        UnboundRecoveryList(
                            records = records, selected = selected.value, labels = recoveredDraftLabels(),
                            unboundWarning = UiText.Verbatim("Unbound text. Manual copy does not send."),
                            destinationLabel = destination.value?.let { UiText.Verbatim("Selected chat") },
                            working = working.value, failure = failure.value,
                            onSelect = { selected.value = it }, onRefresh = {},
                            onChooseChat = { destination.value = copier.selectDestination(target) },
                            onCopy = { displayed ->
                                val chosen = checkNotNull(destination.value)
                                scope.launch {
                                    working.value = true
                                    try {
                                        copier.copy(displayed, chosen)
                                        completed++
                                    } catch (rejected: UnboundRecoveryCopyException) {
                                        failures += rejected.failure
                                        failure.value = UiText.Verbatim(rejected.failure.name)
                                    } finally {
                                        working.value = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            fun node(text: String): SemanticsNodeInteraction {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
                return compose.onNodeWithText(text)
            }
            node(source.exactText).performClick()
            node("Choose chat").performClick()
            assertEquals(0, reads)
            assertNull(drafts.get(target.conversationId))
            node("Copy to selected chat").performClick()
            compose.waitUntil(5_000) { readStarted.isCompleted }
            val newer = "newer destination text"
            if (outcome == UnboundCopyOutcome.SLOT_REPLACED ||
                outcome == UnboundCopyOutcome.SLOT_REPLACED_BACK
            ) {
                store.saveChannel(radio, channel(2))
                rig.appState.handleChannelSlotOccupantChanged(radio, setOf(2u))
                if (outcome == UnboundCopyOutcome.SLOT_REPLACED_BACK) {
                    store.saveChannel(radio, channel(1))
                    rig.appState.handleChannelSlotOccupantChanged(radio, setOf(2u))
                }
            }
            compose.runOnIdle {
                when (outcome) {
                    UnboundCopyOutcome.EXACT, UnboundCopyOutcome.SLOT_REPLACED,
                    UnboundCopyOutcome.SLOT_REPLACED_BACK -> Unit
                    UnboundCopyOutcome.SOURCE_CHANGED -> inventory = listOf(source.copy(exactText = "replacement"))
                    UnboundCopyOutcome.NEWER_DRAFT -> drafts.set(target.conversationId, newer)
                    UnboundCopyOutcome.TYPED_THEN_CLEARED -> {
                        drafts.set(target.conversationId, newer)
                        drafts.set(target.conversationId, null)
                    }
                }
                readGate.complete(Unit)
            }
            compose.waitUntil(5_000) { !working.value }
            assertEquals(1, reads)
            assertEquals(source.key, selected.value)
            when (outcome) {
                UnboundCopyOutcome.EXACT -> {
                    assertEquals(1, completed)
                    assertEquals(source.exactText.toCharArray().toList(),
                        assertNotNull(drafts.get(target.conversationId)).toCharArray().toList())
                    assertTrue(failures.isEmpty())
                    assertEquals(records, inventory)
                }
                else -> {
                    assertEquals(0, completed)
                    val expected = if (outcome == UnboundCopyOutcome.SOURCE_CHANGED) {
                        UnboundRecoveryCopyException.Failure.SOURCE_CHANGED
                    } else UnboundRecoveryCopyException.Failure.DESTINATION_CHANGED
                    assertEquals(listOf(expected), failures)
                    node(expected.name).assertIsDisplayed()
                    assertEquals(if (outcome == UnboundCopyOutcome.NEWER_DRAFT) newer else null,
                        drafts.get(target.conversationId))
                }
            }
            compose.waitForIdle()
            assertEquals(1, reads)
            assertTrue(store.fetchMessages(radio, target.channel.index, 20, 0).isEmpty())
            assertTrue(store.fetchPendingSends(radio).isEmpty())
            assertTrue(rig.links.links.isEmpty())
        } finally {
            host.pause().stop().destroy()
            rig.container.close()
        }
    }

    @Test fun mountedNoRouteInventoryRequiresExactEntryAndIndependentDestinationBeforeCopy() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        val storeId = UUID(0, 100)
        val recordId = UUID(0, 101)
        val first = UnboundRecoveryText(UnboundRecoveryKey(storeId, recordId, 1), "  original \u4e2d\uD83D\uDE80  ")
        val second = UnboundRecoveryText(UnboundRecoveryKey(storeId, recordId, 2), "changed text, separate record")
        val records = mutableStateOf(listOf(first, second))
        val selected = mutableStateOf<UnboundRecoveryKey?>(null)
        val destination = mutableStateOf<UiText?>(null)
        val failure = mutableStateOf<UiText?>(null)
        val copies = mutableListOf<UnboundRecoveryText>()
        try {
            host.get().setContent {
                MeshCoreTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        UnboundRecoveryList(
                            records = records.value, selected = selected.value,
                            labels = recoveredDraftLabels(),
                            unboundWarning = UiText.Verbatim("No authenticated destination. Copy only; delivery may be uncertain."),
                            destinationLabel = destination.value, working = false, failure = failure.value,
                            onSelect = { selected.value = it },
                            onRefresh = {},
                            onChooseChat = { destination.value = UiText.Verbatim("User-selected chat") },
                            onCopy = { copies += it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            fun node(text: String): SemanticsNodeInteraction {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
                return compose.onNodeWithText(text)
            }
            node("Copy to selected chat").assertIsNotEnabled()
            node(second.exactText).performClick()
            assertEquals(second.key, selected.value)
            node("Copy to selected chat").assertIsNotEnabled()
            node("Choose chat").performClick()
            assertTrue(copies.isEmpty())
            node("Copy to selected chat").performClick()
            assertEquals(listOf(second), copies)
            assertEquals(listOf(first, second), records.value)
            compose.runOnIdle { records.value = listOf(first) }
            node("Copy to selected chat").assertIsNotEnabled()
            compose.runOnIdle {
                failure.value = UiText.Verbatim("Selected original no longer available. Refresh before copying.")
            }
            node("Copy to selected chat").assertIsNotEnabled()
            node("Selected original no longer available. Refresh before copying.").assertIsDisplayed()
            assertEquals(listOf(second), copies)
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test fun mountedLossAwareNoticeRequiresAcknowledgmentAndProducerReadiness() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        val acknowledged = mutableStateOf(false)
        val available = mutableStateOf(false)
        val working = mutableStateOf(false)
        val failure = mutableStateOf<UiText?>(null)
        var creations = 0
        var inspections = 0
        val acknowledgment = "I understand that a new generation does not recover lost text."
        val create = "Create a new notification action generation"
        val inspect = "Inspect readable text for manual copy"
        try {
            host.get().setContent {
                MeshCoreTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        RecoveryContinuityNotice(
                            labels = RecoveryContinuityLabels(
                                UiText.Verbatim("Notification continuity unavailable"),
                                UiText.Verbatim("Previous installation continuity cannot be established."),
                                UiText.Verbatim("Physically lost text cannot be recovered. Unsaved text can be lost when the process stops."),
                                UiText.Verbatim("Readable old or unknown text has no authenticated destination. Choose a chat and copy manually; nothing is sent."),
                                UiText.Verbatim(acknowledgment),
                                UiText.Verbatim(create), UiText.Verbatim(inspect),
                            ),
                            acknowledged = acknowledged.value, working = working.value,
                            creationAvailable = available.value, failure = failure.value,
                            onAcknowledgedChange = { acknowledged.value = it },
                            onCreateNewGeneration = { creations++ },
                            onInspectRecoveredText = { inspections++ },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            fun node(text: String): SemanticsNodeInteraction {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
                return compose.onNodeWithText(text)
            }
            node(create).assertIsNotEnabled()
            node(inspect).performClick()
            assertEquals(1, inspections)
            assertEquals(0, creations)
            node(acknowledgment).performClick()
            node(create).assertIsNotEnabled()
            compose.runOnIdle { available.value = true }
            node(create).assertIsEnabled()
            assertEquals(0, creations)
            node(create).performClick()
            assertEquals(1, creations)
            compose.runOnIdle { working.value = true }
            node(create).assertIsNotEnabled()
            node(inspect).assertIsNotEnabled()
            compose.runOnIdle {
                working.value = false
                available.value = false
                acknowledged.value = false
                failure.value = UiText.Verbatim("Storage unavailable. No new generation was confirmed.")
            }
            node("Storage unavailable. No new generation was confirmed.").assertIsDisplayed()
            node(create).assertIsNotEnabled()
            assertEquals(1, creations)
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test fun replacementListAndExactCopyKeepBothKnownAndUnknownOriginalsUnchanged() = runBlocking {
        store.saveChannel(radio, channel(1))
        val originA = assertNotNull(store.fetchChannelOccupantID(radio, 2u))
        val old = row(10, originA)
        val unknown = row(11, null)
        store.saveMessage(old)
        store.saveMessage(unknown)
        store.saveChannel(radio, channel(2))
        val target = ComposerTarget.Channel(assertNotNull(store.fetchChannel(radio, 2u)))
        val current = row(12, assertNotNull(store.fetchChannelOccupantID(radio, 2u)))
        store.saveMessage(current)
        val copies = mutableListOf<Pair<MessageDTO, ComposerTarget>>()
        val adapter = ChannelDraftRecoveryAdapter(radio, store, store) { source, destination ->
            copies += source to destination
        }
        assertEquals(listOf(unknown, old), adapter.fetchPage(radio, 40, 0))
        assertTrue(copies.isEmpty())
        val changed = old.copy(text = "latest exact-key text", status = MessageStatus.SENT)
        store.saveMessage(changed)
        adapter.copyToDraft(EntityKey(radio, old.id), target)
        assertEquals(listOf(changed to target), copies)
        assertEquals(changed, store.fetchMessage(EntityKey(radio, old.id)))
        assertEquals(unknown, store.fetchMessage(EntityKey(radio, unknown.id)))
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        assertNotEquals(current.channelOccupantID, copies.single().first.channelOccupantID)
    }

    @Test fun missingOrForeignSourceNeverInvokesDestinationCopy() = runBlocking {
        val adapter = ChannelDraftRecoveryAdapter(radio, store, store) { _, _ ->
            fail("Invalid source must not reach destination copy")
        }
        val target = ComposerTarget.Channel(ChannelDTO(
            id = UUID(0, 20), radioId = radio, index = 2u, name = "Selected",
            secret = Bytes(ByteArray(16)),
        ))
        val missing = assertFailsWith<PersistenceStoreException> {
            adapter.copyToDraft(EntityKey(radio, UUID(0, 21)), target)
        }
        assertEquals(PersistenceStoreError.MessageNotFound, missing.error)
        val foreign = RadioId(UUID(0, 99))
        val rejected = assertFailsWith<PersistenceStoreException> {
            adapter.copyToDraft(EntityKey(foreign, UUID(0, 21)), target)
        }
        assertEquals(PersistenceStoreError.InvalidData, rejected.error)
        assertFailsWith<PersistenceStoreException> { adapter.fetchPage(foreign, 40, 0) }
    }

    @Test fun deletedSourceAndDestinationConflictDoNotDeleteOrEnqueueRecoveryRows() = runBlocking {
        store.saveChannel(radio, channel(1))
        val original = row(10, null)
        store.saveMessage(original)
        val target = ComposerTarget.Channel(assertNotNull(store.fetchChannel(radio, 2u)))
        val conflict = IllegalStateException("Newer destination draft")
        val adapter = ChannelDraftRecoveryAdapter(radio, store, store) { _, _ -> throw conflict }
        assertEquals(listOf(original), adapter.fetchPage(radio, 40, 0))
        assertSame(conflict, assertFailsWith<IllegalStateException> {
            adapter.copyToDraft(EntityKey(radio, original.id), target)
        })
        assertEquals(original, store.fetchMessage(EntityKey(radio, original.id)))
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        store.deleteMessage(EntityKey(radio, original.id))
        val missing = assertFailsWith<PersistenceStoreException> {
            adapter.copyToDraft(EntityKey(radio, original.id), target)
        }
        assertEquals(PersistenceStoreError.MessageNotFound, missing.error)
    }

    private enum class CopyOutcome { COMPLETED, CONFLICT, CANCELLED, CANCELLED_AFTER_WRITE }

    @Test fun mountedFont200RecoveryRequiresSelectionAndCopiesIntoAppDraftWithoutSending() =
        mountedRecovery(knownOrigin = false, outcome = CopyOutcome.COMPLETED)

    @Test fun mountedReplacedOriginCopyPreservesProofAndShowsCompletedFeedback() =
        mountedRecovery(knownOrigin = true, outcome = CopyOutcome.COMPLETED)

    @Test fun mountedDestinationConflictPreservesNewerDraftAndRecoverySelection() =
        mountedRecovery(knownOrigin = false, outcome = CopyOutcome.CONFLICT)

    @Test fun mountedCancelledCopyKeepsOriginalAndNeverShowsCompletedFeedback() =
        mountedRecovery(knownOrigin = true, outcome = CopyOutcome.CANCELLED)

    @Test fun mountedCopyCommittedBeforeCancelledReturnPreservesDestinationWithoutSuccessOrReplay() =
        mountedRecovery(knownOrigin = false, outcome = CopyOutcome.CANCELLED_AFTER_WRITE)

    @Test fun mountedDiskReopenRecoveryCopiesOldOriginWithoutChangingOrSendingOriginal() =
        mountedRecovery(knownOrigin = true, outcome = CopyOutcome.COMPLETED, reopen = true)

    private class DiskRecoveryStore {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val name = "wp308-recovery-${UUID.randomUUID()}.db"
        private var owner = SupervisorJob()
        private var database = MeshCoreDatabase.open(context, name)
        var store = RoomPersistenceStore(database, CoroutineScope(owner + Dispatchers.Default))
            private set

        suspend fun reopen() {
            release()
            owner = SupervisorJob()
            database = MeshCoreDatabase.open(context, name)
            store = RoomPersistenceStore(database, CoroutineScope(owner + Dispatchers.Default))
        }

        private suspend fun release() = withContext(NonCancellable) {
            try { store.close() } finally {
                owner.cancelAndJoin()
                database.close()
            }
        }

        suspend fun close() {
            try { release() } finally { context.deleteDatabase(name) }
        }
    }

    private fun mountedRecovery(
        knownOrigin: Boolean, outcome: CopyOutcome, reopen: Boolean = false,
    ) = runTest {
        val disk = if (reopen) DiskRecoveryStore() else null
        try {
            mountedRecoveryBody(knownOrigin, outcome, disk)
        } finally {
            disk?.close()
        }
    }

    private suspend fun TestScope.mountedRecoveryBody(
        knownOrigin: Boolean, outcome: CopyOutcome, disk: DiskRecoveryStore?,
    ) {
        var store = disk?.store ?: this@ChannelDraftRecoveryAdapterTest.store
        store.saveChannel(radio, channel(1))
        val originA = assertNotNull(store.fetchChannelOccupantID(radio, 2u))
        val originals = (1L..45L).map { row(it, if (it % 2L == 0L) originA else null) }
        originals.forEach { store.saveMessage(it) }
        store.saveChannel(radio, channel(2))
        if (disk != null) {
            disk.reopen()
            store = disk.store
            originals.forEach { assertEquals(it, store.fetchMessage(EntityKey(radio, it.id))) }
        }
        val selected = ComposerTarget.Channel(assertNotNull(store.fetchChannel(radio, 2u)))
        val rig = ContainerHarness(this, store)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        try {
            val drafts = rig.container.chatsFeature.drafts
            val enteredCopy = CompletableDeferred<Unit>()
            val wroteCopy = CompletableDeferred<Unit>()
            val copyGate = CompletableDeferred<Unit>()
            var copyJob: Job? = null
            val newerDraft = "destination text typed after selection"
            val conflict = IllegalStateException("Destination already has newer draft text")
            val failures = mutableListOf<Throwable>()
            var copyAttempts = 0
            val adapter = ChannelDraftRecoveryAdapter(radio, store, store) { current, destination ->
                copyAttempts++
                enteredCopy.complete(Unit)
                if (outcome == CopyOutcome.CANCELLED) copyGate.await()
                val revision = drafts.revision(destination.conversationId)
                if (!drafts.restoreIfUnchanged(destination.conversationId, revision, current.text)) throw conflict
                wroteCopy.complete(Unit)
                if (outcome == CopyOutcome.CANCELLED_AFTER_WRITE) copyGate.await()
            }
            val holder = RecoveredDraftStateHolder(radio, adapter, { _, failure -> failures += failure })
            holder.refresh()
            val destination = mutableStateOf<ComposerTarget?>(null)
            host.get().setContent {
                MeshCoreTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        val state by holder.state.collectAsState()
                        val scope = rememberCoroutineScope()
                        RecoveredDraftList(
                            state = state,
                            labels = recoveredDraftLabels(),
                            destinationLabel = destination.value?.let { UiText.Verbatim("Selected channel B") },
                            originLabel = { message ->
                                UiText.Resource(if (message.channelOccupantID == null) {
                                    com.meshcoreone.android.core.l10n.R.string.wp308_recovery_status_unknown_origin
                                } else {
                                    com.meshcoreone.android.core.l10n.R.string.wp308_recovery_status_replaced_occupant
                                })
                            },
                            failureText = { failure ->
                                com.meshcoreone.android.core.ui.uiString(UiText.Resource(
                                    if (failure === conflict) {
                                        com.meshcoreone.android.core.l10n.R.string.wp308_recovery_destination_conflict
                                    } else {
                                        com.meshcoreone.android.core.l10n.R.string.wp308_recovery_copy_failed
                                    },
                                ))
                            },
                            onSelect = holder::select,
                            onRefresh = { scope.launch { holder.refresh() } },
                            onLoadMore = { scope.launch { holder.loadMore() } },
                            onChooseChat = { destination.value = selected },
                            onCopy = { copyJob = scope.launch { holder.copySelectedTo(checkNotNull(destination.value)) } },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            fun node(text: String): SemanticsNodeInteraction {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
                return compose.onNodeWithText(text)
            }
            node("Copy to selected chat").assertIsNotEnabled()
            node("Load more recovered drafts").performClick()
            compose.waitUntil(5_000) { holder.state.value.messages.size == 45 }
            val source = originals[if (knownOrigin) 1 else 0]
            node(source.text).performClick()
            node("Copy to selected chat").assertIsNotEnabled()
            assertNull(drafts.get(selected.conversationId))
            node("Choose chat").performClick()
            node("Selected channel B").assertIsDisplayed()
            assertNull(drafts.get(selected.conversationId))
            if (outcome == CopyOutcome.CONFLICT) drafts.set(selected.conversationId, newerDraft)
            node("Copy to selected chat").performClick()
            compose.waitUntil(5_000) { enteredCopy.isCompleted }
            val copiedFeedback = "Text copied to the selected chat draft. This action did not send a message or remove the recovered text."
            when (outcome) {
                CopyOutcome.COMPLETED -> {
                    compose.waitUntil(5_000) { holder.state.value.copied }
                    assertEquals(source.text, drafts.get(selected.conversationId))
                    assertNull(holder.state.value.selected)
                    node(copiedFeedback).assertIsDisplayed()
                    assertTrue(failures.isEmpty())
                }
                CopyOutcome.CONFLICT -> {
                    compose.waitUntil(5_000) { holder.state.value.failure != null }
                    assertEquals(newerDraft, drafts.get(selected.conversationId))
                    assertEquals(EntityKey(radio, source.id), holder.state.value.selected)
                    assertFalse(holder.state.value.copied)
                    node("The selected chat already has draft text. Nothing was replaced. Keep or clear that draft before copying.")
                        .assertIsDisplayed()
                    compose.onNodeWithText(copiedFeedback).assertDoesNotExist()
                    assertEquals(listOf(conflict), failures)
                }
                CopyOutcome.CANCELLED, CopyOutcome.CANCELLED_AFTER_WRITE -> {
                    if (outcome == CopyOutcome.CANCELLED_AFTER_WRITE) {
                        compose.waitUntil(5_000) { wroteCopy.isCompleted }
                    }
                    assertTrue(holder.state.value.copying)
                    compose.runOnIdle { checkNotNull(copyJob).cancel() }
                    checkNotNull(copyJob).join()
                    compose.waitUntil(5_000) { !holder.state.value.copying }
                    if (outcome == CopyOutcome.CANCELLED_AFTER_WRITE) {
                        assertEquals(source.text, drafts.get(selected.conversationId))
                    } else {
                        assertNull(drafts.get(selected.conversationId))
                    }
                    assertEquals(EntityKey(radio, source.id), holder.state.value.selected)
                    assertFalse(holder.state.value.copied)
                    assertNull(holder.state.value.failure)
                    compose.onNodeWithText(copiedFeedback).assertDoesNotExist()
                    assertTrue(failures.isEmpty())
                }
            }
            assertEquals(45, holder.state.value.messages.size)
            assertEquals(1, copyAttempts)
            originals.forEach { assertEquals(it, store.fetchMessage(EntityKey(radio, it.id))) }
            assertTrue(store.fetchPendingSends(radio).isEmpty())
            assertTrue(rig.links.links.isEmpty())
        } finally {
            host.pause().stop().destroy()
            rig.container.close()
        }
    }
}
