// PortedFrom: MC1Tests/ViewModels/RepeaterSettingsViewModelRegionTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.settings.RepeaterRegionParsing.ParsedDefaultScope
import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import com.meshcoreone.android.feature.remotenodes.support.runSuspend
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Swift suite `RepeaterSettingsDefaultScopeTests`. */
class RepeaterDefaultScopeTest {
    private val unknownRegion = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsUnknownRegion)
    private val notEmpty = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsNotEmpty)

    private fun recorder(vararg replies: Pair<String, String>) =
        CommandRecorder("OK - (flood allowed)").apply { repliesByCommand = mutableMapOf(*replies) }

    private val duckburg = arrayOf("region" to "* F\n duckburg F\n", "region default" to " default scope is duckburg")

    private val RepeaterSettingsStateHolder.scope get() = regions.state.value

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::parseDefaultScopeReply reads get and set lines()")
    fun `parseDefaultScopeReply reads get and set lines`() {
        assertEquals(ParsedDefaultScope.Cleared, RepeaterRegionParsing.parseDefaultScopeReply(" default scope is <null>"))
        assertEquals(ParsedDefaultScope.Named("duckburg"), RepeaterRegionParsing.parseDefaultScopeReply(" default scope is duckburg"))
        assertEquals(ParsedDefaultScope.Named("duckburg"), RepeaterRegionParsing.parseDefaultScopeReply(">  default scope is now duckburg"))
        assertEquals(ParsedDefaultScope.Cleared, RepeaterRegionParsing.parseDefaultScopeReply(" default scope is now <null>"))
        assertNull(RepeaterRegionParsing.parseDefaultScopeReply("OK"))
        assertEquals(ParsedDefaultScope.Cleared, RepeaterRegionParsing.parseDefaultScopeReply(" default scope is *"))
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::fetchRegions does not send region default()")
    fun `fetchRegions does not send region default`() = runSuspend {
        val recorder = recorder(*duckburg)
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        assertEquals(listOf("region"), recorder.commands)
        assertEquals(listOf("*", "duckburg"), holder.regionNames)
        assertNull(holder.scope.defaultScopeName)
        assertFalse(holder.scope.defaultScopeLoaded)
        assertFalse(holder.scope.regionsError)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::fetchDefaultScope reads the firmware default()")
    fun `fetchDefaultScope reads the firmware default`() = runSuspend {
        val recorder = recorder(*duckburg)
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        recorder.resetCommands()
        holder.regions.fetchDefaultScope()
        assertEquals(listOf("region default"), recorder.commands)
        assertEquals("duckburg", holder.scope.defaultScopeName)
        assertTrue(holder.scope.defaultScopeLoaded)
        assertFalse(holder.scope.regionsError)
        assertFalse(holder.scope.isLoadingDefaultScope)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::fetchDefaultScope sends nothing before repeater v1_15()")
    fun `fetchDefaultScope sends nothing before repeater v1_15`() = runSuspend {
        val recorder = recorder(*duckburg)
        val holder = regionHolder(recorder, "v1.14.1")
        holder.regions.fetchRegions()
        recorder.resetCommands()
        holder.regions.fetchDefaultScope()
        assertTrue(recorder.commands.isEmpty())
        assertFalse(holder.regions.supportsRegionDefaultScope)
        assertFalse(holder.scope.defaultScopeLoaded)
        assertNull(holder.scope.defaultScopeName)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::fetchDefaultScope sends region default for CLI ver banner at v1_15()")
    fun `fetchDefaultScope sends region default for CLI ver banner at v1_15`() = runSuspend {
        val recorder = recorder(*duckburg)
        val holder = regionHolder(recorder, "MeshCore v1.15.0 (2025-04-18)")
        holder.regions.fetchRegions()
        recorder.resetCommands()
        holder.regions.fetchDefaultScope()
        assertEquals(listOf("region default"), recorder.commands)
        assertTrue(holder.regions.supportsRegionDefaultScope)
        assertTrue(holder.scope.defaultScopeLoaded)
        assertEquals("duckburg", holder.scope.defaultScopeName)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::fetchDefaultScope sends nothing when firmware version is unknown()")
    fun `fetchDefaultScope sends nothing when firmware version is unknown`() = runSuspend {
        val recorder = recorder(*duckburg)
        val holder = regionHolder(recorder, null)
        holder.regions.fetchRegions()
        recorder.resetCommands()
        holder.regions.fetchDefaultScope()
        assertTrue(recorder.commands.isEmpty())
        assertFalse(holder.regions.supportsRegionDefaultScope)
        assertFalse(holder.scope.defaultScopeLoaded)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::setDefaultScope sends nothing before repeater v1_15()")
    fun `setDefaultScope sends nothing before repeater v1_15`() = runSuspend {
        val recorder = recorder("region" to "* F\n duckburg F\n", "region default duckburg" to " default scope is now duckburg")
        val holder = regionHolder(recorder, "v1.14.1")
        holder.regions.fetchRegions()
        recorder.resetCommands()
        holder.regions.setDefaultScope("duckburg")
        assertTrue(recorder.commands.isEmpty())
        assertNull(holder.scope.defaultScopeName)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::removeRegion of current default skips clear before repeater v1_15()")
    fun `removeRegion of current default skips clear before repeater v1_15`() = runSuspend {
        val recorder = recorder(
            "region" to "* F\n duckburg F\n", "region remove duckburg" to "OK",
            "region default <null>" to " default scope is now <null>",
        )
        val holder = regionHolder(recorder, "v1.14.1")
        holder.regions.fetchRegions()
        holder.regions.setDefaultScopeName("duckburg")
        recorder.resetCommands()
        holder.regions.removeRegion("duckburg")
        assertEquals(listOf("region remove duckburg"), recorder.commands)
        assertEquals("duckburg", holder.scope.defaultScopeName)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::fetchDefaultScope treats firmware null as no default scope()")
    fun `fetchDefaultScope treats firmware null as no default scope`() = runSuspend {
        val holder = regionHolder(recorder("region" to "* F\n", "region default" to " default scope is <null>"))
        holder.regions.fetchRegions()
        holder.regions.fetchDefaultScope()
        assertNull(holder.scope.defaultScopeName)
        assertTrue(holder.scope.defaultScopeLoaded)
        assertFalse(holder.scope.regionsError)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::unparsed default reply does not fail the regions section()")
    fun `unparsed default reply does not fail the regions section`() = runSuspend {
        val holder = regionHolder(recorder("region" to "* F\n", "region default" to "OK"))
        holder.regions.fetchRegions()
        assertEquals(listOf("*"), holder.regionNames)
        assertNull(holder.scope.defaultScopeName)
        assertFalse(holder.scope.defaultScopeLoaded)
        assertFalse(holder.scope.regionsError)
        holder.regions.fetchDefaultScope()
        assertNull(holder.scope.defaultScopeName)
        assertFalse(holder.scope.defaultScopeLoaded)
        assertFalse(holder.scope.regionsError)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::unparsed default reply keeps the previous default scope()")
    fun `unparsed default reply keeps the previous default scope`() = runSuspend {
        val recorder = recorder(*duckburg)
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.fetchDefaultScope()
        recorder.repliesByCommand["region default"] = "OK"
        holder.regions.fetchDefaultScope()
        assertEquals("duckburg", holder.scope.defaultScopeName)
        assertTrue(holder.scope.defaultScopeLoaded)
        assertFalse(holder.scope.regionsError)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::default-scope timeout keeps the previous value and does not fail regions()")
    fun `default-scope timeout keeps the previous value and does not fail regions`() = runSuspend {
        val recorder = recorder(*duckburg)
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.fetchDefaultScope()
        recorder.errorsByCommand["region default"] = FakeTimeout()
        holder.regions.fetchDefaultScope()
        assertEquals("duckburg", holder.scope.defaultScopeName)
        assertTrue(holder.scope.defaultScopeLoaded)
        assertFalse(holder.scope.regionsError)
        assertFalse(holder.scope.isLoadingDefaultScope)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::setDefaultScope sends nothing when not loaded()")
    fun `setDefaultScope sends nothing when not loaded`() = runSuspend {
        val recorder = CommandRecorder("OK - (flood allowed)")
        val holder = regionHolder(recorder)
        holder.regions.setDefaultScope("duckburg")
        assertTrue(recorder.commands.isEmpty())
        assertNull(holder.scope.defaultScopeName)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::setDefaultScope sends region default and does not mark unsaved()")
    fun `setDefaultScope sends region default and does not mark unsaved`() = runSuspend {
        val recorder = recorder(
            "region" to "* F\n duckburg\n", "region default" to " default scope is <null>",
            "region default duckburg" to " default scope is now duckburg",
        )
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.fetchDefaultScope()
        recorder.resetCommands()
        holder.regions.setDefaultScope("duckburg")
        assertEquals(listOf("region default duckburg"), recorder.commands)
        assertEquals("duckburg", holder.scope.defaultScopeName)
        assertFalse(holder.scope.hasUnsavedRegionChanges)
        assertEquals(true, holder.scope.regions.firstOrNull { it.name == "duckburg" }?.floodAllowed)
        assertNull(holder.errorMessage)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::setDefaultScope nil sends firmware null token()")
    fun `setDefaultScope null sends firmware null token`() = runSuspend {
        val recorder = recorder(*duckburg, "region default <null>" to " default scope is now <null>")
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.fetchDefaultScope()
        recorder.resetCommands()
        holder.regions.setDefaultScope(null)
        assertEquals(listOf("region default <null>"), recorder.commands)
        assertNull(holder.scope.defaultScopeName)
        assertFalse(holder.scope.hasUnsavedRegionChanges)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::setDefaultScope non-matching reply leaves the current value()")
    fun `setDefaultScope non-matching reply leaves the current value`() = runSuspend {
        val recorder = recorder(*duckburg, "region default nope" to "Err - unknown region")
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.fetchDefaultScope()
        recorder.resetCommands()
        holder.regions.setDefaultScope("nope")
        assertEquals("duckburg", holder.scope.defaultScopeName)
        assertEquals(unknownRegion, holder.errorMessage)
        assertFalse(holder.helper.state.value.isApplying)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::setDefaultScope does not send wildcard()")
    fun `setDefaultScope does not send wildcard`() = runSuspend {
        val recorder = recorder(*duckburg)
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.fetchDefaultScope()
        recorder.resetCommands()
        holder.regions.setDefaultScope(RepeaterRegionEntry.UNSCOPED_NAME)
        assertTrue(recorder.commands.isEmpty())
        assertEquals("duckburg", holder.scope.defaultScopeName)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::setDefaultScope same value is a no-op()")
    fun `setDefaultScope same value is a no-op`() = runSuspend {
        val recorder = recorder(*duckburg)
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.fetchDefaultScope()
        recorder.resetCommands()
        holder.regions.setDefaultScope("duckburg")
        assertTrue(recorder.commands.isEmpty())
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::removeRegion sends nothing when not loaded()")
    fun `removeRegion sends nothing when not loaded`() = runSuspend {
        val recorder = CommandRecorder("OK - (flood allowed)")
        val holder = regionHolder(recorder)
        holder.regions.setRegions(listOf(region("*", null, 0), region("duckburg", "*", 1)))
        holder.regions.removeRegion("duckburg")
        assertTrue(recorder.commands.isEmpty())
        assertEquals(listOf("*", "duckburg"), holder.regionNames)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::toggleRegionFlood re-finds by name after the reply()")
    fun `toggleRegionFlood re-finds by name after the reply`() = runSuspend {
        val recorder = recorder(
            "region" to "* F\n duckburg F\n", "region default" to " default scope is <null>", "region denyf duckburg" to "OK",
        )
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        recorder.resetCommands()
        recorder.onSend = { command ->
            if (command == "region denyf duckburg") {
                holder.regions.setRegions(listOf(region("duckburg", "*", 1), region("other", "*", 1)))
            }
        }
        holder.regions.toggleRegionFlood("duckburg")
        assertEquals(false, holder.scope.regions.firstOrNull { it.name == "duckburg" }?.floodAllowed)
        assertEquals(true, holder.scope.regions.firstOrNull { it.name == "other" }?.floodAllowed)
        assertTrue(holder.scope.hasUnsavedRegionChanges)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::toggleRegionFlood does not trap when the row is gone after await()")
    fun `toggleRegionFlood does not trap when the row is gone after await`() = runSuspend {
        val recorder = recorder(
            "region" to "* F\n duckburg F\n", "region default" to " default scope is <null>", "region denyf duckburg" to "OK",
        )
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        recorder.onSend = { command ->
            if (command.startsWith("region denyf")) holder.regions.setRegions(listOf(region("*", null, 0)))
        }
        holder.regions.toggleRegionFlood("duckburg")
        assertEquals(listOf("*"), holder.regionNames)
        assertNull(holder.errorMessage)
        assertFalse(holder.scope.hasUnsavedRegionChanges)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::toggleRegionFlood sends nothing when not loaded()")
    fun `toggleRegionFlood sends nothing when not loaded`() = runSuspend {
        val recorder = CommandRecorder("OK - (flood allowed)")
        val holder = regionHolder(recorder)
        holder.regions.setRegions(listOf(region("*", null, 0), region("duckburg", "*", 1)))
        holder.regions.toggleRegionFlood("duckburg")
        assertTrue(recorder.commands.isEmpty())
        assertEquals(true, holder.scope.regions.firstOrNull { it.name == "duckburg" }?.floodAllowed)
        assertFalse(holder.scope.hasUnsavedRegionChanges)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::removeRegion of current default sends remove then default clear()")
    fun `removeRegion of current default sends remove then default clear`() = runSuspend {
        val recorder = recorder(*duckburg, "region remove duckburg" to "OK", "region default <null>" to " default scope is now <null>")
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.fetchDefaultScope()
        recorder.resetCommands()
        holder.regions.removeRegion("duckburg")
        assertEquals(listOf("region remove duckburg", "region default <null>"), recorder.commands)
        assertEquals(listOf("*"), holder.regionNames)
        assertNull(holder.scope.defaultScopeName)
        assertTrue(holder.scope.hasUnsavedRegionChanges)
        assertNull(holder.errorMessage)
        assertFalse(holder.helper.state.value.isApplying)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::removeRegion of current default keeps the name when trailing clear fails()")
    fun `removeRegion of current default keeps the name when trailing clear fails`() = runSuspend {
        val recorder = recorder(*duckburg, "region remove duckburg" to "OK", "region default <null>" to "Err - save failed")
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.fetchDefaultScope()
        recorder.resetCommands()
        holder.regions.removeRegion("duckburg")
        assertEquals(listOf("region remove duckburg", "region default <null>"), recorder.commands)
        assertEquals(listOf("*"), holder.regionNames)
        assertEquals("duckburg", holder.scope.defaultScopeName)
        assertTrue(holder.scope.defaultScopeLoaded)
        assertTrue(holder.scope.hasUnsavedRegionChanges)
        assertEquals(unknownRegion, holder.errorMessage)
        assertFalse(holder.helper.state.value.isApplying)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::removeRegion of current default does not send default clear when remove is rejected()")
    fun `removeRegion of current default does not send default clear when remove is rejected`() = runSuspend {
        val recorder = recorder(*duckburg, "region remove duckburg" to "Err - not empty")
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.fetchDefaultScope()
        recorder.resetCommands()
        holder.regions.removeRegion("duckburg")
        assertEquals(listOf("region remove duckburg"), recorder.commands)
        assertEquals(listOf("*", "duckburg"), holder.regionNames)
        assertEquals("duckburg", holder.scope.defaultScopeName)
        assertFalse(holder.scope.hasUnsavedRegionChanges)
        assertEquals(notEmpty, holder.errorMessage)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::removeRegion of a different region does not send region default()")
    fun `removeRegion of a different region does not send region default`() = runSuspend {
        val recorder = recorder(
            "region" to "* F\n duckburg F\n goosetown F\n", "region default" to " default scope is duckburg",
            "region remove goosetown" to "OK",
        )
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.fetchDefaultScope()
        recorder.resetCommands()
        holder.regions.removeRegion("goosetown")
        assertEquals(listOf("region remove goosetown"), recorder.commands)
        assertEquals("duckburg", holder.scope.defaultScopeName)
        assertEquals(listOf("*", "duckburg"), holder.regionNames)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::removeRegion of a parent maps not empty and does not drop the row()")
    fun `removeRegion of a parent maps not empty and does not drop the row`() = runSuspend {
        val recorder = recorder(
            "region" to "* F\n on F\n  gta F\n", "region default" to " default scope is <null>", "region remove on" to "Err - not empty",
        )
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        recorder.resetCommands()
        holder.regions.removeRegion("on")
        assertEquals(listOf("region remove on"), recorder.commands)
        assertEquals(listOf("*", "on", "gta"), holder.regionNames)
        assertEquals(notEmpty, holder.errorMessage)
    }

    @Test @OriginalCase("RepeaterSettingsDefaultScopeTests::removeRegion of nested non-default does not send region default()")
    fun `removeRegion of nested non-default does not send region default`() = runSuspend {
        val recorder = recorder("region" to "* F\n on F\n  gta F\n", "region default" to " default scope is on", "region remove gta" to "OK")
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.fetchDefaultScope()
        recorder.resetCommands()
        holder.regions.removeRegion("gta")
        assertEquals(listOf("region remove gta"), recorder.commands)
        assertEquals("on", holder.scope.defaultScopeName)
    }

    @Test
    fun `region queries use raw matching with a ten second budget`() = runSuspend {
        val recorder = recorder(*duckburg)
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.fetchDefaultScope()
        assertEquals(listOf(kotlin.time.Duration.parse("10s"), kotlin.time.Duration.parse("10s")), recorder.timeouts)
    }
}
