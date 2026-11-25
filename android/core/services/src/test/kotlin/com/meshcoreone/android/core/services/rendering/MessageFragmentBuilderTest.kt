// PortedFrom: MC1Tests/Views/Chats/Models/MessageFragmentBuilderTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.LinkPreviewDataDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Adaptation: Swift compares `hashValue` (process-seeded); Kotlin compares `hashCode()` from the same
 * structural fields. `hasCachedURLEntry` / `autoPlayGIFs` / `currentUserName` / … parameters of the Swift
 * `makeInputs` helper were unused by `MessageBuildInputs` and are dropped.
 */
class MessageFragmentBuilderTest {
    private enum class FragmentKind { TEXT, INLINE_IMAGE, LINK_PREVIEW, MAP_PREVIEW, MALWARE_WARNING, REACTION_SUMMARY }

    private fun kind(fragment: MessageFragment): FragmentKind = when (fragment) {
        is MessageFragment.Text -> FragmentKind.TEXT
        is MessageFragment.InlineImage -> FragmentKind.INLINE_IMAGE
        is MessageFragment.LinkPreview -> FragmentKind.LINK_PREVIEW
        is MessageFragment.MapPreview -> FragmentKind.MAP_PREVIEW
        is MessageFragment.MalwareWarning -> FragmentKind.MALWARE_WARNING
        is MessageFragment.ReactionSummary -> FragmentKind.REACTION_SUMMARY
    }

    private fun kinds(item: MessageItem): List<FragmentKind> = item.content.map(::kind)

    private fun url(value: String): WebURL = requireNotNull(WebURL.parse(value))

    private fun build(message: MessageDTO, inputs: MessageBuildInputs, env: EnvInputs = makeEnvInputs()) =
        MessageFragmentBuilder.makeItem(message, inputs, env)

