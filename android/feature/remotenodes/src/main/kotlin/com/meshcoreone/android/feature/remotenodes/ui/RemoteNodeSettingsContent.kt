// PortedFrom: MC1/Views/RemoteNodes/SharedNodeSettingsViews.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Repeaters/RepeaterSettingsView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomSettingsView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteRadioOptions
import com.meshcoreone.android.feature.remotenodes.settings.*
import com.meshcoreone.android.feature.remotenodes.map.*
import com.meshcoreone.android.core.model.Coordinate
import java.util.UUID
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun RemoteNodeSettingsContent(
    helper: NodeSettingsStateHolder,
    repeater: RepeaterSettingsStateHolder?,
    room: RoomSettingsStateHolder?,
    enabled: Boolean,
    confirm: (Int, suspend () -> Unit) -> Unit,
    modifier: Modifier,
    radioOptions: RemoteRadioOptions?,
    mapSurface: RemoteNodesMapSurface,
) {
    val state by helper.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val editable = enabled && !state.isApplying && !state.isRebooting
    var picking by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Coordinate?>(null) }
    val pickId = remember { UUID.randomUUID() }
    androidx.activity.compose.BackHandler(picking) { picking = false }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RemoteFailure(state.errorMessage)
        if (state.isApplying || state.isRebooting) LinearProgressIndicator(Modifier.fillMaxWidth())
        RemoteSection(
            L.remoteNodesSettingsRadioParameters, state.isRadioExpanded,
            { helper.setExpanded(NodeSettingsSection.RADIO, !state.isRadioExpanded); if (!state.radioLoaded && enabled) scope.launch { helper.fetchRadioSettings() } },
            state.isLoadingRadio, state.radioError, enabled,
            { scope.launch { helper.fetchRadioSettings() } },
        ) {
            RemoteNumberField(L.remoteNodesSettingsFrequencyMHz, state.frequency, editable) {
                helper.setRadio(it, state.bandwidth, state.spreadingFactor, state.codingRate)
            }
            RemoteOptionField(L.remoteNodesSettingsBandwidthKHz, state.bandwidth, radioOptions?.bandwidthsKHz.orEmpty(), editable) {
                helper.setRadio(state.frequency, it, state.spreadingFactor, state.codingRate)
            }
            RemoteOptionField(L.remoteNodesSettingsSpreadingFactor, state.spreadingFactor, radioOptions?.spreadingFactors.orEmpty(), editable) {
                helper.setRadio(state.frequency, state.bandwidth, it, state.codingRate)
            }
            RemoteOptionField(L.remoteNodesSettingsCodingRate, state.codingRate, radioOptions?.codingRates.orEmpty(), editable) {
                helper.setRadio(state.frequency, state.bandwidth, state.spreadingFactor, it)
            }
            Text(stringResource(L.remoteNodesSettingsRadioRestartWarning))
            RemoteApply(L.remoteNodesSettingsApplyRadioSettings, editable && state.radioSettingsModified) {
                confirm(L.remoteNodesSettingsApplyRadioSettings, helper::applyRadioSettings)
            }
        }
        RemoteSection(
            L.remoteNodesSettingsIdentityLocation, state.isIdentityExpanded,
            { helper.setExpanded(NodeSettingsSection.IDENTITY, !state.isIdentityExpanded); if (!state.identityLoaded && enabled) scope.launch { helper.fetchIdentity() } },
            state.isLoadingIdentity, state.identityError, enabled,
            { scope.launch { helper.fetchIdentity() } },
        ) {
            OutlinedTextField(
                state.name.orEmpty(), helper::setName, Modifier.fillMaxWidth(), enabled = editable,
                label = { Text(stringResource(L.remoteNodesName)) }, isError = state.nameError != null,
            )
            RemoteFailure(state.nameError)
            RemoteNumberField(L.remoteNodesSettingsLatitude, state.latitude, editable, helper::setLatitude)
            RemoteFailure(state.latitudeError)
            RemoteNumberField(L.remoteNodesSettingsLongitude, state.longitude, editable, helper::setLongitude)
            RemoteFailure(state.longitudeError)
            TextButton({
                picked = Coordinate(state.latitude ?: 0.0, state.longitude ?: 0.0).takeIf { it.isValidFix }
                picking = true
            }, enabled = editable && state.identityLoaded) { Text(stringResource(L.remoteNodesSettingsPickOnMap)) }
            if (picking) {
                val coordinate = picked ?: Coordinate(0.0, 0.0)
                // Selection follows camera reports; only opening the picker initializes its bounds.
                val initialRegion = remember { CoordinateRegion.around(coordinate, if (picked == null) 80.0 else .05) }
                RemoteNodeMapContent(
                    stringResource(L.remoteNodesSettingsPickOnMap),
                    listOf(MapPoint(pickId, coordinate, PinStyle.CROSSHAIR, null, false, null, null)),
                    emptyList(), initialRegion,
                    surface = mapSurface,
                    onCameraChanged = { picked = Coordinate(it.center.latitude, it.center.longitude) },
                )
                RemoteValue(stringResource(L.remoteNodesSettingsLatitude), coordinate.latitude.toString())
                RemoteValue(stringResource(L.remoteNodesSettingsLongitude), coordinate.longitude.toString())
                TextButton({ picking = false }) { Text(stringResource(L.remoteNodesCancel)) }
                RemoteApply(L.remoteNodesDone, editable && picked?.isValidFix == true) {
                    picked?.let { helper.setLocationFromPicker(it.latitude, it.longitude) }
                    picking = false
                }
            }
            RemoteApply(L.remoteNodesSettingsApplyIdentitySettings, editable && state.identitySettingsModified) {
                confirm(L.remoteNodesSettingsApplyIdentitySettings, helper::applyIdentitySettings)
            }
            if (state.identityApplySuccess) Text(stringResource(L.remoteNodesSettingsSettingsApplied))
        }
        RemoteSection(
            L.remoteNodesSettingsContactInfo, state.isContactInfoExpanded,
            { helper.setExpanded(NodeSettingsSection.CONTACT_INFO, !state.isContactInfoExpanded); if (!state.contactInfoLoaded && enabled) scope.launch { helper.fetchContactInfo() } },
            state.isLoadingContactInfo, state.contactInfoError, enabled,
            { scope.launch { helper.fetchContactInfo() } },
        ) {
            OutlinedTextField(
                state.ownerInfo.orEmpty(), helper::setOwnerInfo,
                Modifier.fillMaxWidth().testTag("remote-owner-info"),
                enabled = editable && state.contactInfoLoaded, minLines = 3,
                label = { Text(stringResource(L.remoteNodesSettingsContactInfoPlaceholder)) },
                isError = state.isOwnerInfoTooLong,
                supportingText = { Text(NodeSettingsPresentation.ownerInfoCounter(state).first) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
            Text(stringResource(L.remoteNodesSettingsContactInfoFooter))
            RemoteApply(L.remoteNodesSettingsApplyContactInfo, editable && state.canApplyContactInfo) {
                confirm(L.remoteNodesSettingsApplyContactInfo, helper::applyContactInfoSettings)
            }
            if (state.contactInfoApplySuccess) Text(stringResource(L.remoteNodesSettingsSettingsApplied))
        }
        if (repeater != null) RepeaterBehaviorContent(repeater, editable, confirm)
        if (room != null) RoomBehaviorContent(room, editable, confirm)
        if (repeater != null) RemoteRegionsContent(repeater.regions, editable, confirm)
        RemoteSection(
            L.remoteNodesSettingsSecurity, state.isSecurityExpanded,
            { helper.setExpanded(NodeSettingsSection.SECURITY, !state.isSecurityExpanded) },
        ) {
            OutlinedTextField(
                state.newPassword, helper::setNewPassword, Modifier.fillMaxWidth(), enabled = editable,
                label = { Text(stringResource(L.remoteNodesSettingsNewPassword)) }, visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                state.confirmPassword, helper::setConfirmPassword, Modifier.fillMaxWidth(), enabled = editable,
                label = { Text(stringResource(L.remoteNodesSettingsConfirmPassword)) }, visualTransformation = PasswordVisualTransformation(),
            )
            RemoteApply(L.remoteNodesSettingsChangePassword, editable) { confirm(L.remoteNodesSettingsChangePassword, helper::changePassword) }
        }
        RemoteSection(
            L.remoteNodesSettingsDeviceInfo, state.isDeviceInfoExpanded,
            { helper.setExpanded(NodeSettingsSection.DEVICE_INFO, !state.isDeviceInfoExpanded); if (!state.deviceInfoLoaded && enabled) scope.launch { helper.fetchDeviceInfo() } },
            state.isLoadingDeviceInfo, state.deviceInfoError, enabled,
            { scope.launch { helper.fetchDeviceInfo() } },
        ) {
            RemoteValue(stringResource(L.remoteNodesSettingsFirmware), state.firmwareVersion ?: NodeSettingsPresentation.EM_DASH)
            RemoteValue(stringResource(L.remoteNodesSettingsDeviceTime), NodeSettingsPresentation.deviceTime(state, Locale.getDefault(), ZoneId.systemDefault()) ?: NodeSettingsPresentation.EM_DASH)
            ClockDriftWarning.of(state.clockDrift)?.let { warning ->
                val formatter = android.icu.text.MeasureFormat.getInstance(
                    Locale.getDefault(), android.icu.text.MeasureFormat.FormatWidth.SHORT,
                )
                val magnitude = formatter.formatMeasures(*warning.parts.map { part ->
                    android.icu.util.Measure(part.value, when (part.unit) {
                        DriftUnit.DAY -> android.icu.util.MeasureUnit.DAY
                        DriftUnit.HOUR -> android.icu.util.MeasureUnit.HOUR
                        DriftUnit.MINUTE -> android.icu.util.MeasureUnit.MINUTE
                        DriftUnit.SECOND -> android.icu.util.MeasureUnit.SECOND
                    })
                }.toTypedArray())
                Text(
                    if (warning.ahead) L.remoteNodesStatusClockAhead(resources, magnitude)
                    else L.remoteNodesStatusClockBehind(resources, magnitude),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        RemoteHeading(stringResource(L.remoteNodesSettingsDeviceActions))
        RemoteApply(L.remoteNodesSettingsSyncTime, editable) { confirm(L.remoteNodesSettingsSyncTime, helper::syncTime) }
        RemoteApply(L.remoteNodesSettingsSendAdvert, editable && !state.isSendingAdvert) { confirm(L.remoteNodesSettingsSendAdvert, helper::forceAdvert) }
        RemoteApply(L.remoteNodesSettingsRebootDevice, editable) { confirm(L.remoteNodesSettingsRebootDevice, helper::reboot) }
    }
}

@Composable
internal fun RemoteApply(label: Int, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("action:$label"), enabled) { Text(stringResource(label)) }
}

@Composable
internal fun RemoteNumberField(label: Int, value: Double?, enabled: Boolean, onChange: (Double) -> Unit) {
    var draft by remember { mutableStateOf(value?.takeIf { it.isFinite() }?.toString().orEmpty()) }
    LaunchedEffect(value) {
        if (value?.isFinite() == true && draft.toDoubleOrNull() != value) draft = value.toString()
    }
    OutlinedTextField(
        draft, { draft = it; onChange(it.toDoubleOrNull() ?: Double.NaN) }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) }, enabled = enabled && value != null,
        isError = value != null && draft.toDoubleOrNull()?.isFinite() != true,
    )
}

