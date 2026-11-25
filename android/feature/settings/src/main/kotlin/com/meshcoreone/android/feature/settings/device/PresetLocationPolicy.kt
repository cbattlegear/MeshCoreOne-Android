// PortedFrom: MC1/Views/Settings/PresetLocationPolicy.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.model.RegionSelection

/** Pure decisions of the preset-location section. */
object PresetLocationPolicy {
    enum class ResolveKind { APPEAR, USER_INITIATED }
    enum class UseMyLocationAction { RESOLVE, WAIT_FOR_AUTHORIZATION, OPEN_SETTINGS }
    enum class AfterAuthorizationWait { RESOLVE, OPEN_SETTINGS, NONE }

    /**
     * Mirror of `RegionalAreas.showsSubdivisionPicker` as of the pinned reference: only the US and Australia
     * carry subdivision lists (`RegionalAreas.countries`); WP-303 may pass the core:services catalog instead.
     */
    val PinnedSubdivisionCatalog: SubdivisionCatalog = SubdivisionCatalog { it == "US" || it == "AU" }

    fun isIncomplete(selection: RegionSelection?, catalog: SubdivisionCatalog = PinnedSubdivisionCatalog): Boolean {
        if (selection == null) return true
        return catalog.showsSubdivisionPicker(selection.countryCode) && selection.administrativeAreaCode == null
    }

    fun shouldExpandOnRadio(
        authorized: Boolean, selection: RegionSelection?, catalog: SubdivisionCatalog = PinnedSubdivisionCatalog,
    ): Boolean = !authorized && isIncomplete(selection, catalog)

    fun shouldResolveOnAppear(authorized: Boolean, source: RegionSelection.Source?): Boolean =
        authorized && source != RegionSelection.Source.MANUAL

    fun shouldCommitAppearResult(currentSource: RegionSelection.Source?): Boolean =
        currentSource != RegionSelection.Source.MANUAL

    fun committedSelection(current: RegionSelection?, result: RegionSelection?, kind: ResolveKind): RegionSelection? {
        if (result == null) return current
        return when (kind) {
            ResolveKind.APPEAR -> if (shouldCommitAppearResult(current?.source)) result else current
            ResolveKind.USER_INITIATED -> result
        }
    }

    fun useMyLocationAction(authorization: LocationAuthorization): UseMyLocationAction = when (authorization) {
        LocationAuthorization.AUTHORIZED -> UseMyLocationAction.RESOLVE
        LocationAuthorization.NOT_DETERMINED -> UseMyLocationAction.WAIT_FOR_AUTHORIZATION
        LocationAuthorization.DENIED, LocationAuthorization.RESTRICTED -> UseMyLocationAction.OPEN_SETTINGS
    }

    fun actionAfterAuthorizationWait(authorization: LocationAuthorization): AfterAuthorizationWait = when (authorization) {
        LocationAuthorization.AUTHORIZED -> AfterAuthorizationWait.RESOLVE
        LocationAuthorization.DENIED, LocationAuthorization.RESTRICTED -> AfterAuthorizationWait.OPEN_SETTINGS
        LocationAuthorization.NOT_DETERMINED -> AfterAuthorizationWait.NONE
    }

    fun shouldPresentLookupMiss(kind: ResolveKind, requestInProgress: Boolean): Boolean =
        kind == ResolveKind.USER_INITIATED && !requestInProgress
}
