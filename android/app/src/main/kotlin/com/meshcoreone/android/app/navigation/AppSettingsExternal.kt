// AndroidOnly: WP-407 Explicit settings actions over the existing device/contact/region owners.
package com.meshcoreone.android.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.app.container.AppSettingsFeature
import com.meshcoreone.android.app.container.AppContainer
import com.meshcoreone.android.app.state.AppSession
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.datastore.KeyGenerationService
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.services.contacts.ContactService
import com.meshcoreone.android.core.services.contacts.ContactWriteRecoveryState
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.onboarding.ui.RegionPicker
import com.meshcoreone.android.feature.settings.device.SettingsExternalDestination
import com.meshcoreone.android.feature.settings.device.SettingsNotConnectedException
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

internal suspend fun removeSettingsKnownRegion(container: AppContainer, session: AppSession, region: String) {
    fun requireCurrent() {
        if (container.sessions.current !== session || !container.appState.canRunSettingsStartupReads) {
            throw SettingsNotConnectedException()
        }
    }
    requireCurrent()
    if (container.appState.connectedDevice?.defaultFloodScopeName == region) {
        val current = container.sessions.current?.takeIf { it === session } ?: throw SettingsNotConnectedException()
        current.setDefaultFloodScopePersisted(null)
        requireCurrent()
    }
    if (!container.deviceEditor.persistKnownRegionRemoval(region, session.token)) throw SettingsNotConnectedException()
}

internal suspend fun addSettingsKnownRegion(container: AppContainer, session: AppSession, region: String) {
    if (container.sessions.current !== session || !container.appState.canRunSettingsStartupReads) {
        throw SettingsNotConnectedException()
    }
    if (!container.deviceEditor.addKnownRegion(region, session.token)) throw SettingsNotConnectedException()
}

