// PortedFrom: MC1/Views/Chats/ChatConversationMessagesContent.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/ChatTiledView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/ScrollToBottomButton.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/IncomingAvatarGutter.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/IncomingAvatarFlight.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/IncomingBubbleAvatarMetrics.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/ViewModel/ChatMessageBakeState+ItemBuild.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/MessageBubbleConfiguration.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/UnifiedMessageBubble.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/SenderNameLabel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.timeline.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.designsystem.toComposeColor
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.MecpReading
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.core.ui.ContactAvatar
import com.meshcoreone.android.feature.chats.actions.NodeNameMatchKind
import com.meshcoreone.android.feature.chats.actions.NodeNameResolution
import com.meshcoreone.android.feature.chats.actions.ReactionBadges
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineState
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.InitialTimelineAnchor
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.feature.chats.timeline.TimelineScrollAnchor
import com.meshcoreone.android.feature.chats.timeline.PassiveTimelineOperation
import com.meshcoreone.android.feature.chats.timeline.incomingAvatarClusterContinues
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Route wrapper: retains the supplied screen owner and performs no radio/session work. */
@Composable
fun ChatTimelineRoute(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
    messageAccessibilityLabel: ((MessageDTO) -> String)? = null,
    incomingAvatar: @Composable (MessageDTO) -> Unit = DefaultIncomingAvatar,
    messageBody: @Composable (MessageDTO, Boolean) -> Unit = DefaultMessageBody,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val callbacks = remember(viewModel, scope) {
        ChatTimelineCallbacks(
            onLoadOlder = { scope.launch { viewModel.loadOlder() } },
            onRetry = { message -> scope.launch { viewModel.retry(message.id) } },
            onInitialAnchorConsumed = viewModel::consumeInitialAnchor,
            onScrollPositionChanged = viewModel::updateScrollPosition,
            onJumpToLatest = viewModel::jumpToLatest,
            onRetryLoad = { scope.launch { viewModel.retryLoad() } },
        )
    }
    LaunchedEffect(viewModel) {
        viewModel.start()
        viewModel.open()
    }
    val isChannel = viewModel.conversation is TimelineConversation.Channel
    ChatTimeline(
        state, callbacks, modifier,
        messageAccessibilityLabel = messageAccessibilityLabel,
        showSenderName = isChannel,
        showsIncomingAvatars = isChannel,
        incomingAvatar = incomingAvatar,
        messageBody = messageBody,
    )
}

/** Plain-text body; WP-308 supplies the linkified/mention/preview body through the `messageBody` slot. */
val DefaultMessageBody: @Composable (MessageDTO, Boolean) -> Unit = { message, _ ->
    Text(MecpReading.display(message.text, LocalResources.current, "\n"), style = MaterialTheme.typography.bodyLarge)
}

data class ChatTimelineCallbacks(
    val onLoadOlder: () -> Unit,
    val onRetry: (MessageDTO) -> Unit,
    val onInitialAnchorConsumed: () -> Unit,
    val onScrollPositionChanged: (TimelineScrollAnchor?, Boolean) -> Unit,
    val onJumpToLatest: () -> Unit,
    val onRetryLoad: () -> Unit,
)

val DefaultSenderResolution: @Composable (MessageDTO) -> NodeNameResolution = { message ->
    val name = message.senderNodeName?.takeIf(String::isNotEmpty)
        ?: message.senderKeyPrefix?.takeIf { it.size >= 2 }?.prefix(2)?.uppercaseHexString()
        ?: stringResource(AppChatsStrings.chatsMessageSenderUnknown)
    NodeNameResolution(name, if (!message.senderNodeName.isNullOrEmpty()) NodeNameMatchKind.EXACT else NodeNameMatchKind.UNRESOLVED)
}

val DefaultIncomingAvatar: @Composable (MessageDTO) -> Unit = { message ->
    ContactAvatar(name = DefaultSenderResolution(message).displayName, size = 28.dp)
}

