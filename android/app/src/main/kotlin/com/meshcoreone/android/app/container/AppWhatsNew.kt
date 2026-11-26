// PortedFrom: MC1/State/AppState.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/ContentView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.container

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.app.state.ConnectionUiSnapshot
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.PreferenceStore
import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.settings.app.whatsnew.WhatsNewBaselineStore
import com.meshcoreone.android.feature.settings.app.whatsnew.WhatsNewState
import com.meshcoreone.android.feature.settings.app.whatsnew.ui.WhatsNewHost
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class DataStoreWhatsNewBaseline(private val preferences: PreferenceStore) : WhatsNewBaselineStore {
    override suspend fun read(): String? = preferences.snapshot().stored(AppStorageKey.lastShownWhatsNewVersion)
    override suspend fun write(version: String) = preferences.set(AppStorageKey.lastShownWhatsNewVersion, version)
}

class AppWhatsNew internal constructor(val state: WhatsNewState) {
    internal val failure = MutableStateFlow<Throwable?>(null)

    internal fun report(problem: Throwable) {
        Logger.getLogger("com.mc1.WhatsNew").log(Level.WARNING, "What's New persistence failed", problem)
        failure.value = problem
    }

    companion object {
        internal suspend fun create(
            preferences: PreferenceStore,
            currentVersion: String,
            wasOnboardedAtLaunch: Boolean,
            isScreenshotMode: Boolean = false,
        ): AppWhatsNew {
            val host = AppWhatsNew(WhatsNewState(DataStoreWhatsNewBaseline(preferences), currentVersion))
            try {
                host.state.evaluate(wasOnboardedAtLaunch, isScreenshotMode)
            } catch (problem: StorageFailure) {
                // Optional release notes must not prevent the core app from starting.
                host.report(problem)
            }
            return host
        }
    }
}

@Composable
internal fun AppWhatsNewHost(
    host: AppWhatsNew,
    connectionUi: StateFlow<ConnectionUiSnapshot>,
    scanRequested: StateFlow<Boolean>?,
    onSupport: () -> Unit,
) {
    val connection by connectionUi.collectAsStateWithLifecycle()
    val scanning = scanRequested?.collectAsStateWithLifecycle()?.value ?: false
    val failure = host.failure.collectAsStateWithLifecycle().value
    val quiescent = !connection.showingConnectionFailedAlert &&
        connection.otherAppWarningDeviceId == null && connection.pendingSystemPairingSetup == null && !scanning
    WhatsNewHost(
        state = host.state,
        connectionUiQuiescent = quiescent && failure == null,
        onSupport = onSupport,
        onStorageFailure = host::report,
    )
    if (quiescent && failure != null) {
        AlertDialog(
            onDismissRequest = { host.failure.value = null },
            text = { Text(uiString(UiErrorMapper().message(failure))) },
            confirmButton = {
                TextButton(onClick = { host.failure.value = null }) {
                    Text(stringResource(AppLocalizableStrings.commonOk))
                }
            },
        )
    }
}
