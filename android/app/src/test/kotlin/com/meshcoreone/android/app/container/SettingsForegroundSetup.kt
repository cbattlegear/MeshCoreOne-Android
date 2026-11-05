// AndroidOnly: WP-317 Preserve foreground-first setup and retain sanitized initiating runtime failure diagnostics.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.runtime.RuntimeDiagnostic
import kotlinx.coroutines.CancellationException

internal suspend fun ContainerHarness.connectForegroundSettings() {
    container.foreground.activityStarted()
    try {
        manager.connect(target())
    } catch (failure: CancellationException) {
        val causes = diagnostics.toList().filterIsInstance<RuntimeDiagnostic.Failure>().joinToString(";") {
            "${it.operation}:${it.cause.javaClass.name}:${it.cause.stackTrace.firstOrNull()?.methodName}"
        }
        failure.addSuppressed(IllegalStateException(
            "WP317_SETUP_RUNTIME|state=${manager.connectionState}|" +
                "sync=${sessions.current?.syncCoordinator?.state?.javaClass?.simpleName}|failures=$causes",
        ))
        throw failure
    }
}
