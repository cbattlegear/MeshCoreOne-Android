// PortedFrom: MC1/Views/RemoteNodes/Repeaters/RegionFloodToggleRow.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/RemoteNodes/Repeaters/RepeaterAddRegionSheet.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.feature.remotenodes.cli.RemoteSwiftText
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText

/** Non-layout logic of the region flood toggle row. */
object RegionFloodToggleRowLayout {
    const val INDENT_PER_LEVEL = 12.0
    const val MAX_VISUAL_INDENT_DEPTH = 4
    const val MAX_GUTTER_WIDTH = 48.0
    const val SPINE_WIDTH = 2.0

    /** Indent levels drawn for a region: capped at four and by the gutter width at large type sizes. */
    fun visibleDepth(regionDepth: Int, indentPerLevel: Double): Int {
        if (regionDepth <= 0) return 0
        val depthCap = minOf(regionDepth, MAX_VISUAL_INDENT_DEPTH)
        val widthCap = maxOf(1, (MAX_GUTTER_WIDTH / indentPerLevel).toInt())
        return minOf(depthCap, widthCap)
    }

    /** Row title: the wildcard label for Unscoped, else the region name. */
    fun displayName(region: RepeaterRegionEntry): RemoteNodesText = if (region.isUnscoped) {
        RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsAllTrafficWildcard)
    } else {
        RemoteNodesText.Verbatim(region.name)
    }

    /** Medium weight for named regions that have children. */
    fun isEmphasized(region: RepeaterRegionEntry, hasChildren: Boolean): Boolean = !region.isUnscoped && hasChildren

    fun accessibilityLabel(region: RepeaterRegionEntry): RemoteNodesText {
        if (region.isUnscoped) return RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsAllTraffic)
        if (region.parentName == null) return RemoteNodesText.Verbatim(region.name)
        val parent: Any = region.namedParent
            ?: RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsAllTraffic)
        return RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_settings_regions_childof, region.name, parent)
    }
}

/**
 * Form logic of the add-region sheet. Args of [RemoteNodesText.Resource] may themselves be
 * [RemoteNodesText] (resolved first by the screen), as in the child-of accessibility label.
 */
data class AddRegionForm(
    val regionName: String = "",
    val selectedParent: RepeaterRegionEntry.Parent,
    val isSubmitting: Boolean = false,
    val errorMessage: RemoteNodesText? = null,
) {
    val trimmedName: String get() = RemoteSwiftText.trimWhitespaces(regionName)

    fun validationError(existing: List<RepeaterRegionEntry>): RegionNameValidator.ValidationError? =
        RegionNameValidator.validate(trimmedName, existing.map { it.name })

    fun validationErrorText(existing: List<RepeaterRegionEntry>): RemoteNodesText? = when (val error = validationError(existing)) {
        RegionNameValidator.ValidationError.InvalidCharacters ->
            RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsInvalidName)
        is RegionNameValidator.ValidationError.TooLong ->
            RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_settings_regions_nametoolong, error.maxBytes)
        RegionNameValidator.ValidationError.Duplicate ->
            RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsDuplicate)
        RegionNameValidator.ValidationError.Empty, null -> null
    }

    /** The submit error wins over the live validation message. */
    fun displayedError(existing: List<RepeaterRegionEntry>): RemoteNodesText? = errorMessage ?: validationErrorText(existing)

    fun canAdd(existing: List<RepeaterRegionEntry>): Boolean =
        trimmedName.isNotEmpty() && validationError(existing) == null && !isSubmitting

    /** Editing the name or parent clears a submit error. */
    fun editingName(name: String): AddRegionForm = copy(regionName = name, errorMessage = null)
    fun selectingParent(parent: RepeaterRegionEntry.Parent): AddRegionForm = copy(selectedParent = parent, errorMessage = null)

    companion object {
        /** Parent choices: Unscoped first, then every named region in tree order. */
        fun parentOptions(existing: List<RepeaterRegionEntry>): List<RepeaterRegionEntry.Parent> =
            listOf(RepeaterRegionEntry.Parent.Unscoped) +
                existing.filter { !it.isUnscoped }.map { RepeaterRegionEntry.Parent.Named(it.name) }

        /** Submit-failure text: rejection maps to addFailed, anything else to the error itself. */
        fun submitError(error: Throwable): RemoteNodesText = when (error) {
            is AddRegionRejectedException -> RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsAddFailed)
            is NodeSettingsNoServiceException -> error.text
            else -> RemoteNodesText.Failure(error)
        }
    }
}
