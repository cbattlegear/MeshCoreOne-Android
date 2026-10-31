// AndroidOnly: WP-308 native tests for link detection, mention deeplinks, sanitizing, tap resolution and previews (oracle: wp308_oracle.swift).
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.feature.chats.list.support.Fixtures
import com.meshcoreone.android.feature.chats.list.support.scenario
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import org.junit.Test

class LinkLogicTest {
    @Test
    fun `url detection links schemes and www hosts and trims sentence punctuation`() {
        fun urls(text: String) = LinkDetection.detectUrls(text).map { text.substring(it.start, it.end) to it.url }
        assertEquals(listOf("https://a.com/x" to "https://a.com/x"), urls("https://a.com/x."))
        assertEquals(listOf("https://a.com/x?y=1&z=2" to "https://a.com/x?y=1&z=2"), urls("https://a.com/x?y=1&z=2!"))
        assertEquals(listOf("www.example.com" to "https://www.example.com"), urls("go www.example.com, ok"))
        assertEquals(listOf("https://a.com/b_(c)" to "https://a.com/b_(c)"), urls("(https://a.com/b_(c))"))
        assertEquals(emptyList(), urls("visit example.com now or mail me@x.com"))
        assertEquals(emptyList(), urls("https://. and www."))
        assertEquals(1, urls("https://a.com,https://b.com").size, "the detector does not split on a comma")
    }

    @Test
    fun `hashtags follow the ASCII pattern and skip URL fragments`() {
        fun tags(text: String) = Hashtags.extract(text, emptyList()).map { it.name }
        assertEquals(listOf("#general", "#ops-1", "#a", "#b", "#a"), tags("#general #ops-1 #-x ##a a#b #日本 #a_b"))
        assertEquals(listOf("#y"), tags("x#y"))
        assertEquals(listOf("#hi"), tags("(#hi)"))
        assertEquals("general", Hashtags.normalizeName("#General"))
        assertEquals("##a".drop(1), Hashtags.normalizeName("##A"))
    }

    @Test
    fun `mention links percent-encode like Foundation and decode strictly`() {
        val oracle = mapOf(
            "Alice Smith" to "Alice%20Smith", "A/B" to "A%2FB", "日本" to "%E6%97%A5%E6%9C%AC", "A#B?" to "A%23B%3F",
            "100%" to "100%25", "WCMesh.com" to "WCMesh.com", "a@b:c" to "a@b:c", "x+y;z=1&2" to "x+y;z=1&2",
            "Bob's Solar Node" to "Bob's%20Solar%20Node", "[x]" to "%5Bx%5D", "a|b" to "a%7Cb", "a%20b" to "a%2520b",
        )
        for ((name, encoded) in oracle) {
            assertEquals("meshcoreone://mention/$encoded", MentionDeeplink.url(name))
            assertEquals(name, MentionDeeplink.name("meshcoreone://mention/$encoded"))
        }
        assertNull(MentionDeeplink.url(""))
        assertNull(MentionDeeplink.name("meshcoreone://mention/"))
        assertNull(MentionDeeplink.name("meshcoreone://mention/bad%ZZ"))
        assertNull(MentionDeeplink.name("meshcoreone://mention/a%2"))
        assertNull(MentionDeeplink.name("meshcoreone://mention/%FF"))
        assertNull(MentionDeeplink.name("meshcoreone://hashtag/x"))
        assertNull(MentionDeeplink.name("https://mention/x"))
        assertEquals("\u0000", MentionDeeplink.name("meshcoreone://mention/%00"))
    }

    @Test
    fun `display names drop the oracle's invisible and control scalars`() {
        // Oracle "scalar" rows: strippable = bidi control || default ignorable || control/format/line/paragraph separator.
        val stripped = listOf(0x202E, 0x200B, 0x200C, 0x200D, 0x0A, 0x09, 0xAD, 0x2060, 0xFEFF, 0x061C, 0x2028, 0x2029, 0x7F, 0x85,
            0x180E, 0xFE0F, 0x3164, 0xE0001, 0x034F, 0x115F, 0x17B4, 0x2066, 0x200E, 0x200F, 0x206A, 0xFFF0, 0xFFA0)
        val kept = listOf(0xA0, 0x20)
        for (scalar in stripped) assertEquals("", MessageTextNormalizer.displayName(String(Character.toChars(scalar))), "U+%X".format(scalar))
        for (scalar in kept) assertEquals(String(Character.toChars(scalar)), MessageTextNormalizer.displayName(String(Character.toChars(scalar))))
        assertEquals("a b", MessageTextNormalizer.displayName("a​ b"))
    }

