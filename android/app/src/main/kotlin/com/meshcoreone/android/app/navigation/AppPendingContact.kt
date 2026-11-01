// PortedFrom: MC1/Views/Chats/Sheets/AddContactConfirmationSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/ChatsConversationSheets.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.core.ui.UiFormatArgument
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.nodes.add.ContactAddConfirmation
import com.meshcoreone.android.feature.nodes.deps.NodesContactFailure
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.ScannedContact
import com.meshcoreone.android.feature.nodes.model.localizedNameRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C

internal data class ContactLinkConfirmationState(
    val existing: ContactDTO? = null,
    val loading: Boolean = true,
    val importing: Boolean = false,
    val failure: UiText? = null,
)

internal class ContactLinkConfirmation(
    private val dependencies: NodesFeatureDependencies,
    private val navigation: NavigationCoordinator,
    val link: ContactLinkRequest,
) {
    private val session = dependencies.session
    private val radio = link.targetRadioId ?: session.currentRadioId()
    private val mutableState = MutableStateFlow(ContactLinkConfirmationState())
    private val operation = Mutex()
    val state = mutableState.asStateFlow()

    private fun isCurrent(): Boolean = navigation.state.value.let {
        link.deliveryId != null && it.pendingContactLink?.deliveryId == link.deliveryId && it.generation == link.generation &&
            (radio == null || session.currentRadioId() == radio)
    }

    suspend fun load() {
        if (!operation.tryLock()) return
        mutableState.update { it.copy(loading = true, failure = null) }
        try {
            if (!isCurrent()) return
            val store = session.servicesDataStore() ?: session.offlineDataStore()
            val existing = if (radio != null && store != null) store.fetchContact(radio, link.publicKey) else null
            currentCoroutineContext().ensureActive()
            check(existing == null || existing.radioId == radio && existing.publicKey == link.publicKey) {
                "Contact lookup returned a mismatched identity"
            }
            if (isCurrent()) mutableState.update { it.copy(existing = existing, failure = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report(failure)
        } finally {
            mutableState.update { it.copy(loading = false) }
            operation.unlock()
        }
    }

    suspend fun confirm(): Boolean {
        if (!operation.tryLock()) return false
        return try { confirmCurrent() } finally { operation.unlock() }
    }

    private suspend fun confirmCurrent(): Boolean {
        if (!isCurrent() || state.value.loading || state.value.importing || state.value.failure != null) return false
        state.value.existing?.let { return navigation.completeContactLink(link, it) }
        val isCurrentSession = session.captureSessionValidity()
        val device = session.connectedDevice()
        val service = session.contactService()
        val store = session.servicesDataStore()
        if (!isCurrentSession() || device == null || device.radioId != radio || service == null || store == null ||
            session.connectionState() != DeviceConnectionState.READY
        ) {
            mutableState.update { it.copy(failure = UiText.Resource(C.contactsAddErrorNotConnected)) }
            return false
        }
        mutableState.update { it.copy(importing = true, failure = null) }
        try {
            service.addOrUpdateContact(device.radioId, ContactFrame(
                publicKey = link.publicKey, type = link.type, flags = 0u,
                outPathLength = PacketBuilder.FLOOD_PATH_SENTINEL, outPath = Bytes.EMPTY,
                name = link.name, lastAdvertTimestamp = 0u, latitude = 0.0, longitude = 0.0,
                lastModified = dependencies.clock.wallNow.epochSecond.toUInt(),
            ))
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return false
            if (!isCurrentSession()) {
                mutableState.update { it.copy(failure = UiText.Resource(C.contactsAddErrorNotConnected)) }
                return false
            }
            val added = store.fetchContact(device.radioId, link.publicKey)
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return false
            if (!isCurrentSession()) {
                mutableState.update { it.copy(failure = UiText.Resource(C.contactsAddErrorNotConnected)) }
                return false
            }
            if (added == null) {
                mutableState.update { it.copy(failure = UiText.Resource(C.contactsCommonErrorOccurred)) }
                return false
            }
            check(added.radioId == device.radioId && added.publicKey == link.publicKey) {
                "Imported contact lookup returned a mismatched identity"
            }
            return navigation.completeContactLink(link, added, isCurrentSession)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: NodesContactFailure.ContactTableFull) {
            mutableState.update { it.copy(failure = UiText.Format(
                com.meshcoreone.android.core.l10n.R.string.l10n_app_contacts_contacts_add_error_nodelistfull,
                listOf(UiFormatArgument.Integer(device.maxContacts.toLong())).snapshot(),
            )) }
        } catch (failure: Exception) {
            report(failure)
        } finally {
            mutableState.update { it.copy(importing = false) }
        }
        return false
    }

    private fun report(failure: Exception) {
        java.util.logging.Logger.getLogger("com.mc1.Navigation").log(
            java.util.logging.Level.WARNING, "Contact-link confirmation failed", failure,
        )
        if (isCurrent()) mutableState.update { it.copy(failure = UiErrorMapper().message(failure)) }
    }
}

@Composable
internal fun AppPendingContact(dependencies: NodesFeatureDependencies, navigation: NavigationCoordinator) {
    val pending by navigation.state.collectAsStateWithLifecycle()
    val link = pending.pendingContactLink ?: return
    key(link.deliveryId, pending.generation) {
        val holder = remember(dependencies, link) { ContactLinkConfirmation(dependencies, navigation, link) }
        val state by holder.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var importJob by remember { mutableStateOf<Job?>(null) }
        val dismiss = {
            importJob?.cancel()
            navigation.completeContactLink(link)
            Unit
        }
        LaunchedEffect(holder) { holder.load() }
        val identity = ContactAddConfirmation(
            ScannedContact(link.name, link.publicKey, link.type), state.existing,
            errorMessage = null, isAdding = state.importing, canScanAgain = false,
        )
        AlertDialog(
            modifier = Modifier.testTag("contact-link-confirmation"),
            onDismissRequest = dismiss,
            title = { Text(identity.displayedName, Modifier.testTag("contact-link-name")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(identity.displayedType.localizedNameRes()))
                    identity.scannedAsName?.let {
                        Text(stringResource(com.meshcoreone.android.core.l10n.R.string.l10n_app_contacts_contacts_add_scannedas, it))
                    }
                    Text(stringResource(C.contactsAddPublicKey))
                    Text(identity.publicKeyText, Modifier.testTag("contact-link-public-key"), fontFamily = FontFamily.Monospace)
                    if (state.loading || state.importing) CircularProgressIndicator()
                    state.failure?.let {
                        Text(uiString(it), Modifier.testTag("contact-link-error"), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("contact-link-confirm"),
                    enabled = !state.loading && !state.importing,
                    onClick = {
                        importJob = scope.launch {
                            if (state.failure != null) holder.load() else holder.confirm()
                        }
                    },
                ) { Text(stringResource(if (state.failure != null)
                    com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonTryAgain
                    else if (state.existing != null) C.contactsAddView else C.contactsAddAdd)) }
            },
            dismissButton = {
                TextButton(dismiss, Modifier.testTag("contact-link-cancel")) { Text(stringResource(C.contactsCommonCancel)) }
            },
        )
    }
}
