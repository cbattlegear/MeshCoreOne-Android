// AndroidOnly: WP-313 Android ICU recency text, including zero/future and non-English fallback; not a Swift runtime test.
package com.meshcoreone.android.feature.remotenodes.history

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
        assertEquals("2h ago", RelativeTime(2, RelativeTimeUnit.HOURS, true).format(Locale.US))
        assertEquals("in 2h", RelativeTime(2, RelativeTimeUnit.HOURS, false).format(Locale.US))
        assertEquals("in 0s", RelativeTime(0, RelativeTimeUnit.SECONDS, false).format(Locale.US))
    }

    @Test fun localeSelectsNativeRecencyInsteadOfEnglishAgoSuffix() {
        assertEquals("2時間前", RelativeTime(2, RelativeTimeUnit.HOURS, true).format(Locale.JAPAN))
        assertEquals("2時間後", RelativeTime(2, RelativeTimeUnit.HOURS, false).format(Locale.JAPAN))
    }
}