    @TestFactory
    fun messageFragmentBuilderTests(): List<DynamicTest> = listOf(
        case("plain text produces a single text fragment") {
            val message = makeMessage(text = "hello")
            val item = build(message, makeInputs(message.id))
            assertEquals(1, item.content.size)
            assertEquals("hello", assertIs<MessageFragment.Text>(item.content[0]).payload.raw)
        },
        case("translation chrome copies onto the payload and leaves raw original") {
            val original = "Guten Morgen, wie geht es dir heute?"
            val message = makeMessage(text = original)
            val offerChrome = MessageTranslationChrome(MessageTranslationChrome.Phase.Offer, "de")
            val showingPhase = MessageTranslationChrome.Phase.Showing("Hello", "en")
            val offer = build(message, makeInputs(message.id, translation = offerChrome))
            val showing = build(message, makeInputs(message.id, translation = MessageTranslationChrome(showingPhase, "de")))
            val none = build(message, makeInputs(message.id, translation = null))
            val offerPayload = assertIs<MessageFragment.Text>(offer.content[0]).payload
            val showingPayload = assertIs<MessageFragment.Text>(showing.content[0]).payload
            val nonePayload = assertIs<MessageFragment.Text>(none.content[0]).payload
            assertEquals(original, offerPayload.raw)
            assertEquals(MessageTranslationChrome.Phase.Offer, offerPayload.translation?.phase)
            assertEquals(original, showingPayload.raw)
            assertEquals(showingPhase, showingPayload.translation?.phase)
            assertEquals(original, nonePayload.raw)
            assertNull(nonePayload.translation)
            assertNotEquals(offer, showing)
            assertNotEquals(offer, none)
            assertNotEquals(showing, none)
        },
        case("reaction summary appears after the text fragment") {
            val message = makeMessage(text = "hi", reactionSummary = "👍:1")
            val item = build(message, makeInputs(message.id))
            assertEquals(2, item.content.size)
            assertIs<MessageFragment.Text>(item.content[0])
            assertEquals("👍:1", assertIs<MessageFragment.ReactionSummary>(item.content[1]).summary)
        },
        case("adding a reaction flips the item hash") {
            val messageID = UUID.randomUUID()
            val inputs = makeInputs(messageID)
            val itemA = build(makeMessage(id = messageID, text = "hi"), inputs)
            val itemB = build(makeMessage(id = messageID, text = "hi", reactionSummary = "👍:1"), inputs)
            assertNotEquals(itemA.hashCode(), itemB.hashCode())
            assertNotEquals(itemA, itemB)
        },
        case("malware warning replaces preview and inline image fragments") {
            val message = makeMessage(text = "click me")
            val item = build(message, makeInputs(message.id, previewState = PreviewLoadState.MALWARE_WARNING, cachedURL = url("https://bad.example")))
            assertEquals(listOf(FragmentKind.TEXT, FragmentKind.MALWARE_WARNING), kinds(item))
        },
        case("image url with isInlineImageURL true routes to inline image") {
            val message = makeMessage(text = "see")
            val inputs = makeInputs(message.id, cachedURL = url("https://example.com/cat.jpg"), isInlineImageURL = true)
            val kinds = kinds(build(message, inputs, makeEnvInputs(previewsEnabled = true)))
            assertTrue(FragmentKind.INLINE_IMAGE in kinds)
            assertFalse(FragmentKind.LINK_PREVIEW in kinds)
        },
        case("link content off suppresses inline image and link preview for image url") {
            val message = makeMessage(text = "see")
            val inputs = makeInputs(message.id, cachedURL = url("https://example.com/cat.jpg"), isInlineImageURL = true)
            val kinds = kinds(build(message, inputs, makeEnvInputs(previewsEnabled = false)))
            assertFalse(FragmentKind.INLINE_IMAGE in kinds)
            assertFalse(FragmentKind.LINK_PREVIEW in kinds)
        },
        case("link content off suppresses link preview for non image url") {
            val message = makeMessage(text = "see")
            val inputs = makeInputs(message.id, cachedURL = url("https://example.com/page"), isInlineImageURL = false)
            assertFalse(FragmentKind.LINK_PREVIEW in kinds(build(message, inputs, makeEnvInputs(previewsEnabled = false))))
        },
        case("link content on routes non image url to link preview") {
            val message = makeMessage(text = "see")
            val inputs = makeInputs(message.id, cachedURL = url("https://example.com/page"), isInlineImageURL = false)
            val kinds = kinds(build(message, inputs, makeEnvInputs(previewsEnabled = true)))
            assertTrue(FragmentKind.LINK_PREVIEW in kinds)
            assertFalse(FragmentKind.INLINE_IMAGE in kinds)
        },
        case("disabled preview state yields disabled inline image") {
            val message = makeMessage(text = "see")
            val imageURL = url("https://example.com/cat.jpg")
            val inputs = makeInputs(message.id, previewState = PreviewLoadState.DISABLED, cachedURL = imageURL, isInlineImageURL = true)
            val fragment = build(message, inputs, makeEnvInputs(previewsEnabled = true)).content.first { kind(it) == FragmentKind.INLINE_IMAGE }
            assertEquals(InlineImage.LoadState.Disabled(imageURL), assertIs<MessageFragment.InlineImage>(fragment).image.state)
        },
        case("page serving image url reroutes to link preview") {
            val message = makeMessage(text = "see")
            val inputs = makeInputs(message.id, cachedURL = url("https://example.com/cat.jpg"), isInlineImageURL = false)
            val kinds = kinds(build(message, inputs, makeEnvInputs(previewsEnabled = true)))
            assertTrue(FragmentKind.LINK_PREVIEW in kinds)
            assertFalse(FragmentKind.INLINE_IMAGE in kinds)
        },
        case("legacy link preview surfaces with persisted fields when state is idle") {
            val message = makeMessage(text = "see", linkPreviewURL = "https://example.com", linkPreviewTitle = "Example")
            val item = build(message, makeInputs(message.id, previewState = PreviewLoadState.IDLE), makeEnvInputs(previewsEnabled = true))
            val legacy = assertIs<LinkPreviewFragmentState.Mode.Legacy>(assertIs<MessageFragment.LinkPreview>(item.content.last()).state.mode)
            assertEquals("https://example.com", legacy.url.absoluteString)
            assertEquals("Example", legacy.title)
        },
        case("legacy link preview carries image reference when inputs say so") {
            val message = makeMessage(text = "see", linkPreviewURL = "https://example.com", linkPreviewTitle = "Example")
            val inputs = makeInputs(message.id, previewState = PreviewLoadState.IDLE, hasPreviewImageRef = true)
            val item = build(message, inputs, makeEnvInputs(previewsEnabled = true))
            val legacy = assertIs<LinkPreviewFragmentState.Mode.Legacy>(assertIs<MessageFragment.LinkPreview>(item.content.last()).state.mode)
            assertEquals(ImageReference(message.id, ImageReference.Role.LINK_PREVIEW_IMAGE), legacy.image)
        },
        case("same inputs produce equal items and equal hashes") {
            val message = makeMessage(text = "hello")
            val inputs = makeInputs(message.id)
            val a = build(message, inputs)
            val b = build(message, inputs)
            assertEquals(a, b)
            assertEquals(a.hashCode(), b.hashCode())
        },
        case("shouldRequestPreviewFetch is true on idle with URL and no legacy fields") {
            val message = makeMessage(text = "hi")
            val inputs = makeInputs(message.id, previewState = PreviewLoadState.IDLE, cachedURL = url("https://example.com"))
            assertTrue(build(message, inputs).shouldRequestPreviewFetch)
        },
        case("shouldRequestPreviewFetch is false on legacy message") {
            val message = makeMessage(text = "hi", linkPreviewURL = "https://example.com")
            val inputs = makeInputs(message.id, previewState = PreviewLoadState.IDLE, cachedURL = url("https://example.com"))
            assertFalse(build(message, inputs).shouldRequestPreviewFetch)
        },
        case("envelope captures containsSelfMention from message") {
            val message = makeMessage(text = "hi", containsSelfMention = true)
            assertTrue(build(message, makeInputs(message.id)).envelope.containsSelfMention)
        },
        case("envelope captures mentionSeen from message") {
            val message = makeMessage(text = "hi", containsSelfMention = true, mentionSeen = true)
            assertTrue(build(message, makeInputs(message.id)).envelope.mentionSeen)
        },
        case("envelope date is the message send time, not its drain time") {
            val drainTime = REFERENCE_DATE
            val sendTime = REFERENCE_DATE.minus(Duration.ofDays(3))
            val message = MessageDTO(
                id = UUID.randomUUID(), radioId = RenderingFixtures.RADIO_ID, contactID = RenderingFixtures.CONTACT_ID,
                text = "older message just arrived", timestamp = sendTime.epochSecond.toUInt(), createdAt = drainTime,
                direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED, isRead = true,
            )
            val item = build(message, makeInputs(message.id))
            assertEquals(message.senderDate, item.envelope.date)
            assertNotEquals(message.date, message.senderDate, "test must distinguish send time from drain time")
        },
        case("footer captures heardRepeats from message") {
            val message = makeMessage(text = "hi", heardRepeats = 3)
            assertEquals(3L, build(message, makeInputs(message.id)).footer.heardRepeats)
        },
        case("footer captures retryAttempt from message") {
            val message = makeMessage(text = "hi", retryAttempt = 2)
            assertEquals(2L, build(message, makeInputs(message.id)).footer.retryAttempt)
        },
        case("footer captures maxRetryAttempts from message") {
            val message = makeMessage(text = "hi", maxRetryAttempts = 5)
            assertEquals(5L, build(message, makeInputs(message.id)).footer.maxRetryAttempts)
        },
        case("footer shows send time on incoming message") {
            val wire = 1_700_000_000u
            val message = makeIncomingMessage(wire)
            val item = build(message, makeInputs(message.id))
            assertEquals(Instant.ofEpochSecond(wire.toLong()), item.footer.sendTimeToShow)
            assertFalse(item.footer.sendTimeWasCorrected)
        },
        case("footer shows send time regardless of the legacy showIncomingSendTime flag") {
            val wire = 1_700_000_000u
            val message = makeIncomingMessage(wire)
            val item = build(message, makeInputs(message.id), makeEnvInputs(showIncomingSendTime = false))
            assertEquals(Instant.ofEpochSecond(wire.toLong()), item.footer.sendTimeToShow)
        },
        case("footer shows send time on outgoing messages") {
            val message = makeMessage(text = "hi")
            assertEquals(message.senderDate, build(message, makeInputs(message.id)).footer.sendTimeToShow)
        },
        case("footer send time uses the corrected value and flags correction") {
            val corrected = 1_700_000_000u
            val message = makeIncomingMessage(corrected, senderTimestamp = 100u, timestampCorrected = true)
            val item = build(message, makeInputs(message.id), makeEnvInputs(showIncomingSendTime = true))
            assertEquals(Instant.ofEpochSecond(corrected.toLong()), item.footer.sendTimeToShow)
            assertTrue(item.footer.sendTimeWasCorrected)
        },
        case("previewState change flips the item hash") {
            val messageID = UUID.randomUUID()
            val message = makeMessage(id = messageID, text = "hi")
            val env = makeEnvInputs(previewsEnabled = true)
            val a = build(message, makeInputs(messageID, previewState = PreviewLoadState.IDLE, cachedURL = url("https://example.com")), env)
            val b = build(message, makeInputs(messageID, previewState = PreviewLoadState.LOADED, cachedURL = url("https://example.com")), env)
            assertNotEquals(a.hashCode(), b.hashCode())
        },
        case("loadedPreview change flips the item hash") {
            val messageID = UUID.randomUUID()
            val message = makeMessage(id = messageID, text = "hi")
            val preview = LinkPreviewDataDTO(url = "https://example.com", title = "Example", fetchedAt = Instant.ofEpochSecond(1_700_000_000))
            val env = makeEnvInputs(previewsEnabled = true)
            val a = build(message, makeInputs(messageID, previewState = PreviewLoadState.LOADED), env)
            val b = build(message, makeInputs(messageID, previewState = PreviewLoadState.LOADED, loadedPreview = preview), env)
            assertNotEquals(a.hashCode(), b.hashCode())
        },
        case("preview fetch task id is distinct per fetch-wanting message") {
            val env = makeEnvInputs(previewsEnabled = true)
            val messageA = makeMessage(text = "see https://example.com")
            val messageB = makeMessage(text = "also https://example.com")
            val itemA = build(messageA, makeInputs(messageA.id, previewState = PreviewLoadState.IDLE, cachedURL = url("https://example.com")), env)
            val itemB = build(messageB, makeInputs(messageB.id, previewState = PreviewLoadState.IDLE, cachedURL = url("https://example.com")), env)
            assertTrue(itemA.shouldRequestPreviewFetch)
            assertTrue(itemB.shouldRequestPreviewFetch)
            assertEquals(itemA.shouldRequestPreviewFetch, itemB.shouldRequestPreviewFetch)
            assertEquals(itemA.id, itemA.previewFetchTaskID)
            assertEquals(itemB.id, itemB.previewFetchTaskID)
            assertNotEquals(itemA.previewFetchTaskID, itemB.previewFetchTaskID)
        },
        case("preview fetch task id is nil when no fetch is wanted") {
            val messageID = UUID.randomUUID()
            val message = makeMessage(id = messageID, text = "hi")
            val env = makeEnvInputs(previewsEnabled = true)
            val idle = build(message, makeInputs(messageID, previewState = PreviewLoadState.IDLE, cachedURL = url("https://example.com")), env)
            val loading = build(message, makeInputs(messageID, previewState = PreviewLoadState.LOADING, cachedURL = url("https://example.com")), env)
            val noURL = build(message, makeInputs(messageID, previewState = PreviewLoadState.IDLE), env)
            assertEquals(messageID, idle.previewFetchTaskID)
            assertNull(loading.previewFetchTaskID)
            assertNull(noURL.previewFetchTaskID)
        },
        DynamicTest.dynamicTest("MessageFragmentBuilderTests::MessageDTO field change flips the item hash(scenario : HashFlipScenario)") {
            for (scenario in hashFlipScenarios) {
                val messageID = UUID.randomUUID()
                val inputs = makeInputs(messageID)
                val a = build(scenario.variantA(messageID), inputs)
                val b = build(scenario.variantB(messageID), inputs)
                assertNotEquals(a.hashCode(), b.hashCode(), "${scenario.name} change must flip the item hash")
            }
        },
        case("A coordinate in build inputs emits a mapPreview fragment") {
            val message = RenderingFixtures.makeMessage(text = "Meet at 37.7749, -122.4194")
            val inputs = RenderingFixtures.makeInputs(message, mapPreviewLatitude = 37.7749, mapPreviewLongitude = -122.4194, isMapPreviewReady = true)
            val state = assertIs<MessageFragment.MapPreview>(build(message, inputs, makeEnvInputs(isDark = true)).content.last()).state
            assertEquals(37.7749, state.latitude)
            assertEquals(-122.4194, state.longitude)
            assertTrue(state.isDark)
            assertTrue(state.isReady)
        },
        case("No coordinate means no mapPreview fragment") {
            val message = RenderingFixtures.makeMessage(text = "no coordinates here")
            val item = build(message, RenderingFixtures.makeInputs(message))
            assertFalse(item.content.any { it is MessageFragment.MapPreview })
        },
    )

