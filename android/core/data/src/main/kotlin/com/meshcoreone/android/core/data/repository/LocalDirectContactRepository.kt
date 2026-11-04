// AndroidOnly: WP-202 Atomic local receive attribution; deliberately not RF-origin authority.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * All supported membership/identity writers, including device clear and restore, join this gate.
 * The revision is intentionally conservative and process-local: failed writes invalidate snapshots
 * too. A snapshot from a previous process cannot survive the newly generated revision.
 */
internal object LocalDirectRoster {
    private val mutex = Mutex()
    var revision: UUID = UUID.randomUUID()
        private set

    suspend fun <T> read(block: suspend () -> T): T =
        mutex.withLock(checkNotNull(currentCoroutineContext()[Job])) { block() }

    suspend fun <T> mutate(block: suspend () -> T): T = mutex.withLock(checkNotNull(currentCoroutineContext()[Job])) {
        invalidate()
        block()
    }

    fun invalidate() { revision = UUID.randomUUID() }
}

internal class LocalDirectContactRepository(
    private val context: RoomRepositoryContext,
    private val discovered: DiscoveredNodeRepository,
) : LocalDirectContactPersisting {
    override suspend fun classifyLocalDirectContact(
        radioId: RadioId, prefix: Bytes,
    ): LocalDirectContactResolution = LocalDirectRoster.read {
        context.read("classifyLocalDirectContact") { classify(radioId, prefix) }
    }

    override suspend fun <T> withLocalDirectContactOperation(
        radioId: RadioId,
        prefix: Bytes,
        observed: LocalDirectContactResolution,
        block: suspend (LocalDirectContactAdmission) -> T,
    ): T = LocalDirectRoster.read {
        val resolution = context.read("admitLocalDirectContact") { revalidate(radioId, prefix, observed) }
        val owner = checkNotNull(currentCoroutineContext()[Job])
        val admission = Admission(radioId, prefix, resolution, owner)
        try {
            block(admission)
        } finally {
            admission.close()
        }
    }

    override suspend fun saveIncomingDirectMessage(
        dto: MessageDTO, observed: LocalDirectContactResolution,
    ): LocalDirectMessageSaveResult = withLocalDirectContactOperation(
        dto.radioId, dto.senderKeyPrefix ?: invalidData("Incoming direct message requires a sender prefix"), observed,
    ) { it.saveIncomingDirectMessage(dto) }

    private inner class Admission(
        private val radioId: RadioId,
        private val prefix: Bytes,
        override val resolution: LocalDirectContactResolution,
        private val owner: Job,
    ) : LocalDirectContactAdmission {
        private var active = true
        fun close() { active = false }

        override suspend fun saveIncomingDirectMessage(dto: MessageDTO): LocalDirectMessageSaveResult {
            check(active && currentCoroutineContext()[Job] === owner) {
                "Local direct admission is expired or belongs to another Job"
            }
            return context.write("saveIncomingDirectMessage") {
                if (dto.radioId != radioId || dto.senderKeyPrefix != prefix || dto.channelIndex != null ||
                    dto.direction != MessageDirection.INCOMING || dto.channelOccupantID != null
                ) invalidData("Incoming direct message does not match its local admission")
                val final = revalidate(radioId, prefix, resolution)
                val contact = (final as? LocalDirectContactResolution.Unique)?.contact
                val provenance = if (contact == null) LocalDirectMessageProvenance.UNRESOLVED
                    else LocalDirectMessageProvenance.LOCAL_CANDIDATE
                val dedup = provenance.occurrenceKey(dto.id)
                val saved = dto.copy(contactID = contact?.id, deduplicationKey = dedup)
                val existing = database.messages().byId(radioId.value, dto.id)
                if (existing != null) {
                    val old = existing.toDTO()
                    if (old.direction != MessageDirection.INCOMING || old.channelIndex != null ||
                        old.text != dto.text || old.senderKeyPrefix != prefix ||
                        (old.senderTimestamp ?: old.timestamp) != (dto.senderTimestamp ?: dto.timestamp)
                    ) invalidData("Incoming direct occurrence conflicts with stored content")
                    // Retry by occurrence never overwrites an earlier committed attribution decision.
                    val oldResolution = if (old.contactID == contact?.id) final else LocalDirectContactResolution.Stale
                    return@write LocalDirectMessageSaveResult(old, oldResolution, false)
                }
                database.messages().insert(saved.toEntity())
                save()
                LocalDirectMessageSaveResult(saved, final, true)
            }
        }
    }

    override suspend fun applyLocalDirectPathIfCurrent(
        radioId: RadioId,
        prefix: Bytes,
        observed: LocalDirectContactResolution,
        outPathLength: UByte,
        outPath: Bytes,
        heardAt: Instant,
    ): LocalDirectContactResolution = LocalDirectRoster.read {
        context.write("applyLocalDirectPathIfCurrent") {
            val current = revalidate(radioId, prefix, observed)
            if (current !is LocalDirectContactResolution.Unique) return@write current
            val row = requireNotNull(database.contacts().byId(radioId.value, current.contact.id))
            val updated = row.copy(outPathLength = outPathLength.toLong(), outPath = outPath)
            val contact = stampHeard(updated, radioId, heardAt)
            save()
            LocalDirectContactResolution.Unique(contact, LocalDirectRoster.revision)
        }
    }

    override suspend fun materializeLocalDirectContactIfAbsent(
        radioId: RadioId, prefix: Bytes, frame: ContactFrame, heardAt: Instant,
    ): LocalDirectContactResolution = LocalDirectRoster.read {
        context.write("materializeLocalDirectContactIfAbsent") {
            if (frame.publicKey.size != 32 || !frame.publicKey.startsWith(prefix)) {
                invalidData("Materialized contact key does not match the requested prefix")
            }
            val current = classify(radioId, prefix)
            if (current != LocalDirectContactResolution.NotFound) return@write current
            LocalDirectRoster.invalidate()
            val row = ContactDTO.fromFrame(radioId, frame).toEntity()
            database.contacts().insert(row)
            val contact = stampHeard(row, radioId, heardAt)
            save()
            LocalDirectContactResolution.Unique(contact, LocalDirectRoster.revision)
        }
    }

    private suspend fun RepositoryTransaction.stampHeard(
        row: ContactEntity, radioId: RadioId, heardAt: Instant,
    ): ContactDTO {
        val stamp = clampedPhoneClockTimestamp(heardAt.truncatedUnixUInt(), clock.instant())
        val updated = row.copy(lastHeardTimestamp = maxOf(row.lastHeardTimestamp, stamp.toLong()))
        database.contacts().upsert(updated)
        var node = database.discoveredNodes().forPublicKey(radioId.value, row.publicKey).firstOrNull()
        if (node == null) {
            node = discovered.upsertInTransaction(this, radioId, updated.toDTO().toContactFrame()).node.toEntity()
        }
        database.discoveredNodes().upsert(node.copy(lastHeard = StoredInstant.from(
            maxOf(node.lastHeard.toInstant(), heardAt),
        )))
        return updated.toDTO()
    }

    private suspend fun RepositoryTransaction.classify(
        radioId: RadioId, prefix: Bytes,
    ): LocalDirectContactResolution {
        if (prefix.size != 6) invalidData("Local direct attribution requires a six-byte prefix")
        val rows = database.contacts().forPrefix(radioId.value, prefix, 6)
        return when (rows.size) {
            0 -> LocalDirectContactResolution.NotFound
            1 -> LocalDirectContactResolution.Unique(rows.single().toDTO(), LocalDirectRoster.revision)
            else -> LocalDirectContactResolution.Ambiguous
        }
    }

    private suspend fun RepositoryTransaction.revalidate(
        radioId: RadioId, prefix: Bytes, observed: LocalDirectContactResolution,
    ): LocalDirectContactResolution {
        val current = classify(radioId, prefix)
        if (observed == LocalDirectContactResolution.Ambiguous ||
            current == LocalDirectContactResolution.Ambiguous
        ) return LocalDirectContactResolution.Ambiguous
        if (observed == LocalDirectContactResolution.Stale) return observed
        if (observed == LocalDirectContactResolution.NotFound) {
            return if (current == LocalDirectContactResolution.NotFound) current else LocalDirectContactResolution.Stale
        }
        observed as LocalDirectContactResolution.Unique
        return if (current is LocalDirectContactResolution.Unique &&
            observed.rosterRevision == current.rosterRevision &&
            observed.contact.radioId == radioId && observed.contact.id == current.contact.id &&
            observed.contact.publicKey == current.contact.publicKey
        ) current else LocalDirectContactResolution.Stale
    }
}
