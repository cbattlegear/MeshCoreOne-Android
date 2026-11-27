// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomConversationView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomConversationViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/ViewModels/RoomConversationViewModelOrderingTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.rooms

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RoomMessageRow(
    val message: RoomMessageDTO,
    val showTimestamp: Boolean,
    val showSenderName: Boolean,
    val showAvatar: Boolean,
)

data class RoomConversationState(
    val session: RemoteNodeSessionDTO,
    val messages: List<RoomMessageDTO> = emptyList(),
    val rows: List<RoomMessageRow> = emptyList(),
    val draft: String = "",
    val isLoading: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val isSending: Boolean = false,
    val isAuthenticating: Boolean = false,
    val authenticationContact: ContactDTO? = null,
    val authenticationLookupComplete: Boolean = false,
    /** The remembered password offered to the sheet; null when none is stored. */
    val savedPassword: String? = null,
    /** What the running login is waiting for; null while the radio has not accepted it yet. */
    val loginProgress: RoomLoginProgress? = null,
    /** When the running login began ([RoomConversationStateHolder] clock), for the elapsed-time readout. */
    val authenticationStartedAtMillis: Long? = null,
    val failure: Throwable? = null,
)

class RoomConversationStateHolder(
    session: RemoteNodeSessionDTO,
    private val dependencies: RoomConversationDependencies,
    private val scope: CoroutineScope,
    private val awaitReloadDebounce: suspend () -> Unit = { delay(RELOAD_DEBOUNCE) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(RoomConversationState(session))
    val state: StateFlow<RoomConversationState> = mutableState

    private val operation = Mutex()
    private val authentication = Mutex()
    private var eventsJob: Job? = null
    private var reloadJob: Job? = null
    private var generation = 0L

    suspend fun keepAliveWhileVisible() {
        combine(dependencies.keepAlive, state) { source, current -> source to current.session }
            .distinctUntilChanged { old, new ->
                old.first === new.first && old.second.id == new.second.id &&
                    old.second.radioId == new.second.radioId && old.second.publicKey == new.second.publicKey &&
                    old.second.isConnected == new.second.isConnected && old.second.isRoom == new.second.isRoom
            }
            .collectLatest { (source, session) ->
                if (!session.isConnected || !session.isRoom) return@collectLatest
                val lease = source?.acquire(session) ?: return@collectLatest
                try {
                    awaitCancellation()
                } finally {
                    lease.close()
                }
            }
    }

    fun start() {
        if (eventsJob != null) return
        eventsJob = scope.launch {
            dependencies.events.collect { event ->
                val session = mutableState.value.session
                if (event.sessionId != session.id) return@collect
                when (event) {
                    is RoomConversationEvent.MessageReceived -> {
                        appendIfNew(event.message)
                        scheduleReload()
                    }
                    is RoomConversationEvent.MessageChanged -> {
                        if (mutableState.value.messages.any { it.id == event.messageId }) scheduleReload()
                    }
                    is RoomConversationEvent.MessageFailed -> {
                        if (mutableState.value.messages.none { it.id == event.messageId }) return@collect
                        scheduleReload()
                        try {
                            dependencies.data.markFailedSendsSeen(session)
                            dependencies.conversationsChanged()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            dependencies.diagnostics.report("mark room failed sends seen", failure)
                        }
                    }
                    is RoomConversationEvent.ConnectionRecovered -> mutableState.update {
                        it.copy(session = event.session, failure = null)
                    }
                }
            }
        }
    }

    suspend fun load() {
        val loadGeneration = ++generation
        mutableState.update { it.copy(isLoading = true, failure = null) }
        try {
            operation.withLock {
                val session = mutableState.value.session
                val messages = dependencies.data.fetchMessages(session)
                    .distinctBy(RoomMessageDTO::id)
                    .sortedWith(compareBy<RoomMessageDTO> { it.timestamp }.thenBy { it.createdAt }.thenBy { it.id })
                if (loadGeneration != generation) return@withLock
                mutableState.update {
                    it.copy(messages = messages, rows = rows(messages), isLoading = false, hasLoadedOnce = true)
                }
                dependencies.data.markAsRead(session)
                dependencies.data.markFailedSendsSeen(session)
                // A mounted read is not a durable OPEN receipt, so retain content taps for retry.
                dependencies.updateBadgeCount()
                dependencies.conversationsChanged()
            }
        } catch (cancelled: CancellationException) {
            if (loadGeneration == generation) mutableState.update { it.copy(isLoading = false) }
            throw cancelled
        } catch (failure: Exception) {
            if (loadGeneration == generation) {
                mutableState.update { it.copy(isLoading = false, hasLoadedOnce = true, failure = failure) }
            }
            dependencies.diagnostics.report("load room", failure)
        }
    }

    suspend fun refreshSession() {
        try {
            dependencies.data.refreshSession(mutableState.value.session)?.let { refreshed ->
                mutableState.update { it.copy(session = refreshed) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            dependencies.diagnostics.report("refresh room session", failure)
        }
    }

    suspend fun prepareAuthentication() {
        mutableState.update { it.copy(authenticationContact = null, authenticationLookupComplete = false, failure = null) }
        try {
            val session = mutableState.value.session
            val contact = dependencies.data.contactFor(session)
            currentCoroutineContext().ensureActive()
            if (contact != null && (contact.radioId != session.radioId || contact.publicKey != session.publicKey)) {
                throw PersistenceStoreException(PersistenceStoreError.InvalidData)
            }
            val saved = contact?.let { readSavedPassword(it) }
            mutableState.update {
                it.copy(authenticationContact = contact, authenticationLookupComplete = true, savedPassword = saved)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(authenticationLookupComplete = true, failure = failure) }
            dependencies.diagnostics.report("find room contact", failure)
        }
    }

    /** A storage failure reads as "nothing saved", like Swift's `try?` lookup; the sheet then starts empty. */
    private suspend fun readSavedPassword(contact: ContactDTO): String? = try {
        dependencies.service.savedPassword(contact)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        dependencies.diagnostics.report("read saved room password", failure)
        null
    }

    suspend fun authenticate(password: String?, rememberPassword: Boolean): Boolean {
        if (authenticateForNavigation(password, rememberPassword) == null) return false
        mutableState.update { it.copy(authenticationContact = null) }
        load()
        return true
    }

    /**
     * Logs in with [password] as typed: an empty string is a guest login (rooms without a password), `null` falls back
     * to the stored password, and anything past [MAX_PASSWORD_LENGTH] characters is dropped like the node would.
     */
    suspend fun authenticateForNavigation(password: String?, rememberPassword: Boolean): RemoteNodeSessionDTO? {
        if (!authentication.tryLock()) return null
        try {
            val current = mutableState.value
            val contact = current.authenticationContact ?: return null
            mutableState.update {
                it.copy(
                    isAuthenticating = true, failure = null, loginProgress = null,
                    authenticationStartedAtMillis = nowMillis(),
                )
            }
            try {
                val joined = dependencies.service.join(contact, password?.let { truncatedPassword(it.filterNot { c -> c == '\n' || c == '\r' }) }, rememberPassword) { progress ->
                    mutableState.update { it.copy(loginProgress = progress) }
                }
                currentCoroutineContext().ensureActive()
                if (joined.radioId != current.session.radioId || joined.publicKey != current.session.publicKey ||
                    !joined.isRoom
                ) throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                if (!joined.isConnected) throw MessageServiceException(MessageServiceError.NotConnected)
                forgetSavedPasswordIfUnchecked(contact, current.savedPassword != null, rememberPassword)
                mutableState.update {
                    it.copy(session = joined, isAuthenticating = false, loginProgress = null, authenticationStartedAtMillis = null)
                }
                return joined
            } catch (cancelled: CancellationException) {
                mutableState.update {
                    it.copy(isAuthenticating = false, loginProgress = null, authenticationStartedAtMillis = null)
                }
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update {
                    it.copy(
                        isAuthenticating = false, failure = failure, loginProgress = null,
                        authenticationStartedAtMillis = null,
                    )
                }
                dependencies.diagnostics.report("authenticate room", failure)
                return null
            }
        } finally {
            authentication.unlock()
        }
    }

    /** Swift deletes the saved password when the user unchecks "Remember Password" and the login succeeds. */
    private suspend fun forgetSavedPasswordIfUnchecked(contact: ContactDTO, hadSaved: Boolean, remember: Boolean) {
        if (!hadSaved || remember) return
        try {
            dependencies.service.forgetPassword(contact)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            dependencies.diagnostics.report("forget saved room password", failure)
        }
    }

    fun reportAuthenticationRetired() {
        val failure = MessageServiceException(MessageServiceError.NotConnected)
        mutableState.update { it.copy(failure = failure) }
        dependencies.diagnostics.report("complete room authentication", failure)
    }

    suspend fun reconnect() {
        mutableState.update { it.copy(isAuthenticating = true, failure = null) }
        try {
            val connected = dependencies.service.reconnect(mutableState.value.session)
            mutableState.update { it.copy(session = connected, isAuthenticating = false) }
            load()
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(isAuthenticating = false) }
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(isAuthenticating = false, failure = failure) }
            dependencies.diagnostics.report("reconnect room", failure)
        }
    }

    fun updateDraft(value: String) = mutableState.update { it.copy(draft = value) }

    suspend fun send(): Boolean {
        val current = mutableState.value
        if (!current.session.canPost || !current.session.isConnected) return false
        return post(current.draft, clearDraft = true)
    }

    suspend fun sendAgain(message: RoomMessageDTO): Boolean {
        if (!message.isFromSelf || message.sessionID != mutableState.value.session.id) return false
        return post(message.text, clearDraft = false)
    }

    private suspend fun post(text: String, clearDraft: Boolean): Boolean {
        val current = mutableState.value
        if (text.isEmpty() || current.isSending) return false
        mutableState.update { it.copy(isSending = true, failure = null) }
        return try {
            val message = dependencies.service.post(current.session, text)
            appendIfNew(message)
            mutableState.update {
                it.copy(draft = if (clearDraft && it.draft == text) "" else it.draft, isSending = false)
            }
            if (mutableState.value.isLoading) scheduleReload()
            true
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(isSending = false) }
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(isSending = false, failure = failure) }
            dependencies.diagnostics.report("send room message", failure)
            false
        }
    }

    suspend fun retry(messageId: UUID) {
        try {
            val updated = dependencies.service.retry(mutableState.value.session, messageId)
            mutableState.update { current ->
                val messages = current.messages.map { if (it.id == messageId) updated else it }
                current.copy(messages = messages, rows = rows(messages), failure = null)
            }
            if (mutableState.value.isLoading) scheduleReload()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(failure = failure) }
            dependencies.diagnostics.report("retry room message", failure)
        }
    }

    fun clearFailure() = mutableState.update { it.copy(failure = null) }

    fun reportLinkFailure(failure: Exception) {
        mutableState.update { it.copy(failure = failure) }
        dependencies.diagnostics.report("load room link contacts", failure)
    }

    fun reportActionFailure(failure: Exception) {
        mutableState.update { it.copy(failure = failure) }
        dependencies.diagnostics.report("room message action", failure)
    }

    private fun appendIfNew(message: RoomMessageDTO) {
        mutableState.update { current ->
            if (current.messages.any { it.id == message.id }) return@update current
            val insertion = current.messages.indexOfFirst { it.timestamp > message.timestamp }
                .let { if (it < 0) current.messages.size else it }
            val messages = current.messages.toMutableList().apply { add(insertion, message) }
            current.copy(messages = messages, rows = rows(messages))
        }
    }

    private fun scheduleReload() {
        // Events invalidate snapshots already being fetched, even within a coalesced burst.
        generation++
        if (reloadJob?.isActive == true) return
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            try {
                awaitReloadDebounce()
                reloadJob = null
                load()
            } finally {
                if (reloadJob === coroutineContext[Job]) reloadJob = null
            }
        }
        reloadJob = pending
        pending.start()
    }

    companion object {
        const val GROUPING_GAP_SECONDS = 300L

        /** MeshCore rooms accept 15-character passwords (Swift `NodeAuthenticationSheet.maxPasswordLength`). */
        const val MAX_PASSWORD_LENGTH = 15

        private val GRAPHEME = java.util.regex.Pattern.compile("\\X")

        /** Swift `Character` count: extended grapheme clusters, so an emoji with a skin tone counts once. */
        fun characterCount(text: String): Int {
            val matcher = GRAPHEME.matcher(text)
            var count = 0
            while (matcher.find()) count += 1
            return count
        }

        /** The first [MAX_PASSWORD_LENGTH] characters of [password]. */
        fun truncatedPassword(password: String): String {
            val matcher = GRAPHEME.matcher(password)
            var end = 0
            var count = 0
            while (count < MAX_PASSWORD_LENGTH && matcher.find()) {
                end = matcher.end()
                count += 1
            }
            return if (matcher.find()) password.substring(0, end) else password
        }
        private val RELOAD_DEBOUNCE = 50.milliseconds

        fun rows(messages: List<RoomMessageDTO>): List<RoomMessageRow> = messages.mapIndexed { index, message ->
            val previous = messages.getOrNull(index - 1)
            val next = messages.getOrNull(index + 1)
            val continuesFromPrevious = previous?.let { incomingClusterContinues(it, message) } == true
            val continuesToNext = next?.let { incomingClusterContinues(message, it) } == true
            val gap = previous?.let { kotlin.math.abs(message.timestamp.toLong() - it.timestamp.toLong()) }
            RoomMessageRow(
                message = message,
                showTimestamp = previous == null || gap!! > GROUPING_GAP_SECONDS,
                showSenderName = !message.isFromSelf && !continuesFromPrevious,
                showAvatar = !message.isFromSelf && !continuesToNext,
            )
        }

        fun incomingClusterContinues(earlier: RoomMessageDTO, later: RoomMessageDTO): Boolean =
            !earlier.isFromSelf && !later.isFromSelf &&
                kotlin.math.abs(later.timestamp.toLong() - earlier.timestamp.toLong()) <= GROUPING_GAP_SECONDS &&
                earlier.authorKeyPrefix == later.authorKeyPrefix
    }
}
