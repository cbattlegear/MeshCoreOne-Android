// AndroidOnly: WP-313 Native tests for the neighbour SNR map screen logic (NeighborSNRMapView has no Swift unit suite).
package com.meshcoreone.android.feature.remotenodes.map

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.feature.remotenodes.resolver.NeighborNameResolver
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class NeighborSNRMapStateHolderTest {
    private val favorite = contact(EXACT_PREFIX, "Fav", 37.1, -122.1, isFavorite = true)
    private val other = contact(SECOND_EXACT_PREFIX, "Other", 37.2, -121.9)

    private fun holder(
        latitude: Double = 37.0,
        longitude: Double = -122.0,
        filter: MapFilterState = MapFilterState(),
    ) = NeighborSNRMapStateHolder(
        NeighborSNRMapInputs(
            session = centerSession(latitude, longitude),
            neighbors = listOf(neighbor(EXACT_PREFIX), neighbor(SECOND_EXACT_PREFIX)),
            contacts = listOf(favorite, other),
            discoveredNodes = emptyList(),
            userLocation = null,
            keyDisplayByteCount = NeighborNameResolver.MINIMUM_KEY_DISPLAY_BYTE_COUNT,
        ),
        filter,
        Locale.US,
    )

    @Test
    fun appearBuildsOnceAndFitsTheCamera() {
        val holder = holder()
        assertNull(holder.state.value.plotted)
        holder.onAppear()
        val built = holder.state.value
        val plotted = assertNotNull(built.plotted)
        assertEquals(2, plotted.lines.size)
        assertEquals(plotted.region, built.cameraRegion)
        assertEquals(1, built.cameraRegionVersion)
        assertFalse(built.showsNoLocationPill)
        assertTrue(built.isCenterAllEnabled)

        holder.onAppear()
        assertSame(built, holder.state.value)
    }

    @Test
    fun styleLoadReissuesTheFitAndUserCameraMovesDoNotBumpTheVersion() {
        val holder = holder()
        holder.onAppear()
        holder.onStyleLoaded(false)
        assertEquals(1, holder.state.value.cameraRegionVersion)
        holder.onStyleLoaded(true)
        assertEquals(2, holder.state.value.cameraRegionVersion)

        val moved = CoordinateRegion(Coordinate(1.0, 1.0), 1.0, 1.0)
        holder.onCameraRegionChange(moved)
        assertEquals(moved, holder.state.value.cameraRegion)
        assertEquals(2, holder.state.value.cameraRegionVersion)

        holder.onCenteredOnUserChange(true)
        holder.centerAll()
        assertFalse(holder.state.value.isCenteredOnUser)
        assertEquals(holder.state.value.plotted?.region, holder.state.value.cameraRegion)
        assertEquals(3, holder.state.value.cameraRegionVersion)

        holder.onUserLocationRegion(moved)
        assertEquals(moved, holder.state.value.cameraRegion)
        assertEquals(4, holder.state.value.cameraRegionVersion)
    }

    @Test
    fun filterChangesRebuildWithoutRefittingAndDriveTheNotShownPill() {
        val holder = holder()
        holder.onAppear()
        holder.onFilterChange(MapFilterState(favoritesOnly = true))
        val state = holder.state.value
        assertEquals(MapFilterState(favoritesOnly = true), state.filter)
        assertEquals(1, state.cameraRegionVersion)
        assertEquals(1, state.notShownCount)
        assertTrue(state.showsNoLocationPill)
        assertEquals("B1B2", state.unplottable.single().displayName)
        assertEquals(1, state.plotted?.lines?.size)

        holder.showNoLocationList()
        assertTrue(holder.state.value.showingNoLocationList)
        holder.dismissNoLocationList()
        assertFalse(holder.state.value.showingNoLocationList)
    }

    @Test
    fun initialFilterIsHonouredOnFirstBuild() {
        val holder = holder(filter = MapFilterState(favoritesOnly = true, showDiscovered = true))
        holder.onAppear()
        assertEquals(1, holder.state.value.notShownCount)
    }

    @Test
    fun firstLiveInputsFitOnlyTheCurrentFilteredGeometry() {
        val holder = holder()
        holder.onInputsChange(
            NeighborSNRMapInputs(
                centerSession(37.0, -122.0),
                listOf(neighbor(EXACT_PREFIX), neighbor(SECOND_EXACT_PREFIX)),
                listOf(favorite, other), emptyList(), null, 3,
            ),
            MapFilterState(favoritesOnly = true),
        )
        val state = holder.state.value
        val plotted = assertNotNull(state.plotted)
        assertEquals(1, plotted.lines.size)
        assertEquals(plotted.region, state.cameraRegion)
        assertEquals(1, state.cameraRegionVersion)
        holder.onAppear()
        assertSame(state, holder.state.value)
    }

    @Test
    fun liveInputsRebuildGeometryAndRowsWithoutRefittingOrClearingScreenState() {
        val holder = holder()
        holder.onAppear()
        val pan = CoordinateRegion(Coordinate(10.0, 20.0), .3, .4)
        holder.onCameraRegionChange(pan)
        holder.showNoLocationList()
        holder.onCenteredOnUserChange(true)
        val version = holder.state.value.cameraRegionVersion
        val originalId = holder.state.value.plotted!!.points.first { it.label == "Fav" }.id
        val updated = NeighborSNRMapInputs(
            centerSession(37.0, -122.0),
            listOf(neighbor(EXACT_PREFIX), neighbor(SECOND_EXACT_PREFIX)),
            listOf(favorite.copy(name = "Updated", latitude = 39.0)),
            emptyList(), Coordinate(37.5, -122.5), 3,
        )
        holder.onInputsChange(updated, MapFilterState())
        val state = holder.state.value
        assertEquals(pan, state.cameraRegion)
        assertEquals(version, state.cameraRegionVersion)
        assertTrue(state.showingNoLocationList)
        assertTrue(state.isCenteredOnUser)
        val point = assertNotNull(state.plotted).points.first { it.label == "Updated" }
        assertEquals(originalId, point.id)
        assertEquals(Coordinate(39.0, favorite.longitude), point.coordinate)
        assertEquals(1, state.notShownCount)
        assertEquals("B1B2B3", state.unplottable.single().displayName)
        holder.onFilterChange(MapFilterState(favoritesOnly = true))
        assertTrue(holder.state.value.plotted!!.points.any { it.label == "Updated" })
        assertEquals(pan, holder.state.value.cameraRegion)
        holder.centerAll()
        assertEquals(holder.state.value.plotted!!.region, holder.state.value.cameraRegion)
        assertEquals(version + 1, holder.state.value.cameraRegionVersion)
        assertFalse(holder.state.value.isCenteredOnUser)
    }

    @Test
    fun nothingPlottableDisablesCenterAllAndLeavesTheCameraAlone() {
        val holder = NeighborSNRMapStateHolder(
            NeighborSNRMapInputs(
                session = centerSession(0.0, 0.0),
                neighbors = listOf(neighbor(listOf(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01))),
                contacts = emptyList(),
                discoveredNodes = emptyList(),
                userLocation = null,
                keyDisplayByteCount = 3,
            ),
            MapFilterState(),
            Locale.US,
        )
        holder.onAppear()
        holder.onStyleLoaded(true)
        holder.centerAll()
        val state = holder.state.value
        assertFalse(state.isCenterAllEnabled)
        assertNull(state.cameraRegion)
        assertEquals(0, state.cameraRegionVersion)
        assertEquals("DEADBE", state.unplottable.single().displayName)
        assertEquals(AppRemoteNodesStrings.remoteNodesStatusNeighborsMapTitle, NeighborSNRMapStateHolder.TITLE_RESOURCE)
    }
}
