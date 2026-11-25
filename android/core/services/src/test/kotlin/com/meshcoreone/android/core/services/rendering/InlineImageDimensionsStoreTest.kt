// PortedFrom: MC1Services/Tests/MC1ServicesTests/InlineImageDimensionsStoreTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.MessageDTO
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Stand-in for the disposable JSON file in Application Support (bytes survive store re-creation). */
private class MemoryFile(var contents: ByteArray? = null)

/**
 * Reference implementation of the [InlineImageDimensionsStoring] contract. The production store (file
 * location, JSON codec, atomic writes) is WP-218's; this double encodes `aspect<TAB>url` lines so the
 * contract's persistence and corrupt-file recovery are observable without that implementation.
 */
private class ReferenceInlineImageDimensionsStore(private val file: MemoryFile) : InlineImageDimensionsStoring {
    private val lock = Any()
    private var aspects: Map<String, Double> = decode(file.contents) ?: emptyMap()
    private var subscribers: List<Channel<WebURL>> = emptyList()

    override fun aspect(url: WebURL): Double? = synchronized(lock) { aspects[url.absoluteString] }

    override suspend fun save(url: WebURL, width: Double, height: Double) {
        if (!(width > 0 && height > 0)) return
        val targets = synchronized(lock) {
            aspects = aspects + (url.absoluteString to width / height)
            file.contents = aspects.entries.joinToString("\n") { "${it.value}\t${it.key}" }.toByteArray()
            subscribers
        }
        targets.forEach { it.trySend(url) }
    }

    override fun resolutionUpdates(): ReceiveChannel<WebURL> {
        val channel = Channel<WebURL>(InlineImageDimensionsStoring.RESOLUTION_BUFFER_DEPTH, BufferOverflow.DROP_OLDEST)
        synchronized(lock) { subscribers = subscribers + channel }
        return channel
    }

    private companion object {
        fun decode(bytes: ByteArray?): Map<String, Double>? {
            val text = bytes?.toString(Charsets.UTF_8) ?: return null
            if (text.isEmpty()) return emptyMap()
            return text.lines().associate { line ->
                val parts = line.split('\t', limit = 2)
                val aspect = parts.first().toDoubleOrNull()
                if (parts.size != 2 || aspect == null) return null
                parts[1] to aspect
            }
        }
    }
}

/**
 * Adaptation: the store implementation is owned by WP-218 and is not on main. Each Swift case runs
 * against the [InlineImageDimensionsStoring] port through a reference double, and additionally checks
 * the WP-213 behavior the store feeds: the resolved aspect lands in [InlineImage.cachedAspect] /
 * [LinkPreviewFragmentState.heroAspectHint] via [MessageFragmentBuilder] (null keeps the 16:9 fallback).
 * Swift's 1 s timeout tasks become `withTimeout` bounds on the receives.
 */
class InlineImageDimensionsStoreTest {
    private fun url(value: String): WebURL = requireNotNull(WebURL.parse(value))

    private fun cachedAspectThroughBuilder(store: InlineImageDimensionsStoring, imageURL: WebURL): Double? {
        val message: MessageDTO = RenderingFixtures.makeMessage(text = "see ${imageURL.absoluteString}")
        val inputs = MessageBuildInputs(
            messageID = message.id, previewState = PreviewLoadState.IDLE, loadedPreview = null, cachedURL = imageURL,
            isInlineImageURL = true, hasInlineImageRef = false, hasPreviewImageRef = false, hasPreviewIconRef = false,
            imageIsGIF = false, inlineImageAspect = store.aspect(imageURL), formattedText = null, baseColor = BaseColorSlot.OUTGOING,
            formattedPath = null, senderResolution = NodeNameResolution("Me", NodeNameMatchKind.EXACT), showTimestamp = false,
            showDirectionGap = false, showSenderName = false, showNewMessagesDivider = false,
        )
        val env = RenderingFixtures.makeEnvInputs().copy(previewsEnabled = true)
        val fragment = MessageFragmentBuilder.makeItem(message, inputs, env).content.filterIsInstance<MessageFragment.InlineImage>().single()
        return fragment.image.cachedAspect
    }