@Composable
fun ChatTimeline(
    state: ChatTimelineState,
    callbacks: ChatTimelineCallbacks,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    messageAccessibilityLabel: ((MessageDTO) -> String)? = null,
    showSenderName: Boolean = true,
    showsIncomingAvatars: Boolean = true,
    incomingAvatar: @Composable (MessageDTO) -> Unit = DefaultIncomingAvatar,
    senderResolution: @Composable (MessageDTO) -> NodeNameResolution = DefaultSenderResolution,
    // Repeated deliveries may request the same message without replacing the timeline.
    initialAnchorRequestId: Long? = null,
    onMessageActions: ((MessageDTO) -> Unit)? = null,
    onReactionDetails: ((MessageDTO, String?) -> Unit)? = null,
    messageBody: @Composable (MessageDTO, Boolean) -> Unit = DefaultMessageBody,
) {
    val visualRows = state.rows.asReversed()
    val avatarClusterEnds = remember(state.messages) {
        state.messages.mapIndexedNotNull { index, message ->
            if (message.isChannelMessage && !message.isOutgoing &&
                !incomingAvatarClusterContinues(message, state.messages.getOrNull(index + 1))
            ) message.id else null
        }.toSet()
    }
    val scope = rememberCoroutineScope()
    var savedViewportMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var savedViewportOffset by rememberSaveable { mutableIntStateOf(0) }
    val savedViewportAnchor = savedViewportMessageId?.let {
        TimelineScrollAnchor(UUID.fromString(it), savedViewportOffset)
    }
    LaunchedEffect(state.initialAnchor, state.initialAnchorConsumed, visualRows, initialAnchorRequestId) {
        if (state.initialAnchorConsumed || visualRows.isEmpty()) return@LaunchedEffect
        val position = resolveInitialTimelinePosition(state.initialAnchor, savedViewportAnchor, visualRows)
            ?: return@LaunchedEffect
        listState.scrollToItem(position.index, position.offset)
        callbacks.onInitialAnchorConsumed()
    }
    val follow = remember(listState) { TimelineFollowTracker() }
    val avatarFrames = remember { mutableStateMapOf<UUID, Rect>() }
    var avatarFlight by remember { mutableStateOf<AvatarFlight?>(null) }
    var flightJob by remember { mutableStateOf<Job?>(null) }
    val progress = remember { Animatable(0f) }
    val motionScale = LocalMeshTheme.current.motionScale
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(state.incomingAvatarHandoff) {
        val handoff = state.incomingAvatarHandoff ?: return@LaunchedEffect
        if (avatarFlight != null) {
            flightJob?.cancel()
            avatarFlight = null
            return@LaunchedEffect
        }
        val from = avatarFrames[handoff.fromId]
        val message = state.messages.lastOrNull()?.takeIf { it.id == handoff.toId }
        if (!showsIncomingAvatars || !state.initialAnchorConsumed || !follow.wasAtLatest ||
            motionScale == 0f || from == null || message == null
        ) return@LaunchedEffect
        progress.snapTo(0f)
        val flight = AvatarFlight(handoff.fromId, handoff.toId, message, from)
        avatarFlight = flight
        flightJob = scope.launch {
            try {
                progress.animateTo(1f, tween(250, easing = FastOutSlowInEasing))
            } finally {
                if (avatarFlight == flight) avatarFlight = null
            }
        }
    }
    val newestKey = visualRows.firstOrNull()?.stableKey
    LaunchedEffect(listState, newestKey, state.initialAnchorConsumed, initialAnchorRequestId) {
        val previousKey = follow.trackedNewestKey?.takeIf { key -> visualRows.any { it.stableKey == key } }
        if (state.initialAnchorConsumed) {
            val newestIsOutgoing = (visualRows.firstOrNull() as? TimelineRow.Message)?.message?.isOutgoing == true
            if (shouldFollowLatest(previousKey, newestKey, follow.wasAtLatest, newestIsOutgoing)) {
                listState.scrollToItem(0)
            }
        }
        follow.trackedNewestKey = newestKey
    }
    LaunchedEffect(listState, visualRows) {
        var previousVisibleIndices: List<Int>? = null
        val rowsByKey = visualRows.associateBy(TimelineRow::stableKey)
        snapshotFlow {
            if (newestKey != follow.trackedNewestKey) return@snapshotFlow null
            Triple(
                listState.layoutInfo.visibleItemsInfo.map { it.index to it.key },
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }.collect { viewport ->
            val (items, firstVisibleIndex, firstVisibleOffset) = viewport ?: return@collect
            if (items.isEmpty() && newestKey != null) return@collect
            val atLatest = isViewportAtLatest(items.map { (_, key) -> key }, newestKey)
            follow.wasAtLatest = atLatest
            val firstVisibleKey = items.firstOrNull { (index, _) -> index == firstVisibleIndex }?.second as? String
            val anchor = (rowsByKey[firstVisibleKey] as? TimelineRow.Message)
                ?.message?.id?.let { TimelineScrollAnchor(it, firstVisibleOffset) }
            if (anchor != null) {
                savedViewportOffset = anchor.offset
                savedViewportMessageId = anchor.messageId.toString()
            }
            callbacks.onScrollPositionChanged(anchor, atLatest)
            val visible = items.map { (index, _) -> index }
            if (visible != previousVisibleIndices &&
                visible.any { it >= visualRows.lastIndex - 3 } && state.hasMoreMessages && !state.isLoadingOlder
            ) {
                callbacks.onLoadOlder()
            }
            previousVisibleIndices = visible
        }
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { overlayOrigin = it.positionInRoot() }) {
            when {
                state.isLoading && !state.hasLoadedOnce -> {
                    CircularProgressIndicator(
                        Modifier
                            .size(48.dp)
                            .align(Alignment.Center),
                    )
                    return@Box
                }
                state.loadError != null && state.messages.isEmpty() -> {
                    LoadFailure(callbacks.onRetryLoad, Modifier.align(Alignment.Center))
                    return@Box
                }
                state.hasLoadedOnce && state.messages.isEmpty() &&
                    state.passiveError == null && state.renderError == null && state.bookkeepingError == null -> {
                    Text(
                        stringResource(AppChatsStrings.chatsRowNoMessages),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                    return@Box
                }
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize().testTag("timeline-list"),
            ) {
                if (state.passiveError != null || state.renderError != null || state.bookkeepingError != null) {
                    item("passive-error") {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(
                                        if (state.passiveError != null &&
                                            state.passiveErrorOperation == PassiveTimelineOperation.LOAD_OLDER
                                        ) AppChatsStrings.chatsErrorLoadOlderMessagesFailed
                                        else AppLocalizableStrings.commonErrorFailedToLoad,
                                    ),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                Button(onClick = callbacks.onRetryLoad, modifier = Modifier.sharedTouchTarget()) {
                                    Text(stringResource(AppLocalizableStrings.commonTryAgain))
                                }
                            }
                        }
                    }
                }
                if (state.isLoadingOlder) {
                    item("loading-older") {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                items(visualRows, key = TimelineRow::stableKey) { row ->
                    when (row) {
                        is TimelineRow.DayDivider -> DayDivider(row)
                        is TimelineRow.UnreadDivider -> UnreadDivider()
                        is TimelineRow.Message -> MessageBubble(
                            row,
                            retrying = row.message.id in state.retryingMessageIds,
                            onRetry = { callbacks.onRetry(row.message) },
                            messageBody = messageBody,
                            messageAccessibilityLabel = messageAccessibilityLabel,
                            showSenderName = showSenderName,
                            showsIncomingAvatars = showsIncomingAvatars,
                            isAvatarClusterEnd = row.message.id in avatarClusterEnds,
                            incomingAvatar = incomingAvatar,
                            senderResolution = senderResolution,
                            avatarFlight = avatarFlight,
                            onAvatarFrame = { avatarFrames[row.message.id] = it },
                            onAvatarDispose = { avatarFrames.remove(row.message.id) },
                            onMessageActions = onMessageActions,
                            onReactionDetails = onReactionDetails,
                        )
                    }
                }
            }
            avatarFlight?.let { flight ->
                Box(
                    Modifier.align(AbsoluteAlignment.TopLeft)
                        .absoluteOffset {
                            val from = avatarFrames[flight.fromId] ?: flight.initialFrom
                            val to = avatarFrames[flight.toId] ?: from
                            val fraction = progress.value
                            IntOffset(
                                (from.left + (to.left - from.left) * fraction - overlayOrigin.x).roundToInt(),
                                (from.top + (to.top - from.top) * fraction - overlayOrigin.y).roundToInt(),
                            )
                        }
                        .size(28.dp)
                        .testTag("timeline-avatar-flight")
                        .clearAndSetSemantics {},
                ) {
                    incomingAvatar(flight.message)
                }
            }
        }
        if (!state.isAtLatest || state.newMessageCount > 0) {
            val label = stringResource(AppChatsStrings.chatsScrollButtonScrollToBottomAccessibilityLabel)
            TextButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(0)
                        callbacks.onJumpToLatest()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("timeline-jump-to-latest")
                    .sharedTouchTarget()
                    .semantics { contentDescription = label },
            ) {
                Text(if (state.newMessageCount > 0) "$label (${state.newMessageCount})" else label)
            }
        }
    }
}

