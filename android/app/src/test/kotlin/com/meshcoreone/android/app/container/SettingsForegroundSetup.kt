// AndroidOnly: WP-317 Preserve foreground-first setup and retain sanitized initiating runtime failure diagnostics.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.runtime.RuntimeDiagnostic
import com.meshcoreone.android.core.contracts.domain.ConnectionIssue
import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.protocol.session.SessionCorrelationException
import com.meshcoreone.android.core.runtime.RuntimePreferenceValue
import kotlinx.coroutines.CancellationException

internal suspend fun ContainerHarness.connectForegroundSettings() {
    container.foreground.activityStarted()
    try {
        manager.connect(target())
    } catch (failure: CancellationException) {
        val causes = diagnostics.toList().filterIsInstance<RuntimeDiagnostic.Failure>().joinToString(";") {
            "${it.operation}:${it.cause.javaClass.name}:${it.cause.stackTrace.firstOrNull()?.methodName}"
        }
        val issue = manager.snapshot.value.issue
        val protocol = (issue as? ConnectionIssue.Protocol)?.error
        val correlation = protocol?.cause as? SessionCorrelationException.UnresolvedReply
        val persisted = (preferences.values[PersistenceKeys.LAST_DISCONNECT_DIAGNOSTIC] as? RuntimePreferenceValue.Text)
            ?.value?.substringAfter("source=")?.substringBefore(", intent=")
        val commands = links.radios.flatMap { it.sent }.takeLast(32).joinToString(",") {
            it[0].toInt().toString(16)
        }
        failure.addSuppressed(IllegalStateException(
            "WP317_SETUP_RUNTIME|state=${manager.connectionState}|" +
                "sync=${sessions.current?.syncCoordinator?.state?.javaClass?.simpleName}|failures=$causes|" +
                "issue=${issue?.javaClass?.simpleName}|protocol=${protocol?.javaClass?.simpleName}|" +
                "correlation=${correlation?.responseFamily?.substringBefore(':')}|" +
                "disconnect=$persisted|virtualMillis=${scheduler.currentTime}|opcodes=$commands",
        ))
        throw failure
    }
}
