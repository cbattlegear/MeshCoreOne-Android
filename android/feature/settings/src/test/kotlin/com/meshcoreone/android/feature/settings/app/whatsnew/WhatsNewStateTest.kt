// PortedFrom: MC1Tests/State/WhatsNewStateTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.app.whatsnew

import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.feature.settings.app.support.OriginalCase
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Test

class WhatsNewStateTest {
    private class FakeBaseline(var lastShownVersion: String? = null, var failRead: Boolean = false, var failWrite: Boolean = false) : WhatsNewBaselineStore {
        var writes = 0

        override suspend fun read(): String? = if (failRead) throw java.io.IOException("read") else lastShownVersion

        override suspend fun write(version: String) {
            if (failWrite) throw java.io.IOException("write")
            writes++
            lastShownVersion = version
        }
    }

    private fun version(text: String) = WhatsNewVersion.parse(text)!!

    private fun release(major: Int, minor: Int, items: Int = 1) = WhatsNewRelease(
        WhatsNewVersion(major, minor), (0 until items).map { WhatsNewItem(MeshSymbol.CHECK, it + 1, it + 100) }, "https://example.com/releases",
    )

    private fun resolve(current: String, baseline: String?, onboarded: Boolean, catalog: List<WhatsNewRelease>) =
        WhatsNewState.resolve(version(current), baseline, onboarded, catalog)

    @Test
    @OriginalCase("WhatsNewStateTests::new install (not onboarded, no baseline) suppresses()")
    fun `new install suppresses`() = assertNull(resolve("1.1", null, false, listOf(release(1, 1))))

    @Test
    @OriginalCase("WhatsNewStateTests::upgrader (onboarded, no baseline, entry exists) shows()")
    fun `upgrader shows`() = assertEquals(WhatsNewVersion(1, 1), resolve("1.1", null, true, listOf(release(1, 1)))?.version)

    @Test
    @OriginalCase("WhatsNewStateTests::patch bump suppresses()")
    fun `patch bump suppresses`() = assertNull(resolve("1.0.2", "1.0", true, listOf(release(1, 0))))

    @Test
    @OriginalCase("WhatsNewStateTests::minor bump shows()")
    fun `minor bump shows`() = assertEquals(WhatsNewVersion(1, 1), resolve("1.1", "1.0", true, listOf(release(1, 1)))?.version)

    @Test
    @OriginalCase("WhatsNewStateTests::major bump shows()")
    fun `major bump shows`() = assertEquals(WhatsNewVersion(2, 0), resolve("2.0", "1.9", true, listOf(release(2, 0)))?.version)

    @Test
    @OriginalCase("WhatsNewStateTests::skipped version shows the current release's entry()")
    fun `skipped version shows the current releases entry`() =
        assertEquals(WhatsNewVersion(1, 2), resolve("1.2", "1.0", true, listOf(release(1, 1), release(1, 2)))?.version)

    @Test
    @OriginalCase("WhatsNewStateTests::no catalog entry for current version suppresses()")
    fun `no catalog entry suppresses`() = assertNull(resolve("1.3", "1.0", true, listOf(release(1, 1), release(1, 2))))

    @Test
    @OriginalCase("WhatsNewStateTests::matched entry with empty items suppresses()")
    fun `matched entry with empty items suppresses`() = assertNull(resolve("1.1", null, true, listOf(release(1, 1, items = 0))))

    @Test
    @OriginalCase("WhatsNewStateTests::re-launch after a show (baseline equals current) does not re-show()")
    fun `relaunch after a show does not re-show`() = assertNull(resolve("1.1", "1.1", true, listOf(release(1, 1))))

    @Test
    @OriginalCase("WhatsNewStateTests::screenshot mode neither shows nor writes a baseline()")
    fun `screenshot mode neither shows nor writes a baseline`() = runBlocking<Unit> {
        val baseline = FakeBaseline()
        val state = WhatsNewState(baseline, "1.1")
        state.evaluate(isOnboarded = true, isScreenshotMode = true, catalog = listOf(release(1, 1)))
        assertNull(state.pendingRelease.value)
        assertNull(baseline.lastShownVersion)
    }

