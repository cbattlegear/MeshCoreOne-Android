// AndroidOnly: WP-313 Shared app projection for both terminals; features remain independent of core:services.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.services.remote.RemoteNodeError
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliErrorPresentation
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliRemoteFault

internal val appCliErrors = CliErrorPresentation(
    remoteFault = { failure ->
        when (failure) {
            is RemoteNodeError.Timeout -> CliRemoteFault.Timeout
            is RemoteNodeError.PasswordNotFound -> CliRemoteFault.PasswordNotFound
            is RemoteNodeError.LoginFailed -> CliRemoteFault.LoginFailed(failure.reason)
            is RemoteNodeError.Cancelled -> CliRemoteFault.Cancelled
            else -> null
        }
    },
)
