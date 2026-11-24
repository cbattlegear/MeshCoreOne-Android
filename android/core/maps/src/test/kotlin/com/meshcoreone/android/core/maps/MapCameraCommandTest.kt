// AndroidOnly: WP-312 regression for explicit camera framing after native pan feedback.
package com.meshcoreone.android.core.maps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCameraCommandTest {
    @Test
    fun northLockEnforcesCurrentAndRestoredBearingAndUnlocksRotation() {
        val center = GeoPoint(37.0, -122.0)
        val rotated = MapCameraPose(12.0, 130.0, 45.0)
        val target = RecordingCameraTarget(center, rotated)
        applyMapCameraCommands(target, true, true, null)
        assertFalse(target.rotationState)
        assertEquals(center to rotated.copy(bearing = 0.0), target.currentPosition())

        val restoredCenter = GeoPoint(38.0, -121.0)
        val restored = MapCamera(restoredCenter, 1.0, 1.0, rotated)
        applyMapCameraCommands(target, true, true, restored)
        assertEquals(restoredCenter to rotated.copy(bearing = 0.0), target.currentPosition())
        assertFalse(target.rotationState)

        applyMapCameraCommands(target, true, false, null)
        assertTrue(target.rotationState)
        assertEquals(restoredCenter to rotated.copy(bearing = 0.0), target.currentPosition())
        applyMapCameraCommands(target, true, false, restored)
        assertEquals(restoredCenter to rotated, target.currentPosition())
    }

    private class RecordingCameraTarget(center: GeoPoint, pose: MapCameraPose) : MapCameraCommandTarget {
        var rotationState = true
        private var position = center to pose
        override fun setRotationEnabled(enabled: Boolean) { rotationState = enabled }
        override fun currentPosition() = position
        override fun moveToPosition(center: GeoPoint, pose: MapCameraPose) { position = center to pose }
        override fun fitBounds(camera: MapCamera) = error("No bounds command expected")
    }

    @Test
    fun explicitFitAfterPanAndRepeatedFitBypassOrdinaryCameraDeduplication() {
        val fit = MapCamera(GeoPoint(37.5, -122.5), 1.0, 1.0)
        val pan = MapCamera(GeoPoint(10.0, 20.0), 0.3, 0.4)

        assertTrue(shouldApplyMapCamera(fit, null, null, 0, Long.MIN_VALUE))
        assertFalse(shouldApplyMapCamera(fit, fit, fit, 0, 0))
        assertFalse(shouldApplyMapCamera(pan, pan, fit, 0, 0))
        assertTrue(shouldApplyMapCamera(fit, pan, fit, 1, 0))
        assertFalse(shouldApplyMapCamera(fit, fit, fit, 1, 1))
        assertTrue(shouldApplyMapCamera(fit, fit, fit, 2, 1))
    }
}
