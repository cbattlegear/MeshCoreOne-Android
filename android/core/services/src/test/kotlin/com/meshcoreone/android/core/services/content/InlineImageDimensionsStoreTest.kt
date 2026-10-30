// PortedFrom: MC1Services/Tests/MC1ServicesTests/InlineImageDimensionsStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Primary file ownership stays WP-213 per port-manifest.json (InlineImageDimensionsStore
// *production* is WP-218's; its original Swift test file is WP-213's). Per explicit coordinator
// correction, that split does NOT mean WP-218 gets to independently re-derive similar coverage
// and move on -- each of the source's 9 original cases is ported below as a disclosed
// cross-consumer binding (see the `PortedFrom-case` comment above each matching test), not
// claimed as WP-218's own 154-declaration credit. Three additional cases
// (negative-width/negative-height rejection, single-malformed-entry-rejects-whole-parse) are
// disclosed WP-218 additions strengthening coverage of behavior the source already specifies
// (the `size.width > 0`/`size.height > 0` guard covers negative values identically to zero; the
// source's `try? JSONDecoder().decode(...)` is all-or-nothing by construction) but are not
// present as distinct `@Test` cases in the frozen source file.
package com.meshcoreone.android.core.services.content

import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.cancelAndJoin

class InlineImageDimensionsStoreTest {

    private val createdDirs = mutableListOf<File>()

