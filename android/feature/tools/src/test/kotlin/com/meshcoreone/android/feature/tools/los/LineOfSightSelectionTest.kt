// PortedFrom: MC1Tests/ViewModels/LineOfSightViewModelTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.los

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.protocol.model.ContactType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class LineOfSightSelectionTest {
    // region InitialStateTests

    @Test @OriginalCase("InitialStateTests::Initial state has nil points()")
    fun `Initial state has nil points`() = losScenario {
        val state = holder().state.value
        assertNull(state.pointA)
        assertNull(state.pointB)
    }

    @Test @OriginalCase("InitialStateTests::Initial status is idle()")
    fun `Initial status is idle`() = losScenario {
        assertEquals(AnalysisStatus.Idle, holder().state.value.analysisStatus)
    }

    @Test @OriginalCase("InitialStateTests::canAnalyze is false initially()")
    fun `canAnalyze is false initially`() = losScenario {
        assertFalse(holder().state.value.canAnalyze)
    }

    @Test @OriginalCase("InitialStateTests::Initial elevation profile is empty()")
    fun `Initial elevation profile is empty`() = losScenario {
        assertTrue(holder().state.value.elevationProfile.isEmpty())
    }

    @Test @OriginalCase("InitialStateTests::Default frequency is 906 MHz()")
    fun `Default frequency is 906 MHz`() = losScenario {
        assertEquals(906.0, holder().state.value.frequencyMHz)
    }

    @Test @OriginalCase("InitialStateTests::Default refraction K is 1.0()")
    fun `Default refraction K is 1_0`() = losScenario {
        assertEquals(1.0, holder().state.value.refractionK)
    }

    // endregion

    // region PreselectedContactTests

    @Test @OriginalCase("PreselectedContactTests::Preselected contact sets point A()")
    fun `Preselected contact sets point A`() = losScenario {
        val contact = createTestContact(name = "Preselected", latitude = 37.8, longitude = -122.4)
        val viewModel = holder(preselectedContact = contact)

        waitUntil("preselected contact elevation should load") { viewModel.state.value.pointA?.isLoadingElevation == false }

        val pointA = assertNotNull(viewModel.state.value.pointA)
        assertEquals("Preselected", pointA.contact?.name)
        assertEquals(37.8, pointA.coordinate.latitude)
    }

    @Test @OriginalCase("PreselectedContactTests::Preselected contact with no location does not set point A()")
    fun `Preselected contact with no location does not set point A`() = losScenario {
        val contact = createTestContact(name = "No Location", latitude = 0.0, longitude = 0.0)
        assertNull(holder(preselectedContact = contact).state.value.pointA)
    }

    @Test @OriginalCase("PreselectedContactTests::Nil preselected contact leaves point A nil()")
    fun `Nil preselected contact leaves point A nil`() = losScenario {
        assertNull(holder(preselectedContact = null).state.value.pointA)
    }

    // endregion

    // region PointSelectionTests

    @Test @OriginalCase("PointSelectionTests::selectPoint sets point A when empty()")
    fun `selectPoint sets point A when empty`() = losScenario {
        val viewModel = holder()
        viewModel.selectPoint(SAN_FRANCISCO)
        assertEquals(SAN_FRANCISCO.latitude, viewModel.state.value.pointA?.coordinate?.latitude)
        assertNull(viewModel.state.value.pointB)
    }

    @Test @OriginalCase("PointSelectionTests::selectPoint sets point B when A exists()")
    fun `selectPoint sets point B when A exists`() = losScenario {
        val viewModel = holder()
        viewModel.selectPoint(SAN_FRANCISCO)
        viewModel.selectPoint(OAKLAND)
        assertNotNull(viewModel.state.value.pointA)
        assertEquals(OAKLAND.latitude, viewModel.state.value.pointB?.coordinate?.latitude)
    }

    @Test @OriginalCase("PointSelectionTests::selectPoint replaces B when both exist()")
    fun `selectPoint replaces B when both exist`() = losScenario {
        val viewModel = holder()
        viewModel.selectPoint(SAN_FRANCISCO)
        viewModel.selectPoint(OAKLAND)
        viewModel.selectPoint(BERKELEY)
        assertEquals(SAN_FRANCISCO.latitude, viewModel.state.value.pointA?.coordinate?.latitude)
        assertEquals(BERKELEY.latitude, viewModel.state.value.pointB?.coordinate?.latitude)
    }

    @Test @OriginalCase("PointSelectionTests::setPointA sets coordinate and contact()")
    fun `setPointA sets coordinate and contact`() = losScenario {
        val viewModel = holder()
        val contact = createTestContact(name = "Point A Contact")
        viewModel.setPointA(SAN_FRANCISCO, contact)
        val pointA = assertNotNull(viewModel.state.value.pointA)
        assertEquals("Point A Contact", pointA.contact?.name)
        assertEquals("Point A Contact", pointA.displayName(droppedPinLabel = "unused"))
    }

    @Test @OriginalCase("PointSelectionTests::setPointB sets coordinate and contact()")
    fun `setPointB sets coordinate and contact`() = losScenario {
        val viewModel = holder()
        val contact = createTestContact(name = "Point B Contact")
        viewModel.setPointA(SAN_FRANCISCO)
        viewModel.setPointB(OAKLAND, contact)
        assertEquals("Point B Contact", assertNotNull(viewModel.state.value.pointB).contact?.name)
    }

    @Test @OriginalCase(
        "PointSelectionTests::Point without contact shows Dropped pin()",
        disposition = "platform-adaptation",
    )
    fun `Point without contact shows Dropped pin`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        assertEquals(R.string.l10n_app_tools_tools_lineofsight_droppedpin, SelectedPoint.DROPPED_PIN_LABEL)
        val label = englishString("l10n_app_tools_tools_lineofsight_droppedpin")
        assertEquals("Dropped pin", assertNotNull(viewModel.state.value.pointA).displayName(label))
    }

    // endregion

    // region SameLocationTests

    @Test @OriginalCase("SameLocationTests::Cannot set B to same location as A()")
    fun `Cannot set B to same location as A`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        viewModel.setPointB(SAN_FRANCISCO)
        assertNotNull(viewModel.state.value.pointA)
        assertNull(viewModel.state.value.pointB)
    }

    @Test @OriginalCase("SameLocationTests::Can set B to different location after same location rejected()")
    fun `Can set B to different location after same location rejected`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        viewModel.setPointB(SAN_FRANCISCO)
        viewModel.setPointB(OAKLAND)
        assertEquals(OAKLAND.latitude, assertNotNull(viewModel.state.value.pointB).coordinate.latitude)
    }

    // endregion

    // region ElevationFetchingTests

    @Test @OriginalCase("ElevationFetchingTests::Point starts with nil elevation()")
    fun `Point starts with nil elevation`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        assertEquals(true, viewModel.state.value.pointA?.isLoadingElevation)
    }

    @Test @OriginalCase("ElevationFetchingTests::Elevation is fetched asynchronously()")
    fun `Elevation is fetched asynchronously`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        waitUntil("point A elevation should be loaded") {
            val pointA = viewModel.state.value.pointA
            pointA != null && pointA.groundElevation == 100.0 && !pointA.isLoadingElevation
        }
        assertEquals(100.0, viewModel.state.value.pointA?.groundElevation)
        assertEquals(false, viewModel.state.value.pointA?.isLoadingElevation)
    }

    @Test @OriginalCase("ElevationFetchingTests::canAnalyze is true when both elevations loaded()")
    fun `canAnalyze is true when both elevations loaded`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        viewModel.setPointB(OAKLAND)
        waitUntil("both elevations should load") { viewModel.state.value.canAnalyze }
        assertTrue(viewModel.state.value.canAnalyze)
    }

    @Test @OriginalCase("ElevationFetchingTests::totalHeight includes additionalHeight()")
    fun `totalHeight includes additionalHeight`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        waitUntil("point A elevation should load") { viewModel.state.value.pointA?.isLoadingElevation == false }
        viewModel.updateAdditionalHeight(PointID.POINT_A, 10.0)
        val pointA = assertNotNull(viewModel.state.value.pointA)
        assertEquals(100.0, pointA.groundElevation)
        assertEquals(10.0, pointA.additionalHeight)
        assertEquals(110.0, pointA.totalHeight)
    }

    // endregion

    // region HeightAdjustmentTests

    @Test @OriginalCase("HeightAdjustmentTests::Height adjustment clamps to zero()")
    fun `Height adjustment clamps to zero`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        waitUntil("point A elevation should load") { viewModel.state.value.pointA?.isLoadingElevation == false }
        viewModel.updateAdditionalHeight(PointID.POINT_A, -10.0)
        assertEquals(0.0, viewModel.state.value.pointA?.additionalHeight)
    }

    @Test @OriginalCase("HeightAdjustmentTests::Height adjustment accepts positive values()")
    fun `Height adjustment accepts positive values`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        waitUntil("point A elevation should load") { viewModel.state.value.pointA?.isLoadingElevation == false }
        viewModel.updateAdditionalHeight(PointID.POINT_A, 15.0)
        assertEquals(15.0, viewModel.state.value.pointA?.additionalHeight)
    }

    @Test @OriginalCase("HeightAdjustmentTests::Height adjustment for point B works()")
    fun `Height adjustment for point B works`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        viewModel.setPointB(OAKLAND)
        waitUntil("both elevations should load") { viewModel.state.value.canAnalyze }
        viewModel.updateAdditionalHeight(PointID.POINT_B, 20.0)
        assertEquals(20.0, viewModel.state.value.pointB?.additionalHeight)
    }

    @Test @OriginalCase("HeightAdjustmentTests::Height adjustment on nil point does nothing()")
    fun `Height adjustment on nil point does nothing`() = losScenario {
        val viewModel = holder()
        viewModel.updateAdditionalHeight(PointID.POINT_A, 10.0)
        viewModel.updateAdditionalHeight(PointID.POINT_B, 10.0)
        assertNull(viewModel.state.value.pointA)
        assertNull(viewModel.state.value.pointB)
    }

    @Test @OriginalCase("HeightAdjustmentTests::Height change invalidates analysis()")
    fun `Height change invalidates analysis`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        viewModel.setPointB(OAKLAND)
        waitUntil("both point elevations should load") { viewModel.state.value.canAnalyze }
        viewModel.analyze()
        waitUntil("analysis should produce result") { viewModel.state.value.analysisStatus is AnalysisStatus.Result }

        viewModel.updateAdditionalHeight(PointID.POINT_A, 5.0)
        waitUntil("analysis should be invalidated after height change") {
            viewModel.state.value.analysisStatus == AnalysisStatus.Idle
        }
        assertEquals(AnalysisStatus.Idle, viewModel.state.value.analysisStatus)
    }

    // endregion

    // region ClearTests

    @Test @OriginalCase("ClearTests::clear removes all state()")
    fun `clear removes all state`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        viewModel.setPointB(OAKLAND)
        waitUntil("both elevations should load") { viewModel.state.value.canAnalyze }
        viewModel.clear()
        val state = viewModel.state.value
        assertNull(state.pointA)
        assertNull(state.pointB)
        assertEquals(AnalysisStatus.Idle, state.analysisStatus)
        assertTrue(state.elevationProfile.isEmpty())
    }

    @Test @OriginalCase("ClearTests::clearPointA removes only point A()")
    fun `clearPointA removes only point A`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        viewModel.setPointB(OAKLAND)
        waitUntil("both elevations should load") { viewModel.state.value.canAnalyze }
        viewModel.clearPointA()
        assertNull(viewModel.state.value.pointA)
        assertNotNull(viewModel.state.value.pointB)
    }

    @Test @OriginalCase("ClearTests::clearPointB removes only point B()")
    fun `clearPointB removes only point B`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        viewModel.setPointB(OAKLAND)
        waitUntil("both elevations should load") { viewModel.state.value.canAnalyze }
        viewModel.clearPointB()
        assertNotNull(viewModel.state.value.pointA)
        assertNull(viewModel.state.value.pointB)
    }

    @Test @OriginalCase("ClearTests::clearPointA invalidates analysis()")
    fun `clearPointA invalidates analysis`() = losScenario {
        val viewModel = holder()
        viewModel.setPointA(SAN_FRANCISCO)
        viewModel.setPointB(OAKLAND)
        waitUntil("both elevations should load") { viewModel.state.value.canAnalyze }
        viewModel.analyze()
        waitUntil("analysis should complete") { !viewModel.state.value.isAnalyzing }
        assertIs<AnalysisStatus.Result>(viewModel.state.value.analysisStatus)
        viewModel.clearPointA()
        assertEquals(AnalysisStatus.Idle, viewModel.state.value.analysisStatus)
    }

    // endregion

    // region ContactToggleTests

    @Test @OriginalCase("ContactToggleTests::toggleContact sets point A for first contact()")
    fun `toggleContact sets point A for first contact`() = losScenario {
        val viewModel = holder()
        val contact = createTestContact(name = "Repeater", latitude = 37.8, longitude = -122.4, type = ContactType.REPEATER)
        viewModel.toggleContact(contact)
        assertEquals(contact.id, assertNotNull(viewModel.state.value.pointA).contact?.id)
    }

    @Test @OriginalCase("ContactToggleTests::toggleContact sets point B for second contact()")
    fun `toggleContact sets point B for second contact`() = losScenario {
        val viewModel = holder()
        val contact1 = createTestContact(name = "Repeater 1", latitude = 37.8, longitude = -122.4, type = ContactType.REPEATER)
        val contact2 = createTestContact(name = "Repeater 2", latitude = 37.7, longitude = -122.3, type = ContactType.REPEATER)
        viewModel.toggleContact(contact1)
        viewModel.toggleContact(contact2)
        assertEquals(contact1.id, viewModel.state.value.pointA?.contact?.id)
        assertEquals(contact2.id, viewModel.state.value.pointB?.contact?.id)
    }

    @Test @OriginalCase("ContactToggleTests::toggleContact clears point A when tapped again()")
    fun `toggleContact clears point A when tapped again`() = losScenario {
        val viewModel = holder()
        val contact = createTestContact(name = "Repeater", latitude = 37.8, longitude = -122.4, type = ContactType.REPEATER)
        viewModel.toggleContact(contact)
        viewModel.toggleContact(contact)
        assertNull(viewModel.state.value.pointA)
    }

    @Test @OriginalCase("ContactToggleTests::toggleContact clears point B when tapped again()")
    fun `toggleContact clears point B when tapped again`() = losScenario {
        val viewModel = holder()
        val contact1 = createTestContact(name = "Repeater 1", latitude = 37.8, longitude = -122.4, type = ContactType.REPEATER)
        val contact2 = createTestContact(name = "Repeater 2", latitude = 37.7, longitude = -122.3, type = ContactType.REPEATER)
        viewModel.toggleContact(contact1)
        viewModel.toggleContact(contact2)
        viewModel.toggleContact(contact2)
        assertNotNull(viewModel.state.value.pointA)
        assertNull(viewModel.state.value.pointB)
    }

    @Test @OriginalCase("ContactToggleTests::toggleContact assigns contact to point A when empty()")
    fun `toggleContact assigns contact to point A when empty`() = losScenario {
        val viewModel = holder()
        val contact = createTestContact(name = "Repeater", latitude = 37.8, longitude = -122.4, type = ContactType.REPEATER)
        assertTrue(viewModel.state.value.pointA?.contact?.id != contact.id)
        assertTrue(viewModel.state.value.pointB?.contact?.id != contact.id)
        viewModel.toggleContact(contact)
        assertEquals(contact.id, viewModel.state.value.pointA?.contact?.id)
    }

    @Test @OriginalCase("ContactToggleTests::toggleContact assigns second contact to point B()")
    fun `toggleContact assigns second contact to point B`() = losScenario {
        val viewModel = holder()
        val contact1 = createTestContact(name = "Repeater 1", latitude = 37.8, longitude = -122.4, type = ContactType.REPEATER)
        val contact2 = createTestContact(name = "Repeater 2", latitude = 37.7, longitude = -122.3, type = ContactType.REPEATER)
        viewModel.toggleContact(contact1)
        viewModel.toggleContact(contact2)
        assertEquals(contact1.id, viewModel.state.value.pointA?.contact?.id)
        assertEquals(contact2.id, viewModel.state.value.pointB?.contact?.id)
    }

    // endregion
}
