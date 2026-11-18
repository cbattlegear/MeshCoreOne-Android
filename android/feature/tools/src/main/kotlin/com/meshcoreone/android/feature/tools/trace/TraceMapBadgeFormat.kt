// AndroidOnly: WP-312 Locale-aware trace map badge distance and SNR presentation.
package com.meshcoreone.android.feature.tools.trace

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import com.meshcoreone.android.core.maps.MapRoadDistanceMeasure
import com.meshcoreone.android.core.maps.MapRoadUnit
import java.text.NumberFormat
import java.util.Locale

internal class TraceMapBadgeFormat(locale: Locale, private val snrUnit: String) {
    private val distanceFormat = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT)
    private val snrFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    private val measurementSystem = MapRoadDistanceMeasure.measurementSystem(locale)

    fun format(meters: Double, snr: Double): String {
        val roadMeasure = MapRoadDistanceMeasure.measure(meters, measurementSystem)
        val unit = when (roadMeasure.unit) {
            MapRoadUnit.METER -> MeasureUnit.METER
            MapRoadUnit.KILOMETER -> MeasureUnit.KILOMETER
            MapRoadUnit.FOOT -> MeasureUnit.FOOT
            MapRoadUnit.YARD -> MeasureUnit.YARD
            MapRoadUnit.MILE -> MeasureUnit.MILE
        }
        val distance = Measure(roadMeasure.value, unit)
        return "${distanceFormat.formatMeasures(distance)} \u00b7 ${snrFormat.format(snr)} $snrUnit"
    }
}
