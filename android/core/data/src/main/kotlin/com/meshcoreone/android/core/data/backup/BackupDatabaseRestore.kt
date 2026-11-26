// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+BackupImport.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+BackupBatchInsert.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class BackupDatabaseRestore(
    private val db: MeshCoreDatabase,
    private val clock: Clock,
    private val beforeWrite: suspend (BackupModelKind) -> Unit = {},
) {
    private val accounting = ImportAccounting()
    private val contactRemap = hashMapOf<ParentIdentity, UUID>()
    private val sessionRemap = hashMapOf<ParentIdentity, UUID>()
    private val channelRemap = hashMapOf<ChannelSlot, UByte>()
    private val droppedChannels = hashSetOf<ChannelSlot>()

    suspend fun restore(envelope: AppBackupEnvelope): ImportResult {
        val radioMap = insertDevices(envelope.devices)
        fun radio(id: RadioId): RadioId = radioMap[id] ?: id
        val contacts = envelope.contacts.map { it.copy(radioId = radio(it.radioId)) }
        val channels = envelope.channels.map { it.copy(radioId = radio(it.radioId)) }
        val sessions = envelope.remoteNodeSessions.map { it.copy(radioId = radio(it.radioId)) }
        val messages = envelope.messages.map { it.copy(radioId = radio(it.radioId)) }
        val reactions = envelope.reactions.map { it.copy(radioId = radio(it.radioId)) }
        insertContacts(contacts)
        val capacities = linkedMapOf<RadioId, UByte>()
        for (device in envelope.devices) capacities.putIfAbsent(radio(device.radioId), device.maxChannels)
        insertChannels(channels, capacities)
        insertSessions(sessions)
        val remappedMessages = messages.mapNotNull { dto ->
            val slot = dto.channelIndex?.let { ChannelSlot(dto.radioId, it) }
            if (slot in droppedChannels) {
                accounting.record(BackupModelKind.MESSAGES, dropped = 1)
                null
            } else {
                val contactId = dto.contactID?.let { contactRemap[ParentIdentity(dto.radioId, it)] }
                val index = slot?.let { channelRemap[it] }
                dto.copy(
                    contactID = contactId ?: dto.contactID, channelIndex = index ?: dto.channelIndex,
                    deduplicationKey = when {
                        contactId != null -> rewriteDirectKey(dto.deduplicationKey, requireNotNull(dto.contactID), contactId)
                        index != null -> rewriteChannelKey(dto.deduplicationKey, requireNotNull(dto.channelIndex), index)
                        else -> dto.deduplicationKey
                    },
                )
            }
        }
        val remappedReactions = reactions.mapNotNull { dto ->
            val slot = dto.channelIndex?.let { ChannelSlot(dto.radioId, it) }
            if (slot in droppedChannels) {
                accounting.record(BackupModelKind.REACTIONS, dropped = 1)
                null
            } else dto.copy(
                contactID = dto.contactID?.let { contactRemap[ParentIdentity(dto.radioId, it)] ?: it },
                channelIndex = slot?.let { channelRemap[it] ?: it.index },
            )
        }
        BackupMessageRestore(db, accounting, beforeWrite).restore(
            remappedMessages, envelope.messageRepeats, remappedReactions,
        )
        insertRoomMessages(
            envelope.roomMessages,
            sessions.associate { it.id to ParentIdentity(it.radioId, sessionRemap[ParentIdentity(it.radioId, it.id)] ?: it.id) },
        )
        BackupDiagnosticRestore(db, accounting, beforeWrite).restore(
            envelope.savedTracePaths.map { it.copy(radioId = radio(it.radioId)) },
            envelope.blockedChannelSenders.map { it.copy(radioId = radio(it.radioId)) },
            envelope.nodeStatusSnapshots,
            envelope.discoveredNodes.map { it.copy(radioId = radio(it.radioId)) },
            radioMap,
        )
        reconcileDates(remappedMessages)
        currentCoroutineContext().ensureActive()
        return accounting.result()
    }

    private suspend fun write(kind: BackupModelKind, block: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        beforeWrite(kind)
        currentCoroutineContext().ensureActive()
        block()
    }

    private suspend fun insertDevices(devices: List<DeviceDTO>): Map<RadioId, RadioId> {
        val existing = db.devices().all().associateByFirst { it.publicKey }
        val firstRadio = linkedMapOf<Bytes, RadioId>()
        val mapping = linkedMapOf<RadioId, RadioId>()
        for (dto in devices) {
            currentCoroutineContext().ensureActive()
            val first = firstRadio[dto.publicKey]
            if (first != null) {
                accounting.record(BackupModelKind.DEVICES, skipped = 1)
                if (dto.radioId != first) mapping.putIfAbsent(dto.radioId, mapping.getValue(first))
                continue
            }
            firstRadio[dto.publicKey] = dto.radioId
            val local = existing[dto.publicKey]
            mapping[dto.radioId] = local?.let { RadioId(it.radioId) } ?: dto.radioId
            if (local == null) {
                write(BackupModelKind.DEVICES) { db.devices().insert(dto.cleanedForImport().copy(id = UUID.randomUUID()).toEntity()) }
                accounting.record(BackupModelKind.DEVICES, inserted = 1)
            }
        }
        return mapping
    }

    private suspend fun insertContacts(contacts: List<ContactDTO>) {
        val known = db.contacts().backupAll().associateByFirst { PublicKeyIdentity(RadioId(it.radioId), it.publicKey) }.toMutableMap()
        for (dto in contacts) {
            currentCoroutineContext().ensureActive()
            val key = PublicKeyIdentity(dto.radioId, dto.publicKey)
            val local = known[key]
            val parent = ParentIdentity(dto.radioId, dto.id)
            if (local != null) {
                val merged = mergeContact(local, dto, clock.instant())
                if (merged != local) {
                    write(BackupModelKind.CONTACTS) { db.contacts().upsert(merged) }
                    known[key] = merged
                    accounting.record(BackupModelKind.CONTACTS, merged = 1)
                }
                accounting.record(BackupModelKind.CONTACTS, skipped = 1)
                contactRemap[parent] = local.id
            } else {
                val row = dto.toEntity().copy(
                    flags = dto.flags.toLong() and 0xef,
                    lastHeardTimestamp = dto.lastHeardTimestamp?.let {
                        RoomPersistenceStore.clampedPhoneClockTimestamp(it, clock.instant()).toLong()
                    } ?: 0,
                )
                write(BackupModelKind.CONTACTS) { db.contacts().insert(row) }
                known[key] = row
                contactRemap[parent] = row.id
                accounting.record(BackupModelKind.CONTACTS, inserted = 1)
            }
        }
    }

    private suspend fun insertChannels(channels: List<ChannelDTO>, capacities: Map<RadioId, UByte>) {
        val localRows = db.channels().backupAll()
        val bySlot = localRows.associateBy { ChannelSlot(RadioId(it.radioId), it.index.toUByte()) }.toMutableMap()
        val bySecret = localRows.filter { it.secret.hasStableSecret() }
            .associateBy { PublicKeyIdentity(RadioId(it.radioId), it.secret) }.toMutableMap()
        val ordered = channels.sortedWith(compareBy<ChannelDTO> { it.radioId.canonicalString }.thenBy { it.index })
        for (dto in ordered) {
            currentCoroutineContext().ensureActive()
            val slot = ChannelSlot(dto.radioId, dto.index)
            val local = if (dto.secret.hasStableSecret()) bySecret[PublicKeyIdentity(dto.radioId, dto.secret)] else bySlot[slot]
            if (local != null) {
                val merged = mergeChannel(local, dto)
                if (merged != local) {
                    write(BackupModelKind.CHANNELS) { db.channels().upsert(merged) }
                    bySlot[ChannelSlot(dto.radioId, local.index.toUByte())] = merged
                    if (local.secret.hasStableSecret()) bySecret[PublicKeyIdentity(dto.radioId, local.secret)] = merged
                    accounting.record(BackupModelKind.CHANNELS, merged = 1)
                }
                accounting.record(BackupModelKind.CHANNELS, skipped = 1)
                if (local.index.toUByte() != dto.index) channelRemap[slot] = local.index.toUByte()
                continue
            }
            val occupied = bySlot.keys.filter { it.radioId == dto.radioId }.map { it.index.toInt() }.toSet()
            val upper = capacities[dto.radioId]?.toInt() ?: ((occupied.maxOrNull() ?: 0) + 1)
            val index = if (dto.index.toInt() !in occupied) dto.index else
                (1 until upper).firstOrNull { it !in occupied }?.toUByte()
            if (index == null) {
                droppedChannels += slot
                accounting.affectedSlot(dto.radioId, dto.index)
                accounting.record(BackupModelKind.CHANNELS, dropped = 1)
                continue
            }
            val row = dto.copy(id = UUID.randomUUID(), index = index).toEntity()
            write(BackupModelKind.CHANNELS) { db.channels().insert(row) }
            bySlot[ChannelSlot(dto.radioId, index)] = row
            if (row.secret.hasStableSecret()) bySecret[PublicKeyIdentity(dto.radioId, row.secret)] = row
            accounting.affectedSlot(dto.radioId, index)
            if (index != dto.index) channelRemap[slot] = index
            accounting.record(BackupModelKind.CHANNELS, inserted = 1)
        }
    }

    private suspend fun insertSessions(sessions: List<RemoteNodeSessionDTO>) {
        val known = db.sessions().backupAll().associateByFirst { PublicKeyIdentity(RadioId(it.radioId), it.publicKey) }.toMutableMap()
        for (dto in sessions) {
            currentCoroutineContext().ensureActive()
            val key = PublicKeyIdentity(dto.radioId, dto.publicKey)
            val local = known[key]
            if (local != null) {
                val merged = mergeSession(local, dto)
                if (merged != local) {
                    write(BackupModelKind.REMOTE_NODE_SESSIONS) { db.sessions().upsert(merged) }
                    known[key] = merged
                    accounting.record(BackupModelKind.REMOTE_NODE_SESSIONS, merged = 1)
                }
                accounting.record(BackupModelKind.REMOTE_NODE_SESSIONS, skipped = 1)
                sessionRemap[ParentIdentity(dto.radioId, dto.id)] = local.id
            } else {
                val row = dto.copy(isConnected = false).toEntity()
                write(BackupModelKind.REMOTE_NODE_SESSIONS) { db.sessions().insert(row) }
                known[key] = row
                sessionRemap[ParentIdentity(dto.radioId, dto.id)] = row.id
                accounting.record(BackupModelKind.REMOTE_NODE_SESSIONS, inserted = 1)
            }
        }
    }

    private suspend fun insertRoomMessages(rows: List<RoomMessageDTO>, parents: Map<UUID, ParentIdentity>) {
        val local = db.roomMessages().backupAll()
        val known = local.mapTo(hashSetOf()) {
            roomMessageBackupKey(ParentIdentity(RadioId(it.radioId), it.sessionID), it.toDTO())
        }
        val affected = hashMapOf<ParentIdentity, java.time.Instant>()
        for (dto in rows) {
            currentCoroutineContext().ensureActive()
            val parent = parents[dto.sessionID]
            if (parent == null || roomMessageBackupKey(parent, dto) in known) {
                accounting.record(BackupModelKind.ROOM_MESSAGES, skipped = 1)
                continue
            }
            write(BackupModelKind.ROOM_MESSAGES) { db.roomMessages().insert(dto.copy(sessionID = parent.id).toEntity(parent.radioId)) }
            known += roomMessageBackupKey(parent, dto)
            accounting.record(BackupModelKind.ROOM_MESSAGES, inserted = 1)
            affected[parent] = maxOf(affected[parent] ?: dto.createdAt, dto.createdAt)
        }
        for ((parent, latest) in affected) {
            val row = db.sessions().byId(parent.radioId.value, parent.id) ?: invalidValue("roomMessages.sessionID", BackupValueProblem.RELATIONSHIP)
            val updated = row.copy(lastMessageDate = maxDate(row.lastMessageDate, latest))
            if (updated != row) write(BackupModelKind.REMOTE_NODE_SESSIONS) { db.sessions().upsert(updated) }
        }
    }

    private suspend fun reconcileDates(messages: List<MessageDTO>) {
        val contacts = messages.filter { it.contactID != null }.groupBy { ParentIdentity(it.radioId, requireNotNull(it.contactID)) }
        for ((parent, rows) in contacts) {
            val row = db.contacts().byId(parent.radioId.value, parent.id) ?: continue
            val updated = row.copy(lastMessageDate = maxDate(row.lastMessageDate, rows.maxOf { it.createdAt }))
            if (updated != row) write(BackupModelKind.CONTACTS) { db.contacts().upsert(updated) }
        }
        val channels = messages.filter { it.channelIndex != null }.groupBy { ChannelSlot(it.radioId, requireNotNull(it.channelIndex)) }
        for ((slot, rows) in channels) {
            for (row in db.channels().forIndex(slot.radioId.value, slot.index.toLong())) {
                val updated = row.copy(lastMessageDate = maxDate(row.lastMessageDate, rows.maxOf { it.createdAt }))
                if (updated != row) write(BackupModelKind.CHANNELS) { db.channels().upsert(updated) }
            }
        }
    }
}

internal fun Bytes.hasStableSecret(): Boolean = !isEmpty && any { it != 0.toUByte() }
internal fun <T, Key> List<T>.associateByFirst(key: (T) -> Key): Map<Key, T> =
    linkedMapOf<Key, T>().also { result -> for (value in this) result.putIfAbsent(key(value), value) }
