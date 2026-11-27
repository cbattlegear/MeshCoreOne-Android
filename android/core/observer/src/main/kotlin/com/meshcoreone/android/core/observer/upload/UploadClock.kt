// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import java.time.Instant

/** Injected so batching, queue age and rx_time are deterministic in tests. */
fun interface UploadClock {
    fun now(): Instant
}