    private class HashFlipScenario(val name: String, val variantA: (UUID) -> MessageDTO, val variantB: (UUID) -> MessageDTO)

    private val hashFlipScenarios: List<HashFlipScenario> = listOf(
        HashFlipScenario("heardRepeats", { makeMessage(id = it, heardRepeats = 0) }, { makeMessage(id = it, heardRepeats = 3) }),
        HashFlipScenario("retryAttempt", { makeMessage(id = it, retryAttempt = 0) }, { makeMessage(id = it, retryAttempt = 1) }),
        HashFlipScenario("maxRetryAttempts", { makeMessage(id = it, maxRetryAttempts = 3) }, { makeMessage(id = it, maxRetryAttempts = 5) }),
        HashFlipScenario("status", { makeMessage(id = it, status = MessageStatus.SENT) }, { makeMessage(id = it, status = MessageStatus.DELIVERED) }),
        HashFlipScenario(
            "containsSelfMention",
            { makeMessage(id = it, containsSelfMention = false) },
            { makeMessage(id = it, containsSelfMention = true) },
        ),
        // Implies containsSelfMention so the distinction is mentionSeen alone.
        HashFlipScenario(
            "mentionSeen",
            { makeMessage(id = it, containsSelfMention = true, mentionSeen = false) },
            { makeMessage(id = it, containsSelfMention = true, mentionSeen = true) },
        ),
    )

