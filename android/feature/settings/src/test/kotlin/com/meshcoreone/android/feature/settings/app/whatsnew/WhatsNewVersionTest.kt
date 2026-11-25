// PortedFrom: MC1Tests/Models/WhatsNewVersionTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.app.whatsnew

import com.meshcoreone.android.feature.settings.app.support.OriginalCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class WhatsNewVersionTest {
    @Test
    @OriginalCase("WhatsNewVersionTests::parses major.minor()")
    fun `parses major dot minor`() {
        val version = WhatsNewVersion.parse("1.2")
        assertEquals(1, version?.major)
        assertEquals(2, version?.minor)
    }

    @Test
    @OriginalCase("WhatsNewVersionTests::ignores the patch component()")
    fun `ignores the patch component`() {
        assertEquals(WhatsNewVersion(1, 0), WhatsNewVersion.parse("1.0.2"))
    }

    @Test
    @OriginalCase("WhatsNewVersionTests::comparison is lexicographic on (major, minor)()")
    fun `comparison is lexicographic on major then minor`() {
        assertTrue(WhatsNewVersion(1, 0) < WhatsNewVersion(1, 1))
        assertTrue(WhatsNewVersion(1, 9) < WhatsNewVersion(2, 0))
        assertTrue(WhatsNewVersion(1, 0) < WhatsNewVersion(1, 2))
        assertTrue(WhatsNewVersion(2, 0) > WhatsNewVersion(1, 9))
    }

    @Test
    @OriginalCase("WhatsNewVersionTests::a patch bump is not greater(baseline : String , current : String)")
    fun `a patch bump is not greater`() {
        for ((baseline, current) in listOf("1.0" to "1.0.2", "1.0.0" to "1.0.9")) {
            val lhs = assertNotNull(WhatsNewVersion.parse(current))
            val rhs = assertNotNull(WhatsNewVersion.parse(baseline))
            assertEquals(rhs, lhs)
            assertFalse(lhs > rhs)
        }
    }

    @Test
    @OriginalCase("WhatsNewVersionTests::unparseable strings yield nil(input : String)")
    fun `unparseable strings yield null`() {
        for (input in listOf("unknown", "2", "2.0-beta", "1.0 (123)", "", "x.y", "1.")) {
            assertNull(WhatsNewVersion.parse(input), "'$input'")
        }
    }

    @Test
    fun `components must be ASCII digits and empty components are dropped like Swift split`() {
        assertNull(WhatsNewVersion.parse("١.٢"))
        assertNull(WhatsNewVersion.parse(" 1.2"))
        assertEquals(WhatsNewVersion(1, 2), WhatsNewVersion.parse("1..2"))
        assertEquals(WhatsNewVersion(1, 2), WhatsNewVersion.parse(".1.2"))
    }
}
