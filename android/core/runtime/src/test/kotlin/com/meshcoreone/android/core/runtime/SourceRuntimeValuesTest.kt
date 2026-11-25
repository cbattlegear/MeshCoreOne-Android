// PortedFrom: MC1Services/Tests/MC1ServicesTests/Connection/LastConnectionStoreTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionIntentTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/DeviceConnectionStateTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/EventBroadcasterTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.canonicalString
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

class SourceRuntimeValuesTest {
    @TestFactory
    fun sourceCases() = listOf(
        original("LastConnectionStoreTests", "persist then read round-trips deviceID, radioID, and deviceName") {
            val preferences = TestPreferences()
            val store = LastConnectionStore(preferences, TestClock(testScheduler))
            val device = UUID.randomUUID(); val radio = RadioId(UUID.randomUUID())
            store.persist(device, radio, "Test Radio")
            assertEquals(device, store.read().deviceId); assertEquals(radio, store.read().radioId)
            assertEquals("Test Radio", store.read().deviceName)
            assertEquals(device.canonicalString(), preferences.read().text(PersistenceKeys.LAST_CONNECTED_DEVICE_ID))
        },
        original("LastConnectionStoreTests", "clear removes all three persisted values for the holder") {
            val store = LastConnectionStore(TestPreferences(), TestClock(testScheduler)); val id = UUID.randomUUID()
            store.persist(id, RadioId(UUID.randomUUID()), "Radio"); assertTrue(store.clear(id))
            assertNull(store.read().deviceId); assertNull(store.read().radioId); assertNull(store.read().deviceName)
        },
        original("LastConnectionStoreTests", "clear for a non-holder leaves last-connection keys intact") {
            val store = LastConnectionStore(TestPreferences(), TestClock(testScheduler)); val id = UUID.randomUUID()
            store.persist(id, RadioId(UUID.randomUUID()), "Holder")
            assertFalse(store.clear(UUID.randomUUID())); assertEquals(id, store.read().deviceId); assertEquals("Holder", store.read().deviceName)
        },
        original("LastConnectionStoreTests", "empty defaults read as nil") {
            val last = LastConnectionStore(TestPreferences(), TestClock(testScheduler)).read()
            assertNull(last.deviceId); assertNull(last.radioId); assertNull(last.diagnostic)
        },
        original("LastConnectionStoreTests", "malformed UUID strings read as nil") {
            val values = TestPreferences()
            values.values[PersistenceKeys.LAST_CONNECTED_DEVICE_ID] = RuntimePreferenceValue.Text("not-a-uuid")
            values.values[PersistenceKeys.LAST_CONNECTED_RADIO_ID] = RuntimePreferenceValue.Text("also-not-a-uuid")
            val result = LastConnectionStore(values, TestClock(testScheduler)).read()
            assertNull(result.deviceId); assertNull(result.radioId)
        },
        original("LastConnectionStoreTests", "bond verification round-trips for the stamped device and is nil for others") {
            val clock = TestClock(testScheduler); val store = LastConnectionStore(TestPreferences(), clock); val id = UUID.randomUUID()
            assertNull(store.bondVerificationDate(id))
            store.persistBondVerification(id)
            assertEquals(clock.instant, store.bondVerificationDate(id)); assertNull(store.bondVerificationDate(UUID.randomUUID()))
        },
        original("LastConnectionStoreTests", "a later bond verification for another device replaces the slot") {
            val store = LastConnectionStore(TestPreferences(), TestClock(testScheduler))
            val first = UUID.randomUUID(); val second = UUID.randomUUID()
            store.persistBondVerification(first); store.persistBondVerification(second)
            assertNull(store.bondVerificationDate(first)); assertNotNull(store.bondVerificationDate(second))
        },
        original("LastConnectionStoreTests", "clear removes the bond verification only for the bond-slot holder") {
            val store = LastConnectionStore(TestPreferences(), TestClock(testScheduler)); val id = UUID.randomUUID()
            store.persistBondVerification(id); store.clear(UUID.randomUUID()); assertNotNull(store.bondVerificationDate(id))
            store.clear(id); assertNull(store.bondVerificationDate(id))
        },
        original("LastConnectionStoreTests", "persistDisconnectDiagnostic prefixes a parseable ISO8601 timestamp") {
            val store = LastConnectionStore(TestPreferences(), TestClock(testScheduler))
            val summary = "source=unitTest, reason=verifyFormat"; store.persistDisconnectDiagnostic(summary)
            val text = assertNotNull(store.read().diagnostic)
            assertEquals(epochTime, Instant.parse(text.substringBefore(' '))); assertEquals(summary, text.substringAfter(' '))
        },
        original("ConnectionIntentTests", "wantsConnection returns true for .wantsConnection") {
            assertTrue(ConnectionIntent.WantsConnection().wantsConnection)
        },
        original("ConnectionIntentTests", "wantsConnection returns true for .wantsConnection(forceFullSync: true)") {
            assertTrue(ConnectionIntent.WantsConnection(true).wantsConnection)
        },
        original("ConnectionIntentTests", "wantsConnection returns false for .none") {
            assertFalse(ConnectionIntent.None.wantsConnection)
        },
        original("ConnectionIntentTests", "wantsConnection returns false for .userDisconnected") {
            assertFalse(ConnectionIntent.UserDisconnected.wantsConnection)
        },
        original("ConnectionIntentTests", "isUserDisconnected returns true only for .userDisconnected") {
            assertTrue(ConnectionIntent.UserDisconnected.persistedUserDisconnected)
            assertFalse(ConnectionIntent.None.persistedUserDisconnected); assertFalse(ConnectionIntent.WantsConnection().persistedUserDisconnected)
        },
        original("ConnectionIntentTests", "wantsConnection default is equatable") {
            assertEquals(ConnectionIntent.WantsConnection(false), ConnectionIntent.WantsConnection())
        },
        original("ConnectionIntentTests", "wantsConnection with different forceFullSync are not equal") {
            assertNotEquals(ConnectionIntent.WantsConnection(true), ConnectionIntent.WantsConnection(false))
        },
        original("ConnectionIntentTests", "wantsConnection replaces shouldBeConnected = true") {
            val intent = ConnectionIntent.WantsConnection()
            assertTrue(intent.wantsConnection); assertFalse(intent.persistedUserDisconnected)
        },
        original("ConnectionIntentTests", "userDisconnected replaces setUserDisconnected + shouldBeConnected = false") {
            val intent = ConnectionIntent.UserDisconnected
            assertFalse(intent.wantsConnection); assertTrue(intent.persistedUserDisconnected)
        },
        original("ConnectionIntentTests", "forceFullSync can be consumed and reset") {
            var intent = ConnectionIntent.WantsConnection(true)
            assertTrue(intent.forceFullSync); intent = ConnectionIntent.WantsConnection()
            assertFalse(intent.forceFullSync)
        },
        original("ConnectionIntentPersistenceTests", "userDisconnected persists and restores") {
            val store = LastConnectionStore(TestPreferences(), TestClock(testScheduler))
            store.persistIntent(ConnectionIntent.UserDisconnected); assertEquals(ConnectionIntent.UserDisconnected, store.restoredIntent())
        },
        original("ConnectionIntentPersistenceTests", "none clears persisted userDisconnected") {
            val prefs = TestPreferences(); val store = LastConnectionStore(prefs, TestClock(testScheduler))
            store.persistIntent(ConnectionIntent.UserDisconnected); store.persistIntent(ConnectionIntent.None)
            assertEquals(ConnectionIntent.None, store.restoredIntent()); assertFalse(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED in prefs.values)
        },
        original("ConnectionIntentPersistenceTests", "wantsConnection clears persisted userDisconnected") {
            val prefs = TestPreferences(); val store = LastConnectionStore(prefs, TestClock(testScheduler))
            store.persistIntent(ConnectionIntent.UserDisconnected); store.persistIntent(ConnectionIntent.WantsConnection(true))
            assertEquals(ConnectionIntent.None, store.restoredIntent()); assertFalse(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED in prefs.values)
        },
        original("ConnectionIntentPersistenceTests", "restored returns .none when nothing persisted") {
            assertEquals(ConnectionIntent.None, LastConnectionStore(TestPreferences(), TestClock(testScheduler)).restoredIntent())
        },
        original("DeviceConnectionStateTests", "isOperational returns true only for syncing and ready") {
            assertEquals(listOf(false, false, false, true, true), DeviceConnectionState.entries.map { it.isOperational })
            assertEquals(listOf(false, false, false, false, true), DeviceConnectionState.entries.map { it.canDrainSendQueue })
        },
        original("DeviceConnectionStateTests", "isConnected returns true for connected, syncing, and ready") {
            assertEquals(listOf(false, false, true, true, true), DeviceConnectionState.entries.map { it.isConnected })
        },
        original("EventBroadcasterTests", "two subscribers both receive every event in yield order") {
            val broadcaster = EventBroadcaster<Int>(); val a = broadcaster.subscribe(); val b = broadcaster.subscribe()
            val values = listOf(1, 2, 3, 4, 5); values.forEach(broadcaster::yield); broadcaster.finish()
            assertEquals(values, a.events.toList()); assertEquals(values, b.events.toList())
        },
        original("EventBroadcasterTests", "a cancelled subscriber is pruned and does not affect its sibling") {
            val broadcaster = EventBroadcaster<Int>(); val doomed = broadcaster.subscribe(); val survivor = broadcaster.subscribe()
            val consumer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { doomed.events.toList() }
            assertEquals(2, broadcaster.subscriberCount); consumer.cancelAndJoin(); assertEquals(1, broadcaster.subscriberCount)
            broadcaster.yield(7); broadcaster.finish(); assertEquals(listOf(7), survivor.events.toList())
        },
        original("EventBroadcasterTests", "finish ends every subscriber's for-await loop") {
            val broadcaster = EventBroadcaster<Int>(); val a = broadcaster.subscribe(); val b = broadcaster.subscribe()
            var aEnded = false; var bEnded = false
            val ca = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { a.events.toList(); aEnded = true }
            val cb = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { b.events.toList(); bEnded = true }
            broadcaster.finish(); ca.join(); cb.join(); assertTrue(aEnded && bEnded); assertEquals(0, broadcaster.subscriberCount)
        },
        original("EventBroadcasterTests", "an event yielded immediately after subscribe is never dropped") {
            val broadcaster = EventBroadcaster<String>(); val stream = broadcaster.subscribe()
            broadcaster.yield("first"); broadcaster.finish(); assertEquals(listOf("first"), stream.events.toList())
        },
        original("EventBroadcasterTests", "subscribing after finish returns a stream that completes immediately") {
            val broadcaster = EventBroadcaster<Int>(); broadcaster.finish(); assertTrue(broadcaster.subscribe().events.toList().isEmpty())
        },
        original("EventBroadcasterTests", "yield after finish reaches no subscriber") {
            val broadcaster = EventBroadcaster<Int>(); broadcaster.finish(); broadcaster.yield(42); assertEquals(0, broadcaster.subscriberCount)
        },
    )
}
