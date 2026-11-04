// AndroidOnly: WP-201 Local receive attribution is not evidence of radio sender or dispatch identity.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ContactMessage
import java.time.Instant
import java.util.UUID

data class LocalDirectMessageEnvelope(
    val occurrenceID: UUID,
    val session: SessionToken,
    val message: ContactMessage,
    val context: DeliveryContext,
    val resolution: LocalDirectContactResolution,
)

/** Separate opt-in receive callback. Legacy CLI, signed and contact callbacks are unchanged. */
interface LocalDirectMessagePolling {
    suspend fun setLocalDirectMessageHandler(handler: suspend (LocalDirectMessageEnvelope) -> Unit)
}

sealed interface LocalDirectContactResolution {
    data object NotFound : LocalDirectContactResolution
    data object Ambiguous : LocalDirectContactResolution
    data object Stale : LocalDirectContactResolution

    /** Process-local observation, invalidated by roster writes, including delete/reinsert ABA. */
    data class Unique(val contact: ContactDTO, val rosterRevision: UUID) : LocalDirectContactResolution
}

data class LocalDirectMessageSaveResult(
    val message: MessageDTO,
    val resolution: LocalDirectContactResolution,
    val inserted: Boolean,
)

/** Durable local bookkeeping in the existing backup-compatible deduplication field, never RF proof. */
enum class LocalDirectMessageProvenance {
    UNRESOLVED,
    LOCAL_CANDIDATE;

    fun occurrenceKey(id: UUID): String = when (this) {
        UNRESOLVED -> "local-direct-unresolved:$id"
        LOCAL_CANDIDATE -> "local-direct-candidate:$id"
    }

    companion object {
        fun of(message: MessageDTO): LocalDirectMessageProvenance? = when (message.deduplicationKey) {
            UNRESOLVED.occurrenceKey(message.id) -> UNRESOLVED
            LOCAL_CANDIDATE.occurrenceKey(message.id) -> LOCAL_CANDIDATE
            else -> null
        }
    }
}

/** Valid only in its owning callback/Job; it must not escape or be used by a child coroutine. */
interface LocalDirectContactAdmission {
    val resolution: LocalDirectContactResolution
    suspend fun saveIncomingDirectMessage(dto: MessageDTO): LocalDirectMessageSaveResult
}

interface LocalDirectContactPersisting {
    /** Six-byte receive prefixes only. Storage failures throw; they are never NotFound. */
    suspend fun classifyLocalDirectContact(radioId: RadioId, prefix: Bytes): LocalDirectContactResolution

    /**
     * Fences supported local roster writes, not autonomous radio state. No Room transaction is held
     * across [block]. Reads, contact metadata and message/reaction writes may reenter the store.
     * Roster writes, backup restore and channel operations must not be invoked inside this scope.
     * Lock order for composite mutations is channel operation, local roster, then Room.
     */
    suspend fun <T> withLocalDirectContactOperation(
        radioId: RadioId,
        prefix: Bytes,
        observed: LocalDirectContactResolution,
        block: suspend (LocalDirectContactAdmission) -> T,
    ): T

    suspend fun saveIncomingDirectMessage(
        dto: MessageDTO,
        observed: LocalDirectContactResolution,
    ): LocalDirectMessageSaveResult

    suspend fun applyLocalDirectPathIfCurrent(
        radioId: RadioId,
        prefix: Bytes,
        observed: LocalDirectContactResolution,
        outPathLength: UByte,
        outPath: Bytes,
        heardAt: Instant,
    ): LocalDirectContactResolution

    /** Conditional local insertion only; callers still own pending-advert admission/revalidation. */
    suspend fun materializeLocalDirectContactIfAbsent(
        radioId: RadioId,
        prefix: Bytes,
        frame: ContactFrame,
        heardAt: Instant,
    ): LocalDirectContactResolution
}
