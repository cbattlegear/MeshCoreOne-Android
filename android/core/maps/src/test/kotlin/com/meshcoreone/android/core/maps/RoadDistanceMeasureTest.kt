// AndroidOnly: WP-312 shared road-distance selection and rounding regressions.
package com.meshcoreone.android.core.maps

import org.junit.Assert.assertEquals
import org.junit.Test

class RoadDistanceMeasureTest {
    @Test
    fun metricThresholdAndRoundingMatchTheExistingRoadFormatter() {
        assertEquals(MapRoadMeasure(900.0, MapRoadUnit.METER),
            MapRoadDistanceMeasure.measure(899.0, MapRoadMeasurementSystem.METRIC))
        assertEquals(MapRoadMeasure(0.9, MapRoadUnit.KILOMETER),
            MapRoadDistanceMeasure.measure(900.0, MapRoadMeasurementSystem.METRIC))
        assertEquals(MapRoadMeasure(2.0, MapRoadUnit.METER),
            MapRoadDistanceMeasure.measure(2.5, MapRoadMeasurementSystem.METRIC))
        assertEquals(MapRoadMeasure(4.0, MapRoadUnit.METER),
            MapRoadDistanceMeasure.measure(3.5, MapRoadMeasurementSystem.METRIC))
    }

    @Test
    fun imperialThresholdsPreserveUsFeetAndUkYards() {
        assertEquals(MapRoadMeasure(2_650.0, MapRoadUnit.FOOT),
            MapRoadDistanceMeasure.measure(804.0, MapRoadMeasurementSystem.US))
        assertEquals(MapRoadMeasure(0.5, MapRoadUnit.MILE),
            MapRoadDistanceMeasure.measure(805.0, MapRoadMeasurementSystem.US))
        assertEquals(MapRoadMeasure(900.0, MapRoadUnit.YARD),
            MapRoadDistanceMeasure.measure(804.0, MapRoadMeasurementSystem.UK))
        assertEquals(MapRoadMeasure(0.5, MapRoadUnit.MILE),
            MapRoadDistanceMeasure.measure(805.0, MapRoadMeasurementSystem.UK))
    }
}
