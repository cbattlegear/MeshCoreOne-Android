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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.settings.*
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
) {
    val state by helper.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val editable = enabled && !state.isApplying && !state.isRebooting
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
            RemoteNumberField(L.remoteNodesSettingsBandwidthKHz, state.bandwidth, editable) {
                helper.setRadio(state.frequency, it, state.spreadingFactor, state.codingRate)
            }
            RemoteIntegerField(L.remoteNodesSettingsSpreadingFactor, state.spreadingFactor, editable) {
                helper.setRadio(state.frequency, state.bandwidth, it, state.codingRate)
            }
            RemoteIntegerField(L.remoteNodesSettingsCodingRate, state.codingRate, editable) {
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
    var draft by remember(value) { mutableStateOf(value?.takeIf { it.isFinite() }?.toString().orEmpty()) }
    OutlinedTextField(
        draft, { draft = it; onChange(it.toDoubleOrNull() ?: Double.NaN) }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) }, enabled = enabled && value != null,
        isError = draft.isNotEmpty() && draft.toDoubleOrNull()?.isFinite() != true,
    )
}

@Composable
internal fun RemoteIntegerField(label: Int, value: Long?, enabled: Boolean, onChange: (Long) -> Unit) {
    var draft by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        draft, { draft = it; onChange(it.toLongOrNull() ?: Long.MIN_VALUE) }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) }, enabled = enabled && value != null,
        isError = draft.isNotEmpty() && draft.toLongOrNull() == null,
    )
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
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(state.repeaterEnabled == true, holder::setRepeaterEnabled, enabled = enabled && state.repeaterEnabled != null)
            Text(stringResource(L.remoteNodesSettingsRepeaterMode))
        }
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
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(state.allowReadOnly == true, holder::setAllowReadOnly, enabled = enabled && state.allowReadOnly != null)
            Text(stringResource(L.remoteNodesRoomSettingsAllowReadOnly))
        }
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