@Composable
internal fun RemoteIntegerField(label: Int, value: Long?, enabled: Boolean, onChange: (Long) -> Unit) {
    var draft by remember { mutableStateOf(value?.toString().orEmpty()) }
    LaunchedEffect(value) {
        if (value != null && value != Long.MIN_VALUE && draft.toLongOrNull() != value) draft = value.toString()
    }
    OutlinedTextField(
        draft, { draft = it; onChange(it.toLongOrNull() ?: Long.MIN_VALUE) }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) }, enabled = enabled && value != null,
        isError = value != null && draft.toLongOrNull() == null,
    )
}

@Composable
private fun <T : Number> RemoteOptionField(label: Int, value: T?, options: List<T>, enabled: Boolean, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    RemoteValue(stringResource(label), value?.toString() ?: NodeSettingsPresentation.EM_DASH)
    Box {
        TextButton({ expanded = true }, enabled = enabled && value != null && options.isNotEmpty()) {
            Text(stringResource(label))
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.toString()) }, onClick = { expanded = false; onChange(option) })
            }
        }
    }
}
@Composable
private fun RepeaterBehaviorContent(holder: RepeaterSettingsStateHolder, enabled: Boolean, confirm: (Int, suspend () -> Unit) -> Unit) {
    val state by holder.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    RemoteSection(
        L.remoteNodesSettingsBehavior, state.isBehaviorExpanded,
        { holder.setBehaviorExpanded(!state.isBehaviorExpanded); if (!state.behaviorLoaded && enabled) scope.launch { holder.fetchBehaviorSettings() } },
        state.isLoadingBehavior, state.behaviorError, enabled, { scope.launch { holder.fetchBehaviorSettings() } },
    ) {
        RemoteToggle(
            L.remoteNodesSettingsRepeaterMode, state.repeaterEnabled == true, holder::setRepeaterEnabled,
            enabled && state.repeaterEnabled != null, androidx.compose.ui.semantics.Role.Switch,
        )
        RemoteBehaviorFields(state.advertIntervalMinutes, state.floodAdvertIntervalHours, state.floodMaxHops, enabled,
            holder::setAdvertIntervalMinutes, holder::setFloodAdvertIntervalHours, holder::setFloodMaxHops)
        RemoteFailure(state.advertIntervalError); RemoteFailure(state.floodAdvertIntervalError); RemoteFailure(state.floodMaxHopsError)
        RemoteApply(L.remoteNodesSettingsApplyBehaviorSettings, enabled && state.behaviorSettingsModified) {
            confirm(L.remoteNodesSettingsApplyBehaviorSettings, holder::applyBehaviorSettings)
        }
        if (state.behaviorApplySuccess) Text(stringResource(L.remoteNodesSettingsSettingsApplied))
    }
}