    @Test
    fun `normalizer rewrites mentions and flags the self mention`() {
        val result = MessageTextNormalizer.normalize("Hi @[Me] and @[Bob]", MessageTextNormalizer.StyleContext(false, "me"))
        assertEquals("Hi @Me and @Bob", result.text)
        assertEquals(listOf(true, false), result.spans.map { it.selfMention })
        assertEquals(listOf(LinkColor.MENTION_IDENTITY, LinkColor.MENTION_IDENTITY), result.spans.map { it.color })
        assertEquals(listOf("Me", "Bob"), result.spans.map { it.colorKey })
        val outgoing = MessageTextNormalizer.normalize("@[Me]", MessageTextNormalizer.StyleContext(true, "Me"))
        assertEquals(LinkColor.BASE, outgoing.spans.single().color)
        assertTrue(outgoing.spans.single().selfMention)
    }

    @Test
    fun `outgoing hashtags use the outgoing text color`() {
        val token = MessageTextFormatter.format("#ops", isOutgoing = true).tokens.single()
        assertEquals(LinkColor.OUTGOING_TEXT, token.color)
        assertEquals(LinkColor.HASHTAG, MessageTextFormatter.format("#ops").tokens.single().color)
    }

    @Test
    fun `mention tap resolves one match to navigation and anything else to the picker`() {
        val radio = Fixtures.radio
        val alice = Fixtures.contact("Alice")
        val bobA = Fixtures.contact("Bob")
        val bobB = Fixtures.contact("bob")
        fun eval(name: String, contacts: List<com.meshcoreone.android.core.model.ContactDTO>, device: String? = null) =
            MentionTapEvaluator.evaluate(name, contacts, device, radio)
        assertEquals(MentionTapEvaluator.Outcome.Navigate(alice), eval("Alice", listOf(alice, bobA)))
        val ambiguous = assertIs<MentionTapEvaluator.Outcome.Picker>(eval("bob", listOf(bobA, bobB))).context
        assertEquals(2, ambiguous.matches.size)
        assertTrue(assertIs<MentionTapEvaluator.Outcome.Picker>(eval("Nobody", listOf(alice))).context.matches.isEmpty())
        assertTrue(assertIs<MentionTapEvaluator.Outcome.Picker>(eval("MyNode", listOf(alice), "mynode")).context.isSelfMention)
        val blank = assertIs<MentionTapEvaluator.Outcome.Picker>(eval("‮ ​", listOf(alice))).context
        assertTrue(blank.matches.isEmpty() && !blank.isSelfMention)
        assertEquals(RadioId::class, blank.radioId::class)
    }

    // Link previews

    private class FakePreviewPort : LinkPreviewPort {
        var cached: LinkPreviewData? = null
        var auto: LinkPreviewOutcome = LinkPreviewOutcome.Disabled
        var manual: LinkPreviewOutcome = LinkPreviewOutcome.Failed
        var gate: CompletableDeferred<Unit>? = null
        var failure: Throwable? = null
        val calls = ArrayList<String>()
        override suspend fun cachedPreview(url: String): LinkPreviewData? = cached
        override suspend fun preview(url: String, isChannelMessage: Boolean): LinkPreviewOutcome {
            calls += "preview:$isChannelMessage"
            failure?.let { throw it }
            return auto
        }
        override suspend fun manualFetch(url: String): LinkPreviewOutcome {
            calls += "manual"
            gate?.await()
            return manual
        }
    }

    private val data = LinkPreviewData("https://example.com/a", title = "T", imageWidth = 1200, imageHeight = 630)

