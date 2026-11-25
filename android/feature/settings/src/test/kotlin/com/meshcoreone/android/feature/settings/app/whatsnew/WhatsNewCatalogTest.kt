// PortedFrom: MC1Tests/Models/WhatsNewCatalogTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.app.whatsnew

import com.meshcoreone.android.feature.settings.app.support.OriginalCase
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class WhatsNewCatalogTest {
    @Test
    @OriginalCase("WhatsNewCatalogTests::every catalog SF Symbol name resolves to an image()", "platform-adaptation")
    fun `every catalog item has a glyph and distinct title and description resources`() {
        for (release in WhatsNewCatalog.releases) {
            for (item in release.items) {
                assertNotEquals(0, item.titleResource)
                assertNotEquals(0, item.descriptionResource)
                assertNotEquals(item.titleResource, item.descriptionResource)
                assertTrue(item.symbol.vector.defaultWidth.value > 0f)
            }
            assertEquals(release.items.size, release.items.map { it.titleResource }.toSet().size)
        }
    }

    @Test
    @OriginalCase("WhatsNewCatalogTests::no release ships an empty item list()")
    fun `no release ships an empty item list`() {
        for (release in WhatsNewCatalog.releases) assertTrue(release.items.isNotEmpty())
    }

    @Test
    fun `releases have unique versions and https notes links`() {
        assertEquals(WhatsNewCatalog.releases.size, WhatsNewCatalog.releases.map { it.version }.toSet().size)
        assertTrue(WhatsNewCatalog.releases.all { it.releaseNotesUrl.startsWith("https://") })
    }
}
