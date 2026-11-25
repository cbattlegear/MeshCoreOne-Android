// PortedFrom: MC1Tests/Views/Chats/Models/MessageFragmentBuilderFixtures.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/MessageDTO+Testing.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Shared minimal fixtures (Swift `MessageFragmentBuilderFixtures` plus `MessageDTO.testDirectMessage`). */
internal object RenderingFixtures {
    val RADIO_ID: RadioId = RadioId(UUID.fromString("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"))
    val CONTACT_ID: UUID = UUID.fromString("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
    val REFERENCE_DATE: Instant = Instant.ofEpochSecond(1_700_000_000)
    private val REFERENCE_TIMESTAMP: UInt = REFERENCE_DATE.epochSecond.toUInt()

    fun makePlainTextMessage(index: Int): MessageDTO = MessageDTO(
        id = UUID.randomUUID(),
        radioId = RADIO_ID,
        contactID = CONTACT_ID,
        text = "Message $index",
        timestamp = REFERENCE_TIMESTAMP,
        createdAt = REFERENCE_DATE,
        direction = MessageDirection.OUTGOING,
        status = MessageStatus.SENT,
        isRead = true,
    )

    fun makeMinimalInputs(messageID: UUID): MessageBuildInputs = MessageBuildInputs(
        messageID = messageID,
        previewState = PreviewLoadState.IDLE,
        loadedPreview = null,
        cachedURL = null,
        isInlineImageURL = false,
        hasInlineImageRef = false,
        hasPreviewImageRef = false,
        hasPreviewIconRef = false,
        imageIsGIF = false,
        formattedText = null,
        baseColor = BaseColorSlot.INCOMING,
        formattedPath = null,
        senderResolution = NodeNameResolution("Sender", NodeNameMatchKind.EXACT),
        showTimestamp = false,
        showDirectionGap = false,
        showSenderName = false,
        showNewMessagesDivider = false,
    )

    fun makeMessage(
        id: UUID = UUID.randomUUID(),
        text: String = "hello",
        status: MessageStatus = MessageStatus.SENT,
        heardRepeats: Long = 0,
        sendCount: Long = 1,
        retryAttempt: Long = 0,
        maxRetryAttempts: Long = 0,
    ): MessageDTO = MessageDTO(
        id = id,
        radioId = RADIO_ID,
        contactID = CONTACT_ID,
        text = text,
        timestamp = REFERENCE_TIMESTAMP,
        createdAt = REFERENCE_DATE,
        direction = MessageDirection.OUTGOING,
        status = status,
        isRead = true,
        heardRepeats = heardRepeats,
        sendCount = sendCount,
        retryAttempt = retryAttempt,
        maxRetryAttempts = maxRetryAttempts,
    )

    fun makeInputs(messageID: UUID): MessageBuildInputs = makeMinimalInputs(messageID)

    fun makeInputs(
        message: MessageDTO,
        mapPreviewLatitude: Double? = null,
        mapPreviewLongitude: Double? = null,
        isMapPreviewReady: Boolean = false,
    ): MessageBuildInputs = MessageBuildInputs(
        messageID = message.id,
        previewState = PreviewLoadState.IDLE,
        loadedPreview = null,
        cachedURL = null,
        isInlineImageURL = false,
        hasInlineImageRef = false,
        hasPreviewImageRef = false,
        hasPreviewIconRef = false,
        imageIsGIF = false,
        mapPreviewLatitude = mapPreviewLatitude,
        mapPreviewLongitude = mapPreviewLongitude,
        isMapPreviewReady = isMapPreviewReady,
        formattedText = null,
        baseColor = BaseColorSlot.INCOMING,
        formattedPath = null,
        senderResolution = NodeNameResolution("Sender", NodeNameMatchKind.EXACT),
        showTimestamp = false,
        showDirectionGap = false,
        showSenderName = false,
        showNewMessagesDivider = false,
    )

    fun makeEnvInputs(isOutgoing: Boolean = true): EnvInputs = EnvInputs(
        autoPlayGIFs = true,
        showIncomingPath = false,
        showIncomingHopCount = false,
        showIncomingRegion = false,
        showIncomingHeardCount = false,
        showIncomingSendTime = false,
        previewsEnabled = false,
        isHighContrast = false,
        isDark = false,
        showMapPreviews = true,
        isOffline = false,
        currentUserName = if (isOutgoing) "Me" else "Sender",
        themeID = "default",
        contentSizeCategory = EnvInputs.DEFAULT_CONTENT_SIZE_CATEGORY,
        preferredLanguageCode = EnvInputs.DEFAULT_PREFERRED_LANGUAGE_CODE,
    )

    /** Swift `MessageDTO.testDirectMessage(...)`. */
    fun testDirectMessage(
        id: UUID = UUID.randomUUID(),
        radioId: RadioId = RadioId(UUID.randomUUID()),
        contactID: UUID = UUID.randomUUID(),
        text: String = "Test message",
        timestamp: UInt = Instant.now().epochSecond.toUInt(),
        createdAt: Instant = Instant.now(),
        direction: MessageDirection = MessageDirection.OUTGOING,
        status: MessageStatus = MessageStatus.PENDING,
        heardRepeats: Long = 0,
        sendCount: Long = 1,
        retryAttempt: Long = 0,
        maxRetryAttempts: Long = 0,
        containsSelfMention: Boolean = false,
    ): MessageDTO = MessageDTO(
        id = id,
        radioId = radioId,
        contactID = contactID,
        text = text,
        timestamp = timestamp,
        createdAt = createdAt,
        direction = direction,
        status = status,
        heardRepeats = heardRepeats,
        sendCount = sendCount,
        retryAttempt = retryAttempt,
        maxRetryAttempts = maxRetryAttempts,
        containsSelfMention = containsSelfMention,
    )
}

/**
 * Runs [block] on a single-threaded event loop that stands in for the Swift main actor: work the
 * coordinator launches on [CoroutineScope] runs only when the block suspends (or after it returns),
 * exactly like a Swift `Task` scheduled from `@MainActor` code.
 */
internal fun <T> runRendering(block: suspend CoroutineScope.() -> T): T = runBlocking(block = block)

/** Swift `ChatCoordinator.makeForTesting`: a standalone coordinator over an in-memory store. */
internal fun CoroutineScope.renderingCoordinator(
    conversationID: ChatConversationID = ChatConversationID.dm(RadioId(UUID.randomUUID()), UUID.randomUUID()),
    store: RenderingInMemoryMessageStore = RenderingInMemoryMessageStore(),
): ChatCoordinator = ChatCoordinator(conversationID, store, this, Dispatchers.Default)

/** Swift `ChatCoordinatorRegistry(dataStore:capacity:)` bound to the test event loop. */
internal fun CoroutineScope.renderingRegistry(
    store: RenderingInMemoryMessageStore = RenderingInMemoryMessageStore(),
    capacity: Int = ChatCoordinatorRegistry.DEFAULT_CAPACITY,
): ChatCoordinatorRegistry = ChatCoordinatorRegistry(store, this, capacity)

/** One build-input pair per message, as the bound view model would assemble them. */
internal fun renderingBuildPairs(messages: List<MessageDTO>): List<Pair<MessageDTO, MessageBuildInputs>> =
    messages.map { it to RenderingFixtures.makeMinimalInputs(it.id) }
