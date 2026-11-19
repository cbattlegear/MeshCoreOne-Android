// AndroidOnly: WP-312 Locale-sensitive road-distance badge behavior on the Android ICU runtime.
package com.meshcoreone.android.feature.tools.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class TraceMapBadgeFormatTest {
    @Test
    fun localeRoadPolicySelectsUsFeetAndMilesAndUkYards() {
        assertEquals("2,650 ft · 5.0 dB", TraceMapBadgeFormat(Locale.US, "dB").format(804.0, 5.0))
        assertEquals("0.5 mi · 5.0 dB", TraceMapBadgeFormat(Locale.US, "dB").format(805.0, 5.0))
        assertEquals("900 yd · 5.0 dB", TraceMapBadgeFormat(Locale.UK, "dB").format(804.0, 5.0))
    }

    @Test
    fun metricLocaleUsesKilometersAtTheRoadUsageThreshold() {
        assertEquals("0.9 km · 2.5 dB", TraceMapBadgeFormat(Locale.forLanguageTag("en-AU"), "dB").format(900.0, 2.5))
    }
}
