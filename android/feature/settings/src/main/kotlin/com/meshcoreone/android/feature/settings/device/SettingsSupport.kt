// PortedFrom: MC1/Views/Settings/Sections/RetryAlertModifier.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.ui.UiText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Injected sleep so the 1.5 s success checkmark and the reset grace period are deterministic in tests. */
fun interface SettingsClock {
    suspend fun sleep(duration: Duration)

    companion object {
        val Real: SettingsClock = SettingsClock { delay(it) }
    }
}

/**
 * What every section holder needs from its host. [scope] must be single-threaded (Swift `@MainActor`);
 * [describe] stands in for `Error.userFacingMessage` (WP-303 supplies `UiErrorMapper.message`);
 * [dismiss] is SwiftUI's `@Environment(\.dismiss)`.
 */
class SettingsEnvironment(
    val scope: CoroutineScope,
    val clock: SettingsClock = SettingsClock.Real,
    val describe: (Throwable) -> UiText = { UiText.Verbatim(it.message ?: it.toString()) },
    val dismiss: () -> Unit = {},
)

/** Feature-local mirror of `ConnectionError.notConnected`, thrown when a write is attempted with no live service. */
class SettingsNotConnectedException : Exception("Device not connected")

/** Success checkmark display time (Swift `Task.sleep(for: .seconds(1.5))`). */
val SUCCESS_DISPLAY: Duration = 1500.milliseconds

/** Maximum retries before the final error (Swift `private let maxRetries = 3`). */
const val MAX_RETRIES = 3

data class RetryAlertState(
    val isPresented: Boolean = false,
    val message: UiText? = null,
    val retryCount: Int = 0,
) {
    val isMaxRetriesExceeded: Boolean get() = retryCount >= MAX_RETRIES
}

/** Swift `RetryAlertState` plus the three button actions of `RetryAlertModifier`. */
class RetryAlertController {
    private val mutable = MutableStateFlow(RetryAlertState())
    private var onRetry: (() -> Unit)? = null
    private var onMaxRetriesExceeded: (() -> Unit)? = null
    val state: StateFlow<RetryAlertState> = mutable.asStateFlow()

    fun show(message: UiText, onRetry: () -> Unit, onMaxRetriesExceeded: () -> Unit) {
        this.onRetry = onRetry
        this.onMaxRetriesExceeded = onMaxRetriesExceeded
        mutable.update { it.copy(isPresented = true, message = message, retryCount = it.retryCount + 1) }
    }

    /** Operation succeeded or the user cancelled: count restarts. Leaves `isPresented` alone, as in Swift. */
    fun reset() {
        onRetry = null
        onMaxRetriesExceeded = null
        mutable.update { it.copy(retryCount = 0) }
    }

    /** "Retry" button. */
    fun retry() {
        mutable.update { it.copy(isPresented = false) }
        onRetry?.invoke()
    }

    /** "Cancel" button. */
    fun cancel() {
        mutable.update { it.copy(isPresented = false) }
        reset()
    }

    /** "OK" button of the max-retries alert. */
    fun acknowledgeMaxRetries() {
        mutable.update { it.copy(isPresented = false) }
        onMaxRetriesExceeded?.invoke()
        reset()
    }
}

/**
 * Swift's `catch let error as SettingsServiceError where error.isRetryable` / `catch` pair: a retryable
 * service failure goes to the retry alert, anything else is returned as the message to show.
 * Cancellation is never swallowed.
 */
class SettingsFailureRouter(
    private val env: SettingsEnvironment,
    private val retryAlert: RetryAlertController,
) {
    fun route(error: Throwable, onRetry: () -> Unit): UiText? {
        if (error is CancellationException) throw error
        if (error is SettingsServiceException && error.isRetryable) {
            retryAlert.show(env.describe(error), onRetry, env.dismiss)
            return null
        }
        return env.describe(error)
    }
}
