// PortedFrom: MC1/Views/RemoteNodes/NodeAuthenticationSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.auth

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.feature.remotenodes.cli.RemoteSwiftText
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText

/** Non-layout decisions of the login sheet's sections. */
object NodeAuthenticationPresentation {
    /** Countdown values that get a time-remaining announcement when crossed. */
    private val ANNOUNCEMENT_THRESHOLDS = listOf(30, 15, 10)

    /** Below this many seconds every tick is announced. */
    private const val FINAL_COUNTDOWN_SECONDS = 5

    fun title(role: RemoteNodeRole, customTitle: RemoteNodesText?): RemoteNodesText = customTitle
        ?: RemoteNodesText.resource(
            if (role == RemoteNodeRole.ROOM_SERVER) AppRemoteNodesStrings.remoteNodesAuthJoinRoom else AppRemoteNodesStrings.remoteNodesAuthManagement,
        )

    fun typeLabel(role: RemoteNodeRole): RemoteNodesText = RemoteNodesText.resource(
        when (role) {
            RemoteNodeRole.ROOM_SERVER -> AppRemoteNodesStrings.remoteNodesAuthTypeRoom
            RemoteNodeRole.REPEATER -> AppRemoteNodesStrings.remoteNodesAuthTypeRepeater
            RemoteNodeRole.SENSOR -> R.string.l10n_app_remotenodes_remotenodes_status_sensor_genericsensor
        },
    )

    fun connectLabel(role: RemoteNodeRole): RemoteNodesText = RemoteNodesText.resource(
        if (role == RemoteNodeRole.ROOM_SERVER) AppRemoteNodesStrings.remoteNodesAuthJoinRoom else AppRemoteNodesStrings.remoteNodesAuthConnect,
    )

    /** Authentication-section footer lines, in Swift's priority order; empty means the blank spacer. */
    fun authenticationFooter(state: NodeAuthenticationState, role: RemoteNodeRole): List<RemoteNodesText> {
        val max = NodeAuthenticationStateHolder.MAX_PASSWORD_LENGTH
        val remaining = state.authSecondsRemaining?.takeIf { it > 0 }
        val secondsText = remaining?.let { RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_auth_secondsremaining, it) }
        return when {
            state.errorMessage != null -> listOf(state.errorMessage)
            role == RemoteNodeRole.SENSOR && state.password.toByteArray(Charsets.UTF_8).size > max ->
                listOf(RemoteNodesText.resource(R.string.sensor_password_byte_limit, max))
            RemoteSwiftText.characterCount(state.password) > max -> listOf(
                RemoteNodesText.resource(
                    if (role == RemoteNodeRole.REPEATER) {
                        R.string.l10n_app_remotenodes_remotenodes_auth_passwordtoolongrepeaters
                    } else {
                        R.string.l10n_app_remotenodes_remotenodes_auth_passwordtoolongrooms
                    },
                    max,
                ),
            )
            state.isRetryingViaFlood ->
                listOfNotNull(RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesAuthFloodRetryStatus), secondsText)
            secondsText != null -> listOf(secondsText)
            else -> emptyList()
        }
    }

    /** Whether a countdown change from [old] to [new] gets an accessibility announcement. */
    fun shouldAnnounce(old: Int?, new: Int?): Boolean {
        if (new == null || new <= 0) return false
        val crossed = ANNOUNCEMENT_THRESHOLDS.any { threshold -> new <= threshold && (old ?: Int.MAX_VALUE) > threshold }
        return old == null || crossed || new <= FINAL_COUNTDOWN_SECONDS
    }

    fun announcement(remaining: Int): RemoteNodesText =
        RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_auth_secondsremainingannouncement, remaining)

    /** Which path rows show: the hop list (when resolved), the summary only, or "no route set". */
    enum class PathRows { HOPS, SUMMARY, NO_ROUTE, NONE }

    fun pathRows(hasStoredPath: Boolean, useFloodRouting: Boolean, hasHops: Boolean): PathRows = when {
        hasStoredPath && !useFloodRouting -> if (hasHops) PathRows.HOPS else PathRows.SUMMARY
        !hasStoredPath -> PathRows.NO_ROUTE
        else -> PathRows.NONE
    }

    fun pathFooter(hasStoredPath: Boolean, useFloodRouting: Boolean): RemoteNodesText = RemoteNodesText.resource(
        when {
            !hasStoredPath -> AppRemoteNodesStrings.remoteNodesAuthNoRouteFooter
            useFloodRouting -> AppRemoteNodesStrings.remoteNodesAuthFloodFooter
            else -> AppRemoteNodesStrings.remoteNodesAuthPathFooter
        },
    )
}
