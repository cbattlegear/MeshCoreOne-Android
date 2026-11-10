// AndroidOnly: WP-312 regression for explicit camera framing after native pan feedback.
package com.meshcoreone.android.core.maps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCameraCommandTest {
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
