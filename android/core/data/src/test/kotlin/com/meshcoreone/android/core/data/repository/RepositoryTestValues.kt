// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/PersistenceStore+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/PersistenceStore+TestFetch.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Mocks/MockPersistenceStore.swift@db14559b39d32322b06477c6ae676112f583db50
// Real Room replaces the source's storage mock; the fixture never implements a production persistence role.
package com.meshcoreone.android.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.DirectAckStorage
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
internal annotation class OriginalCase(val value: String, val disposition: String = "source-behavior")

internal val RADIO_A = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
internal val RADIO_B = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
internal val CONTACT_A = UUID.fromString("00000000-0000-0000-0000-000000000003")
internal val AT: Instant = Instant.ofEpochSecond(1_700_000_000)

internal suspend fun <T> withUnchangedDirectAckState(
    database: MeshCoreDatabase,
    key: EntityKey,
    claimId: UUID,
    block: suspend () -> T,
): T {
    suspend fun snapshot(): List<Any?> = database.withTransaction {
        val radio = key.radioId.value
        val pending = database.pendingSends().forMessage(radio, key.id)
        listOf(
            database.messages().byId(radio, key.id),
            pending,
            pending.map { database.directAcks().incarnation(radio, it.id, "pending") },
            database.directAcks().incarnation(radio, key.id, "message"),
            database.directAcks().claim(radio, claimId),
            database.directAcks().wires(radio, claimId),
            database.directAcks().operations(radio, claimId),
            database.directAcks().delivery(radio, claimId),
            database.directAckExclusions().emissions(radio, Int.MAX_VALUE),
            database.directAckExclusions().saturated(radio),
            database.directAckPrehistory().rows(),
        )
    }
    val before = snapshot()
    val result = block()
    assertEquals(before, snapshot(), "Strict refusal must not change message, pending, identity or retained history")
    return result
}
internal fun key(byte: Int = 0x44): Bytes = Bytes(ByteArray(32) { byte.toByte() })
internal fun entity(radio: RadioId = RADIO_A, id: UUID = CONTACT_A): EntityKey = EntityKey(radio, id)
internal fun device(radio: RadioId = RADIO_A, id: UUID = UUID.randomUUID()): DeviceDTO =
    DeviceDTO(id, radio, key(), "TestDevice", firmwareVersion = 8u, firmwareVersionString = "v1.11.0",
        multiAcks = 0u, lastConnected = AT)
internal fun frame(byte: Int = 0x44, name: String = "TestContact", flags: UByte = 0u): ContactFrame =
    ContactFrame(key(byte), ContactType.CHAT, flags, 2u, Bytes.of(1, 2), name, 1u, 37.7749, -122.4194, 1u)
internal fun contact(
    radio: RadioId = RADIO_A, id: UUID = CONTACT_A, publicKey: Bytes = key(), name: String = "TestContact",
): ContactDTO = ContactDTO(id, radio, publicKey, name, typeRawValue = ContactType.CHAT.rawValue, lastHeardTimestamp = 0u)
internal fun message(
    radio: RadioId = RADIO_A, id: UUID = UUID.randomUUID(), contactID: UUID? = CONTACT_A, channelIndex: UByte? = null,
    text: String = "test", timestamp: UInt = 1_700_000_000u, createdAt: Instant = AT,
    direction: MessageDirection = MessageDirection.OUTGOING, status: MessageStatus = MessageStatus.SENT,
): MessageDTO = MessageDTO(id, radio, contactID, channelIndex, text, timestamp, createdAt,
    direction = direction, status = status)
internal fun pending(
    radio: RadioId = RADIO_A, messageID: UUID = UUID.randomUUID(), sequence: Long = 0, attempt: Long? = 0,
): PendingSendDTO = PendingSendDTO(
    UUID.randomUUID(), radio, messageID, PendingSendKind.DM, CONTACT_A, null, false, "", 0u, null, sequence, AT, attempt,
)
internal fun session(
    radio: RadioId = RADIO_A, id: UUID = UUID.randomUUID(), publicKey: Bytes = key(),
): RemoteNodeSessionDTO = RemoteNodeSessionDTO(id, radio, publicKey, "TestRoom", RemoteNodeRole.ROOM_SERVER)
internal fun rx(
    radio: RadioId = RADIO_A, id: UUID = UUID.randomUUID(), timestamp: UInt? = 42u,
    receivedAt: Instant = AT, channelIndex: UByte? = 1u, payload: PayloadType = PayloadType.GROUP_TEXT,
): RxLogEntryDTO = RxLogEntryDTO(
    id, radio, receivedAt, 10.5, -65, RouteType.FLOOD, payload, 0u, null, 1u, Bytes.of(0x42),
    Bytes.of(0xAB, 0xCD, 0xEF), Bytes.of(0x15, 1, 2, 3), "fixture",
    channelIndex = channelIndex, channelName = "TestChannel", decryptStatus = DecryptStatus.SUCCESS,
    senderTimestamp = timestamp, payloadTypeBits = 5u, decodedText = "Hello mesh!",
)

class RepositoryClock(var now: Instant = AT) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
abstract class RepositoryTest {
    protected lateinit var db: MeshCoreDatabase
    protected lateinit var store: RoomPersistenceStore
    protected val clock = RepositoryClock()
    protected val scheduler = TestCoroutineScheduler()
    protected val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    protected val issues = mutableListOf<PersistenceStoreException>()
    protected val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun openRepository() {
        val immediate = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .addCallback(DirectAckStorage.callback)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        store = newStore()
    }

    protected fun newStore(): RoomPersistenceStore =
        RoomPersistenceStore(db, owner, clock, RepositoryIssueReporter { _, failure -> issues += failure })

    @After fun closeRepository() {
        try {
            runBlocking { store.close() }
        } finally {
            owner.cancel()
            db.close()
        }
    }
}
