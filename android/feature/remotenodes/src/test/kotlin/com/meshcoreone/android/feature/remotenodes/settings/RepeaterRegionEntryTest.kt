// PortedFrom: MC1Tests/ViewModels/RepeaterRegionEntryTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class RepeaterRegionEntryTest {
    private fun entry(name: String, parent: String?, depth: Int) = RepeaterRegionEntry(name, parent, depth, floodAllowed = true, isHome = false)

    @Test @OriginalCase("RepeaterRegionEntryNamedParentTests::namedParent is nil for Unscoped and for children of Unscoped()")
    fun `namedParent is null for Unscoped and for children of Unscoped`() {
        assertNull(entry("*", null, 0).namedParent)
        assertNull(entry("on", "*", 1).namedParent)
    }

    @Test @OriginalCase("RepeaterRegionEntryNamedParentTests::namedParent is the immediate named parent()")
    fun `namedParent is the immediate named parent`() {
        assertEquals("on", entry("gta", "on", 2).namedParent)
        assertEquals("gta", entry("downtown", "gta", 3).namedParent)
    }

    @Test @OriginalCase("RegionFloodToggleRowLayoutTests::visibleDepth is zero for the root()")
    fun `visibleDepth is zero for the root`() {
        assertEquals(0, RegionFloodToggleRowLayout.visibleDepth(0, 12.0))
    }

    @Test @OriginalCase("RegionFloodToggleRowLayoutTests::visibleDepth caps at four when indent is the default()")
    fun `visibleDepth caps at four when indent is the default`() {
        assertEquals(3, RegionFloodToggleRowLayout.visibleDepth(3, 12.0))
        assertEquals(4, RegionFloodToggleRowLayout.visibleDepth(5, 12.0))
    }

    @Test @OriginalCase("RegionFloodToggleRowLayoutTests::visibleDepth caps by gutter width at large type()")
    fun `visibleDepth caps by gutter width at large type`() {
        assertEquals(2, RegionFloodToggleRowLayout.visibleDepth(5, 24.0))
        assertEquals(1, RegionFloodToggleRowLayout.visibleDepth(5, 48.0))
    }

    @Test
    fun `row titles and accessibility labels follow the tree position`() {
        val allTraffic = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsAllTraffic)
        assertEquals(
            RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsAllTrafficWildcard),
            RegionFloodToggleRowLayout.displayName(entry("*", null, 0)),
        )
        assertEquals(allTraffic, RegionFloodToggleRowLayout.accessibilityLabel(entry("*", null, 0)))
        assertEquals(RemoteNodesText.Verbatim("root"), RegionFloodToggleRowLayout.accessibilityLabel(entry("root", null, 0)))
        assertEquals(
            RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_settings_regions_childof, "on", allTraffic),
            RegionFloodToggleRowLayout.accessibilityLabel(entry("on", "*", 1)),
        )
        assertEquals(
            RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_settings_regions_childof, "gta", "on"),
            RegionFloodToggleRowLayout.accessibilityLabel(entry("gta", "on", 2)),
        )
        assertTrue(RegionFloodToggleRowLayout.isEmphasized(entry("on", "*", 1), hasChildren = true))
        assertFalse(RegionFloodToggleRowLayout.isEmphasized(entry("*", null, 0), hasChildren = true))
    }

    @Test
    fun `add region form validates trimmed names and lists parents`() {
        val existing = listOf(entry("*", null, 0), entry("on", "*", 1), entry("gta", "on", 2))
        val form = AddRegionForm(selectedParent = RepeaterRegionEntry.Parent.Unscoped)
        assertFalse(form.canAdd(existing))
        assertNull(form.validationErrorText(existing))
        assertTrue(form.editingName("  ottawa ").canAdd(existing))
        assertEquals(
            RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsInvalidName),
            form.editingName("my region").validationErrorText(existing),
        )
        assertEquals(
            RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsDuplicate),
            form.editingName("gta").validationErrorText(existing),
        )
        assertEquals(
            RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_settings_regions_nametoolong, 30),
            form.editingName("a".repeat(31)).validationErrorText(existing),
        )
        assertTrue(form.editingName("a".repeat(30)).canAdd(existing))
        assertFalse(form.editingName("ottawa").copy(isSubmitting = true).canAdd(existing))
        assertEquals(
            listOf(RepeaterRegionEntry.Parent.Unscoped, RepeaterRegionEntry.Parent.Named("on"), RepeaterRegionEntry.Parent.Named("gta")),
            AddRegionForm.parentOptions(existing),
        )
        val failed = form.copy(errorMessage = AddRegionForm.submitError(AddRegionRejectedException()))
        assertEquals(RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsAddFailed), failed.displayedError(existing))
        assertNull(failed.editingName("x").errorMessage)
        assertNull(failed.selectingParent(RepeaterRegionEntry.Parent.Named("on")).errorMessage)
        assertEquals(
            RegionNameValidator.ValidationError.InvalidCharacters,
            RegionNameValidator.validate("café", emptyList()),
        )
    }
}