    @Test
    @OriginalCase("WhatsNewStateTests::show path sets pendingRelease and defers the baseline to markShown()")
    fun `show path sets pending and defers the baseline`() = runBlocking<Unit> {
        val baseline = FakeBaseline()
        val state = WhatsNewState(baseline, "1.1")
        state.evaluate(isOnboarded = true, isScreenshotMode = false, catalog = listOf(release(1, 1)))
        assertEquals(WhatsNewVersion(1, 1), state.pendingRelease.value?.version)
        assertNull(baseline.lastShownVersion)
    }

    @Test
    @OriginalCase("WhatsNewStateTests::suppress path finalizes the baseline immediately()")
    fun `suppress path finalizes the baseline immediately`() = runBlocking<Unit> {
        val baseline = FakeBaseline()
        val state = WhatsNewState(baseline, "1.1")
        state.evaluate(isOnboarded = true, isScreenshotMode = false, catalog = emptyList())
        assertNull(state.pendingRelease.value)
        assertEquals("1.1", baseline.lastShownVersion)
    }

    @Test
    @OriginalCase("WhatsNewStateTests::unparseable version fails closed: neither shows nor writes a baseline()")
    fun `unparseable version fails closed`() = runBlocking<Unit> {
        val baseline = FakeBaseline()
        val state = WhatsNewState(baseline, "unknown")
        state.evaluate(isOnboarded = true, isScreenshotMode = false, catalog = listOf(release(1, 1)))
        assertNull(state.pendingRelease.value)
        assertNull(baseline.lastShownVersion)
    }

    @Test
    @OriginalCase("WhatsNewStateTests::markShown persists the running version and clears the pending release()")
    fun `markShown persists the running version and clears pending`() = runBlocking<Unit> {
        val baseline = FakeBaseline()
        val state = WhatsNewState(baseline, "1.1.7")
        state.evaluate(isOnboarded = true, isScreenshotMode = false, catalog = listOf(release(1, 1)))
        assertEquals(WhatsNewVersion(1, 1), state.pendingRelease.value?.version)
        assertNull(baseline.lastShownVersion)
        state.markShown()
        assertNull(state.pendingRelease.value)
        assertEquals("1.1.7", baseline.lastShownVersion)
    }

    @Test
    fun `an unparseable stored baseline fails closed`() = assertNull(resolve("1.1", "garbage", true, listOf(release(1, 1))))

    @Test
    fun `a persisted baseline from markShown suppresses the next launch`() = runBlocking<Unit> {
        val baseline = FakeBaseline("1.0")
        val first = WhatsNewState(baseline, "1.1")
        first.evaluate(isOnboarded = true, isScreenshotMode = false, catalog = listOf(release(1, 1)))
        first.markShown()
        val relaunch = WhatsNewState(baseline, "1.1")
        relaunch.evaluate(isOnboarded = true, isScreenshotMode = false, catalog = listOf(release(1, 1)))
        assertNull(relaunch.pendingRelease.value)
        assertEquals("1.1", baseline.lastShownVersion)
    }

    @Test
    fun `a patch bump with a stored baseline finalizes without showing`() = runBlocking<Unit> {
        val baseline = FakeBaseline("1.0")
        val state = WhatsNewState(baseline, "1.0.2")
        state.evaluate(isOnboarded = true, isScreenshotMode = false, catalog = listOf(release(1, 0)))
        assertNull(state.pendingRelease.value)
        assertEquals("1.0.2", baseline.lastShownVersion)
    }

    @Test
    fun `a new install suppresses and records the baseline`() = runBlocking<Unit> {
        val baseline = FakeBaseline()
        val state = WhatsNewState(baseline, "1.1")
        state.evaluate(isOnboarded = false, isScreenshotMode = false, catalog = listOf(release(1, 1)))
        assertNull(state.pendingRelease.value)
        assertEquals("1.1", baseline.lastShownVersion)
    }

    @Test
    fun `an onboarded device without a baseline shows and defers the baseline`() = runBlocking<Unit> {
        val baseline = FakeBaseline()
        val state = WhatsNewState(baseline, "1.1")
        state.evaluate(isOnboarded = true, isScreenshotMode = false, catalog = listOf(release(1, 1)))
        assertEquals(WhatsNewVersion(1, 1), state.pendingRelease.value?.version)
        assertNull(baseline.lastShownVersion)
    }

