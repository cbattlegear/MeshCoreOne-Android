// PortedFrom: MC1Services/Sources/MC1Services/Services/RemoteOperationTimeoutPolicy.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object RemoteOperationTimeoutPolicy {
    const val FIRMWARE_ROUND_TRIP_MULTIPLIER = 2L
    val loginMaximum: Duration = 20.seconds

    /** Floor for login retransmit spacing while waiting for `loginSuccess`; raised to the firmware suggestion. */
    val loginRetransmitInterval: Duration = 1.seconds

    /** Outer cap for remote binary / status / telemetry waits: one binary exchange plus a small link margin. */
    val binaryMaximum: Duration = 45.seconds
    val cliMaximum: Duration = 15.seconds

    /** Default wait for a CLI reply; generous because waiting costs no airtime, unlike a resend. */
    val defaultCLITimeout: Duration = 10.seconds

    /** Wait for commands that get no reply by design (`reboot`). */
    val fireAndForgetCLI: Duration = 2.seconds
    val pollInterval: Duration = 500.milliseconds

    fun firmwareRoundTripTimeout(sentInfo: MessageSentInfo): Duration =
        (sentInfo.suggestedTimeoutMs.toLong() * FIRMWARE_ROUND_TRIP_MULTIPLIER).milliseconds

    fun loginTimeout(sentInfo: MessageSentInfo, pathLength: UByte): Duration = minOf(
        maxOf(firmwareRoundTripTimeout(sentInfo), LoginTimeoutConfig.timeout(pathLength)),
        loginMaximum,
    )

    fun cliTimeout(sentInfo: MessageSentInfo, requestedTimeout: Duration): Duration =
        minOf(maxOf(requestedTimeout, firmwareRoundTripTimeout(sentInfo)), cliMaximum)
}
