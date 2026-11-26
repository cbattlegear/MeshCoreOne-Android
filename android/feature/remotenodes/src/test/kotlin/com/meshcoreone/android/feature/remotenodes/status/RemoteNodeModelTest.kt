// PortedFrom: MC1Tests/Models/RemoteNodeModelTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import com.meshcoreone.android.feature.remotenodes.support.TEST_RADIO
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

/** core:model `RemoteNodeSessionDTO` / `RoomMessageDTO` behavior the remote-node screens rely on. */
class RemoteNodeModelTest {
    private val publicKeySize = 32

    private fun randomKey() = Bytes(Random.nextBytes(publicKeySize))

    // The two Swift role cases round-trip a session through an in-memory `DataStore`; a feature JVM test
    // cannot open the Room store, so these assert the DTO role helpers the round trip preserves.
    // core:data's RoomSourceTest covers the persisted role round trip.

    @Test
    @OriginalCase("RemoteNodeModelTests::RemoteNodeSession correctly stores role()", "platform-adaptation")
    fun `room session reports the room role`() {
        val session = RemoteNodeSessionDTO(radioId = TEST_RADIO, publicKey = randomKey(), name = "TestRoom", role = RemoteNodeRole.ROOM_SERVER)
        assertEquals(RemoteNodeRole.ROOM_SERVER, session.role)
        assertTrue(session.isRoom)
        assertFalse(session.isRepeater)
    }

    @Test
    @OriginalCase("RemoteNodeModelTests::RemoteNodeSession correctly stores repeater role()", "platform-adaptation")
    fun `repeater session reports the repeater role`() {
        val session = RemoteNodeSessionDTO(radioId = TEST_RADIO, publicKey = randomKey(), name = "TestRepeater", role = RemoteNodeRole.REPEATER)
        assertEquals(RemoteNodeRole.REPEATER, session.role)
        assertFalse(session.isRoom)
        assertTrue(session.isRepeater)
    }

    @Test
    @OriginalCase("RemoteNodeModelTests::RemoteNodeSessionDTO computed properties work()")
    fun `session computed properties work`() {
        val publicKey = Bytes.of(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF) + Bytes(ByteArray(26))
        val session = RemoteNodeSessionDTO(
            radioId = TEST_RADIO, publicKey = publicKey, name = "Test", role = RemoteNodeRole.ROOM_SERVER,
            isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE,
        )
        assertEquals(6, session.publicKeyPrefix.size)
        assertEquals(Bytes.of(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF), session.publicKeyPrefix)
        assertTrue(session.publicKeyHex.startsWith("AABBCCDDEEFF"))
        assertTrue(session.isRoom)
        assertFalse(session.isRepeater)
        assertTrue(session.canPost)
        assertFalse(session.isAdmin)
    }

    @Test
    @OriginalCase("RemoteNodeModelTests::RemoteNodeSessionDTO canPost requires room and readWrite()")
    fun `canPost requires room and readWrite`() {
        fun session(role: RemoteNodeRole, level: RoomPermissionLevel) = RemoteNodeSessionDTO(
            radioId = TEST_RADIO, publicKey = Bytes(ByteArray(32)), name = "Test", role = role, permissionLevel = level,
        )
        assertFalse(session(RemoteNodeRole.ROOM_SERVER, RoomPermissionLevel.GUEST).canPost)
        assertFalse(session(RemoteNodeRole.REPEATER, RoomPermissionLevel.ADMIN).canPost)
        assertTrue(session(RemoteNodeRole.ROOM_SERVER, RoomPermissionLevel.ADMIN).canPost)
    }

    @Test
    @OriginalCase("RemoteNodeModelTests::RoomMessage.generateDeduplicationKey produces consistent keys()")
    fun `deduplication key is consistent`() {
        val author = Bytes.of(0xAA, 0xBB, 0xCC, 0xDD)
        assertEquals(
            RoomMessageDTO.generateDeduplicationKey(1_702_500_000u, author, "Hello world"),
            RoomMessageDTO.generateDeduplicationKey(1_702_500_000u, author, "Hello world"),
        )
    }

    @Test
    @OriginalCase("RemoteNodeModelTests::RoomMessage.generateDeduplicationKey differs for different content()")
    fun `deduplication key differs for different content`() {
        val author = Bytes.of(0xAA, 0xBB, 0xCC, 0xDD)
        assertNotEquals(
            RoomMessageDTO.generateDeduplicationKey(1_702_500_000u, author, "Hello"),
            RoomMessageDTO.generateDeduplicationKey(1_702_500_000u, author, "World"),
        )
    }

    @Test
    @OriginalCase("RemoteNodeModelTests::RoomMessage.generateDeduplicationKey differs for different timestamps()")
    fun `deduplication key differs for different timestamps`() {
        val author = Bytes.of(0xAA, 0xBB, 0xCC, 0xDD)
        assertNotEquals(
            RoomMessageDTO.generateDeduplicationKey(1_702_500_000u, author, "Same text"),
            RoomMessageDTO.generateDeduplicationKey(1_702_500_001u, author, "Same text"),
        )
    }

    @Test
    @OriginalCase("RemoteNodeModelTests::RoomMessage.generateDeduplicationKey differs for different authors()")
    fun `deduplication key differs for different authors`() {
        assertNotEquals(
            RoomMessageDTO.generateDeduplicationKey(1_702_500_000u, Bytes.of(0xAA, 0xBB, 0xCC, 0xDD), "Same text"),
            RoomMessageDTO.generateDeduplicationKey(1_702_500_000u, Bytes.of(0x11, 0x22, 0x33, 0x44), "Same text"),
        )
    }

    @Test
    @OriginalCase("RemoteNodeModelTests::RoomMessage author display name fallback works()")
    fun `author display name falls back to hex`() {
        fun message(name: String?) = RoomMessageDTO(
            sessionID = UUID.randomUUID(), authorKeyPrefix = Bytes.of(0xAA, 0xBB, 0xCC, 0xDD), authorName = name,
            text = "Hello", timestamp = 1_702_500_000u,
        )
        assertEquals("Alice", message("Alice").authorDisplayName)
        assertEquals("AABBCCDD", message(null).authorDisplayName)
    }

    @Test
    @OriginalCase("RemoteNodeModelTests::RoomMessageDTO date conversion works()")
    fun `room message date converts from the timestamp`() {
        val message = RoomMessageDTO(
            sessionID = UUID.randomUUID(), authorKeyPrefix = Bytes.of(0xAA, 0xBB, 0xCC, 0xDD), authorName = null,
            text = "Test", timestamp = 1_702_500_000u,
        )
        assertEquals(Instant.ofEpochSecond(1_702_500_000), message.date)
    }
}