    @TestFactory
    fun inlineImageDimensionsStoreTests(): List<DynamicTest> = listOf(
        case("save then aspect(for:) returns the expected ratio") {
            runRendering {
                val store = ReferenceInlineImageDimensionsStore(MemoryFile())
                val imageURL = url("https://example.com/a.png")
                store.save(imageURL, 200.0, 100.0)
                assertEquals(2.0, store.aspect(imageURL))
                assertEquals(2.0, cachedAspectThroughBuilder(store, imageURL))
            }
        },
        case("save with zero width is rejected silently") {
            runRendering {
                val store = ReferenceInlineImageDimensionsStore(MemoryFile())
                val imageURL = url("https://example.com/zero-width.png")
                store.save(imageURL, 0.0, 100.0)
                assertNull(store.aspect(imageURL))
                assertNull(cachedAspectThroughBuilder(store, imageURL))
            }
        },
        case("save with zero height is rejected silently") {
            runRendering {
                val store = ReferenceInlineImageDimensionsStore(MemoryFile())
                val imageURL = url("https://example.com/zero-height.png")
                store.save(imageURL, 100.0, 0.0)
                assertNull(store.aspect(imageURL))
                assertNull(cachedAspectThroughBuilder(store, imageURL))
            }
        },
        case("init recovers from a corrupt file by starting empty") {
            val store = ReferenceInlineImageDimensionsStore(MemoryFile("not json".toByteArray()))
            assertNull(store.aspect(url("https://example.com/anything.png")))
        },
        case("init on non-existent file yields empty store") {
            val store = ReferenceInlineImageDimensionsStore(MemoryFile())
            assertNull(store.aspect(url("https://example.com/unknown.png")))
        },
        case("two saves are both readable via aspect(for:)") {
            runRendering {
                val store = ReferenceInlineImageDimensionsStore(MemoryFile())
                val urlA = url("https://example.com/a.png")
                val urlB = url("https://example.com/b.png")
                store.save(urlA, 400.0, 200.0)
                store.save(urlB, 100.0, 400.0)
                assertEquals(2.0, store.aspect(urlA))
                assertEquals(0.25, store.aspect(urlB))
                assertEquals(0.25, cachedAspectThroughBuilder(store, urlB))
            }
        },
        case("resolutionUpdates emits the URL on save") {
            runRendering {
                val store = ReferenceInlineImageDimensionsStore(MemoryFile())
                val imageURL = url("https://example.com/stream.png")
                val stream = store.resolutionUpdates() // registered on return, before the save
                val received = async { withTimeout(STREAM_WAIT_MILLIS) { stream.receive() } }
                store.save(imageURL, 300.0, 150.0)
                assertEquals(imageURL, received.await())
            }
        },
        case("resolutionUpdates delivers every event to every concurrent subscriber") {
            runRendering {
                val store = ReferenceInlineImageDimensionsStore(MemoryFile())
                val urlA = url("https://example.com/multicast-a.png")
                val urlB = url("https://example.com/multicast-b.png")
                val streamOne = store.resolutionUpdates()
                val streamTwo = store.resolutionUpdates()
                val consumerOne = async { withTimeout(STREAM_WAIT_MILLIS) { List(2) { streamOne.receive() } } }
                val consumerTwo = async { withTimeout(STREAM_WAIT_MILLIS) { List(2) { streamTwo.receive() } } }
                store.save(urlA, 200.0, 100.0)
                store.save(urlB, 300.0, 100.0)
                assertEquals(listOf(urlA, urlB), consumerOne.await())
                assertEquals(listOf(urlA, urlB), consumerTwo.await())
            }
        },
        case("round-trip: recreated store reads previously persisted aspect") {
            runRendering {
                val file = MemoryFile()
                val imageURL = url("https://example.com/persisted.png")
                ReferenceInlineImageDimensionsStore(file).save(imageURL, 600.0, 300.0)
                val reader = ReferenceInlineImageDimensionsStore(file)
                assertEquals(2.0, reader.aspect(imageURL))
                assertEquals(2.0, cachedAspectThroughBuilder(reader, imageURL))
            }
        },
    )

    @TestFactory
    fun nativeAspectRouting(): List<DynamicTest> = listOf(
        DynamicTest.dynamicTest("WP-213::remembered hero aspect reaches the link-preview shimmer hint") {
            val message = RenderingFixtures.makeMessage(text = "see https://example.com/page")
            val inputs = MessageBuildInputs(
                messageID = message.id, previewState = PreviewLoadState.LOADING, loadedPreview = null,
                cachedURL = url("https://example.com/page"), isInlineImageURL = false, hasInlineImageRef = false,
                hasPreviewImageRef = false, hasPreviewIconRef = false, imageIsGIF = false, previewHeroAspect = 1.91,
                formattedText = null, baseColor = BaseColorSlot.OUTGOING, formattedPath = null,
                senderResolution = NodeNameResolution("Me", NodeNameMatchKind.EXACT), showTimestamp = false,
                showDirectionGap = false, showSenderName = false, showNewMessagesDivider = false,
            )
            val env = RenderingFixtures.makeEnvInputs().copy(previewsEnabled = true)
            val card = MessageFragmentBuilder.makeItem(message, inputs, env).content.filterIsInstance<MessageFragment.LinkPreview>().single()
            assertEquals(1.91, card.state.heroAspectHint)
            assertEquals(LinkPreviewFragmentState.Mode.Loading(url("https://example.com/page")), card.state.mode)
        },
    )

    private fun case(name: String, body: () -> Unit) = DynamicTest.dynamicTest("InlineImageDimensionsStoreTests::$name()", body)

    private companion object {
        const val STREAM_WAIT_MILLIS = 1_000L
    }
}
