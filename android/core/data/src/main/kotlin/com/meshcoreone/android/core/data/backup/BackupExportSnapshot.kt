// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+BackupExport.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.data.repository.RepositoryDeduplicationKey
import com.meshcoreone.android.core.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun MeshCoreDatabase.backupEnvelope(appVersion: String, appBuild: String, exportDate: java.time.Instant): AppBackupEnvelope {
    val messages = mutableListOf<MessageDTO>()
    for (radioId in messages().backupRadioIds()) {
        var offset = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = messages().backupPageWithoutPreviewBlobs(radioId, 500, offset)
            for (row in page) {
                var dto = row.toDTO(includeLinkPreviewBlobs = false)
                if (dto.direction != MessageDirection.OUTGOING && dto.deduplicationKey == null) {
                    dto = dto.copy(deduplicationKey = RepositoryDeduplicationKey.contentBased(
                        dto.contactID, dto.channelIndex, dto.senderNodeName, dto.timestamp, dto.text,
                    ))
                }
                messages += dto
            }
            if (page.size < 500) break
            offset = Math.addExact(offset, page.size.toLong())
        }
    }
    val sessions = sessions().backupAll()
    requireUnambiguousRadios(messages.map { it.id to it.radioId }, "messages")
    requireUnambiguousRadios(sessions.map { it.id to RadioId(it.radioId) }, "remoteNodeSessions")
    val messageParents = messages.associate { it.id to it.radioId.value }
    val sessionParents = sessions.associate { it.id to it.radioId }
    val runs = traceRuns().backupAll().groupBy { RadioId(it.radioId) to it.savedPathID }
    val snapshots = nodeSnapshots().backupAll().map { it.toDTO() }.snapshot()
    return AppBackupEnvelope(
        version = if (snapshots.any { it.selfRadioID != null }) BackupContract.CURRENT_VERSION else BackupContract.LEGACY_VERSION,
        exportDate = exportDate, appVersion = appVersion, appBuild = appBuild,
        devices = devices().all().map { it.toDTO().redactedForBackup() }.snapshot(),
        contacts = contacts().backupAll().map { it.toDTO() }.snapshot(),
        channels = channels().backupAll().map { it.toDTO() }.snapshot(),
        messages = messages.snapshot(),
        messageRepeats = repeats().backupAll().filter { messageParents[it.messageID] == it.radioId }.map { it.toDTO() }.snapshot(),
        reactions = reactions().backupAll().map { it.toDTO() }.snapshot(),
        roomMessages = roomMessages().backupAll().filter { sessionParents[it.sessionID] == it.radioId }.map { it.toDTO() }.snapshot(),
        remoteNodeSessions = sessions.map { it.toDTO() }.snapshot(),
        savedTracePaths = tracePaths().backupAll().map {
            it.toDTO(runs[RadioId(it.radioId) to it.id].orEmpty().map { run -> run.toDTO() }.snapshot())
        }.snapshot(),
        blockedChannelSenders = blockedSenders().backupAll().map { it.toDTO() }.snapshot(),
        nodeStatusSnapshots = snapshots,
        discoveredNodes = discoveredNodes().backupAll().map { it.toDTO() }.snapshot(),
    ).withActualManifest().also(::validateRelationships)
}

internal fun requireUnambiguousRadios(identities: List<Pair<java.util.UUID, RadioId>>, field: String) {
    val known = hashMapOf<java.util.UUID, RadioId>()
    for ((id, radioId) in identities) {
        if (known.putIfAbsent(id, radioId)?.let { it != radioId } == true) {
            invalidValue(field, BackupValueProblem.IDENTITY)
        }
    }
}
