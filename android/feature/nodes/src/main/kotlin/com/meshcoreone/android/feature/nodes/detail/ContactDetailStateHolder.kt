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
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val isSavingFavorite: Boolean = false,
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

enum class AvatarSaveStage {
    LAUNCHED,
    STARTED,
    REJECTED_BUSY,
    ENCODE_STARTED,
    ENCODE_RETURNED,
    ENCODE_FAILED,
    ENCODE_CANCELLED,
    SERVICE_LOOKUP_STARTED,
    SERVICE_LOOKUP_RETURNED,
    SERVICE_LOOKUP_FAILED,
    SERVICE_LOOKUP_CANCELLED,
    STORE_LOOKUP_STARTED,
    STORE_LOOKUP_RETURNED,
    STORE_LOOKUP_FAILED,
    STORE_LOOKUP_CANCELLED,
    RADIO_LOOKUP_STARTED,
    RADIO_LOOKUP_RETURNED,
    RADIO_LOOKUP_FAILED,
    RADIO_LOOKUP_CANCELLED,
    CONTACT_LOOKUP_STARTED,
    CONTACT_LOOKUP_RETURNED,
    CONTACT_LOOKUP_FAILED,
    CONTACT_LOOKUP_CANCELLED,
    SERVICE_CALL_STARTED,
    SERVICE_CALL_COMPLETED,
    SERVICE_CALL_FAILED,
    SERVICE_CALL_CANCELLED,
    SERVICE_ENTERED,
    SERVICE_RETURNED,
    SERVICE_FAILED,
    SAVE_SUCCEEDED,
    SAVE_FAILED,
    SAVE_CANCELLED,
    JOB_COMPLETED,
}

enum class AvatarSaveOutcome { PRESENT, ABSENT, MATCHED, MISMATCHED, COMPLETED, FAILED, CANCELLED, REJECTED }

data class AvatarSaveObservation(
    val operationId: UUID,
    val stage: AvatarSaveStage,
    val outcome: AvatarSaveOutcome,
    val failureType: String? = null,
)

/** Synchronous test diagnostic hook; observers must not block or retain media/contact data. */
fun interface AvatarSaveObserver {
    fun onObservation(observation: AvatarSaveObservation)

    companion object {
        val NONE = AvatarSaveObserver {}
    }
}

class AvatarSaveOperationContext internal constructor(
    val operationId: UUID,
    private val observer: AvatarSaveObserver,
) : AbstractCoroutineContextElement(Key) {
    fun observe(
        stage: AvatarSaveStage,
        outcome: AvatarSaveOutcome = AvatarSaveOutcome.COMPLETED,
        failure: Throwable? = null,
    ) {
        observer.onObservation(
            AvatarSaveObservation(operationId, stage, outcome, failure?.javaClass?.simpleName),
        )
    }

    companion object Key : CoroutineContext.Key<AvatarSaveOperationContext>
}