    @Test
    fun `a blocked domain shows the malware warning and never fetches`() = scenario {
        val port = FakePreviewPort()
        val holder = LinkPreviewStateHolder("https://evil.example/x", false, port, { true }, { it == "evil.example" }, scope)
        holder.start()
        assertEquals(LinkPreviewCardState.Malware("https://evil.example/x"), holder.state.value)
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun `an unsafe or non-web URL is hidden without a fetch`() = scenario {
        val port = FakePreviewPort()
        LinkPreviewStateHolder("http://192.168.0.1/admin", false, port, { false }, { false }, scope).also { it.start() }
            .let { assertEquals(LinkPreviewCardState.Hidden, it.state.value) }
        LinkPreviewStateHolder("javascript:alert(1)", false, port, { true }, { false }, scope).also { it.start() }
            .let { assertEquals(LinkPreviewCardState.Hidden, it.state.value) }
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun `a cached preview shows immediately and the auto path forwards the channel flag`() = scenario {
        val port = FakePreviewPort().apply { cached = data }
        val cachedHolder = LinkPreviewStateHolder(data.url, false, port, { true }, { false }, scope).also { it.start() }
        assertEquals(LinkPreviewCardState.Loaded(data), cachedHolder.state.value)
        port.cached = null
        port.auto = LinkPreviewOutcome.Loaded(data)
        val auto = LinkPreviewStateHolder(data.url, true, port, { true }, { false }, scope).also { it.start() }
        assertEquals(LinkPreviewCardState.Loaded(data), auto.state.value)
        assertEquals(listOf("preview:true"), port.calls)
    }

    @Test
    fun `persisted cached preview is visible when DNS safety lookup is unavailable`() = scenario {
        val port = FakePreviewPort().apply { cached = data }
        val holder = LinkPreviewStateHolder(data.url, false, port, { false }, { false }, scope).also { it.start() }
        assertEquals(LinkPreviewCardState.Loaded(data), holder.state.value)
        assertTrue(port.calls.isEmpty(), "cached content does not initiate network work")
    }

    @Test
    fun `auto-resolve disabled shows tap to load and a tap fetches once`() = scenario {
        val port = FakePreviewPort().apply { manual = LinkPreviewOutcome.Loaded(data); gate = CompletableDeferred() }
        val holder = LinkPreviewStateHolder(data.url, false, port, { true }, { false }, scope).also { it.start() }
        assertEquals(LinkPreviewCardState.TapToLoad(data.url), holder.state.value)
        assertNull(LinkPreviewStateHolder(data.url, false, port, { true }, { false }, scope).manualLoad(), "nothing to tap before start")
        holder.manualLoad()
        assertEquals(LinkPreviewCardState.Loading(data.url), holder.state.value)
        assertNull(holder.manualLoad(), "a second tap while loading is ignored")
        port.gate!!.complete(Unit)
        assertEquals(LinkPreviewCardState.Loaded(data), holder.state.value)
        assertEquals(listOf("preview:false", "manual"), port.calls)
    }

    @Test
    fun `failures hide the card and cancellation is never swallowed`() = scenario {
        val reported = ArrayList<Throwable>()
        val port = FakePreviewPort().apply { failure = IllegalStateException("boom") }
        val holder = LinkPreviewStateHolder(data.url, false, port, { true }, { false }, scope, { _, f -> reported += f })
        holder.start()
        assertEquals(LinkPreviewCardState.Hidden, holder.state.value)
        assertEquals(1, reported.size)
        port.failure = CancellationException("left screen")
        holder.start()
        assertEquals(1, reported.size, "cancellation is not a failure")
    }

    @Test
    fun `preview metrics follow the iOS sizing and only web links open`() {
        assertEquals(1200.0 / 630, LinkPreviewMetrics.heroAspect(1200, 630))
        assertEquals(16.0 / 9.0, LinkPreviewMetrics.heroAspect(null, 630))
        assertEquals(16.0 / 9.0, LinkPreviewMetrics.heroAspect(0, 10))
        assertTrue(LinkOpenPolicy.isOpenable("https://example.com/x"))
        assertTrue(LinkOpenPolicy.isOpenable("HTTP://example.com"))
        for (bad in listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///etc/passwd", "meshcore://contact/add", "https:///x", "//example.com")) {
            assertFalse(LinkOpenPolicy.isOpenable(bad), bad)
        }
        assertEquals("example.com", LinkOpenPolicy.host("https://user@example.com:8080/x"))
    }

    @Test
    fun `inline image sizing fits the 280 by 300 box`() {
        assertEquals(InlineImageSize(280.0, 300.0), InlineImageMetrics.displaySize(0, 0))
        assertEquals(InlineImageSize(100.0, 50.0), InlineImageMetrics.displaySize(100, 50))
        assertEquals(InlineImageSize(280.0, 140.0), InlineImageMetrics.displaySize(1000, 500))
        val tall = InlineImageMetrics.displaySize(100, 1000)
        assertEquals(300.0, tall.height)
        assertEquals(30.0, tall.width, 0.0001)
        assertEquals(1.5, InlineImageMetrics.reservedAspect(InlineImageState.Loading(1.5)))
        assertEquals(16.0 / 9.0, InlineImageMetrics.reservedAspect(InlineImageState.Failed))
    }

    @Test
    fun `link dispatch resolves mentions locally, routes app links and only opens web links externally`() {
        val alice = Fixtures.contact("Alice")
        val outcomes = ArrayList<MentionTapEvaluator.Outcome>()
        val routed = ArrayList<String>()
        val opened = ArrayList<String>()
        var suppress = false
        val dispatcher = MessageLinkDispatcher(
            { listOf(alice) }, { Fixtures.radio }, { null }, { outcomes += it },
            { url -> routed += url; url.startsWith("meshcore://") }, { opened += it }, { suppress },
        )
        assertTrue(dispatcher.dispatch("meshcoreone://mention/Alice"))
        assertEquals(MentionTapEvaluator.Outcome.Navigate(alice), outcomes.single())
        assertTrue(routed.isEmpty())
        assertTrue(dispatcher.dispatch("meshcore://map?lat=1&lon=2"))
        assertTrue(dispatcher.dispatch("https://example.com"))
        assertEquals(listOf("https://example.com"), opened)
        assertFalse(dispatcher.dispatch("javascript:alert(1)"))
        assertFalse(dispatcher.dispatch("intent://x#Intent;scheme=http;end"))
        assertEquals(listOf("https://example.com"), opened, "unsafe schemes never reach the system")
        suppress = true
        assertTrue(dispatcher.dispatch("https://other.example"))
        assertEquals(1, opened.size, "a suppressed tap (actions sheet is up) opens nothing")
    }
}
