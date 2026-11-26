// PortedFrom: MC1Tests/ViewModels/RepeaterSettingsViewModelRegionTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import com.meshcoreone.android.feature.remotenodes.support.runSuspend
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** Swift suite `RepeaterSettingsParseRegionTreeTests`. */
class RepeaterRegionParseTest {
    private fun parse(dump: String) = RepeaterRegionParsing.parseRegionTree(dump)

    /** One flood-allowed row `<name> F\n` whose UTF-8 size is [utf8Count]. */
    private fun lfTerminatedFloodDump(utf8Count: Int): String {
        val suffix = " F\n"
        val dump = "a".repeat(utf8Count - suffix.length) + suffix
        check(dump.toByteArray(Charsets.UTF_8).size == utf8Count)
        check(dump.endsWith("\n"))
        return dump
    }

    @Test @OriginalCase("RepeaterSettingsParseRegionTreeTests::parseRegionTree keeps Ontario indent and parents()")
    fun `parseRegionTree keeps Ontario indent and parents`() {
        val dump = "* F\n can F\n  on F\n   gta F\n   ottawa F\n   hamilton F\n"
        val parsed = parse(dump)
        assertEquals(listOf("*", "can", "on", "gta", "ottawa", "hamilton"), parsed.map { it.name })
        assertEquals(listOf(null, "*", "can", "on", "on", "on"), parsed.map { it.parentName })
        assertEquals(listOf(0, 1, 2, 3, 3, 3), parsed.map { it.depth })
        assertTrue(parsed.all { it.floodAllowed })
    }

    @Test @OriginalCase("RepeaterSettingsParseRegionTreeTests::parseRegionTree strips caret and reads deny-flood()")
    fun `parseRegionTree strips caret and reads deny-flood`() {
        val parsed = parse("* F\n on^ F\n  gta\n")
        assertEquals(listOf("*", "on", "gta"), parsed.map { it.name })
        assertTrue(parsed[1].floodAllowed)
        assertTrue(parsed[1].isHome)
        assertFalse(parsed[2].floodAllowed)
        assertEquals("on", parsed[2].parentName)
    }

    @Test @OriginalCase("RepeaterSettingsParseRegionTreeTests::parseRegionTree strips factory Unscoped caret()")
    fun `parseRegionTree strips factory Unscoped caret`() {
        val parsed = parse("*^ F\n")
        assertEquals(listOf("*"), parsed.map { it.name })
        assertTrue(parsed[0].floodAllowed)
        assertEquals(null, parsed[0].parentName)
    }

    @Test @OriginalCase("RepeaterSettingsParseRegionTreeTests::parseRegionTree rejects skipped indent()")
    fun `parseRegionTree rejects skipped indent`() {
        assertTrue(parse("* F\n   gta F\n").isEmpty())
    }

    @Test @OriginalCase("RepeaterSettingsParseRegionTreeTests::fetchRegions does not mark loaded on empty parse()")
    fun `fetchRegions does not mark loaded on empty parse`() = runSuspend {
        val recorder = CommandRecorder().apply { repliesByCommand = mutableMapOf("region" to "* F\n   gta F") }
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        assertFalse(holder.regions.state.value.regionsLoaded)
        assertTrue(holder.regions.state.value.regionsError)
        assertFalse(holder.regions.state.value.isLoadingRegions)
    }

    @Test @OriginalCase("RepeaterSettingsParseRegionTreeTests::parseRegionTree rejects a space in the name()")
    fun `parseRegionTree rejects a space in the name`() {
        assertTrue(parse("* F\n foo bar F\n").isEmpty())
    }

    @Test @OriginalCase("RepeaterSettingsParseRegionTreeTests::parseRegionTree CRLF dump matches LF()")
    fun `parseRegionTree CRLF dump matches LF`() {
        assertEquals(parse("* F\n on F\n"), parse("* F\r\n on F\r\n"))
        assertEquals(2, parse("* F\r\n on F\r\n").size)
    }

    @Test @OriginalCase("RepeaterSettingsParseRegionTreeTests::parseRegionTree single-line CRLF dump matches LF()")
    fun `parseRegionTree single-line CRLF dump matches LF`() {
        assertEquals(parse("* F\n"), parse("* F\r\n"))
        assertEquals(1, parse("* F\r\n").size)
    }

    @Test @OriginalCase("RepeaterSettingsParseRegionTreeTests::parseRegionTree rejects a dump that does not end with a newline()")
    fun `parseRegionTree rejects a dump that does not end with a newline`() {
        assertTrue(parse("* F\n on F").isEmpty())
    }