/** Contact detail actions; every radio-changing destructive step goes through [confirm]. */
class ContactDetailStateHolder(
    contact: ContactDTO,
    private val dependencies: NodesFeatureDependencies,
    private val scope: CoroutineScope,
    val showFromDirectChat: Boolean = false,
    private val onClearMessages: () -> Unit = {},
    private val pingHelper: PingHelper = PingHelper(dependencies),
    private var avatarSaveObserver: AvatarSaveObserver = AvatarSaveObserver.NONE,
) {
    private val mutableState = MutableStateFlow(ContactDetailState(contact))
    val state: StateFlow<ContactDetailState> = mutableState.asStateFlow()
    val path = PathManagementStateHolder(dependencies, scope).also { holder ->
        holder.onContactNeedsRefresh = { scope.launch { refreshContact() } }
    }
    private var favoriteJob: Job? = null
    private var favoriteRevision = 0L
    private var nextRefreshRequest = 0L
    private var appliedRefreshRequest = 0L
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
        val refresh = beginContactRefresh()
        val fresh = quietly { session.contactService()?.getContact(current.radioId, current.publicKey) }
        if (fresh != null) applyRefreshedContact(fresh, refresh)
        val advertisements = session.advertisementService() ?: return
        advertisements.events().use { subscription ->
            // Only a response for this contact may resolve this screen's discovery.
            subscription.events.filterIsInstance<NodesAdvertEvent.PathDiscoveryResponse>().collect { event ->
                if (event.path.matches(current.publicKey)) path.handleDiscoveryResponse(event.path.outHopCount)
            }
        }
    }

    fun onDisappear() = path.cancelDiscovery()

    fun setAvatarSaveObserver(observer: AvatarSaveObserver) {
        avatarSaveObserver = observer
    }

    fun setNickname(text: String) = mutableState.update { it.copy(nickname = text) }
    fun beginEditingNickname() = mutableState.update { it.copy(isEditingNickname = true) }
    fun cancelEditingNickname() = mutableState.update {
        it.copy(nickname = it.contact.nickname.orEmpty(), isEditingNickname = false)
    }
    fun clearError() = mutableState.update { it.copy(errorMessage = null) }
    fun cancelConfirmation() = mutableState.update { it.copy(pendingConfirmation = null) }

    /** The favorite toggle; a newer toggle cancels the previous request. */
    fun setFavorite(isFavorite: Boolean) {
        val target = current
        val revision = ++favoriteRevision
        favoriteJob?.cancel()
        mutableState.update { it.copy(isSavingFavorite = true, errorMessage = null) }
        favoriteJob = scope.launch {
            try {
                val service = session.contactService()
                if (service == null || session.currentRadioId() != target.radioId) {
                    if (favoriteRevision == revision) mutableState.update {
                        it.copy(errorMessage = NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_error_servicesunavailable))
                    }
                    return@launch
                }
                service.setContactFavorite(target.key, isFavorite)
                currentCoroutineContext().ensureActive()
                if (favoriteRevision == revision) {
                    if (session.currentRadioId() != target.radioId) {
                        mutableState.update {
                            it.copy(errorMessage = NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_error_servicesunavailable))
                        }
                    } else {
                        mutableState.update {
                            it.copy(isFavorite = isFavorite, contact = it.contact.copy(isFavorite = isFavorite))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (favoriteRevision == revision) mutableState.update { it.copy(errorMessage = failure(error)) }
            } finally {
                if (favoriteRevision == revision) {
                    ++favoriteRevision
                    mutableState.update { it.copy(isSavingFavorite = false) }
                }
            }
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
        val refresh = beginContactRefresh()
        val updated = quietly { session.servicesDataStore()?.fetchContact(current.key) } ?: return
        applyRefreshedContact(updated, refresh)
    }

    private data class ContactRefresh(val request: Long, val favoriteRevision: Long)

    private fun beginContactRefresh() = ContactRefresh(++nextRefreshRequest, favoriteRevision)

    private fun applyRefreshedContact(updated: ContactDTO, refresh: ContactRefresh) {
        if (updated.key != current.key || updated.publicKey != current.publicKey) return
        // Request precedence is screen-local, not a database version. Failed reads never advance it.
        if (refresh.request <= appliedRefreshRequest) return
        appliedRefreshRequest = refresh.request
        mutableState.update {
            if (refresh.favoriteRevision != favoriteRevision || it.isSavingFavorite) {
                it.copy(contact = updated.copy(isFavorite = it.isFavorite))
            } else {
                it.copy(contact = updated, isFavorite = updated.isFavorite)
            }
        }
    }

    suspend fun saveNickname() {
        mutableState.update { it.copy(isSaving = true) }
        reporting {
            session.contactService()?.updateContactPreferences(current.key, nickname = state.value.nickname)
            refreshContact()
        }
        mutableState.update { it.copy(isEditingNickname = false, isSaving = false) }
    }

    fun avatarFailure(error: Exception) {
        mutableState.update { it.copy(errorMessage = failure(error)) }
    }

    fun invalidAvatar() {
        mutableState.update { it.copy(errorMessage = NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_avatar_invalidimage)) }
    }

    /** Encoding is part of the saving operation, before any store mutation. */
    suspend fun saveAvatar(encode: suspend () -> Bytes?): Boolean = updateAvatar {
        encode() ?: throw InvalidAvatar()
    }

    suspend fun saveAvatar(jpeg: Bytes?): Boolean = saveAvatar { jpeg }

    fun launchAvatarSave(encode: suspend () -> Bytes?): Job {
        val operation = AvatarSaveOperationContext(UUID.randomUUID(), avatarSaveObserver)
        operation.observe(AvatarSaveStage.LAUNCHED)
        var result: Boolean? = null
        val job = scope.launch(operation) { result = updateAvatar { encode() ?: throw InvalidAvatar() } }
        job.invokeOnCompletion { failure ->
            val outcome = when {
                failure is CancellationException -> AvatarSaveOutcome.CANCELLED
                failure != null || result == false || result == null -> AvatarSaveOutcome.FAILED
                else -> AvatarSaveOutcome.COMPLETED
            }
            operation.observe(AvatarSaveStage.JOB_COMPLETED, outcome, failure)
        }
        return job
    }

    suspend fun removeAvatar(): Boolean = updateAvatar { null }

    private suspend fun updateAvatar(encode: suspend () -> Bytes?): Boolean {
        val operation = currentCoroutineContext()[AvatarSaveOperationContext]
            ?: AvatarSaveOperationContext(UUID.randomUUID(), avatarSaveObserver).also {
                it.observe(AvatarSaveStage.LAUNCHED)
            }
        operation.observe(AvatarSaveStage.STARTED)
        if (state.value.isSavingAvatar) {
            operation.observe(AvatarSaveStage.REJECTED_BUSY, AvatarSaveOutcome.REJECTED)
            return false
        }
        val target = current
        mutableState.update { it.copy(isSavingAvatar = true, errorMessage = null) }
        return try {
            currentCoroutineContext().ensureActive()
            val imageData = observeAvatarStep(
                operation,
                AvatarSaveStage.ENCODE_STARTED,
                AvatarSaveStage.ENCODE_RETURNED,
                AvatarSaveStage.ENCODE_FAILED,
                AvatarSaveStage.ENCODE_CANCELLED,
                { if (it == null) AvatarSaveOutcome.ABSENT else AvatarSaveOutcome.PRESENT },
                encode,
            )
            currentCoroutineContext().ensureActive()
            val service = observeAvatarStep(
                operation,
                AvatarSaveStage.SERVICE_LOOKUP_STARTED,
                AvatarSaveStage.SERVICE_LOOKUP_RETURNED,
                AvatarSaveStage.SERVICE_LOOKUP_FAILED,
                AvatarSaveStage.SERVICE_LOOKUP_CANCELLED,
                { if (it == null) AvatarSaveOutcome.ABSENT else AvatarSaveOutcome.PRESENT },
            ) { session.contactService() }
            val store = observeAvatarStep(
                operation,
                AvatarSaveStage.STORE_LOOKUP_STARTED,
                AvatarSaveStage.STORE_LOOKUP_RETURNED,
                AvatarSaveStage.STORE_LOOKUP_FAILED,
                AvatarSaveStage.STORE_LOOKUP_CANCELLED,
                { if (it == null) AvatarSaveOutcome.ABSENT else AvatarSaveOutcome.PRESENT },
            ) { session.servicesDataStore() }
            val radioId = observeAvatarStep(
                operation,
                AvatarSaveStage.RADIO_LOOKUP_STARTED,
                AvatarSaveStage.RADIO_LOOKUP_RETURNED,
                AvatarSaveStage.RADIO_LOOKUP_FAILED,
                AvatarSaveStage.RADIO_LOOKUP_CANCELLED,
                { if (it == target.radioId) AvatarSaveOutcome.MATCHED else AvatarSaveOutcome.MISMATCHED },
            ) { session.currentRadioId() }
            if (radioId != target.radioId || service == null || store == null) {
                mutableState.update { it.copy(errorMessage = NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_error_servicesunavailable)) }
                operation.observe(AvatarSaveStage.SAVE_FAILED, AvatarSaveOutcome.FAILED)
                false
            } else {
                val stored = observeAvatarStep(
                    operation,
                    AvatarSaveStage.CONTACT_LOOKUP_STARTED,
                    AvatarSaveStage.CONTACT_LOOKUP_RETURNED,
                    AvatarSaveStage.CONTACT_LOOKUP_FAILED,
                    AvatarSaveStage.CONTACT_LOOKUP_CANCELLED,
                    {
                        when {
                            it == null -> AvatarSaveOutcome.ABSENT
                            it.key == target.key && it.publicKey == target.publicKey -> AvatarSaveOutcome.MATCHED
                            else -> AvatarSaveOutcome.MISMATCHED
                        }
                    },
                ) { store.fetchContact(target.key) }
                if (stored == null || stored.key != target.key || stored.publicKey != target.publicKey) {
                    throw NodesContactFailure.ContactNotFound()
                }
                val currentRadioId = observeAvatarStep(
                    operation,
                    AvatarSaveStage.RADIO_LOOKUP_STARTED,
                    AvatarSaveStage.RADIO_LOOKUP_RETURNED,
                    AvatarSaveStage.RADIO_LOOKUP_FAILED,
                    AvatarSaveStage.RADIO_LOOKUP_CANCELLED,
                    { if (it == target.radioId) AvatarSaveOutcome.MATCHED else AvatarSaveOutcome.MISMATCHED },
                ) { session.currentRadioId() }
                if (currentRadioId != target.radioId) {
                    mutableState.update { it.copy(errorMessage = NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_error_servicesunavailable)) }
                    operation.observe(AvatarSaveStage.SAVE_FAILED, AvatarSaveOutcome.FAILED)
                    return false
                }
                currentCoroutineContext().ensureActive()
                observeAvatarStep(
                    operation,
                    AvatarSaveStage.SERVICE_CALL_STARTED,
                    AvatarSaveStage.SERVICE_CALL_COMPLETED,
                    AvatarSaveStage.SERVICE_CALL_FAILED,
                    AvatarSaveStage.SERVICE_CALL_CANCELLED,
                    { AvatarSaveOutcome.COMPLETED },
                ) { service.updateContactAvatar(target.key, imageData) }
                val refresh = beginContactRefresh()
                val updated = observeAvatarStep(
                    operation,
                    AvatarSaveStage.CONTACT_LOOKUP_STARTED,
                    AvatarSaveStage.CONTACT_LOOKUP_RETURNED,
                    AvatarSaveStage.CONTACT_LOOKUP_FAILED,
                    AvatarSaveStage.CONTACT_LOOKUP_CANCELLED,
                    {
                        when {
                            it == null -> AvatarSaveOutcome.ABSENT
                            it.key == target.key && it.publicKey == target.publicKey -> AvatarSaveOutcome.MATCHED
                            else -> AvatarSaveOutcome.MISMATCHED
                        }
                    },
                ) { store.fetchContact(target.key) } ?: throw NodesContactFailure.ContactNotFound()
                check(updated.key == target.key && updated.publicKey == target.publicKey)
                applyRefreshedContact(updated, refresh)
                operation.observe(AvatarSaveStage.SAVE_SUCCEEDED)
                true
            }
        } catch (error: InvalidAvatar) {
            operation.observe(AvatarSaveStage.SAVE_FAILED, AvatarSaveOutcome.FAILED, error)
            invalidAvatar()
            false
        } catch (cancelled: CancellationException) {
            operation.observe(AvatarSaveStage.SAVE_CANCELLED, AvatarSaveOutcome.CANCELLED, cancelled)
            throw cancelled
        } catch (error: Exception) {
            operation.observe(AvatarSaveStage.SAVE_FAILED, AvatarSaveOutcome.FAILED, error)
            avatarFailure(error)
            false
        } finally {
            mutableState.update { it.copy(isSavingAvatar = false) }
        }
    }

    private suspend fun <T> observeAvatarStep(
        operation: AvatarSaveOperationContext,
        started: AvatarSaveStage,
        returned: AvatarSaveStage,
        failed: AvatarSaveStage,
        cancelled: AvatarSaveStage,
        outcome: (T) -> AvatarSaveOutcome,
        block: suspend () -> T,
    ): T {
        operation.observe(started)
        return try {
            block().also { operation.observe(returned, outcome(it)) }
        } catch (error: CancellationException) {
            operation.observe(cancelled, AvatarSaveOutcome.CANCELLED, error)
            throw error
        } catch (error: Exception) {
            operation.observe(failed, AvatarSaveOutcome.FAILED, error)
            throw error
        }
    }

    private class InvalidAvatar : Exception()

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
