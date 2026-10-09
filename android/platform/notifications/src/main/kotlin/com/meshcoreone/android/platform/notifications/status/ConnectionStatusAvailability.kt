// AndroidOnly: WP-402 Permission fallback: a denied notification grant degrades the status surface only, never the connection.
package com.meshcoreone.android.platform.notifications.status

/** Where the user can see connection status given the notification permission state. */
enum class StatusSurface {
    /** Post and update the ongoing notification. */
    NOTIFICATION,

    /** Prompt has not been shown yet: ask, and show status in the app meanwhile. */
    ASK_THEN_IN_APP,

    /** Denied or blocked: status is shown in the app (and widget/tile); the foreground service's system entry remains. */
    IN_APP_ONLY,
}

object ConnectionStatusAvailability {
    fun evaluate(granted: Boolean, appEnabled: Boolean, channelBlocked: Boolean, promptShown: Boolean): StatusSurface = when {
        granted && appEnabled && !channelBlocked -> StatusSurface.NOTIFICATION
        !granted && !promptShown -> StatusSurface.ASK_THEN_IN_APP
        else -> StatusSurface.IN_APP_ONLY
    }
}

/** Result of one attempt to show the status; none of these affect the connection. */
enum class StatusPostResult { POSTED, SURFACE_UNAVAILABLE, FAILED }
