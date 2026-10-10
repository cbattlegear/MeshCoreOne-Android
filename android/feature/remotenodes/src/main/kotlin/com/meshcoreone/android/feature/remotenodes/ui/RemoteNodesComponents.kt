// PortedFrom: MC1/Views/RemoteNodes/SharedNodeSettingsViews.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/SharedNodeStatusViews.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText

@Composable
internal fun remoteText(text: RemoteNodesText): String {
    val resources = LocalContext.current.resources
    fun resolve(value: RemoteNodesText): String = when (value) {
        is RemoteNodesText.Verbatim -> value.text
        is RemoteNodesText.Failure -> UiErrorMapper().message(value.error).resolve(resources)
        is RemoteNodesText.Resource -> String.format(
            resources.configuration.locales[0], resources.getString(value.id),
            *value.args.map { if (it is RemoteNodesText) resolve(it) else it }.toTypedArray(),
        )
    }
    return resolve(text)
}

@Composable
internal fun RemoteFailure(text: RemoteNodesText?) {
    if (text != null) Text(
        remoteText(text),
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("remote-error"),
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
internal fun RemoteHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun RemoteValue(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun RemoteSection(
    title: Int,
    expanded: Boolean,
    onExpand: () -> Unit,
    loading: Boolean = false,
    failed: Boolean = false,
    enabled: Boolean = true,
    onReload: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RemoteHeading(
                stringResource(title),
                Modifier.weight(1f).clickable(onClick = onExpand).heightIn(min = 48.dp).padding(vertical = 12.dp),
            )
            if (onReload != null) TextButton(
                onClick = onReload, enabled = enabled && !loading,
                modifier = Modifier.heightIn(min = 48.dp).testTag("reload:$title"),
            ) { Text(stringResource(L.remoteNodesStatusRefresh)) }
        }
        if (expanded) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) RemoteFailure(RemoteNodesText.Resource(L.remoteNodesSettingsFailedToLoad))
            content()
        }
        HorizontalDivider(Modifier.padding(top = 12.dp))
    }
}

@Composable
internal fun RemoteConfirmation(title: String, message: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(message) },
        dismissButton = { TextButton(onCancel) { Text(stringResource(L.remoteNodesCancel)) } },
        confirmButton = {
            TextButton(onConfirm, Modifier.testTag("confirm-remote-action")) { Text(stringResource(L.remoteNodesSettingsOk)) }
        },
    )
}
