// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Diagnostics.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/RxLogService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/HeardRepeatsService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/Message.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ReviewedSourceRegressionTest {
    private lateinit var db: MeshCoreDatabase
    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MeshCoreDatabase::class.java).build() }
    @After fun close() { db.close() }

    private fun reception(seconds: Long, path: Int, channel: UByte?, radio: UUID = RADIO_A): RxLogEntryDTO =
        RxLogEntryDTO(UUID.randomUUID(), RadioId(radio), Instant.ofEpochSecond(seconds), 1.0, -90,
            RouteType.FLOOD, PayloadType.TEXT_MESSAGE, 0u, null, 1u, Bytes.of(path),
            Bytes.of(1, 2), Bytes.of(1, 2), "same-packet", channel, "channel", DecryptStatus.NO_MATCHING_KEY,
            senderTimestamp = 1_700_000_000u, payloadTypeBits = 2u)

    @Test fun redecryptionReplaysOldestFirstSoSourceCanonicalPathAdoptionIsFirstWins() = runTest {
        val first = reception(1_700_000_001, 0x1A, 7u)
        val second = reception(1_700_000_002, 0x2B, 7u)
        db.rxLogs().insert(second.toEntity()); db.rxLogs().insert(first.toEntity())
        db.rxLogs().insert(reception(1_700_000_000, 0x3C, 7u, RADIO_B).toEntity())
        val target = message(contactID = null, index = 7u, direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED)
        db.messages().insert(target.toEntity())
        val rows = db.rxLogs().forDecryptStatus(RADIO_A, 1, 1_700_000_000, 0)
        assertEquals(listOf(first.id, second.id), rows.map { it.id })
        val writes = rows.map { db.messages().adoptPathIfUnknown(RADIO_A, target.id, it.pathNodes, it.pathLength) }
        assertEquals(listOf(1, 0), writes)
        assertEquals(Bytes.of(0x1A), assertNotNull(db.messages().byId(RADIO_A, target.id)).pathNodes)
    }

    @Test fun directCorrelationExcludesNewerChannelAttributedRowsButKeepsNewestDirectReception() = runTest {
        val direct = reception(1_700_000_001, 0x1A, null)
        val newerChannel = reception(1_700_000_002, 0x2B, 7u)
        val newestDirect = reception(1_700_000_003, 0x3C, null)
        db.rxLogs().insert(direct.toEntity()); db.rxLogs().insert(newerChannel.toEntity())
        assertEquals(listOf(direct.id), db.rxLogs().directCorrelation(RADIO_A, 1_700_000_000).map { it.id })
        db.rxLogs().insert(newestDirect.toEntity())
        assertEquals(listOf(newestDirect.id, direct.id), db.rxLogs().directCorrelation(RADIO_A, 1_700_000_000).map { it.id })
    }

    @Test fun timestampZeroFallbackTruncatesWholeUnixFractionTowardZeroBeforeCheckingUInt32() = runTest {
        val base = message().copy(timestamp = 0u)
        val negativeHalf = base.copy(createdAt = Instant.ofEpochSecond(-1, 500_000_000), sortDate = Instant.ofEpochSecond(-1, 500_000_000))
        val positiveHalf = base.copy(id = UUID.randomUUID(), createdAt = Instant.ofEpochSecond(0, 500_000_000), sortDate = Instant.ofEpochSecond(0, 500_000_000))
        val maxFraction = base.copy(id = UUID.randomUUID(), createdAt = Instant.ofEpochSecond(0xFFFF_FFFFL, 999_999_999))
        db.messages().insert(negativeHalf.toEntity()); db.messages().insert(positiveHalf.toEntity()); db.messages().insert(maxFraction.toEntity())
        val negative = assertNotNull(db.messages().byId(RADIO_A, negativeHalf.id))
        assertEquals(0L, negative.timestamp); assertEquals(StoredInstant(-1, 500_000_000), negative.createdAt)
        assertEquals(0L, assertNotNull(db.messages().byId(RADIO_A, positiveHalf.id)).timestamp)
        assertEquals(UInt.MAX_VALUE, assertNotNull(db.messages().byId(RADIO_A, maxFraction.id)).toDTO().timestamp)
        listOf(Instant.ofEpochSecond(-1), Instant.ofEpochSecond(-2, 999_999_999), Instant.ofEpochSecond(0x1_0000_0000L)).forEach {
            assertFailsWith<DatabaseValueException> { base.copy(createdAt = it).toEntity() }
        }
        assertEquals(1L, base.copy(timestamp = 1u, createdAt = Instant.ofEpochSecond(-2)).toEntity().timestamp)
    }
}
