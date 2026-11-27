// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.upload

import java.time.Duration

data class UploadPolicy(
    val batchInterval: Duration = Duration.ofSeconds(15),
    /** Used while the app reports the screen is off and the phone is still. */
    val lowActivityInterval: Duration = Duration.ofSeconds(60),
    val maxBatchRecords: Int = 50,
    val maxQueueRecords: Int = 50_000,
    val maxQueueAge: Duration = Duration.ofDays(7),
    /** Bounds one flush's backlog drain so a long outage cannot monopolise the publisher. */
    val maxBacklogBatchesPerFlush: Int = 100,
) {
    init {
        require(maxBatchRecords > 0 && maxQueueRecords > 0 && maxBacklogBatchesPerFlush > 0)
    }
}
