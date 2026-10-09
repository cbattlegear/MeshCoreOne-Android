// PortedFrom: MC1/Views/Contacts/ContactDetailView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.detail

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.nodes.contacts.key
import com.meshcoreone.android.feature.nodes.deps.NodesAdvertEvent
import com.meshcoreone.android.feature.nodes.deps.NodesContactFailure
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.feature.nodes.model.localizedNameRes
import com.meshcoreone.android.feature.nodes.path.PathManagementStateHolder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A destructive or radio-changing action awaiting explicit confirmation. */
sealed interface DetailConfirmation {
    val title: NodesMessage
    val message: NodesMessage

    data class Block(val name: String) : DetailConfirmation {
        override val title get() = NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_alert_block_title)
        override val message get() = NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_alert_block_message, name)
    }

    data class Delete(val name: String, val typeName: NodesMessage) : DetailConfirmation {
        override val title get() = NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_alert_delete_title, typeName)
        override val message get() = NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_alert_delete_message, name)
    }

    data class ClearMessages(val name: String) : DetailConfirmation {
        override val title get() = NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_alert_clearmessages_title)
        override val message get() = NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_alert_clearmessages_message, name)
    }
}

data class ContactDetailState(
    val contact: ContactDTO,
    val nickname: String = contact.nickname.orEmpty(),
    val isEditingNickname: Boolean = false,
    val isSaving: Boolean = false,
    val isFavorite: Boolean = contact.isFavorite,
    val pendingConfirmation: DetailConfirmation? = null,
    val isClearingMessages: Boolean = false,
    val errorMessage: NodesMessage? = null,
    val isPinging: Boolean = false,
    val pingResult: PingResult? = null,
    val isSharing: Boolean = false,
    val showShareSuccess: Boolean = false,
    val isSavingAvatar: Boolean = false,
) {
    val contactTypeLabel: NodesMessage get() = NodesMessage.res(contact.type.localizedNameRes())
}