    @Test @OriginalCase("RepeaterSettingsParseRegionTreeTests::parseRegionTree rejects a saturated newline-terminated dump()")
    fun `parseRegionTree rejects a saturated newline-terminated dump`() {
        assertTrue(parse(lfTerminatedFloodDump(RepeaterRegionParsing.FIRMWARE_REGION_DUMP_MAX_PAYLOAD_BYTES)).isEmpty())
    }

    @Test @OriginalCase("RepeaterSettingsParseRegionTreeTests::parseRegionTree accepts a dump one byte under the firmware cap()")
    fun `parseRegionTree accepts a dump one byte under the firmware cap`() {
        val parsed = parse(lfTerminatedFloodDump(RepeaterRegionParsing.FIRMWARE_REGION_DUMP_MAX_PAYLOAD_BYTES - 1))
        assertEquals(1, parsed.size)
        assertTrue(parsed[0].floodAllowed)
    }

    @Test @OriginalCase("RepeaterSettingsParseRegionTreeTests::fetchRegions empty parse after a successful load unloads and does not wipe()")
    fun `fetchRegions empty parse after a successful load unloads and does not wipe`() = runSuspend {
        val recorder = CommandRecorder().apply {
            repliesByCommand = mutableMapOf("region" to "* F\n duckburg F\n", "region default" to " default scope is duckburg")
        }
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.fetchDefaultScope()
        assertTrue(holder.regions.state.value.regionsLoaded)
        assertEquals(listOf("*", "duckburg"), holder.regionNames)
        assertEquals("duckburg", holder.regions.state.value.defaultScopeName)

        recorder.repliesByCommand = mutableMapOf("region" to "* F\n   gta F")
        holder.regions.fetchRegions()
        with(holder.regions.state.value) {
            assertFalse(regionsLoaded)
            assertTrue(regionsError)
            assertEquals(listOf("*", "duckburg"), regions.map { it.name })
            assertTrue(defaultScopeLoaded)
            assertEquals("duckburg", defaultScopeName)
        }

        recorder.resetCommands()
        assertFailsWith<AddRegionRejectedException> { holder.regions.addRegion("gta", RepeaterRegionEntry.Parent.Unscoped) }
        holder.regions.setDefaultScope(null)
        holder.regions.removeRegion("duckburg")
        holder.regions.toggleRegionFlood("duckburg")

        assertTrue(recorder.commands.isEmpty())
        assertEquals(listOf("*", "duckburg"), holder.regionNames)
        assertEquals("duckburg", holder.regions.state.value.defaultScopeName)
        assertEquals(true, holder.regions.state.value.regions.firstOrNull { it.name == "duckburg" }?.floodAllowed)
    }

    /** Expectations are swiftc output of the verbatim Swift parsers (oracle `settings_regions.swift.txt`). */
    @Test
    fun `region parsers keep Swift character semantics`() {
        // A lone CR is not a separator in Swift, so it stays inside the name.
        assertEquals(listOf("*", "a\rb"), parse("* F\n a\rb F\n").map { it.name })
        // A combining mark on the flag letter makes " F" a different suffix; the space then fails the row.
        assertTrue(parse("* F\u0301\n").isEmpty())
        // Blank interior lines are dropped; a row of only spaces is malformed.
        assertEquals(listOf("*", "on"), parse("* F\n\n on F\n").map { it.name })
        assertTrue(parse("* F\n   \n").isEmpty())
        // Only spaces count as indent or as the separator; a tab stays in the name.
        assertEquals(listOf("*\t"), parse("*\t F\n").map { it.name })
        assertEquals(listOf(Triple("*", false, true)), parse("*^\n").map { Triple(it.name, it.floodAllowed, it.isHome) })

        val named = RepeaterRegionParsing.ParsedDefaultScope::Named
        // Swift splits the default reply on the Character "\n" only, so a CRLF stays in the last token.
        assertEquals(named("x\r\n"), RepeaterRegionParsing.parseDefaultScopeReply(" default scope is x\r\n"))
        assertEquals(named("Y"), RepeaterRegionParsing.parseDefaultScopeReply("DEFAULT SCOPE IS Y"))
        assertEquals(named("z"), RepeaterRegionParsing.parseDefaultScopeReply("> default scope is now z\n"))
        assertEquals(named("is"), RepeaterRegionParsing.parseDefaultScopeReply("default scope is   \n"))
        assertEquals(null, RepeaterRegionParsing.parseDefaultScopeReply(" default scope is a\n other line"))
    }
}
