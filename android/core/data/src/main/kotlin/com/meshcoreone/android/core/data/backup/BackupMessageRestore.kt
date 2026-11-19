// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+BackupBatchInsert.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+BackupImport.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class BackupMessageRestore(
    private val db: MeshCoreDatabase,
    private val accounting: ImportAccounting,
    private val beforeWrite: suspend (BackupModelKind) -> Unit,
) {
    private val affected = linkedSetOf<ParentIdentity>()

    suspend fun restore(messages: List<MessageDTO>, repeats: List<MessageRepeatDTO>, reactions: List<ReactionDTO>) {
        val radioIds = messages.map { it.radioId }.toSet()
        val localMessages = mutableListOf<MessageDTO>()
        for (radioId in radioIds) {
            var offset = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = db.messages().backupPageWithoutPreviewBlobs(radioId.value, 500, offset)
                localMessages += page.map { it.toDTO(includeLinkPreviewBlobs = false) }
                if (page.size < 500) break
                offset = Math.addExact(offset, page.size.toLong())
            }
        }
        val idsByKey = localMessages.groupBy(::messageBackupKey).mapValues { (_, rows) -> rows.map { it.id }.toMutableList() }.toMutableMap()
        val knownIDs = localMessages.mapTo(hashSetOf()) { ParentIdentity(it.radioId, it.id) }
        val remap = hashMapOf<ParentIdentity, UUID>()
        val skippedIncoming = mutableListOf<Pair<ParentIdentity, MessageDTO>>()
        val insert = mutableListOf<MessageDTO>()
        for (dto in messages) {
            currentCoroutineContext().ensureActive()
            val key = messageBackupKey(dto)
            val winners = idsByKey[key]
            if (winners != null) {
                val winner = winners.minBy { it.canonicalString() }
                remap[ParentIdentity(dto.radioId, dto.id)] = winner
                if (dto.direction == MessageDirection.INCOMING) skippedIncoming += ParentIdentity(dto.radioId, winner) to dto
                accounting.record(BackupModelKind.MESSAGES, skipped = 1)
            } else if (!knownIDs.add(ParentIdentity(dto.radioId, dto.id))) {
                accounting.record(BackupModelKind.MESSAGES, skipped = 1)
            } else {
                idsByKey.getOrPut(key) { mutableListOf() } += dto.id
                insert += dto
            }
        }
        for (dto in insert) {
            val reply = dto.replyToID?.let { remap[ParentIdentity(dto.radioId, it)] ?: it }
            val row = dto.copy(replyToID = reply).toEntity()
            write(BackupModelKind.MESSAGES) { db.messages().insert(row) }
            accounting.record(BackupModelKind.MESSAGES, inserted = 1)
        }
        val parents = messages.associate { it.id to ParentIdentity(it.radioId, remap[ParentIdentity(it.radioId, it.id)] ?: it.id) }
        val existingParents = parents.values.toSet().filterTo(hashSetOf()) { db.messages().byId(it.radioId.value, it.id) != null }
        val extras = db.repeats().backupAll().filter { ParentIdentity(RadioId(it.radioId), it.messageID) in existingParents }
        val pathsByParent = extras.groupBy { ParentIdentity(RadioId(it.radioId), it.messageID) }
            .mapValues { (_, values) -> values.mapTo(hashSetOf()) { it.pathNodes } }.toMutableMap()
        val knownRepeatIDs = extras.mapTo(hashSetOf()) { ParentIdentity(RadioId(it.radioId), it.id) }
        val incoming = hashSetOf<ParentIdentity>()
        val canonical = hashMapOf<ParentIdentity, Bytes>()
        for (parent in existingParents) {
            val row = requireNotNull(db.messages().byId(parent.radioId.value, parent.id))
            if (row.directionRawValue == MessageDirection.INCOMING.rawValue) {
                incoming += parent
                row.pathNodes?.let { canonical[parent] = it }
            }
        }
        for ((parent, dto) in skippedIncoming) {
            if (parent !in incoming) continue
            val row = db.messages().byId(parent.radioId.value, parent.id) ?: continue
            val foreignPath = dto.pathNodes
            if (row.pathNodes == null) {
                if (foreignPath != null) {
                    write(BackupModelKind.MESSAGES) {
                        db.messages().adoptPathIfUnknown(parent.radioId.value, parent.id, foreignPath, dto.pathLength.toLong())
                    }
                    canonical[parent] = foreignPath
                    affected += parent
                }
                continue
            }
            if (foreignPath == null || foreignPath == row.pathNodes) continue
            if (!pathsByParent.getOrPut(parent) { hashSetOf() }.add(foreignPath)) continue
            val promoted = MessageRepeatDTO(
                messageID = parent.id, receivedAt = dto.createdAt, pathNodes = foreignPath, pathLength = dto.pathLength,
                snr = dto.snr, rssi = null, rxLogEntryID = null,
            )
            write(BackupModelKind.MESSAGE_REPEATS) { db.repeats().insert(promoted.toEntity(parent.radioId, parent.id)) }
            accounting.record(BackupModelKind.MESSAGE_REPEATS, inserted = 1)
            affected += parent
            knownRepeatIDs += ParentIdentity(parent.radioId, promoted.id)
        }
        for (dto in repeats) {
            currentCoroutineContext().ensureActive()
            val parent = parents[dto.messageID]
            if (parent == null || parent !in existingParents) {
                accounting.record(BackupModelKind.MESSAGE_REPEATS, skipped = 1)
                continue
            }
            if (parent in incoming && (canonical[parent] == dto.pathNodes ||
                    !pathsByParent.getOrPut(parent) { hashSetOf() }.add(dto.pathNodes))) {
                accounting.record(BackupModelKind.MESSAGE_REPEATS, skipped = 1)
                continue
            }
            if (!knownRepeatIDs.add(ParentIdentity(parent.radioId, dto.id))) {
                accounting.record(BackupModelKind.MESSAGE_REPEATS, skipped = 1)
                continue
            }
            write(BackupModelKind.MESSAGE_REPEATS) { db.repeats().insert(dto.copy(messageID = parent.id).toEntity(parent.radioId, parent.id)) }
            accounting.record(BackupModelKind.MESSAGE_REPEATS, inserted = 1)
            affected += parent
        }
        val knownReactions = db.reactions().backupAll().mapTo(hashSetOf()) {
            reactionIdentity(ParentIdentity(RadioId(it.radioId), it.messageID), it.senderName, it.emoji)
        }
        for (dto in reactions) {
            val parent = ParentIdentity(dto.radioId, remap[ParentIdentity(dto.radioId, dto.messageID)] ?: dto.messageID)
            val identity = reactionIdentity(parent, dto.senderName, dto.emoji)
            if (parent !in existingParents || identity in knownReactions) {
                accounting.record(BackupModelKind.REACTIONS, skipped = 1)
                continue
            }
            val existing = db.reactions().byId(dto.radioId.value, dto.id)
            if (existing != null && identity == reactionIdentity(
                    ParentIdentity(RadioId(existing.radioId), existing.messageID), existing.senderName, existing.emoji,
                )
            ) {
                // An older backup must not reattach the same retired occurrence to a new incarnation.
                accounting.record(BackupModelKind.REACTIONS, skipped = 1)
                continue
            }
            write(BackupModelKind.REACTIONS) {
                val incarnation = db.directAcks().incarnation(parent.radioId.value, parent.id, "message")?.incarnation
                db.reactions().insert(dto.copy(messageID = parent.id).toEntity().copy(targetIncarnation = incarnation))
            }
            knownReactions += identity
            accounting.record(BackupModelKind.REACTIONS, inserted = 1)
            affected += parent
        }
        for (parent in affected) {
            val row = db.messages().byId(parent.radioId.value, parent.id) ?: invalidValue("messages.parent", BackupValueProblem.RELATIONSHIP)
            val reactionsForParent = db.reactions().forMessage(parent.radioId.value, parent.id, -1)
            val summary = reactionsForParent.takeIf { it.isNotEmpty() }?.groupBy { sourceStringKey(it.emoji) }?.entries
                ?.sortedWith(compareByDescending<Map.Entry<String, List<ReactionEntity>>> { it.value.size }
                    .thenBy { it.value.minOf { reaction -> reaction.receivedAt.toInstant() } })
                ?.joinToString(",") { "${it.value.first().emoji}:${it.value.size}" }
            val updated = row.copy(
                heardRepeats = db.repeats().forMessage(parent.radioId.value, parent.id).size.toLong(),
                reactionSummary = summary,
            )
            if (updated != row) write(BackupModelKind.MESSAGES) { db.messages().upsert(updated) }
        }
    }

    private suspend fun write(kind: BackupModelKind, block: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        beforeWrite(kind)
        currentCoroutineContext().ensureActive()
        block()
    }
}
