// AndroidOnly: WP-208 Injected radio-generation ownership, clocks and explicit failure reporting.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.ConnectionSignals
import com.meshcoreone.android.core.contracts.domain.MessagingDiagnostic
import com.meshcoreone.android.core.contracts.domain.MessagingIssueReporter
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.protocol.session.SessionClock
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope

object LoggingMessagingIssueReporter : MessagingIssueReporter {
    private val logger = Logger.getLogger("MeshCore.Messaging")
    override fun report(diagnostic: MessagingDiagnostic) {
        val operation = when (diagnostic) {
            is MessagingDiagnostic.Failure -> diagnostic.operation + ":" + diagnostic.cause.javaClass.simpleName
            is MessagingDiagnostic.StaleResult -> diagnostic.operation
            is MessagingDiagnostic.UnmatchedAcknowledgement -> "unmatchedAcknowledgement:orphanSentRow=${diagnostic.orphanSentRow}"
            else -> diagnostic.javaClass.simpleName
        }
        logger.warning("generation=${diagnostic.token().generation.value} operation=$operation")
    }
}

private fun MessagingDiagnostic.token(): SessionToken = when (this) {
    is MessagingDiagnostic.Failure -> token
    is MessagingDiagnostic.StaleResult -> token
    is MessagingDiagnostic.AckCodeMismatch -> token
    is MessagingDiagnostic.AckCodeCollision -> token
    is MessagingDiagnostic.UnmatchedAcknowledgement -> token
}

internal class MessagingOwnership(
    val token: SessionToken,
    parent: CoroutineScope,
    private val signals: ConnectionSignals,
    val reporter: MessagingIssueReporter,
) {
    val job = SupervisorJob(parent.coroutineContext[Job])
    val scope = CoroutineScope(parent.coroutineContext + job)
    @Volatile var closing = false
        private set
    val isCurrent: Boolean get() = !closing && job.isActive && signals.snapshot.value.token == token
    val isReady: Boolean get() = isCurrent && signals.snapshot.value.state.canDrainSendQueue

    suspend fun checkLifetime(operation: String) {
        currentCoroutineContext().ensureActive()
        if (closing || !job.isActive) {
            reporter.report(MessagingDiagnostic.StaleResult(token, operation))
            throw MessageServiceException(MessageServiceError.NotConnected)
        }
    }

    suspend fun check(operation: String) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent) {
            reporter.report(MessagingDiagnostic.StaleResult(token, operation))
            throw MessageServiceException(MessageServiceError.NotConnected)
        }
    }

    fun checkPersistenceCompletion(operation: String) {
        val current = signals.snapshot.value.token
        if (current != null && current != token) {
            reporter.report(MessagingDiagnostic.StaleResult(token, operation))
            throw MessageServiceException(MessageServiceError.NotConnected)
        }
    }

    fun invalidate() { closing = true }
    fun failure(operation: String, cause: Throwable) {
        reporter.report(MessagingDiagnostic.Failure(token, operation, cause))
    }
}

internal sealed interface DeadlineOutcome<out T> {
    data class Value<T>(val value: T) : DeadlineOutcome<T>
    data object Expired : DeadlineOutcome<Nothing>
}

internal suspend fun <T> SessionClock.messagingDeadline(
    seconds: Double, operation: suspend () -> T,
): DeadlineOutcome<T> {
    require(seconds.isFinite() && seconds >= 0) { "Deadline must be finite and nonnegative" }
    val duration: Duration = seconds.seconds
    require(duration.isFinite()) { "Deadline exceeds the clock range" }
    return supervisorScope {
        val result = async { operation() }
        val timer = async { sleepFor(duration) }
        try {
            select {
                result.onAwait { DeadlineOutcome.Value(it) }
                timer.onAwait { DeadlineOutcome.Expired }
            }
        } finally {
            result.cancel()
            timer.cancel()
            result.cancelAndJoin()
            timer.cancelAndJoin()
        }
    }
}
