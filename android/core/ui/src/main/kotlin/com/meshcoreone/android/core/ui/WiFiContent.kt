// PortedFrom: MC1/Views/Components/WiFiAddressFields.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Components/WiFiSheetToolbarModifier.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O

enum class WiFiField { IP_ADDRESS, PORT }
data class WiFiAddressState(
    val ipAddress: String,
    val port: String,
    val focusedField: WiFiField? = null,
    val isProcessing: Boolean = false,
) {
    val isValid: Boolean get() = WiFiAddressValidation.isValidHost(ipAddress) && WiFiAddressValidation.isValidPort(port)
}

@Composable
fun WiFiAddressFields(
    state: WiFiAddressState,
    onAddressChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onFocusChange: (WiFiField?) -> Unit,
    onPortSubmit: () -> Unit,
    sectionHeader: UiText,
    sectionFooter: UiText,
    modifier: Modifier = Modifier,
) {
    val addressFocus = remember { FocusRequester() }
    val portFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.focusedField) {
        when (state.focusedField) {
            WiFiField.IP_ADDRESS -> addressFocus.requestFocus()
            WiFiField.PORT -> portFocus.requestFocus()
            null -> focusManager.clearFocus()
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fullKeyboard = maxWidth >= 600.dp
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiString(sectionHeader), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                state.ipAddress, { onAddressChange(WiFiAddressValidation.replaceCommas(it)) },
                Modifier.fillMaxWidth().focusRequester(addressFocus).onFocusChanged {
                    if (it.isFocused && state.focusedField != WiFiField.IP_ADDRESS) onFocusChange(WiFiField.IP_ADDRESS)
                },
                label = { Text(uiString(UiText.Resource(O.wifiConnectionIpAddressPlaceholder))) },
                keyboardOptions = KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { onFocusChange(WiFiField.PORT) }),
                trailingIcon = if (state.ipAddress.isNotEmpty()) {
                    {
                        IconButton(onClick = { onAddressChange("") }, modifier = Modifier.sharedTouchTarget()) {
                            Icon(MeshSymbol.ERROR.vector, uiString(UiText.Resource(O.wifiConnectionIpAddressClearAccessibility)))
                        }
                    }
                } else null,
            )
            OutlinedTextField(
                state.port, onPortChange,
                Modifier.fillMaxWidth().focusRequester(portFocus).onFocusChanged {
                    if (it.isFocused && state.focusedField != WiFiField.PORT) onFocusChange(WiFiField.PORT)
                },
                label = { Text(uiString(UiText.Resource(O.wifiConnectionPortPlaceholder))) },
                keyboardOptions = KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = if (fullKeyboard) KeyboardType.Ascii else KeyboardType.Number,
                    imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onPortSubmit() }),
                trailingIcon = if (state.port.isNotEmpty()) {
                    {
                        IconButton(onClick = { onPortChange("") }, modifier = Modifier.sharedTouchTarget()) {
                            Icon(MeshSymbol.ERROR.vector, uiString(UiText.Resource(O.wifiConnectionPortClearAccessibility)))
                        }
                    }
                } else null,
            )
            Text(uiString(sectionFooter), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun WiFiSheetToolbar(
    state: WiFiAddressState,
    onFocusChange: (WiFiField?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onFocusChange(null); onCancel() }, enabled = !state.isProcessing,
            modifier = Modifier.sharedTouchTarget()) {
            Text(uiString(UiText.Resource(L.commonCancel)))
        }
        if (state.focusedField != null) TextButton(onClick = { onFocusChange(null) }, modifier = Modifier.sharedTouchTarget()) {
            Text(uiString(UiText.Resource(L.commonDone)))
        }
    }
}
