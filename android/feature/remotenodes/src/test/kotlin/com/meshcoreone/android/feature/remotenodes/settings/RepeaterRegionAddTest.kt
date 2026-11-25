// PortedFrom: MC1Tests/ViewModels/RepeaterSettingsViewModelRegionTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import com.meshcoreone.android.feature.remotenodes.support.runSuspend
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Swift suite `RepeaterSettingsRegionTests`: a successful `region put` stores `floodAllowed: true`. */
class RepeaterRegionAddTest {
    private val addFailed = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsAddFailed)

    private fun loadedRecorder(reply: String = "OK - (flood allowed)") =
        CommandRecorder(reply).apply { repliesByCommand = mutableMapOf("region" to "* F\n") }

    @Test @OriginalCase("RepeaterSettingsRegionTests::firmware flood-allow put reply adds the region as flood allowed()")
    fun `firmware flood-allow put reply adds the region as flood allowed`() = runSuspend {
        val recorder = loadedRecorder()
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.addRegion("Europe", RepeaterRegionEntry.Parent.Unscoped)
        assertEquals(listOf("region", "region put Europe"), recorder.commands)
        assertNull(holder.errorMessage)
        val state = holder.regions.state.value
        assertEquals(2, state.regions.size)
        assertEquals("Europe", state.regions.last().name)
        assertEquals("*", state.regions.last().parentName)
        assertEquals(1, state.regions.last().depth)
        assertTrue(state.regions.last().floodAllowed)
        assertTrue(state.hasUnsavedRegionChanges)
        assertFailsWith<AddRegionRejectedException> { holder.regions.addRegion("Europe", RepeaterRegionEntry.Parent.Unscoped) }
    }

    @Test @OriginalCase("RepeaterSettingsRegionTests::legacy OK put reply still adds the region as flood allowed()")
    fun `legacy OK put reply still adds the region as flood allowed`() = runSuspend {
        val holder = regionHolder(loadedRecorder("OK"))
        holder.regions.fetchRegions()
        holder.regions.addRegion("UK", RepeaterRegionEntry.Parent.Unscoped)
        assertEquals("UK", holder.regions.state.value.regions.last().name)
        assertTrue(holder.regions.state.value.regions.last().floodAllowed)
    }

    @Test @OriginalCase("RepeaterSettingsRegionTests::empty name throws rejected()")
    fun `empty name throws rejected`() = runSuspend {
        val recorder = loadedRecorder()
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        assertFailsWith<AddRegionRejectedException> { holder.regions.addRegion("   ", RepeaterRegionEntry.Parent.Unscoped) }
        assertEquals(listOf("region"), recorder.commands)
        assertEquals(listOf("*"), holder.regionNames)
        assertNull(holder.errorMessage)
        assertFalse(holder.regions.state.value.hasUnsavedRegionChanges)
    }

    @Test @OriginalCase("RepeaterSettingsRegionTests::invalid name sets addFailed and does not send()")
    fun `invalid name sets addFailed and does not send`() = runSuspend {
        val recorder = loadedRecorder()
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        assertFailsWith<AddRegionRejectedException> { holder.regions.addRegion("my region", RepeaterRegionEntry.Parent.Unscoped) }
        assertEquals(listOf("region"), recorder.commands)
        assertEquals(listOf("*"), holder.regionNames)
        assertEquals(addFailed, holder.errorMessage)
    }

    @Test @OriginalCase("RepeaterSettingsRegionTests::non-OK put reply sets addFailed and does not append()")
    fun `non-OK put reply sets addFailed and does not append`() = runSuspend {
        val recorder = loadedRecorder("Err - unable to put")
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        assertFailsWith<AddRegionRejectedException> { holder.regions.addRegion("Europe", RepeaterRegionEntry.Parent.Unscoped) }
        assertEquals(listOf("region", "region put Europe"), recorder.commands)
        assertEquals(listOf("*"), holder.regionNames)
        assertEquals(addFailed, holder.errorMessage)
        assertFalse(holder.regions.state.value.hasUnsavedRegionChanges)
        assertFalse(holder.helper.state.value.isApplying)
    }

    @Test @OriginalCase("RepeaterSettingsRegionTests::addRegion with named parent sends put name parent()")
    fun `addRegion with named parent sends put name parent`() = runSuspend {
        val recorder = loadedRecorder()
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.setRegions(listOf(region("*", null, 0), region("on", "*", 1)))
        holder.regions.addRegion("gta", RepeaterRegionEntry.Parent.Named("on"))
        assertEquals("region put gta on", recorder.commands.last())
        val last = holder.regions.state.value.regions.last()
        assertEquals("on", last.parentName)
        assertEquals(2, last.depth)
        assertTrue(last.floodAllowed)
        assertTrue(holder.regions.state.value.hasUnsavedRegionChanges)
    }

    @Test @OriginalCase("RepeaterSettingsRegionTests::named parent missing from tree throws rejected and does not send()")
    fun `named parent missing from tree throws rejected and does not send`() = runSuspend {
        val recorder = loadedRecorder()
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.setRegions(listOf(region("*", null, 0), region("on", "*", 1)))
        assertFailsWith<AddRegionRejectedException> { holder.regions.addRegion("gta", RepeaterRegionEntry.Parent.Named("nope")) }
        assertEquals(listOf("region"), recorder.commands)
        assertEquals(listOf("*", "on"), holder.regionNames)
        assertEquals(addFailed, holder.errorMessage)
        assertFalse(holder.regions.state.value.hasUnsavedRegionChanges)
    }

    @Test @OriginalCase("RepeaterSettingsRegionTests::addRegion under Unscoped with no star row still appends()")
    fun `addRegion under Unscoped with no star row still appends`() = runSuspend {
        val recorder = loadedRecorder()
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.setRegions(emptyList())
        holder.regions.addRegion("Europe", RepeaterRegionEntry.Parent.Unscoped)
        assertEquals("region put Europe", recorder.commands.last())
        assertEquals("*", holder.regions.state.value.regions.last().parentName)
        assertEquals(1, holder.regions.state.value.regions.last().depth)
    }

    @Test @OriginalCase("RepeaterSettingsRegionTests::addRegion inserts after grandchildren of the parent()")
    fun `addRegion inserts after grandchildren of the parent`() = runSuspend {
        val holder = regionHolder(loadedRecorder())
        holder.regions.fetchRegions()
        holder.regions.setRegions(
            listOf(region("*", null, 0), region("on", "*", 1), region("gta", "on", 2), region("downtown", "gta", 3), region("can", "*", 1)),
        )
        holder.regions.addRegion("ottawa", RepeaterRegionEntry.Parent.Named("on"))
        assertEquals(listOf("*", "on", "gta", "downtown", "ottawa", "can"), holder.regionNames)
    }

    @Test @OriginalCase("RepeaterSettingsRegionTests::addRegion re-finds the parent after the put reply()")
    fun `addRegion re-finds the parent after the put reply`() = runSuspend {
        val holder = regionHolder(loadedRecorder())
        holder.regions.fetchRegions()
        holder.regions.setRegions(listOf(region("west", "*", 1), region("*", null, 0), region("on", "*", 1)))
        holder.regions.addRegion("gta", RepeaterRegionEntry.Parent.Named("on"))
        assertEquals(listOf("west", "*", "on", "gta"), holder.regionNames)
        assertEquals("on", holder.regions.state.value.regions.last().parentName)
        assertEquals(2, holder.regions.state.value.regions.last().depth)
    }

    @Test @OriginalCase("RepeaterSettingsRegionTests::addRegion uses live parent depth after the put reply()")
    fun `addRegion uses live parent depth after the put reply`() = runSuspend {
        val recorder = loadedRecorder()
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.setRegions(listOf(region("*", null, 0), region("on", "*", 1)))
        recorder.onSend = { command ->
            if (command == "region put gta on") {
                holder.regions.setRegions(listOf(region("*", null, 0), region("can", "*", 1), region("on", "can", 2)))
            }
        }
        holder.regions.addRegion("gta", RepeaterRegionEntry.Parent.Named("on"))
        val last = holder.regions.state.value.regions.last()
        assertEquals("gta", last.name)
        assertEquals("on", last.parentName)
        assertEquals(3, last.depth)
    }

    @Test @OriginalCase("RepeaterSettingsRegionTests::saveRegions after a truncated refetch still persists unsaved edits()")
    fun `saveRegions after a truncated refetch still persists unsaved edits`() = runSuspend {
        val recorder = loadedRecorder()
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        holder.regions.addRegion("Europe", RepeaterRegionEntry.Parent.Unscoped)
        assertTrue(holder.regions.state.value.hasUnsavedRegionChanges)
        assertTrue(holder.regions.state.value.regionsLoaded)

        recorder.repliesByCommand = mutableMapOf("region" to "* F\n   gta F")
        holder.regions.fetchRegions()
        with(holder.regions.state.value) {
            assertFalse(regionsLoaded)
            assertTrue(regionsError)
            assertTrue(hasUnsavedRegionChanges)
            assertTrue(regions.map { it.name }.contains("Europe"))
        }

        recorder.resetCommands()
        recorder.repliesByCommand["region save"] = "OK"
        holder.regions.saveRegions()
        assertEquals(listOf("region save"), recorder.commands)
        assertFalse(holder.regions.state.value.hasUnsavedRegionChanges)
        assertFalse(holder.regions.state.value.regionsSaveSuccess)
        assertFalse(holder.helper.state.value.isApplying)
    }

    @Test @OriginalCase("RepeaterSettingsRegionTests::addRegion sends nothing when not loaded()")
    fun `addRegion sends nothing when not loaded`() = runSuspend {
        val recorder = CommandRecorder("OK - (flood allowed)")
        val holder = regionHolder(recorder)
        assertFailsWith<AddRegionRejectedException> { holder.regions.addRegion("Europe", RepeaterRegionEntry.Parent.Unscoped) }
        assertTrue(recorder.commands.isEmpty())
    }

    @Test
    fun `transport failure on put surfaces the error and rethrows it`() = runSuspend {
        val recorder = loadedRecorder()
        val holder = regionHolder(recorder)
        holder.regions.fetchRegions()
        val failure = FakeTimeout()
        recorder.errorsByCommand["region put Europe"] = failure
        val thrown = assertFailsWith<FakeTimeout> { holder.regions.addRegion("Europe", RepeaterRegionEntry.Parent.Unscoped) }
        assertTrue(thrown === failure)
        assertEquals(RemoteNodesText.Failure(failure), holder.errorMessage)
        assertFalse(holder.helper.state.value.isApplying)
        assertEquals(listOf("*"), holder.regionNames)
    }
}