@Composable
private fun RoomBehaviorContent(holder: RoomSettingsStateHolder, enabled: Boolean, confirm: (Int, suspend () -> Unit) -> Unit) {
    val state by holder.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    RemoteSection(
        L.remoteNodesRoomSettingsRoomSettingsSection, state.isRoomAccessExpanded,
        { holder.setRoomAccessExpanded(!state.isRoomAccessExpanded); if (!state.roomAccessLoaded && enabled) scope.launch { holder.fetchRoomAccess() } },
        state.isLoadingRoomAccess, state.roomAccessError, enabled, { scope.launch { holder.fetchRoomAccess() } },
    ) {
        OutlinedTextField(
            state.guestPassword.orEmpty(), holder::setGuestPassword, Modifier.fillMaxWidth(),
            enabled = enabled && state.roomAccessLoaded, label = { Text(stringResource(L.remoteNodesRoomSettingsGuestPassword)) },
            visualTransformation = PasswordVisualTransformation(),
        )
        RemoteToggle(
            L.remoteNodesRoomSettingsAllowReadOnly, state.allowReadOnly == true, holder::setAllowReadOnly,
            enabled && state.allowReadOnly != null, androidx.compose.ui.semantics.Role.Switch,
        )
        RemoteApply(L.remoteNodesRoomSettingsApplyRoomSettings, enabled && state.roomAccessModified && !state.isApplyingRoomAccess) {
            confirm(L.remoteNodesRoomSettingsApplyRoomSettings, holder::applyRoomAccess)
        }
    }
    RemoteSection(
        L.remoteNodesSettingsBehavior, state.isBehaviorExpanded,
        { holder.setBehaviorExpanded(!state.isBehaviorExpanded); if (!state.behaviorLoaded && enabled) scope.launch { holder.fetchBehaviorSettings() } },
        state.isLoadingBehavior, state.behaviorError, enabled, { scope.launch { holder.fetchBehaviorSettings() } },
    ) {
        RemoteBehaviorFields(state.advertIntervalMinutes, state.floodAdvertIntervalHours, state.floodMaxHops, enabled,
            holder::setAdvertIntervalMinutes, holder::setFloodAdvertIntervalHours, holder::setFloodMaxHops)
        RemoteFailure(state.advertIntervalError); RemoteFailure(state.floodAdvertIntervalError); RemoteFailure(state.floodMaxHopsError)
        RemoteApply(L.remoteNodesSettingsApplyBehaviorSettings, enabled && state.behaviorModified && !state.isApplyingBehavior) {
            confirm(L.remoteNodesSettingsApplyBehaviorSettings, holder::applyBehaviorSettings)
        }
    }
}

@Composable
private fun RemoteBehaviorFields(
    advert: Long?, flood: Long?, hops: Long?, enabled: Boolean,
    onAdvert: (Long) -> Unit, onFlood: (Long) -> Unit, onHops: (Long) -> Unit,
) {
    RemoteIntegerField(L.remoteNodesSettingsAdvertInterval0Hop, advert, enabled, onAdvert)
    RemoteIntegerField(L.remoteNodesSettingsAdvertIntervalFlood, flood, enabled, onFlood)
    RemoteIntegerField(L.remoteNodesSettingsMaxFloodHops, hops, enabled, onHops)
}
