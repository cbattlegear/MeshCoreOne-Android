// AndroidOnly: WP-313 Android ICU recency text, including zero/future and non-English fallback; not a Swift runtime test.
package com.meshcoreone.android.feature.remotenodes.history

import android.os.Build
import java.util.Locale
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class RelativeTimeLocalizationTest {
    @Test fun pastFutureAndZeroUseLocalizedNumericRecency() {
        // API31 ICU68 lacks English narrow relative patterns and falls back to CLDR short units.
        val hour = if (Build.VERSION.SDK_INT == 31) "2 hr." else "2h"
        val zero = if (Build.VERSION.SDK_INT == 31) "0 sec." else "0s"
        assertEquals("$hour ago", RelativeTime(2, RelativeTimeUnit.HOURS, true).format(Locale.US))
        assertEquals("in $hour", RelativeTime(2, RelativeTimeUnit.HOURS, false).format(Locale.US))
        assertEquals("in $zero", RelativeTime(0, RelativeTimeUnit.SECONDS, false).format(Locale.US))
    }

    @Test fun localeSelectsNativeRecencyInsteadOfEnglishAgoSuffix() {
        assertEquals("2時間前", RelativeTime(2, RelativeTimeUnit.HOURS, true).format(Locale.JAPAN))
        assertEquals("2時間後", RelativeTime(2, RelativeTimeUnit.HOURS, false).format(Locale.JAPAN))
    }
}