    private fun tempFile(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "InlineImageDimensionsStoreTest-${UUID.randomUUID()}")
        createdDirs += dir
        return File(dir, "InlineImageDimensions.json")
    }

    @Test
    fun `inline subscriber completion cannot mutate a delivery or finish iteration`() = runTest {
        val store = InlineImageDimensionsStore(tempFile())
        val first = async(kotlinx.coroutines.Dispatchers.Unconfined) { store.resolutionUpdates.first() }
        val second = async(kotlinx.coroutines.Dispatchers.Unconfined) { store.resolutionUpdates.first() }
        store.save("first", 2.0, 1.0)
        assertEquals("first", first.await())
        assertEquals("first", second.await())
        val finishingA = async(kotlinx.coroutines.Dispatchers.Unconfined) { store.resolutionUpdates.toList() }
        val finishingB = async(kotlinx.coroutines.Dispatchers.Unconfined) { store.resolutionUpdates.toList() }
        store.close()
        assertEquals(emptyList(), finishingA.await())
        assertEquals(emptyList(), finishingB.await())
    }

    @AfterTest
    fun cleanup() {
        createdDirs.forEach { it.deleteRecursively() }
    }

    // PortedFrom-case: "save then aspect(for:) returns the expected ratio"
    @Test
    fun `save then aspect returns the expected ratio`() = runTest {
        val file = tempFile()
        val store = InlineImageDimensionsStore(file)

        store.save("https://example.com/a.png", width = 200.0, height = 100.0)

        assertEquals(2.0, store.aspect("https://example.com/a.png"))
    }

    // PortedFrom-case: "save with zero width is rejected silently"
    @Test
    fun `save with zero width is rejected silently`() = runTest {
        val file = tempFile()
        val store = InlineImageDimensionsStore(file)

        store.save("https://example.com/zero-width.png", width = 0.0, height = 100.0)

        assertNull(store.aspect("https://example.com/zero-width.png"))
    }

    // PortedFrom-case: "save with zero height is rejected silently"
    @Test
    fun `save with zero height is rejected silently`() = runTest {
        val file = tempFile()
        val store = InlineImageDimensionsStore(file)

        store.save("https://example.com/zero-height.png", width = 100.0, height = 0.0)

        assertNull(store.aspect("https://example.com/zero-height.png"))
    }

    // WP-218 addition (not a distinct source case): the source's `size.width > 0` guard already
    // rejects negative width identically to zero width; this exercises that same guard clause at
    // a different input to close an obvious boundary gap the source's own suite left untested.
    @Test
    fun `save with negative width is rejected silently`() = runTest {
        val file = tempFile()
        val store = InlineImageDimensionsStore(file)

        store.save("https://example.com/negative-width.png", width = -10.0, height = 100.0)

        assertNull(store.aspect("https://example.com/negative-width.png"))
    }

    // WP-218 addition (not a distinct source case): see negative-width rationale above.
    @Test
    fun `save with negative height is rejected silently`() = runTest {
        val file = tempFile()
        val store = InlineImageDimensionsStore(file)

        store.save("https://example.com/negative-height.png", width = 100.0, height = -10.0)

        assertNull(store.aspect("https://example.com/negative-height.png"))
    }

    // PortedFrom-case: "init recovers from a corrupt file by starting empty"
    @Test
    fun `init recovers from a corrupt file by starting empty`() {
        val file = tempFile()
        file.parentFile.mkdirs()
        file.writeText("not json", Charsets.UTF_8)

        val store = InlineImageDimensionsStore(file)

        assertNull(store.aspect("https://example.com/anything.png"))
    }

    // WP-218 addition (not a distinct source case): the source's `try? JSONDecoder().decode(...)`
    // is all-or-nothing by construction (any decode failure, including a single malformed key's
    // value, throws and the whole `try?` becomes `nil`); this exercises that same all-or-nothing
    // contract at a more targeted failure mode (one well-formed-JSON-but-wrong-typed field) than
    // the source's own "not json" case, which only proves top-level parse failure.
    @Test
    fun `init recovers from a single malformed entry by starting entirely empty`() {
        val file = tempFile()
        file.parentFile.mkdirs()
        // "fetchedAt" is a string, not a number: this single malformed entry must reject the
        // WHOLE parse (all-or-nothing), not just this one key.
        file.writeText(
            """{"https://example.com/a.png":{"aspect":2.0,"fetchedAt":"oops"}}""",
            Charsets.UTF_8,
        )

        val store = InlineImageDimensionsStore(file)

        assertNull(store.aspect("https://example.com/a.png"))
    }

    // PortedFrom-case: "init on non-existent file yields empty store"
    @Test
    fun `init on non-existent file yields empty store`() {
        val file = tempFile()

        val store = InlineImageDimensionsStore(file)

        assertNull(store.aspect("https://example.com/unknown.png"))
    }

    // PortedFrom-case: "two saves are both readable via aspect(for:)"
    @Test
    fun `two saves are both readable via aspect`() = runTest {
        val file = tempFile()
        val store = InlineImageDimensionsStore(file)

        store.save("https://example.com/a.png", width = 400.0, height = 200.0)
        store.save("https://example.com/b.png", width = 100.0, height = 400.0)

        assertEquals(2.0, store.aspect("https://example.com/a.png"))
        assertEquals(0.25, store.aspect("https://example.com/b.png"))
    }

    // PortedFrom-case: "resolutionUpdates emits the URL on save"
    @Test
    fun `resolutionUpdates emits the URL on save`() = runTest {
        val file = tempFile()
        val store = InlineImageDimensionsStore(file)

        val received = async { store.resolutionUpdates.first() }
        // Allow the collector above to register before saving, matching the source contract
        // that only events yielded after subscription are delivered.
        testScheduler.runCurrent()

        store.save("https://example.com/stream.png", width = 300.0, height = 150.0)

        assertEquals("https://example.com/stream.png", received.await())
    }

    // PortedFrom-case: "resolutionUpdates delivers every event to every concurrent subscriber"
    @Test
    fun `resolutionUpdates delivers every event to every concurrent subscriber`() = runTest {
        val file = tempFile()
        val store = InlineImageDimensionsStore(file)

        val subscriberOne = async { store.resolutionUpdates.take(2).toList() }
        val subscriberTwo = async { store.resolutionUpdates.take(2).toList() }
        testScheduler.runCurrent()

        store.save("https://example.com/multicast-a.png", width = 200.0, height = 100.0)
        store.save("https://example.com/multicast-b.png", width = 300.0, height = 100.0)

        val expected = listOf(
            "https://example.com/multicast-a.png",
            "https://example.com/multicast-b.png",
        )
        assertEquals(expected, subscriberOne.await())
        assertEquals(expected, subscriberTwo.await())
    }

    @Test
    fun `subscription registers before collection and keeps the newest 64 independently`() = runTest {
        val store = InlineImageDimensionsStore(tempFile())
        val slow = store.resolutionUpdates
        val fastEvents = mutableListOf<String>()
        val fast = launch(start = CoroutineStart.UNDISPATCHED) {
            store.resolutionUpdates.collect { fastEvents.add(it) }
        }
        repeat(100) {
            store.save("https://example.com/$it.png", 200.0, 100.0)
            testScheduler.runCurrent()
        }
        assertEquals((0 until 100).map { "https://example.com/$it.png" }, fastEvents)
        store.close()
        assertEquals((36 until 100).map { "https://example.com/$it.png" }, slow.toList())
        fast.join()
        assertEquals(emptyList(), store.resolutionUpdates.toList())
    }

    @Test
    fun `cancelling one subscription does not terminate another and close drains its tail`() = runTest {
        val store = InlineImageDimensionsStore(tempFile())
        val received = mutableListOf<String>()
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { store.resolutionUpdates.collect {} }
        val survivor = launch(start = CoroutineStart.UNDISPATCHED) {
            store.resolutionUpdates.collect { received.add(it) }
        }
        cancelled.cancelAndJoin()
        repeat(2) { store.save("https://example.com/same.png", 200.0, 100.0) }
        store.close()
        survivor.join()
        assertEquals(List(2) { "https://example.com/same.png" }, received)
    }

    // PortedFrom-case: "round-trip: recreated store reads previously persisted aspect"
    @Test
    fun `round-trip recreated store reads previously persisted aspect`() = runTest {
        val file = tempFile()
        val writer = InlineImageDimensionsStore(file)
        writer.save("https://example.com/persisted.png", width = 600.0, height = 300.0)

        val reader = InlineImageDimensionsStore(file)

        assertEquals(2.0, reader.aspect("https://example.com/persisted.png"))
    }
}
