// AndroidOnly: WP-401 Isolated reject-only destination for the unactivated issued-reply producer.
package com.meshcoreone.android.platform.notifications.messaging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.logging.Logger

/**
 * Never falls through to legacy payload decoding or dispatch, even with hostile mutable extras.
 * This component is NOT an intake implementation: no reply is retained and no recovery is promised.
 * Live posting must not expose it until real installation-bound admission and visible failure exist.
 */
class UnactivatedIssuedReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        logger.warning("Issued reply intake is not activated; attempted reply was not retained")
    }

    private companion object {
        val logger: Logger = Logger.getLogger("com.meshcoreone.android.notifications.IssuedReply")
    }
}