    private fun makeMessage(
        id: UUID = UUID.randomUUID(),
        text: String = "hello",
        status: MessageStatus = MessageStatus.SENT,
        reactionSummary: String? = null,
        linkPreviewURL: String? = null,
        linkPreviewTitle: String? = null,
        heardRepeats: Long = 0,
        retryAttempt: Long = 0,
        maxRetryAttempts: Long = 0,
        containsSelfMention: Boolean = false,
        mentionSeen: Boolean = false,
    ): MessageDTO = MessageDTO(
        id = id, radioId = RenderingFixtures.RADIO_ID, contactID = RenderingFixtures.CONTACT_ID, text = text,
        timestamp = REFERENCE_DATE.epochSecond.toUInt(), createdAt = REFERENCE_DATE, direction = MessageDirection.OUTGOING,
        status = status, isRead = true, heardRepeats = heardRepeats, retryAttempt = retryAttempt, maxRetryAttempts = maxRetryAttempts,
        linkPreviewURL = linkPreviewURL, linkPreviewTitle = linkPreviewTitle, containsSelfMention = containsSelfMention,
        mentionSeen = mentionSeen, reactionSummary = reactionSummary,
    )

    private fun makeIncomingMessage(timestamp: UInt, senderTimestamp: UInt? = null, timestampCorrected: Boolean = false) = MessageDTO(
        id = UUID.randomUUID(), radioId = RenderingFixtures.RADIO_ID, contactID = RenderingFixtures.CONTACT_ID, text = "hi",
        timestamp = timestamp, createdAt = REFERENCE_DATE, direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
        isRead = true, timestampCorrected = timestampCorrected, senderTimestamp = senderTimestamp,
    )

