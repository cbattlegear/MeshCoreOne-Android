// AndroidOnly: WP-214 Recoverable receive-clock range failure; the sender stamp remains deliverable.
package com.meshcoreone.android.core.services.sync

import java.time.Instant

class TimestampRangeError(val receiveTime: Instant) :
    Exception("Receive time $receiveTime is outside UInt32 Unix seconds; retaining the original sender timestamp.")