    @Test
    fun `a malformed stored baseline suppresses and is finalized to the running version`() = runBlocking<Unit> {
        val baseline = FakeBaseline("garbage")
        val state = WhatsNewState(baseline, "1.1")
        state.evaluate(isOnboarded = true, isScreenshotMode = false, catalog = listOf(release(1, 1)))
        assertNull(state.pendingRelease.value)
        assertEquals("1.1", baseline.lastShownVersion)
        assertEquals(1, baseline.writes)
    }

    @Test
    fun `evaluate runs once per launch so a later onboarding change cannot show the sheet`() = runBlocking<Unit> {
        val baseline = FakeBaseline()
        val state = WhatsNewState(baseline, "1.1")
        state.evaluate(isOnboarded = false, isScreenshotMode = false, catalog = listOf(release(1, 1)))
        state.evaluate(isOnboarded = true, isScreenshotMode = false, catalog = listOf(release(1, 1)))
        assertNull(state.pendingRelease.value)
        assertEquals(1, baseline.writes)
    }

    @Test
    fun `a baseline read failure propagates and shows nothing`() = runBlocking<Unit> {
        val state = WhatsNewState(FakeBaseline(failRead = true), "1.1")
        assertFailsWith<java.io.IOException> { state.evaluate(isOnboarded = true, isScreenshotMode = false, catalog = listOf(release(1, 1))) }
        assertNull(state.pendingRelease.value)
    }

    @Test
    fun `a failed markShown write preserves pending and propagates so dismissal can retry`() = runBlocking<Unit> {
        val baseline = FakeBaseline("1.0")
        val state = WhatsNewState(baseline, "1.1")
        state.evaluate(isOnboarded = true, isScreenshotMode = false, catalog = listOf(release(1, 1)))
        baseline.failWrite = true
        assertFailsWith<java.io.IOException> { state.markShown() }
        assertEquals(WhatsNewVersion(1, 1), state.pendingRelease.value?.version)
        assertEquals("1.0", baseline.lastShownVersion)
        baseline.failWrite = false
        state.markShown()
        assertNull(state.pendingRelease.value)
        assertEquals("1.1", baseline.lastShownVersion)
    }

    @Test
    fun `failed evaluation can retry without a false completed launch decision`() = runBlocking<Unit> {
        val baseline = FakeBaseline(failWrite = true)
        val state = WhatsNewState(baseline, "1.1")
        assertFailsWith<java.io.IOException> { state.evaluate(false, false, listOf(release(1, 1))) }
        baseline.failWrite = false
        state.evaluate(false, false, listOf(release(1, 1)))
        assertNull(state.pendingRelease.value)
        assertEquals("1.1", baseline.lastShownVersion)
        assertEquals(1, baseline.writes)
    }

    @Test
    fun `repeated dismissal persists once`() = runBlocking<Unit> {
        val baseline = FakeBaseline("1.0")
        val state = WhatsNewState(baseline, "1.1")
        state.evaluate(true, false, listOf(release(1, 1)))
        state.markShown()
        state.markShown()
        assertEquals(1, baseline.writes)
    }

    @Test
    fun `cancelled dismissal preserves the release and propagates cancellation`() = runBlocking<Unit> {
        var cancelWrite = true
        val baseline = object : WhatsNewBaselineStore {
            override suspend fun read() = "1.0"
            override suspend fun write(version: String) {
                if (cancelWrite) throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        val state = WhatsNewState(baseline, "1.1")
        state.evaluate(true, false, listOf(release(1, 1)))
        assertFailsWith<kotlinx.coroutines.CancellationException> { state.markShown() }
        assertEquals(WhatsNewVersion(1, 1), state.pendingRelease.value?.version)
        cancelWrite = false
        state.markShown()
        assertNull(state.pendingRelease.value)
    }

    @Test
    fun `concurrent launch evaluations do not interleave across the baseline read`() = runBlocking<Unit> {
        val reading = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        var reads = 0
        var writes = 0
        val baseline = object : WhatsNewBaselineStore {
            override suspend fun read(): String? {
                reads++
                reading.complete(Unit)
                releaseRead.await()
                return null
            }
            override suspend fun write(version: String) { writes++ }
        }
        val state = WhatsNewState(baseline, "1.1")
        val first = launch { state.evaluate(false, false, listOf(release(1, 1))) }
        reading.await()
        val second = launch { state.evaluate(true, false, listOf(release(1, 1))) }
        yield()
        releaseRead.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, reads)
        assertEquals(1, writes)
        assertNull(state.pendingRelease.value)
    }
}
