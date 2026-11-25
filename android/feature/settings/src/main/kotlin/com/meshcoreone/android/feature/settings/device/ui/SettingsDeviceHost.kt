// PortedFrom: MC1/Views/Settings/SettingsView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/SettingsDetailView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/ChatSettingsView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/PublicKeyView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/Sections/MessagesSettingsSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.settings.device.ChatPreference
import com.meshcoreone.android.feature.settings.device.ChatSettingsStateHolder
import com.meshcoreone.android.feature.settings.device.SettingsDetail
import com.meshcoreone.android.feature.settings.device.SettingsExternalDestination
import com.meshcoreone.android.feature.settings.device.SettingsHolders
import com.meshcoreone.android.feature.settings.device.SettingsListPresentation
import com.meshcoreone.android.feature.settings.device.SettingsNavigator
import com.meshcoreone.android.feature.settings.device.SettingsSubpage

/**
 * The settings area's in-feature navigation: the list, then one detail page at a time. Pages owned by other work
 * packages (appearance, language, backup, support, feedback) are drawn by [otherPages]; Maps opens the shared maps
 * settings through the navigator.
 */
@Composable
fun SettingsDeviceHost(
    holders: SettingsHolders,
    transport: TransportType?,
    onShareFile: (String) -> Unit,
    onConnect: () -> Unit,
    otherPages: @Composable (SettingsDetail, onDone: () -> Unit) -> Unit,
    versionText: UiText? = null,
) {
    val deps = holders.deps
    val device by deps.connection.connectedDevice.collectAsState()
    var page by rememberSaveable { mutableStateOf<SettingsDetail?>(null) }
    var subpage by remember { mutableStateOf<SettingsSubpage?>(null) }
    LaunchedEffect(device == null) { page = SettingsListPresentation.selectionAfterDeviceChange(page, hasDevice = device != null) }
    val current = page
    LaunchedEffect(current) {
        if (current == SettingsDetail.MAPS) {
            deps.navigator.open(SettingsExternalDestination.MapsSettings)
            page = null
        }
    }
    val back = { subpage = null; page = null }
    val radioHolders = remember(holders) {
        RadioSettingsHolders(holders.radioWriteGate, holders.radioPreset, holders.pathHash, holders.advancedRadio, holders.presetLocationSession,
            deps.regions, deps.radioCatalog, deps.location, deps.subdivisions)
    }
    val advancedHolders = remember(holders) {
        AdvancedSettingsHolders(holders.advancedPage, holders.floodScope, holders.contacts, holders.staleCleanup, holders.telemetry, holders.directMessages,
            holders.batteryCurve, holders.deviceActions, holders.regenerateIdentity, holders.dangerZone, holders.diagnostics, deps.pathFallback)
    }
    val sub = subpage
    when {
        sub is SettingsSubpage.PublicKey -> PublicKeyScreen(sub.publicKey) { subpage = null }
        current == null || current == SettingsDetail.MAPS -> SettingsListScreen(
            device, transport, deps.regions, deps.radioCatalog, { page = it }, onConnect, versionText,
        )
        current == SettingsDetail.DEVICE_INFO -> DeviceInfoScreen(holders.deviceInfo, deps.navigator, { subpage = it }, back)
        current == SettingsDetail.RADIO -> RadioSettingsScreen(radioHolders, deps.navigator, back)
        current == SettingsDetail.LOCATION -> LocationSettingsScreen(holders.location, deps.navigator, back)
        current == SettingsDetail.CONNECTION -> BluetoothSettingsScreen(holders.bluetoothPin, back)
        current == SettingsDetail.ADVANCED -> AdvancedSettingsScreen(
            advancedHolders, deps.navigator, device?.supportsDefaultFloodScope == true, onShareFile, onDismissPage = back, onDone = back,
        )
        current == SettingsDetail.NOTIFICATIONS -> NotificationSettingsScreen(holders.notifications, deps.navigator, back)
        current == SettingsDetail.CHATS -> ChatSettingsScreen(holders.chat, deps.navigator, back)
        else -> otherPages(current, back)
    }
}

