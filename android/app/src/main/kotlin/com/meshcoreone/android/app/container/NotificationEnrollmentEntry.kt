// AndroidOnly: WP-303 Mounted Notifications settings action and exact foreground warning lifetime.
package com.meshcoreone.android.app.container

import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meshcoreone.android.R

/** Null is a visible activation hold, not an in-memory successful enrollment implementation. */
@Composable
internal fun NotificationEnrollmentEntry(producer: NotificationEnrollmentProducer?) {
    val copy = EnrollmentWarningCopy(
        stringResource(R.string.notification_enrollment_title),
        stringResource(R.string.notification_enrollment_warning),
        stringResource(R.string.notification_enrollment_confirm),
        stringResource(R.string.notification_enrollment_cancel),
    )
    if (producer == null) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.notification_enrollment_held))
            TextButton(onClick = {}, enabled = false) { Text(stringResource(R.string.notification_enrollment_action)) }
        }
        return
    }
    val owner = remember(producer, copy) { Any() }
    var attached by remember(owner) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(producer, owner, lifecycle) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) attached = producer.attach(owner)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> attached = producer.attach(owner)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    producer.detach(owner)
                    attached = false
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            producer.detach(owner)
            lifecycle.removeObserver(observer)
        }
    }
    val state by producer.state.collectAsState()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        when (val current = state) {
            NotificationEnrollmentProducer.State.Reading, is NotificationEnrollmentProducer.State.Working ->
                CircularProgressIndicator()
            is NotificationEnrollmentProducer.State.Interrupted ->
                Text(stringResource(R.string.notification_enrollment_interrupted))
            is NotificationEnrollmentProducer.State.Outcome -> Text(stringResource(
                if (current.result is NotificationEnrollmentCoordinator.Result.BoundSnapshot) R.string.notification_enrollment_bound
                else R.string.notification_enrollment_blocked,
            ))
            else -> Unit
        }
        TextButton(onClick = { producer.begin(owner, copy) }, enabled = attached && producer.canBegin) {
            Text(stringResource(R.string.notification_enrollment_action))
        }
        TextButton(
            onClick = { producer.resume(owner, copy) },
            enabled = attached && state !is NotificationEnrollmentProducer.State.Working &&
                state !is NotificationEnrollmentProducer.State.Reading &&
                state !is NotificationEnrollmentProducer.State.Warning,
        ) { Text(stringResource(R.string.notification_enrollment_resume)) }
    }
    val shown = if (!attached) null else when (val current = state) {
        is NotificationEnrollmentProducer.State.Warning -> current.presentation
        is NotificationEnrollmentProducer.State.Working -> current.presentation
        else -> null
    }
    if (shown != null) {
        AlertDialog(
            onDismissRequest = { producer.dismiss(owner, shown) },
            title = { Text(shown.copy.title) },
            text = {
                EnrollmentWarningLifetime(producer, owner, shown)
                Text(shown.copy.body, Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = { producer.confirm(owner, shown) },
                    enabled = state is NotificationEnrollmentProducer.State.Warning) { Text(shown.copy.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { producer.dismiss(owner, shown) }) { Text(shown.copy.cancel) }
            },
        )
    }
}

/** Observe the dialog's own window; its appearance legitimately removes focus from the activity. */
@Composable
private fun EnrollmentWarningLifetime(
    producer: NotificationEnrollmentProducer,
    owner: Any,
    shown: NotificationEnrollmentProducer.Presentation,
) {
    val view = LocalView.current
    DisposableEffect(view, producer, owner, shown) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (!focused) producer.dismiss(owner, shown)
        }
        observer.addOnWindowFocusChangeListener(listener)
        onDispose {
            producer.dismiss(owner, shown)
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
        }
    }
}