private data class AvatarFlight(val fromId: UUID, val toId: UUID, val message: MessageDTO, val initialFrom: Rect)

internal data class TimelineViewportPosition(val index: Int, val offset: Int)

internal fun resolveInitialTimelinePosition(
    anchor: InitialTimelineAnchor?,
    savedAnchor: TimelineScrollAnchor?,
    visualRows: List<TimelineRow>,
): TimelineViewportPosition? {
    val messageId = when (anchor) {
        InitialTimelineAnchor.Latest -> savedAnchor?.messageId
        is InitialTimelineAnchor.Message -> anchor.messageId
        null -> return null
    }
    if (messageId == null) return TimelineViewportPosition(0, 0)
    val index = visualRows.indexOfFirst { row ->
        val incarnation = (anchor as? InitialTimelineAnchor.Message)?.targetIncarnation
        row is TimelineRow.Message && row.message.id == messageId &&
            (incarnation == null || row.message.channelReactionIncarnation == incarnation)
    }
    if (index < 0) return if (anchor == InitialTimelineAnchor.Latest) {
        TimelineViewportPosition(0, 0)
    } else {
        null
    }
    val offset = if (anchor == InitialTimelineAnchor.Latest) savedAnchor?.offset ?: 0 else 0
    return TimelineViewportPosition(index, offset)
}

