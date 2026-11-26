// AndroidOnly: WP-203 Current Swift outgoing reaction metadata survives codecs, winner remapping and native queue boundaries.
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.OutgoingReactionAdmission
import com.meshcoreone.android.core.database.toDTO
import com.meshcoreone.android.core.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class BackupReactionMetadataTest : BackupRoomTest() {
    private val codec = AppBackupCodec()

    @Test fun optionalReactionFieldsPreserveOmissionNullEmptyAndRejectWrongTypes() {
        val source = envelope(messages = listOf(message().copy(
            reactionTargetMessageID = id(20), reactionSenderName = "Nate",
        )))
        val encoded = source.jsonObject()
        assertEquals(source.messages.single(), codec.parseBackup(encoded.compressed()).messages.single())
        for (value in listOf(null, JsonNull)) {
            val legacy = encoded.modifyingRow("messages") {
                it.replacing("reactionTargetMessageID", value).replacing("reactionSenderName", value)
            }
            val decoded = codec.parseBackup(legacy.compressed()).messages.single()
            assertNull(decoded.reactionTargetMessageID)
            assertNull(decoded.reactionSenderName)
        }
        val empty = encoded.modifyingRow("messages") { it.replacing("reactionSenderName", JsonPrimitive("")) }
        assertEquals("", codec.parseBackup(empty.compressed()).messages.single().reactionSenderName)
        for ((field, value) in listOf(
            "reactionTargetMessageID" to JsonPrimitive("not-a-uuid"),
            "reactionSenderName" to JsonPrimitive(42),
        )) {
            assertThrows(AppBackupException::class.java) {
                codec.parseBackup(encoded.modifyingRow("messages") { it.replacing(field, value) }.compressed())
            }
        }
    }

    @Test fun restoreRemapsTargetAndReplyBeforeInsertAndFillsMissingMetadataOnlyOnce() = runBlocking<Unit> {
        val localDevice = device(OTHER_RADIO, device().publicKey, id(90))
        val localTarget = message(OTHER_RADIO, id(20), MessageDirection.INCOMING, index = 0u, contactID = null)
        val incomingTarget = localTarget.copy(id = id(21), radioId = RADIO)
        val outgoing = message(id = id(22), index = 0u, contactID = null, text = "Reaction").copy(
            replyToID = incomingTarget.id, reactionTargetMessageID = incomingTarget.id, reactionSenderName = "Nate",
        )
        seed(envelope(devices = listOf(localDevice), channels = listOf(channel(OTHER_RADIO)), messages = listOf(localTarget)))
        val imported = envelope(devices = listOf(device()), channels = listOf(channel()),
            messages = listOf(outgoing, incomingTarget))
        service.importBackup(codec.parseBackup(codec.encode(imported)), store)
        val row = requireNotNull(db.messages().byId(OTHER_RADIO.value, outgoing.id)).toDTO()
        assertEquals(localTarget.id, row.replyToID)
        assertEquals(localTarget.id, row.reactionTargetMessageID)
        assertEquals("Nate", row.reactionSenderName)
        assertEquals(row, codec.parseBackup(service.export(store).data).messages.single { it.id == row.id })
        assertTrue(db.pendingSends().forRadio(OTHER_RADIO.value).isEmpty())
        assertFalse(store.isOutgoingReaction(EntityKey(OTHER_RADIO, row.id)))
        assertNull(store.fetchOutgoingReaction(EntityKey(OTHER_RADIO, row.id)))

        db.messages().upsert(requireNotNull(db.messages().byId(OTHER_RADIO.value, row.id)).copy(
            reactionTargetMessageID = null, reactionSenderName = null,
        ))
        val first = service.importBackup(imported, store)
        assertEquals(1L, first.count(BackupModelKind.MESSAGES).merged)
        assertTrue(first.hasRestoredChanges)
        val second = service.importBackup(imported, store)
        assertEquals(0L, second.count(BackupModelKind.MESSAGES).merged)
        assertEquals(0L, second.totalInserted)
        assertEquals(localTarget.id, requireNotNull(db.messages().byId(OTHER_RADIO.value, row.id)).reactionTargetMessageID)
        db.messages().upsert(requireNotNull(db.messages().byId(OTHER_RADIO.value, row.id)).copy(
            reactionTargetMessageID = id(99), reactionSenderName = "",
        ))
        assertEquals(0L, service.importBackup(imported, store).count(BackupModelKind.MESSAGES).merged)
        assertEquals(id(99), requireNotNull(db.messages().byId(OTHER_RADIO.value, row.id)).reactionTargetMessageID)
        assertEquals("", requireNotNull(db.messages().byId(OTHER_RADIO.value, row.id)).reactionSenderName)
    }

    @Test fun acceptedNativeIntentExportsMetadataButRetiredIntentCannotAnnotateReusedMessage() = runBlocking<Unit> {
        val peer = contact()
        val original = message(direction = MessageDirection.INCOMING)
        seed(envelope(devices = listOf(device()), contacts = listOf(peer), messages = listOf(original)))
        val target = requireNotNull(store.fetchMessage(EntityKey(RADIO, original.id))).copy(
            channelReactionIncarnation = requireNotNull(db.directAcks().incarnation(RADIO.value, original.id, "message")).incarnation,
        )
        val outgoing = message(id = id(25), text = "reaction-wire").copy(status = MessageStatus.PENDING)
        val pending = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(outgoing.id, peer.id), RADIO)
        store.admitOutgoingReaction(OutgoingReactionAdmission(
            target, outgoing, pending,
            ReactionDTO(messageID = target.id, emoji = ":)", senderName = "Nate", messageHash = "hash",
                rawText = outgoing.text, receivedAt = AT, contactID = peer.id, radioId = RADIO,
                expectedTargetIncarnation = target.channelReactionIncarnation),
            peer.publicKey, null,
        ))
        val stored = requireNotNull(db.messages().byId(RADIO.value, outgoing.id))
        assertEquals(target.id, stored.reactionTargetMessageID)
        assertEquals("Nate", stored.reactionSenderName)
        db.messages().upsert(stored.copy(reactionTargetMessageID = null, reactionSenderName = null))
        val exported = codec.parseBackup(service.export(store).data).messages.single { it.id == outgoing.id }
        assertEquals(target.id, exported.reactionTargetMessageID)
        assertEquals("Nate", exported.reactionSenderName)
        db.messages().delete(RADIO.value, outgoing.id)
        db.messages().insert(stored.copy(text = "replacement", reactionTargetMessageID = null, reactionSenderName = null))
        val replaced = codec.parseBackup(service.export(store).data).messages.single { it.id == outgoing.id }
        assertNull(replaced.reactionTargetMessageID)
        assertNull(replaced.reactionSenderName)
    }
}
