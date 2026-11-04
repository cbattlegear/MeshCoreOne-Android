// AndroidOnly: WP-401 Android-enforced private entry for notification content PendingIntents.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import java.util.logging.Logger

class NotificationOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (intent.action != AndroidMessagingNotificationDelivery.ACTION_PREFIX +
                AndroidMessagingNotificationDelivery.ACTION_OPEN
            ) {
                logger.warning("Rejected unexpected notification content action")
                return
            }
            val payload = NotificationPayloadCodec.read(intent)
            if (payload == null) {
                logger.warning("Rejected malformed notification content payload")
                return
            }
            val identity = intent.data
            val segments = identity?.pathSegments
            val postId = intent.getStringExtra(AndroidMessagingNotificationDelivery.EXTRA_POST_ID)
            if (identity?.scheme != "meshcore-notification" || identity.authority != "action" ||
                segments?.size != 2 || segments[0].isEmpty() ||
                segments[1] != AndroidMessagingNotificationDelivery.ACTION_OPEN || postId.isNullOrEmpty() ||
                identity.getQueryParameter("post") != postId
            ) {
                logger.warning("Rejected malformed notification content identity")
                return
            }
            val launcher = packageManager.getLaunchIntentForPackage(packageName)
            if (launcher == null) {
                logger.severe("Cannot open notification: application launcher is not registered")
                return
            }
            // Only this non-exported Activity stages a content tap; the exported launcher gets no payload.
            val result = NotificationActionDispatcher.enqueue(NotificationResponse(payload, null, null))
            startActivity(
                launcher.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
            if (result == EnqueueResult.DELIVERED_OR_QUEUED) {
                AndroidMessagingNotificationDelivery.retireContentNotification(this, segments[0], postId)
            } else {
                logger.warning("Notification content queue is full; notification remains available for another tap")
            }
        } finally {
            finish()
        }
    }

    private companion object {
        val logger: Logger = Logger.getLogger("com.mc1.NotificationOpen")
    }
}
