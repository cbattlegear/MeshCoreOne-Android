// AndroidOnly: WP-303 Explicit confirmation and file input for process-scoped identity recovery.
package com.meshcoreone.android.app.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.R
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.crypto.CryptoException
import com.meshcoreone.android.core.runtime.ConnectionManager
import com.meshcoreone.android.core.runtime.IdentityImportRecovery
import com.meshcoreone.android.core.runtime.IdentityImportKeyMismatch
import com.meshcoreone.android.core.runtime.IdentityImportPeerMismatch
import com.meshcoreone.android.core.runtime.IdentityImportPeerUnidentified
import com.meshcoreone.android.core.runtime.IdentityImportReadbackMismatch
import com.meshcoreone.android.core.services.remote.NodeConfigJson
import com.meshcoreone.android.core.ui.SharedUiScaffold
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.settings.app.nodeconfig.NodeConfigDocuments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun IdentityImportRecoveryScreen(
    manager: ConnectionManager,
    receiptId: java.util.UUID,
    documents: NodeConfigDocuments,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var key by remember(receiptId) { mutableStateOf<Bytes?>(null) }
    var confirmation by remember(receiptId) { mutableStateOf<IdentityImportRecovery?>(null) }
    var busy by remember(receiptId) { mutableStateOf(false) }
    var complete by remember(receiptId) { mutableStateOf(false) }
    var error by remember(receiptId) { mutableStateOf<Int?>(null) }
    val recovery = manager.prepareIdentityImportRecovery(receiptId)

    SharedUiScaffold(topBar = {
        TextButton(onClick = onDone, modifier = Modifier.sharedTouchTarget()) { Text(stringResource(L.commonDone)) }
    }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.identity_import_recovery_title), style = MaterialTheme.typography.titleLarge)
            if (complete) {
                Text(stringResource(R.string.identity_import_recovery_complete))
            } else if (recovery == null) {
                Text(stringResource(R.string.identity_import_recovery_unavailable))
            } else {
                Text(stringResource(R.string.identity_import_recovery_unknown, recovery.sourceName))
                Button(onClick = {
                    busy = true
                    error = null
                    key = null
                    scope.launch {
                        try {
                            val bytes = documents.pick()
                            if (bytes != null) {
                                val parsed = withContext(Dispatchers.IO) {
                                    val hex = NodeConfigJson.decode(bytes).privateKey
                                    if (hex == null || hex.length != ProtocolLimits.PRIVATE_KEY_SIZE * 2 ||
                                        !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                                    ) null else Bytes.fromHex(hex)
                                }
                                key = parsed
                                if (parsed == null) error = R.string.identity_import_recovery_invalid
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            key = null
                            error = R.string.identity_import_recovery_invalid
                        } finally {
                            busy = false
                        }
                    }
                }, modifier = Modifier.fillMaxWidth().sharedTouchTarget(), enabled = !busy) {
                    Text(stringResource(R.string.identity_import_recovery_select))
                }
                if (key != null) Text(stringResource(R.string.identity_import_recovery_selected))
                Button(onClick = {
                    confirmation = manager.prepareIdentityImportRecovery(receiptId)
                    if (confirmation == null) error = R.string.identity_import_recovery_unavailable
                }, modifier = Modifier.fillMaxWidth().sharedTouchTarget(), enabled = key != null && !busy) {
                    Text(stringResource(R.string.identity_import_recovery_apply))
                }
                if (busy) CircularProgressIndicator()
            }
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        }
    }

    confirmation?.let { selected ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(R.string.identity_import_recovery_confirm_title)) },
            text = { Text(stringResource(R.string.identity_import_recovery_confirm, selected.sourceName)) },
            confirmButton = {
                TextButton(onClick = {
                    val capturedKey = key ?: return@TextButton
                    confirmation = null
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            manager.retryIdentityImport(selected, capturedKey)
                            key = null
                            complete = true
                        } catch (cancelled: CancellationException) {
                            error = R.string.identity_import_recovery_unavailable
                            throw cancelled
                        } catch (_: IdentityImportPeerMismatch) {
                            error = R.string.identity_import_recovery_wrong_peer
                        } catch (_: IdentityImportPeerUnidentified) {
                            error = R.string.identity_import_recovery_wrong_peer
                        } catch (_: IdentityImportReadbackMismatch) {
                            error = R.string.identity_import_recovery_wrong_readback
                        } catch (_: IdentityImportKeyMismatch) {
                            key = null
                            error = R.string.identity_import_recovery_wrong_key
                        } catch (_: CryptoException.InvalidExpandedPrivateKey) {
                            key = null
                            error = R.string.identity_import_recovery_invalid
                        } catch (_: Exception) {
                            error = R.string.identity_import_recovery_failed
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.identity_import_recovery_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text(stringResource(L.commonCancel)) }
            },
        )
    }
}
