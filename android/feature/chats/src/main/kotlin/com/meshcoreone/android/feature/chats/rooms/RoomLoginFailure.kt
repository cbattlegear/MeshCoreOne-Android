// AndroidOnly: WP-310 Actionable room login failures; the Swift sheet shows one generic message per error.
package com.meshcoreone.android.feature.chats.rooms

import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.contracts.domain.errors.RemoteNodeFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFaultCarrier
import com.meshcoreone.android.core.l10n.R

/** What the user can do about a failed login. */
enum class RoomLoginFailureKind {
    /** The node answered and refused the password. */
    WRONG_PASSWORD,

    /** Nothing came back in time: out of range, off the air, or a stale route. */
    NO_RESPONSE,

    /** The radio link is down, so nothing was sent. */
    NOT_CONNECTED,

    /** Anything else; the shared error mapper words it. */
    OTHER,
    ;

    companion object {
        fun of(failure: Throwable): RoomLoginFailureKind {
            if (failure is MessageServiceException && failure.error == MessageServiceError.NotConnected) return NOT_CONNECTED
            return when ((failure as? SourceServiceFaultCarrier)?.sourceServiceFault) {
                is RemoteNodeFault.LoginFailed -> WRONG_PASSWORD
                RemoteNodeFault.Timeout -> NO_RESPONSE
                RemoteNodeFault.NotConnected -> NOT_CONNECTED
                else -> OTHER
            }
        }
    }

    /** The string resource for this kind, or null to fall back to the shared mapper. */
    fun messageResource(): Int? = when (this) {
        WRONG_PASSWORD -> R.string.remote_login_error_wrong_password
        NO_RESPONSE -> R.string.remote_login_error_no_response
        NOT_CONNECTED -> R.string.remote_login_error_not_connected
        OTHER -> null
    }
}