/** Contact detail actions; every radio-changing destructive step goes through [confirm]. */
class ContactDetailStateHolder(
    contact: ContactDTO,
    private val dependencies: NodesFeatureDependencies,
    private val scope: CoroutineScope,
    val showFromDirectChat: Boolean = false,
    private val onClearMessages: () -> Unit = {},
    private val pingHelper: PingHelper = PingHelper(dependencies),
) {
    private val mutableState = MutableStateFlow(ContactDetailState(contact))
    val state: StateFlow<ContactDetailState> = mutableState.asStateFlow()
    val path = PathManagementStateHolder(dependencies, scope).also { holder ->
        holder.onContactNeedsRefresh = { scope.launch { refreshContact() } }
    }
    private var favoriteJob: Job? = null
    private val session get() = dependencies.session
    private val current get() = state.value.contact

    /** The ZephCore V-contact cannot be removed (it would turn off the firmware admin CLI). */
    val isVContact: Boolean
        get() = session.connectedDevice()?.publicKey?.let { VContactIdentity.isVContact(current.publicKey, it) } ?: false

    /**
     * Screen lifetime work: load path resolution data, pick up external changes from the radio, then
     * resolve this contact's path-discovery pushes until cancelled.
     */
    suspend fun run() {
        path.loadContacts(current.radioId)
        val fresh = quietly { session.contactService()?.getContact(current.radioId, current.publicKey) }
        if (fresh != null) mutableState.update { it.copy(contact = fresh) }
        val advertisements = session.advertisementService() ?: return
        advertisements.events().use { subscription ->
            // Only a response for this contact may resolve this screen's discovery.
            subscription.events.filterIsInstance<NodesAdvertEvent.PathDiscoveryResponse>().collect { event ->
                if (event.path.matches(current.publicKey)) path.handleDiscoveryResponse(event.path.outHopCount)
            }
        }
    }

    fun onDisappear() = path.cancelDiscovery()

    fun setNickname(text: String) = mutableState.update { it.copy(nickname = text) }
    fun beginEditingNickname() = mutableState.update { it.copy(isEditingNickname = true) }
    fun cancelEditingNickname() = mutableState.update {
        it.copy(nickname = it.contact.nickname.orEmpty(), isEditingNickname = false)
    }
    fun clearError() = mutableState.update { it.copy(errorMessage = null) }
    fun cancelConfirmation() = mutableState.update { it.copy(pendingConfirmation = null) }

    /** The favorite toggle; a newer toggle cancels the previous request. */
    fun setFavorite(isFavorite: Boolean) {
        mutableState.update { it.copy(isFavorite = isFavorite) }
        favoriteJob?.cancel()
        favoriteJob = scope.launch {
            reporting { session.contactService()?.setContactFavorite(current.key, isFavorite) }
        }
    }

    /** Blocking asks first; unblocking runs immediately. */
    suspend fun requestToggleBlock() {
        if (current.isBlocked) toggleBlocked() else mutableState.update { it.copy(pendingConfirmation = DetailConfirmation.Block(current.displayName)) }
    }

    fun requestDelete() = mutableState.update {
        it.copy(pendingConfirmation = DetailConfirmation.Delete(current.displayName, it.contactTypeLabel))
    }

    fun requestClearMessages() = mutableState.update { it.copy(pendingConfirmation = DetailConfirmation.ClearMessages(current.displayName)) }

    /** Runs the confirmed action. Returns true when the screen should dismiss. */
    suspend fun confirm(): Boolean {
        val pending = state.value.pendingConfirmation ?: return false
        mutableState.update { it.copy(pendingConfirmation = null) }
        return when (pending) {
            is DetailConfirmation.Block -> { toggleBlocked(); false }
            is DetailConfirmation.Delete -> deleteContact()
            is DetailConfirmation.ClearMessages -> clearMessages()
        }
    }

    private suspend fun toggleBlocked() = reporting {
        session.contactService()?.updateContactPreferences(current.key, isBlocked = !current.isBlocked)
        refreshContact()
    }

    private suspend fun deleteContact(): Boolean {
        val contactService = session.contactService() ?: run {
            mutableState.update { it.copy(errorMessage = NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_error_servicesunavailable)) }
            return false
        }
        return reporting { contactService.removeContact(current.radioId, current.publicKey) }
    }

    private suspend fun clearMessages(): Boolean {
        val contactService = session.contactService() ?: run {
            mutableState.update { it.copy(errorMessage = NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_error_servicesunavailable)) }
            return false
        }
        mutableState.update { it.copy(isClearingMessages = true, errorMessage = null) }
        val cleared = reporting {
            contactService.clearContactMessages(current.key)
            session.notificationCleanup()?.removeDeliveredNotifications(current.id)
            session.notificationCleanup()?.updateBadgeCount()
            onClearMessages()
        }
        if (!cleared) mutableState.update { it.copy(isClearingMessages = false) }
        return cleared
    }

    /** Share via a flood advert; success shows a check briefly. */
    suspend fun shareViaAdvert() {
        mutableState.update { it.copy(isSharing = true) }
        try {
            session.contactService()?.shareContact(current.publicKey)
            mutableState.update { it.copy(isSharing = false, showShareSuccess = true) }
            dependencies.clock.sleep(SHARE_SUCCESS_DURATION)
            mutableState.update { it.copy(showShareSuccess = false) }
        } catch (_: NodesContactFailure.ShareContactUnavailable) {
            mutableState.update { it.copy(isSharing = false, errorMessage = NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_sharecontactunavailable)) }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(isSharing = false, showShareSuccess = false) }
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(isSharing = false, errorMessage = failure(error)) }
        }
    }

    suspend fun pingRepeater() {
        if (state.value.isPinging) return
        mutableState.update { it.copy(isPinging = true, pingResult = null) }
        try {
            val result = pingHelper.zeroHopPing(current)
            mutableState.update { it.copy(pingResult = result) }
        } finally {
            mutableState.update { it.copy(isPinging = false) }
        }
    }

    suspend fun refreshContact() {
        val updated = quietly { session.servicesDataStore()?.fetchContact(current.key) } ?: return
        mutableState.update { it.copy(contact = updated) }
    }

    suspend fun saveNickname() {
        mutableState.update { it.copy(isSaving = true) }
        reporting {
            session.contactService()?.updateContactPreferences(current.key, nickname = state.value.nickname)
            refreshContact()
        }
        mutableState.update { it.copy(isEditingNickname = false, isSaving = false) }
    }

    /** Stores an already re-encoded avatar ([AvatarProcessing]); null means the image could not be decoded. */
    suspend fun saveAvatar(jpeg: Bytes?) {
        mutableState.update { it.copy(isSavingAvatar = true) }
        if (jpeg == null) {
            mutableState.update { it.copy(errorMessage = NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_avatar_invalidimage), isSavingAvatar = false) }
            return
        }
        updateAvatar(jpeg)
    }

    suspend fun removeAvatar() {
        mutableState.update { it.copy(isSavingAvatar = true) }
        updateAvatar(null)
    }

    private suspend fun updateAvatar(imageData: Bytes?) {
        reporting {
            session.contactService()?.updateContactAvatar(current.key, imageData)
            refreshContact()
        }
        mutableState.update { it.copy(isSavingAvatar = false) }
    }

    /** Runs [block], reporting a failure as the error message; true when it completed. */
    private suspend fun reporting(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        mutableState.update { it.copy(errorMessage = failure(error)) }
        false
    }

    /** `try?`: a failure yields null; cancellation still propagates. */
    private suspend fun <T> quietly(block: suspend () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun failure(error: Throwable) = NodesMessage.Text(dependencies.messages.message(error))

    private companion object {
        val SHARE_SUCCESS_DURATION = 1500.milliseconds
    }
}