@Composable
internal fun AppSettingsExternal(feature: AppSettingsFeature, target: SettingsExternalDestination, onDone: () -> Unit) {
    val container = feature.container
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action(); version++ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feature.report("Settings action failed", failure) }
            finally { busy = false }
        }
    }
    fun live() = container.sessions.current?.takeIf {
        container.appState.canRunSettingsStartupReads && it.token.radioId == container.appState.currentRadioId
    } ?: throw SettingsNotConnectedException()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onDone) { Text(stringResource(L.commonDone)) }
        when (target) {
            SettingsExternalDestination.RegionPicker -> {
                val region by feature.regions.selection.collectAsState()
                RegionPicker(region, requireNotNull(container.onboarding).region.catalog, feature.regions::set)
            }
            SettingsExternalDestination.LocationPicker -> {
                var latitude by remember { mutableStateOf(container.appState.connectedDevice?.latitude?.toString().orEmpty()) }
                var longitude by remember { mutableStateOf(container.appState.connectedDevice?.longitude?.toString().orEmpty()) }
                val lat = latitude.toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 }
                val lon = longitude.toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 }
                OutlinedTextField(latitude, { latitude = it }, label = { Text(stringResource(S.locationPickerLatitude)) })
                OutlinedTextField(longitude, { longitude = it }, label = { Text(stringResource(S.locationPickerLongitude)) })
                Button(onClick = { run { live().settingsService.setManualLocationVerified(requireNotNull(lat), requireNotNull(lon)); onDone() } },
                    enabled = lat != null && lon != null && !busy) { Text(stringResource(L.commonSave)) }
            }
            SettingsExternalDestination.ShareContactQr -> {
                val device = container.appState.connectedDevice
                if (device == null) Text(stringResource(L.errorConnectionNotConnected))
                else AppChannelQr(ContactService.exportContactURI(device.nodeName, device.publicKey, ContactType.CHAT), onDone)
            }
            SettingsExternalDestination.ImportPrivateKey -> {
                var text by remember { mutableStateOf("") }
                var replacement by remember { mutableStateOf<Pair<Bytes, com.meshcoreone.android.app.container.RadioSessionContainer>?>(null) }
                Text(stringResource(S.importKeySheetExplanation))
                OutlinedTextField(text, { text = it.uppercase(java.util.Locale.ROOT).filter { char -> char in '0'..'9' || char in 'A'..'F' } },
                    label = { Text(stringResource(S.importKeyKeyInputLabel)) }, enabled = !busy)
                Button(enabled = text.isNotBlank() && !busy, onClick = { run {
                    val key = Bytes.fromHex(text.trim())
                    KeyGenerationService.validateExpandedKey(key)
                    replacement = key to live()
                } }) { Text(stringResource(S.importKeyImport)) }
                replacement?.let { (key, session) ->
                    AlertDialog(onDismissRequest = { replacement = null },
                        title = { Text(stringResource(S.regenerateIdentityAlertReplaceTitle)) },
                        text = { Text(stringResource(S.regenerateIdentityAlertReplaceMessage)) },
                        dismissButton = { TextButton(onClick = { replacement = null }) { Text(stringResource(L.commonCancel)) } },
                        confirmButton = { TextButton(onClick = {
                            replacement = null
                            run {
                                if (live() !== session) throw SettingsNotConnectedException()
                                session.settingsService.importPrivateKey(key)
                                session.settingsService.refreshDeviceInfo()
                                text = ""
                                onDone()
                            }
                        }) { Text(stringResource(S.regenerateIdentityAlertReplaceConfirm)) } })
                }
            }
            SettingsExternalDestination.RegionManagement -> {
                var name by remember { mutableStateOf("") }
                var known by remember { mutableStateOf(emptyList<String>()) }
                val connection by container.connectionManager.snapshot.collectAsState()
                val deviceRevision by container.deviceAccess.changes.collectAsState()
                LaunchedEffect(version, connection, deviceRevision) {
                    known = container.appState.connectedDevice?.knownRegions.orEmpty()
                }
                known.forEach { region ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(region)
                        TextButton(enabled = !busy, onClick = { run {
                            val current = live()
                            if (current.token != connection.token) throw SettingsNotConnectedException()
                            removeSettingsKnownRegion(container, current, region)
                        } }) {
                            Text(stringResource(L.commonDelete))
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(S.radioPresetLocation)) })
                Button(enabled = name.isNotBlank() && !busy, onClick = { run {
                    val current = live()
                    if (current.token != connection.token) throw SettingsNotConnectedException()
                    addSettingsKnownRegion(container, current, name.trim())
                    name = ""
                } }) { Text(stringResource(L.commonSave)) }
            }
            SettingsExternalDestination.RemoteCLIContacts -> {
                var contacts by remember { mutableStateOf<List<ContactDTO>?>(null) }
                var loadFailed by remember { mutableStateOf(false) }
                val connection by container.connectionManager.snapshot.collectAsState()
                val contactVersion by container.appState.contactsVersion.collectAsState()
                val owner by container.sessions.currentSessions.collectAsState()
                val radio = container.appState.currentRadioId
                var observation by remember(owner, radio, connection.token) { mutableStateOf<Map<Bytes, Boolean>?>(null) }
                var recoveryRequired by remember(radio) { mutableStateOf<Boolean?>(null) }
                var observedOwner by remember(radio) { mutableStateOf<AppSession?>(null) }
                LaunchedEffect(recoveryRequired) {
                    if (recoveryRequired != true) observation = null
                }
                LaunchedEffect(owner, radio) {
                    if (owner != null && owner?.token?.radioId == radio) {
                        val session = requireNotNull(owner)
                        observedOwner = null
                        try {
                            session.contactService.observeContactWriteRecoveryState(requireNotNull(radio)).collect {
                                recoveryRequired = it is ContactWriteRecoveryState.Required
                                observedOwner = session
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            recoveryRequired = true
                            observedOwner = session
                            feature.report("Could not observe contact recovery", failure)
                        }
                    }
                }
                LaunchedEffect(version, radio, contactVersion, connection.token) {
                    contacts = null
                    loadFailed = false
                    try {
                        contacts = radio?.let { container.processStore.fetchContacts(it) }
                            ?.filter { it.showsRemoteCLI(true) }.orEmpty()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { loadFailed = true; feature.report("Could not load Remote CLI contacts", failure) }
                }
                Text(stringResource(com.meshcoreone.android.core.l10n.R.string.radio_cli_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(com.meshcoreone.android.core.l10n.R.string.radio_cli_permission_explanation))
                val eligible = contacts
                if (owner != null && owner?.token?.radioId == radio && observedOwner !== owner) {
                    CircularProgressIndicator()
                } else if (recoveryRequired == true) {
                    Text(stringResource(com.meshcoreone.android.core.l10n.R.string.radio_cli_recovery),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(
                        enabled = !busy && connection.state.isConnected && owner != null &&
                            owner?.token == connection.token && owner?.token?.radioId == radio &&
                            container.appState.connectedDevice?.supportsRadioCLI == true,
                        onClick = { run {
                            val current = container.sessions.current ?: throw SettingsNotConnectedException()
                            val currentRadio = radio ?: throw SettingsNotConnectedException()
                            if (current !== owner || current.token != connection.token ||
                                current.token.radioId != currentRadio) throw SettingsNotConnectedException()
                            observation = null
                            val result = current.contactService.observeRemoteCLIPermissions(currentRadio)
                            if (container.sessions.current !== current ||
                                container.connectionManager.snapshot.value.token != current.token) throw SettingsNotConnectedException()
                            observation = result
                        } },
                    ) { Text(stringResource(com.meshcoreone.android.core.l10n.R.string.radio_cli_read_radio)) }
                    if (loadFailed) {
                        Text(stringResource(L.commonErrorFailedToLoad), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        TextButton(onClick = { version++ }) {
                            Text(stringResource(com.meshcoreone.android.core.l10n.R.string.radio_cli_retry))
                        }
                    }
                    observation?.let { reported ->
                        Text(stringResource(com.meshcoreone.android.core.l10n.R.string.radio_cli_observation),
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        eligible?.forEach { contact ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(contact.displayName, Modifier.weight(1f))
                                val granted = reported[contact.publicKey]
                                if (granted == null) {
                                    Text(stringResource(com.meshcoreone.android.core.l10n.R.string.radio_cli_not_on_radio))
                                } else {
                                    Switch(granted, onCheckedChange = {}, enabled = false,
                                        modifier = Modifier.semantics { contentDescription = contact.displayName })
                                }
                            }
                        }
                    }
                } else if (loadFailed) {
                    Text(stringResource(L.commonErrorFailedToLoad), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = { version++ }) { Text(stringResource(com.meshcoreone.android.core.l10n.R.string.radio_cli_retry)) }
                } else if (eligible == null) CircularProgressIndicator()
                else if (eligible.isEmpty()) Text(stringResource(com.meshcoreone.android.core.l10n.R.string.radio_cli_empty),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                else eligible.forEach { contact ->
                    val name = contact.displayName
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, Modifier.weight(1f))
                        Switch(
                            checked = contact.flags.toInt() and 0x10 != 0,
                            enabled = !busy && connection.state == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY &&
                                owner != null && owner?.token == connection.token && observedOwner === owner && recoveryRequired == false &&
                                connection.token?.radioId == contact.radioId &&
                                container.appState.connectedDevice?.supportsRadioCLI == true,
                            modifier = Modifier.semantics { contentDescription = name },
                            onCheckedChange = { granted -> run {
                                val current = live()
                                if (current.token != connection.token || current.token.radioId != contact.radioId) throw SettingsNotConnectedException()
                                try { current.contactService.setRemoteCLIAllowed(EntityKey(contact.radioId, contact.id), granted) }
                                finally { version++ }
                            } },
                        )
                    }
                }
            }
            SettingsExternalDestination.BlockedContacts, SettingsExternalDestination.TrustedContacts -> {
                var contacts by remember { mutableStateOf<List<ContactDTO>?>(null) }
                LaunchedEffect(version, container.appState.currentRadioId) {
                    try {
                        contacts = container.appState.currentRadioId?.let { container.processStore.fetchContacts(it) }.orEmpty()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { feature.report("Could not load contact preferences", failure); contacts = emptyList() }
                }
                if (contacts == null) CircularProgressIndicator()
                else contacts.orEmpty().filter {
                    target == SettingsExternalDestination.TrustedContacts || it.isBlocked
                }.forEach { contact ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(contact.displayName)
                        if (target == SettingsExternalDestination.TrustedContacts) {
                            Switch(ContactService.hasTelemetryPermissions(contact.flags), enabled = !busy,
                                onCheckedChange = { granted -> run {
                                    val current = live()
                                    if (current.token.radioId != contact.radioId) throw SettingsNotConnectedException()
                                    current.contactService.setTelemetryPermissions(EntityKey(contact.radioId, contact.id), granted)
                                } })
                        } else TextButton(enabled = !busy, onClick = { run {
                            val current = live()
                            if (current.token.radioId != contact.radioId) throw SettingsNotConnectedException()
                            current.contactService.updateContactPreferences(EntityKey(contact.radioId, contact.id), isBlocked = false)
                        } }) { Text(stringResource(L.commonDelete)) }
                    }
                }
            }
            SettingsExternalDestination.BlockedChannelSenders -> {
                var senders by remember { mutableStateOf<List<BlockedChannelSenderDTO>?>(null) }
                val radio = container.appState.currentRadioId
                LaunchedEffect(version, radio) {
                    try { senders = radio?.let { container.processStore.fetchBlockedChannelSenders(it) }.orEmpty() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { feature.report("Could not load blocked channel senders", failure); senders = emptyList() }
                }
                if (senders == null) CircularProgressIndicator()
                else senders.orEmpty().forEach { sender ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(sender.name)
                        TextButton(enabled = !busy && radio != null, onClick = { run {
                            container.processStore.deleteBlockedChannelSender(requireNotNull(radio), sender.name)
                            container.sessions.current?.takeIf { it.token.radioId == radio }?.syncCoordinator
                                ?.refreshBlockedContactsCache(requireNotNull(radio), container.processStore)
                        } }) { Text(stringResource(L.commonDelete)) }
                    }
                }
            }
            SettingsExternalDestination.AppSystemSettings, SettingsExternalDestination.DeviceSelection,
            SettingsExternalDestination.MapsSettings, SettingsExternalDestination.OfflineMapSettings ->
                error("Platform/navigation destination must be routed before rendering")
        }
        if (busy) CircularProgressIndicator()
    }
}
