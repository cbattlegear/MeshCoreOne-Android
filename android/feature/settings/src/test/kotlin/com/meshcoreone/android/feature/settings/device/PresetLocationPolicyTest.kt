// PortedFrom: MC1Tests/Views/Settings/PresetLocationPolicyTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.model.RegionSelection.Source
import com.meshcoreone.android.feature.settings.device.PresetLocationPolicy.AfterAuthorizationWait
import com.meshcoreone.android.feature.settings.device.PresetLocationPolicy.ResolveKind
import com.meshcoreone.android.feature.settings.device.PresetLocationPolicy.UseMyLocationAction
import com.meshcoreone.android.feature.settings.device.support.OriginalCase
import com.meshcoreone.android.feature.settings.device.support.XmlStrings
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetLocationPolicyTest {
    private fun usCA() = RegionSelection("US", Source.LOCATION, administrativeAreaCode = "US-CA")
    private fun portugal(source: Source) = RegionSelection("PT", source)
    private fun expand(authorized: Boolean, selection: RegionSelection?) = PresetLocationPolicy.shouldExpandOnRadio(authorized, selection)

    @Test @OriginalCase("PresetLocationPolicyTests::expand only when not authorized and incomplete()")
    fun `expand only when not authorized and incomplete`() {
        val usNoAdmin = RegionSelection("US", Source.MANUAL)
        assertTrue(expand(false, null))
        assertFalse(expand(true, null))
        assertTrue(expand(false, usNoAdmin))
        assertFalse(expand(true, usNoAdmin))
        assertFalse(expand(false, usCA()))
    }

    @Test @OriginalCase("PresetLocationPolicyTests::incomplete is nil or a subdivided country with no administrativeAreaCode()")
    fun `incomplete is null or a subdivided country with no area code`() {
        assertTrue(PresetLocationPolicy.isIncomplete(null))
        assertTrue(PresetLocationPolicy.isIncomplete(RegionSelection("US", Source.MANUAL)))
        assertFalse(PresetLocationPolicy.isIncomplete(usCA()))
        assertFalse(PresetLocationPolicy.isIncomplete(RegionSelection("US", Source.MANUAL, administrativeAreaCode = "US-TX")))
        assertTrue(PresetLocationPolicy.isIncomplete(RegionSelection("AU", Source.MANUAL)))
        assertFalse(PresetLocationPolicy.isIncomplete(portugal(Source.MANUAL)))
    }

    @Test @OriginalCase("PresetLocationPolicyTests::shouldResolveOnAppear is true only when authorized and source is not manual()")
    fun `resolve on appear only when authorized and not manual`() {
        assertTrue(PresetLocationPolicy.shouldResolveOnAppear(true, null))
        assertTrue(PresetLocationPolicy.shouldResolveOnAppear(true, Source.LOCATION))
        assertFalse(PresetLocationPolicy.shouldResolveOnAppear(true, Source.MANUAL))
        assertFalse(PresetLocationPolicy.shouldResolveOnAppear(false, Source.LOCATION))
        assertFalse(PresetLocationPolicy.shouldResolveOnAppear(false, null))
    }

    @Test @OriginalCase("PresetLocationPolicyTests::useMyLocationAction maps authorization status()", "platform-adaptation")
    fun `use my location action maps authorization`() {
        // CLAuthorizationStatus.authorizedWhenInUse/authorizedAlways both fold into AUTHORIZED on Android.
        assertEquals(UseMyLocationAction.RESOLVE, PresetLocationPolicy.useMyLocationAction(LocationAuthorization.AUTHORIZED))
        assertEquals(UseMyLocationAction.WAIT_FOR_AUTHORIZATION, PresetLocationPolicy.useMyLocationAction(LocationAuthorization.NOT_DETERMINED))
        assertEquals(UseMyLocationAction.OPEN_SETTINGS, PresetLocationPolicy.useMyLocationAction(LocationAuthorization.DENIED))
        assertEquals(UseMyLocationAction.OPEN_SETTINGS, PresetLocationPolicy.useMyLocationAction(LocationAuthorization.RESTRICTED))
    }

    @Test @OriginalCase("PresetLocationPolicyTests::appear commit keeps manual Portugal over California GPS()")
    fun `appear commit keeps manual portugal`() {
        val pt = portugal(Source.MANUAL)
        assertEquals(pt, PresetLocationPolicy.committedSelection(pt, usCA(), ResolveKind.APPEAR))
    }

    @Test @OriginalCase("PresetLocationPolicyTests::user-initiated commit replaces manual Portugal with California GPS()")
    fun `user initiated commit replaces manual portugal`() {
        assertEquals(usCA(), PresetLocationPolicy.committedSelection(portugal(Source.MANUAL), usCA(), ResolveKind.USER_INITIATED))
    }

    @Test @OriginalCase("PresetLocationPolicyTests::actionAfterAuthorizationWait()", "platform-adaptation")
    fun `action after authorization wait`() {
        assertEquals(AfterAuthorizationWait.RESOLVE, PresetLocationPolicy.actionAfterAuthorizationWait(LocationAuthorization.AUTHORIZED))
        assertEquals(AfterAuthorizationWait.OPEN_SETTINGS, PresetLocationPolicy.actionAfterAuthorizationWait(LocationAuthorization.DENIED))
        assertEquals(AfterAuthorizationWait.OPEN_SETTINGS, PresetLocationPolicy.actionAfterAuthorizationWait(LocationAuthorization.RESTRICTED))
        assertEquals(AfterAuthorizationWait.NONE, PresetLocationPolicy.actionAfterAuthorizationWait(LocationAuthorization.NOT_DETERMINED))
    }

    @Test @OriginalCase("PresetLocationPolicyTests::lookup miss is silent on appear and when request is in flight()")
    fun `lookup miss is silent on appear and while a request is in flight`() {
        assertFalse(PresetLocationPolicy.shouldPresentLookupMiss(ResolveKind.APPEAR, false))
        assertFalse(PresetLocationPolicy.shouldPresentLookupMiss(ResolveKind.USER_INITIATED, true))
        assertTrue(PresetLocationPolicy.shouldPresentLookupMiss(ResolveKind.USER_INITIATED, false))
    }

    @Test @OriginalCase("PresetLocationPolicyTests::committedSelection with nil GPS keeps current()")
    fun `committed selection with a null result keeps current`() {
        val pt = portugal(Source.MANUAL)
        assertEquals(pt, PresetLocationPolicy.committedSelection(pt, null, ResolveKind.APPEAR))
        assertEquals(pt, PresetLocationPolicy.committedSelection(pt, null, ResolveKind.USER_INITIATED))
        assertNull(PresetLocationPolicy.committedSelection(null, null, ResolveKind.APPEAR))
    }

    @Test @OriginalCase("PresetLocationPolicyTests::shouldCommitAppearResult is false only for manual()")
    fun `appear result commits unless manual`() {
        assertFalse(PresetLocationPolicy.shouldCommitAppearResult(Source.MANUAL))
        assertTrue(PresetLocationPolicy.shouldCommitAppearResult(Source.LOCATION))
        assertTrue(PresetLocationPolicy.shouldCommitAppearResult(null))
    }

    @Test @OriginalCase("PresetLocationPolicyTests::appear commit of nil current takes GPS result()")
    fun `appear commit of a null current takes the result`() {
        assertEquals(usCA(), PresetLocationPolicy.committedSelection(null, usCA(), ResolveKind.APPEAR))
    }

    @Test @OriginalCase("PresetLocationPolicyTests::appear commit of location current takes GPS result()")
    fun `appear commit of a location current takes the result`() {
        assertEquals(usCA(), PresetLocationPolicy.committedSelection(portugal(Source.LOCATION), usCA(), ResolveKind.APPEAR))
    }

    private fun containsIgnoringCase(text: String, needle: String) = text.lowercase(Locale.ROOT).contains(needle)

    @Test @OriginalCase("PresetLocationPolicyTests::useMyLocation failure copy does not say region, below, or this screen()")
    fun `failure copy avoids region below and this screen`() {
        val text = XmlStrings().string(R.string.l10n_app_settings_radio_presetlocation_usemylocation_failure)
        assertFalse(containsIgnoringCase(text, "region"))
        assertFalse(containsIgnoringCase(text, "below"))
        assertFalse(containsIgnoringCase(text, "this screen"))
    }

    @Test @OriginalCase("PresetLocationPolicyTests::useMyLocation locating copy does not say region()")
    fun `locating copy avoids region`() {
        val text = XmlStrings().string(R.string.l10n_app_settings_radio_presetlocation_usemylocation_locating)
        assertFalse(containsIgnoringCase(text, "region"))
        assertTrue(text.isNotEmpty())
    }

    @Test @OriginalCase("PresetLocationPolicyTests::denied copy does not say region or mesh contacts()")
    fun `denied copy avoids region and mesh contacts`() {
        val text = XmlStrings().string(R.string.l10n_app_settings_radio_presetlocation_denied)
        assertFalse(containsIgnoringCase(text, "region"))
        assertFalse(containsIgnoringCase(text, "contact"))
        assertFalse(containsIgnoringCase(text, "mesh"))
    }

    @Test @OriginalCase("PresetLocationPolicyTests::pt lookup-miss and denied use voce()")
    fun `portuguese lookup miss and denied use the formal form`() {
        val pt = XmlStrings("pt")
        val failure = pt.string(R.string.l10n_app_settings_radio_presetlocation_usemylocation_failure)
        val denied = pt.string(R.string.l10n_app_settings_radio_presetlocation_denied)
        assertTrue(failure.contains("sua localização"))
        assertFalse(failure.contains("tua"))
        assertTrue(denied.contains("Ative-a"))
        assertFalse(denied.contains("Ativa-a"))
    }
}
