// PortedFrom: MC1Services/Tests/MC1ServicesTests/FailedSendConversationKeysTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/ChannelFloodScopeTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/DevicePublicKeyDeduplicationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Models/ConnectionMethodTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native DAO/column equivalents run on actual Room SQLite, not WP-202 repository algorithms.
package com.meshcoreone.android.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SourceRoomCasesTest {
    private lateinit var db: MeshCoreDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MeshCoreDatabase::class.java).build() }
    @After fun close() { db.close() }

    @Test @OriginalCase("FailedSendConversationKeysTests::outgoing failed DM includes the contact id()")
    fun outgoingFailedDMIncludesContactId() = runTest {
        val id = UUID.randomUUID(); db.messages().upsert(message(contactID = id).toEntity())
        val keys = db.failureKeys(RADIO_A); assertEquals(setOf(id), keys.contactIDs)
        assertTrue(keys.channelIDs.isEmpty()); assertTrue(keys.roomSessionIDs.isEmpty())
    }
    @Test @OriginalCase("FailedSendConversationKeysTests::incoming sent retrying pending sending and other radio DMs are absent()")
    fun incomingNonFailedAndOtherRadioDirectMessagesAreAbsent() = runTest {
        db.messages().upsert(message(direction = MessageDirection.INCOMING).toEntity())
        listOf(MessageStatus.SENT, MessageStatus.RETRYING, MessageStatus.PENDING, MessageStatus.SENDING).forEach {
            db.messages().upsert(message(status = it).toEntity())
        }
        db.messages().upsert(message(radioId = RADIO_B).toEntity())
        assertEquals(FailedSendConversationKeys.EMPTY, db.failureKeys(RADIO_A))
    }
    @Test @OriginalCase("FailedSendConversationKeysTests::outgoing failed channel message includes that channel id()")
    fun failedChannelIncludesResolvedChannelId() = runTest {
        val c = channel(index = 3u); db.channels().upsert(c.toEntity())
        db.messages().upsert(message(contactID = null, index = 3u).toEntity())
        val keys = db.failureKeys(RADIO_A); assertEquals(setOf(c.id), keys.channelIDs)
        assertTrue(keys.contactIDs.isEmpty()); assertTrue(keys.roomSessionIDs.isEmpty())
    }
    @Test @OriginalCase("FailedSendConversationKeysTests::failed channel on another radio with the same index is absent()")
    fun equalChannelIndicesRemainRadioPartitioned() = runTest {
        val a = channel(); val b = channel(RADIO_B); db.channels().upsert(listOf(a.toEntity(), b.toEntity()))
        db.messages().upsert(message(contactID = null, index = 0u).toEntity())
        assertEquals(setOf(a.id), db.failureKeys(RADIO_A).channelIDs)
        assertTrue(db.failureKeys(RADIO_B).channelIDs.isEmpty())
    }
    @Test @OriginalCase("FailedSendConversationKeysTests::failed channel message with no Channel row yields no channel id()")
    fun channelValueReferenceDoesNotInventMissingChannel() = runTest {
        db.messages().upsert(message(contactID = null, index = 3u).toEntity())
        assertTrue(db.failureKeys(RADIO_A).channelIDs.isEmpty())
    }
    @Test @OriginalCase("FailedSendConversationKeysTests::failed self room message includes that session and excludes other radios()")
    fun failedSelfRoomMessagesJoinOnlyTheirRadioSessions() = runTest {
        val a = session(); val b = session(RADIO_B)
        db.sessions().upsert(listOf(a.toEntity(), b.toEntity()))
        db.roomMessages().upsert(roomMessage(a.id).toEntity(RadioId(RADIO_A)))
        db.roomMessages().upsert(roomMessage(b.id).toEntity(RadioId(RADIO_B)))
        db.roomMessages().upsert(roomMessage(a.id, self = false).toEntity(RadioId(RADIO_A)))
        db.roomMessages().upsert(roomMessage(a.id, status = MessageStatus.SENT).toEntity(RadioId(RADIO_A)))
        assertEquals(setOf(a.id), db.failureKeys(RADIO_A).roomSessionIDs)
        assertEquals(setOf(b.id), db.failureKeys(RADIO_B).roomSessionIDs)
    }
    @Test @OriginalCase("FailedSendConversationKeysTests::outgoing failed reaction is included()")
    fun failedReactionTextIsNotFilteredFromConversationKeys() = runTest {
        val id = UUID.randomUUID(); db.messages().upsert(message(contactID = id, text = "\uD83D\uDC4D").toEntity())
        assertEquals(setOf(id), db.failureKeys(RADIO_A).contactIDs)
    }
    @Test @OriginalCase("FailedSendConversationKeysTests::multiple failed DMs for one contact collapse to one id()")
    fun repeatedFailureRowsCollapseToOneContactId() = runTest {
        val id = UUID.randomUUID()
        db.messages().upsert(listOf(message(contactID = id, text = "one").toEntity(), message(contactID = id, text = "two").toEntity()))
        assertEquals(setOf(id), db.failureKeys(RADIO_A).contactIDs)
    }
    @Test @OriginalCase("FailedSendConversationKeysTests::empty store returns empty keys()")
    fun emptyDatabaseReturnsRealEmptyQueryResult() = runTest { assertEquals(FailedSendConversationKeys.EMPTY, db.failureKeys(RADIO_A)) }
    @Test @OriginalCase("FailedSendConversationKeysTests::seen outgoing failed DM is absent()")
    fun seenFailureIsExcluded() = runTest {
        db.messages().upsert(message().copy(failureSeen = true).toEntity()); assertTrue(db.failureKeys(RADIO_A).contactIDs.isEmpty())
    }
    @Test @OriginalCase("FailedSendConversationKeysTests::markFailedSendsSeen drops only that contact()")
    fun seeingOneContactFailurePreservesOthers() = runTest {
        val seen = UUID.randomUUID(); val other = UUID.randomUUID()
        db.messages().upsert(listOf(message(contactID = seen).toEntity(), message(contactID = other).toEntity()))
        assertEquals(1, db.messages().markContactFailuresSeen(RADIO_A, seen))
        assertEquals(setOf(other), db.failureKeys(RADIO_A).contactIDs)
    }
    @Test @OriginalCase("FailedSendConversationKeysTests::markFailedSendsSeen drops only that channel index on that radio()")
    fun seeingChannelFailurePreservesOtherSlotsAndRadio() = runTest {
        val a0 = channel(); val a1 = channel(index = 1u); val b0 = channel(RADIO_B)
        db.channels().upsert(listOf(a0.toEntity(), a1.toEntity(), b0.toEntity()))
        db.messages().upsert(listOf(message(contactID = null, index = 0u).toEntity(),
            message(contactID = null, index = 1u).toEntity(), message(RADIO_B, contactID = null, index = 0u).toEntity()))
        db.messages().markChannelFailuresSeen(RADIO_A, 0)
        assertEquals(setOf(a1.id), db.failureKeys(RADIO_A).channelIDs); assertEquals(setOf(b0.id), db.failureKeys(RADIO_B).channelIDs)
    }
    @Test @OriginalCase("FailedSendConversationKeysTests::markFailedSendsSeen drops only that room session()")
    fun seeingRoomFailurePreservesOtherSessions() = runTest {
        val a = session(); val b = session().copy(publicKey = Bytes(ByteArray(32) { 0xDD.toByte() }))
        db.sessions().upsert(listOf(a.toEntity(), b.toEntity()))
        db.roomMessages().upsert(listOf(roomMessage(a.id).toEntity(RadioId(RADIO_A)), roomMessage(b.id).toEntity(RadioId(RADIO_A))))
        db.roomMessages().markFailuresSeen(RADIO_A, a.id)
        assertEquals(setOf(b.id), db.failureKeys(RADIO_A).roomSessionIDs)
    }
    @Test @OriginalCase("FailedSendConversationKeysTests::transition to failed from sent clears failureSeen()")
    fun newFailureTransitionClearsSeenFlag() = runTest {
        val m = message(status = MessageStatus.SENT).copy(failureSeen = true)
        db.messages().upsert(m.toEntity()); db.messages().setStatus(RADIO_A, m.id, MessageStatus.FAILED.rawValue)
        assertEquals(setOf(m.contactID), db.failureKeys(RADIO_A).contactIDs)
        assertFalse(assertNotNull(db.messages().byId(RADIO_A, m.id)).failureSeen)
    }
    @Test @OriginalCase("FailedSendConversationKeysTests::idempotent failed write does not clear failureSeen()")
    fun idempotentFailureDoesNotReopenSeenBadge() = runTest {
        val m = message(); db.messages().upsert(m.toEntity()); db.messages().markContactFailuresSeen(RADIO_A, assertNotNull(m.contactID))
        db.messages().setStatus(RADIO_A, m.id, MessageStatus.FAILED.rawValue)
        assertTrue(db.failureKeys(RADIO_A).contactIDs.isEmpty())
    }

    @Test @OriginalCase("ChannelFloodScopeTests::Channel model default floodScope is .inherit()")
    fun channelDefaultScopePersists() = runTest {
        val c = channel(); db.channels().upsert(c.toEntity())
        assertEquals(ChannelFloodScope.Inherit, assertNotNull(db.channels().byId(RADIO_A, c.id)).toDTO().floodScope)
    }
    @Test @OriginalCase("ChannelFloodScopeTests::Channel model persists .allRegions distinctly from .inherit()")
    fun explicitAllRegionsPersistsSeparatelyFromInherit() = runTest {
        val c = channel().withFloodScope(ChannelFloodScope.AllRegions); db.channels().upsert(c.toEntity())
        assertEquals(ChannelFloodScope.AllRegions, assertNotNull(db.channels().byId(RADIO_A, c.id)).toDTO().floodScope)
    }
    @Test @OriginalCase("ChannelFloodScopeTests::Channel model persists .region(name)()")
    fun specificNamedScopePersists() = runTest {
        val c = channel().withFloodScope(ChannelFloodScope.Region("Germany")); db.channels().upsert(c.toEntity())
        assertEquals(ChannelFloodScope.Region("Germany"), assertNotNull(db.channels().byId(RADIO_A, c.id)).toDTO().floodScope)
    }
    @Test @OriginalCase("ChannelFloodScopeTests::setChannelFloodScope updates existing channel atomically()")
    fun scopeAndRegionColumnsChangeInOneSqlStatement() = runTest {
        val c = channel().withFloodScope(ChannelFloodScope.Region("Germany")); db.channels().upsert(c.toEntity())
        assertEquals(1, db.channels().setFloodScope(RADIO_A, c.id, "allRegions", null))
        var row = assertNotNull(db.channels().byId(RADIO_A, c.id)); assertEquals(ChannelFloodScope.AllRegions, row.toDTO().floodScope); assertNull(row.regionScope)
        db.channels().setFloodScope(RADIO_A, c.id, "inherit", null)
        row = assertNotNull(db.channels().byId(RADIO_A, c.id)); assertEquals(ChannelFloodScope.Inherit, row.toDTO().floodScope); assertNull(row.regionScope)
    }

    @Test @OriginalCase("DevicePublicKeyDeduplicationTests::fetchDevice(publicKey:) returns matching device()")
    fun actualPublicKeyLookupReturnsPersistedDeviceAndStableRadioId() = runTest {
        val d = device(); db.devices().upsert(d.toEntity())
        val row = db.devices().forPublicKey(KEY).single(); assertEquals(d.id, row.id); assertEquals(d.publicKey, row.publicKey); assertEquals(RADIO_A, row.radioId)
    }
    @Test @OriginalCase("DevicePublicKeyDeduplicationTests::fetchDevice(publicKey:) returns nil for unknown key()")
    fun unknownPublicKeyQueryHasNoRows() = runTest {
        db.devices().upsert(device().toEntity()); assertTrue(db.devices().forPublicKey(Bytes(ByteArray(32) { 0xFF.toByte() })).isEmpty())
    }

    @Test @OriginalCase("ConnectionMethodTests::ConnectionMethod decodes the frozen synthesized wire shape (guards against silent rename)()")
    fun frozenAssociatedEnumJsonDecodesToNativeColumnValues() {
        val converters = ValueConverters()
        assertEquals(ConnectionMethod.WiFi("192.168.1.50", 5000u, "Field Radio"),
            converters.connectionMethodFromJSON(JSONObject("""{"wifi":{"displayName":"Field Radio","host":"192.168.1.50","port":5000}}""")))
        assertEquals(ConnectionMethod.Bluetooth(UUID.fromString("E621E1F8-C36C-495A-93FC-0C247A3E6E5F")),
            converters.connectionMethodFromJSON(JSONObject("""{"bluetooth":{"displayName":null,"peripheralUUID":"E621E1F8-C36C-495A-93FC-0C247A3E6E5F"}}""")))
    }
    @Test @OriginalCase("ConnectionMethodTests::ConnectionMethod ENCODES to the frozen synthesized wire shape (pins the write shape)()")
    fun nativeColumnEncoderPinsTheExactAssociatedEnumWriteShape() {
        assertEquals("""{"wifi":{"displayName":"Field Radio","host":"192.168.1.50","port":5000}}""",
            ValueConverters().connectionMethodToJSON(ConnectionMethod.WiFi("192.168.1.50", 5000u, "Field Radio")).toString())
    }
}
