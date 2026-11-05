// AndroidOnly: WP-308 Foreground loss-warning interaction, not a durable acknowledgment verifier.
package com.meshcoreone.android.feature.chats.composer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.uiString
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Supply the complete immutable producer request (including the predeclared operation/store/nonce,
 * acknowledgment identity and expected current record), complete typed continuity observation and
 * the independent verifier's opaque presentation handle. No handle is minted by this UI.
 * The feature neither substitutes an observation codec nor reconstructs witness types.
 */
data class RecoveryContinuityBinding<Request : Any, Observation : Any, Presentation : Any>(
    val request: Request,
    val observation: Observation,
    val warningVersion: String,
    val presentation: Presentation,
) {
    init { require(warningVersion.isNotBlank()) { "A versioned loss warning is required" } }
}

/** Actual rendered strings, not merely a resource key or a caller's claim about what was displayed. */
data class RecoveryWarningContent(
    val title: String,
    val continuityUnavailable: String,
    val lostTextWarning: String,
    val copyOnlyExplanation: String,
    val acknowledgment: String,
    val createNewGeneration: String,
    val inspectRecoveredText: String,
)

/** An ephemeral UI action. The independent verifier must validate and durably consume it. */
class ForegroundRecoveryAction<Request : Any, Observation : Any, Presentation : Any> internal constructor(
    val binding: RecoveryContinuityBinding<Request, Observation, Presentation>,
    val warning: RecoveryWarningContent,
    private val current: () -> Boolean,
) {
    fun isCurrentForegroundInteraction(): Boolean = current()
}

private class RecoveryInteractionState {
    var acknowledged by mutableStateOf(false)
    var submitted by mutableStateOf(false)
    var resumed by mutableStateOf(false)
    var retired by mutableStateOf(false)
    val attached = AtomicBoolean(true)
    val foreground = AtomicBoolean(false)
    val epoch = AtomicLong(0)

    fun foregroundChanged(value: Boolean) {
        foreground.set(value)
        resumed = value
        if (!value) {
            epoch.incrementAndGet()
            acknowledged = false
        }
    }
}

/**
 * Owns only the live foreground interaction around the existing content. Nothing is remembered across
 * recreation. Effects only invalidate the producer's presentation; they never acknowledge it or
 * create a witness, enrollment or retained text. Invalidation must be nonblocking and idempotent.
 */
@Composable
fun <Request : Any, Observation : Any, Presentation : Any> RecoveryContinuityInteraction(
    binding: RecoveryContinuityBinding<Request, Observation, Presentation>,
    labels: RecoveryContinuityLabels,
    working: Boolean,
    creationAvailable: Boolean,
    failure: UiText?,
    onForegroundAction: (ForegroundRecoveryAction<Request, Observation, Presentation>) -> Unit,
    onInvalidatePresentation: (Presentation) -> Unit,
    onInspectRecoveredText: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focused = LocalWindowInfo.current.isWindowFocused
    val warning = RecoveryWarningContent(
        uiString(labels.title), uiString(labels.continuityUnavailable), uiString(labels.lostTextWarning),
        uiString(labels.copyOnlyExplanation), uiString(labels.acknowledgment),
        uiString(labels.createNewGeneration), uiString(labels.inspectRecoveredText),
    )
    val interaction = remember(binding, warning, lifecycle) {
        RecoveryInteractionState()
    }
    val invalidatePresentation by rememberUpdatedState(onInvalidatePresentation)
    DisposableEffect(interaction, lifecycle, focused) {
        fun invalidate() {
            interaction.foregroundChanged(false)
            if (!interaction.retired) {
                interaction.retired = true
                invalidatePresentation(binding.presentation)
            }
        }
        fun update() = interaction.foregroundChanged(
            focused && !interaction.retired && lifecycle.currentState == Lifecycle.State.RESUMED,
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> invalidate()
                else -> update()
            }
        }
        lifecycle.addObserver(observer)
        if (!focused || lifecycle.currentState == Lifecycle.State.DESTROYED) invalidate() else update()
        onDispose {
            interaction.attached.set(false)
            try { invalidate() } finally { lifecycle.removeObserver(observer) }
        }
    }
    fun foregroundNow(): Boolean = interaction.attached.get() && interaction.foreground.get() &&
        lifecycle.currentState == Lifecycle.State.RESUMED && focused

    RecoveryContinuityNotice(
        labels = labels,
        acknowledged = interaction.acknowledged,
        working = working || !interaction.resumed,
        creationAvailable = creationAvailable && !interaction.submitted && !interaction.retired,
        failure = failure,
        onAcknowledgedChange = { checked ->
            if (foregroundNow() && !working && !interaction.submitted) interaction.acknowledged = checked
        },
        onCreateNewGeneration = {
            if (foregroundNow() && interaction.acknowledged && !interaction.submitted &&
                creationAvailable && !working && !interaction.retired
            ) {
                interaction.submitted = true
                interaction.acknowledged = false
                val epoch = interaction.epoch.get()
                onForegroundAction(ForegroundRecoveryAction(binding, warning) {
                    interaction.attached.get() && interaction.foreground.get() &&
                        interaction.epoch.get() == epoch
                })
            }
        },
        onInspectRecoveredText = {
            if (foregroundNow() && !working) onInspectRecoveredText()
        },
        modifier = modifier,
    )
}