@Composable
fun ChatSettingsScreen(holder: ChatSettingsStateHolder, navigator: SettingsNavigator, onDone: (() -> Unit)?) {
    val values by holder.values.collectAsState()
    val on = { preference: ChatPreference -> values[preference] ?: false }
    SettingsScreenFrame(res(AppSettingsStrings.chatSettingsTitle), onDone) {
        SettingsSection(null, listOf(res(AppSettingsStrings.replyWithQuoteFooter))) {
            SwitchRow(res(AppSettingsStrings.replyWithQuoteToggle), on(ChatPreference.REPLY_WITH_QUOTE), true, onChange = { holder.set(ChatPreference.REPLY_WITH_QUOTE, it) })
        }
        SettingsSection(res(AppSettingsStrings.messagesHeader), listOf(res(AppSettingsStrings.messagesFooter))) {
            SwitchRow(res(AppSettingsStrings.messagesShowIncomingPath), on(ChatPreference.SHOW_INCOMING_PATH), true, onChange = { holder.set(ChatPreference.SHOW_INCOMING_PATH, it) })
            SwitchRow(res(AppSettingsStrings.messagesShowIncomingHopCount), on(ChatPreference.SHOW_INCOMING_HOP_COUNT), true, onChange = { holder.set(ChatPreference.SHOW_INCOMING_HOP_COUNT, it) })
            SwitchRow(res(AppSettingsStrings.messagesShowIncomingRegion), on(ChatPreference.SHOW_INCOMING_REGION), true, onChange = { holder.set(ChatPreference.SHOW_INCOMING_REGION, it) })
            SwitchRow(res(AppSettingsStrings.messagesShowIncomingHeardCount), on(ChatPreference.SHOW_INCOMING_HEARD_COUNT), true, onChange = { holder.set(ChatPreference.SHOW_INCOMING_HEARD_COUNT, it) })
        }
        SettingsSection(res(AppSettingsStrings.blockingHeader)) {
            ActionRow(res(com.meshcoreone.android.core.l10n.R.string.text_rules_title), true,
                { navigator.open(SettingsExternalDestination.TextRules) })
            RowDivider()
            ActionRow(res(AppSettingsStrings.blockingChannelSenders), true, { navigator.open(SettingsExternalDestination.BlockedChannelSenders) })
            RowDivider()
            ActionRow(res(AppSettingsStrings.blockingContacts), true, { navigator.open(SettingsExternalDestination.BlockedContacts) })
        }
    }
}

/** Read-only public key with copy: spaced hex for reading, hex without spaces for the clipboard, and base64. */
@Composable
fun PublicKeyScreen(publicKey: Bytes, onDone: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val bytes = publicKey.toByteArray()
    val spaced = bytes.joinToString(" ") { "%02X".format(java.util.Locale.ROOT, it.toInt() and 0xFF) }
    val plain = bytes.joinToString("") { "%02X".format(java.util.Locale.ROOT, it.toInt() and 0xFF) }
    SettingsScreenFrame(res(AppSettingsStrings.publicKeyTitle), onDone) {
        SettingsSection(res(AppSettingsStrings.publicKeyHeader), listOf(res(AppSettingsStrings.publicKeyFooter))) {
            Text(spaced, Modifier.padding(vertical = 8.dp), fontFamily = FontFamily.Monospace)
        }
        SettingsSection(null) {
            ActionRow(res(AppSettingsStrings.publicKeyCopy), true, { clipboard.setText(AnnotatedString(plain)) })
            Text(java.util.Base64.getEncoder().encodeToString(bytes), Modifier.padding(vertical = 8.dp), fontFamily = FontFamily.Monospace)
        }
    }
}
