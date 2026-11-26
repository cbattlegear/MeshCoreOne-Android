// PortedFrom: MC1Services/Sources/MC1Services/Services/RemoteOperationTimeoutPolicy.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Feature-local mirror of the three WP-210 `RemoteOperationTimeoutPolicy` values the remote-node screens read.
package com.meshcoreone.android.feature.remotenodes.cli

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal object RemoteOperationTimeouts {
    /** Outer cap for remote binary / status / telemetry waits (`binaryMaximum`). */
    val binaryMaximum: Duration = 45.seconds

    /** Default wait for a CLI reply (`defaultCLITimeout`). */
    val defaultCLITimeout: Duration = 10.seconds

    /** Wait for commands that get no reply by design, such as `reboot` (`fireAndForgetCLI`). */
    val fireAndForgetCLI: Duration = 2.seconds
}