    private fun makeEnvInputs(
        previewsEnabled: Boolean = false,
        showIncomingSendTime: Boolean = false,
        isDark: Boolean = false,
    ): EnvInputs = EnvInputs(
        autoPlayGIFs = true, showIncomingPath = false, showIncomingHopCount = false, showIncomingRegion = false,
        showIncomingHeardCount = false, showIncomingSendTime = showIncomingSendTime, previewsEnabled = previewsEnabled,
        isHighContrast = false, isDark = isDark, showMapPreviews = true, isOffline = false, currentUserName = "Me",
        themeID = "default", contentSizeCategory = EnvInputs.DEFAULT_CONTENT_SIZE_CATEGORY,
        preferredLanguageCode = EnvInputs.DEFAULT_PREFERRED_LANGUAGE_CODE,
    )

    private fun makeInputs(
        messageID: UUID,
        previewState: PreviewLoadState = PreviewLoadState.IDLE,
        loadedPreview: LinkPreviewDataDTO? = null,
        cachedURL: WebURL? = null,
        isInlineImageURL: Boolean = false,
        hasPreviewImageRef: Boolean = false,
        translation: MessageTranslationChrome? = null,
    ): MessageBuildInputs = MessageBuildInputs(
        messageID = messageID, previewState = previewState, loadedPreview = loadedPreview, cachedURL = cachedURL,
        isInlineImageURL = isInlineImageURL, hasInlineImageRef = false, hasPreviewImageRef = hasPreviewImageRef,
        hasPreviewIconRef = false, imageIsGIF = false, formattedText = null, baseColor = BaseColorSlot.INCOMING,
        formattedPath = null, senderResolution = NodeNameResolution("Sender", NodeNameMatchKind.EXACT), showTimestamp = false,
        showDirectionGap = false, showSenderName = false, showNewMessagesDivider = false, translation = translation,
    )

    private fun case(name: String, body: () -> Unit): DynamicTest = DynamicTest.dynamicTest("MessageFragmentBuilderTests::$name()", body)

    private companion object {
        val REFERENCE_DATE: Instant = Instant.ofEpochSecond(1_700_000_000)
    }
}
