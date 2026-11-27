// AndroidOnly: WP-101 Prizm Mobile Observer notification copy as a pure function of the observer status.
package com.meshcoreone.android.app.prizm

import com.meshcoreone.android.core.observer.session.ObserverMode
import com.meshcoreone.android.core.observer.session.ObserverStatus

/** The strings the notification needs, so the layout logic can be tested without Android resources. */
interface PrizmNotificationStrings {
    fun waitingForNode(): String
    fun counts(heard: Int, uploaded: Int, metadataOnly: Int, queued: Int): String
}

/** [summary] is the collapsed line; [details] the expanded text (gate text first when paused). */
data class PrizmNotificationContent(val summary: String, val details: String)

object PrizmNotificationText {
    fun content(status: ObserverStatus, strings: PrizmNotificationStrings): PrizmNotificationContent {
        if (status.mode != ObserverMode.UPLOADING) {
            val waiting = strings.waitingForNode()
            return PrizmNotificationContent(waiting, waiting)
        }
        val c = status.counters
        val counts = strings.counts(c.heard, c.uploaded, c.metadataOnly, c.queued)
        val gate = status.gateText
        return PrizmNotificationContent(gate ?: counts, if (gate == null) counts else "$gate\n$counts")
    }
}