@Composable
private fun DayDivider(row: TimelineRow.DayDivider) {
    Text(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(row.date),
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics { heading() },
    )
}

@Composable
private fun UnreadDivider() {
    val label = stringResource(AppChatsStrings.chatsDividerNewMessages)
    Text(
        label,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics {
                heading()
                contentDescription = label
            },
    )
}

@Composable
private fun MessageBubble(
    row: TimelineRow.Message,
    retrying: Boolean,
    onRetry: () -> Unit,
    messageBody: @Composable (MessageDTO, Boolean) -> Unit,
    messageAccessibilityLabel: ((MessageDTO) -> String)?,
    showSenderName: Boolean,
    showsIncomingAvatars: Boolean,
    isAvatarClusterEnd: Boolean,
    incomingAvatar: @Composable (MessageDTO) -> Unit,
    senderResolution: @Composable (MessageDTO) -> NodeNameResolution,
    avatarFlight: AvatarFlight?,
    onAvatarFrame: (Rect) -> Unit,
    onAvatarDispose: () -> Unit,
    onMessageActions: ((MessageDTO) -> Unit)?,
    onReactionDetails: ((MessageDTO, String?) -> Unit)?,
) {
    val outgoing = row.message.direction == MessageDirection.OUTGOING
    val status = if (outgoing) statusText(row.message) else null
    val time = android.text.format.DateFormat.getTimeFormat(LocalContext.current)
        .format(Date.from(row.message.senderDate))
    val timeLabel = if (row.message.timestampCorrected) {
        AppChatsStrings.chatsMessageSendTimeCorrectedAccessibilityLabel(LocalResources.current, time)
    } else {
        AppChatsStrings.chatsMessageSendTimeAccessibilityLabel(LocalResources.current, time)
    }
    val roles = LocalMeshTheme.current.roles
    val resolution = if (!outgoing && row.message.isChannelMessage) senderResolution(row.message) else null
    val possibleMatch = stringResource(AppChatsStrings.chatsMessageSenderPossibleMatch)
    val unverified = stringResource(AppChatsStrings.chatsMessageSenderUnverifiedNicknameAccessibilityLabel)
    val rawSenderLabel = resolution?.unverifiedNickname?.let {
        AppChatsStrings.chatsMessageSenderUnverifiedNicknameFormat(LocalResources.current, resolution.displayName)
    }
    val spokenBody = MecpReading.display(row.message.text, LocalResources.current)
    val narrative = messageAccessibilityLabel?.invoke(row.message) ?: buildString {
        if (resolution != null) {
            if (resolution.unverifiedNickname != null) {
                append(resolution.unverifiedNickname).append(' ').append(rawSenderLabel)
                append(". ").append(unverified)
            } else append(resolution.displayName)
            append(". ")
            if (resolution.isFallback) append(possibleMatch).append(". ")
        }
        append(spokenBody)
        if (status != null) {
            append(". ")
            append(status)
        }
    }
    val accessibilityLabel = "$narrative. $timeLabel"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = if (outgoing) 48.dp else 12.dp,
                end = if (outgoing) 12.dp else 48.dp,
                top = if (row.startsGroup) 6.dp else 1.dp,
                bottom = 1.dp,
            )
            .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel },
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (showsIncomingAvatars && !outgoing && row.message.isChannelMessage) {
            val diameter = 28.dp
            DisposableEffect(row.message.id) {
                onDispose { onAvatarDispose() }
            }
            val isFlying = avatarFlight?.let { row.message.id == it.fromId || row.message.id == it.toId } == true
            val frameModifier = Modifier.size(diameter).onGloballyPositioned {
                if (isAvatarClusterEnd || isFlying) {
                    onAvatarFrame(Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat())))
                }
            }
            if (isAvatarClusterEnd) {
                Box(frameModifier.graphicsLayer { alpha = if (isFlying) 0f else 1f }
                    .testTag("timeline-avatar:${row.message.id}").clearAndSetSemantics {}) {
                    incomingAvatar(row.message)
                }
            } else {
                Spacer(frameModifier.testTag("timeline-avatar-spacer:${row.message.id}"))
            }
            Spacer(Modifier.width(6.dp))
        }
        val actionsLabel = stringResource(AppChatsStrings.chatsMessageActionDetails)
        val currentActions by rememberUpdatedState(onMessageActions)
        val currentMessage by rememberUpdatedState(row.message)
        val actionsModifier = if (onMessageActions == null) Modifier else Modifier
            .sharedTouchTarget()
            .onKeyEvent { event ->
                if (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar) {
                    if (event.type == KeyEventType.KeyUp) currentActions?.invoke(currentMessage)
                    true
                } else false
            }
            .focusable()
            .semantics {
                onClick(label = actionsLabel) { currentActions?.invoke(currentMessage); true }
            }
            .pointerInput(
                row.message.id, row.message.radioId, row.message.contactID, row.message.channelIndex,
                row.message.channelOccupantID, row.message.channelReactionIncarnation,
            ) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val pressedMessage = currentMessage
                    val pressedActions = currentActions
                    // Let nested links receive taps; observe their down before waiting for a long press.
                    awaitPointerEvent(PointerEventPass.Final)
                    if (awaitLongPressOrCancellation(down.id) != null) {
                        pressedActions?.invoke(pressedMessage)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    } else if (currentEvent.changes.any { it.id == down.id && !it.pressed && !it.isConsumed }) {
                        pressedActions?.invoke(pressedMessage)
                    }
                }
            }
        Surface(
            modifier = Modifier.widthIn(max = 640.dp).testTag("timeline-bubble:${row.message.id}").then(actionsModifier),
            color = (if (outgoing) roles.outgoingBubble else roles.incomingBubble).toComposeColor(),
            contentColor = (if (outgoing) roles.onOutgoingBubble else roles.onIncomingBubble).toComposeColor(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (showSenderName && !outgoing && row.message.isChannelMessage && row.startsGroup) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val sender = checkNotNull(resolution)
                        if (sender.unverifiedNickname != null) {
                            Text(sender.unverifiedNickname, style = MaterialTheme.typography.labelMedium)
                            Text(checkNotNull(rawSenderLabel), style = MaterialTheme.typography.labelMedium)
                            Text(unverified, style = MaterialTheme.typography.labelMedium)
                        } else Text(sender.displayName, style = MaterialTheme.typography.labelMedium)
                        if (sender.isFallback) {
                            Text(possibleMatch, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                messageBody(row.message, outgoing)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.widthIn(min = if (outgoing) 148.dp else 48.dp).padding(top = 4.dp),
                ) {
                    if (row.message.timestampCorrected) {
                        Icon(MeshSymbol.WARNING.vector, contentDescription = null,
                            modifier = Modifier.size(14.dp).testTag("corrected-time-indicator"))
                    }
                    Text(time, style = MaterialTheme.typography.labelSmall)
                    if (status != null) Text(status, style = MaterialTheme.typography.labelSmall)
                }
                if (outgoing && row.message.status == MessageStatus.FAILED) {
                    Button(
                        onClick = onRetry,
                        enabled = !retrying,
                        modifier = Modifier.sharedTouchTarget(),
                    ) {
                        Text(stringResource(AppChatsStrings.chatsMessageStatusRetry))
                    }
                }
                ReactionBadges(
                    row.message.reactionSummary,
                    onSelect = onReactionDetails?.let { showDetails -> { emoji: String? -> showDetails(row.message, emoji) } },
                    contentColor = (if (outgoing) roles.onOutgoingBubble else roles.onIncomingBubble).toComposeColor(),
                )
            }
        }
    }
}

@Composable
private fun statusText(message: MessageDTO): String = stringResource(
    when (message.status) {
        MessageStatus.PENDING, MessageStatus.SENDING -> AppChatsStrings.chatsMessageStatusSending
        MessageStatus.SENT -> if (message.isChannelMessage) {
            AppChatsStrings.chatsMessageStatusSent
        } else {
            AppChatsStrings.chatsMessageStatusSending
        }
        MessageStatus.DELIVERED -> AppChatsStrings.chatsMessageStatusDelivered
        MessageStatus.FAILED -> AppChatsStrings.chatsMessageStatusFailed
        MessageStatus.RETRYING -> AppChatsStrings.chatsMessageStatusRetrying
    },
)

@Composable
private fun LoadFailure(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(AppChatsStrings.chatsErrorLoadConversationsFailed),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.sharedTouchTarget()) {
            Text(stringResource(AppLocalizableStrings.commonTryAgain))
        }
    }
}
