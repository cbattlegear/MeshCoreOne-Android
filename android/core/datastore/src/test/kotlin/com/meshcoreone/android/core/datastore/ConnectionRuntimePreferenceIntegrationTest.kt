// PortedFrom: MC1Services/Tests/MC1ServicesTests/Connection/LastConnectionStoreTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionIntentTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: connection preference role over the real process DataStore.
package com.meshcoreone.android.core.datastore

import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.runtime.*
import java.time.Instant
import java.util.UUID
import kotlin.math.floor
import kotlin.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ConnectionRuntimePreferenceIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val clock = object : RuntimeClock {
        override val elapsed = Duration.ZERO
        override val instant = Instant.ofEpochSecond(1_704_067_200, 125_000_000)
        override suspend fun sleep(duration: Duration) = delay(duration)
    }

    @Test
    fun actualCanonicalLastRadioAndIntentSurviveProcessStoreReopen() = runBlocking {
        withStorage(temporary.newFolder()) { h ->
            var role = PreferenceAdapter(h.owner.preferences)
            var last = LastConnectionStore(role, clock)
            val id = UUID.randomUUID(); val radio = RadioId(UUID.randomUUID())
            last.persist(id, radio, "Native Radio")
            last.persistBondVerification(id)
            last.persistDisconnectDiagnostic("source=runtimeTest")
            last.persistIntent(ConnectionIntent.UserDisconnected)
            assertEquals(id.canonicalString(), h.owner.preferences.snapshot().stored(
                PreferenceKey.StringKey(PersistenceKeys.LAST_CONNECTED_DEVICE_ID, null)))
            h.reopen()
            role = PreferenceAdapter(h.owner.preferences); last = LastConnectionStore(role, clock)
            assertEquals(id, last.read().deviceId); assertEquals(radio, last.read().radioId)
            assertEquals("Native Radio", last.read().deviceName); assertEquals(clock.instant, last.bondVerificationDate(id))
            assertEquals(ConnectionIntent.UserDisconnected, last.restoredIntent())
            last.persistIntent(ConnectionIntent.WantsConnection(true))
            assertFalse(h.owner.preferences.snapshot().contains(PreferenceKey.BooleanKey(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED, null)))
            h.reopen()
            assertEquals(ConnectionIntent.None, LastConnectionStore(PreferenceAdapter(h.owner.preferences), clock).restoredIntent())
        }
    }

    @Test
    fun connectionUpdatesPreserveRawOrderedListsUnknownSelectionsAndPerDevicePresence() = runBlocking {
        withStorage(temporary.newFolder()) { h ->
            val id = UUID.randomUUID(); val other = UUID.randomUUID()
            val raw = listOf("custom", "custom", "non-normalized").snapshot()
            h.owner.preferences.set(AppStorageKey.recentReactionEmojis, raw)
            h.owner.preferences.set(AppStorageKey.translationTargetLanguage, "unknown-future-locale")
            assertFalse(h.owner.devicePreferences.hasSetGPSSource(id))
            assertFalse(h.owner.preferences.snapshot().contains(AppStorageKey.showInlineImages))
            h.owner.devicePreferences.setGPSSource(GPSSource.PHONE, id)
            h.owner.devicePreferences.setAutoUpdateLocationEnabled(true, id)
            val last = LastConnectionStore(PreferenceAdapter(h.owner.preferences), clock)
            last.persist(id, RadioId(UUID.randomUUID()), "First")
            last.persist(other, RadioId(UUID.randomUUID()), "Second")
            last.clear(id)
            assertEquals(other, last.read().deviceId)
            assertEquals(raw, h.owner.preferences.get(AppStorageKey.recentReactionEmojis))
            assertEquals("unknown-future-locale", h.owner.preferences.get(AppStorageKey.translationTargetLanguage))
            assertTrue(h.owner.devicePreferences.hasSetGPSSource(id)); assertTrue(h.owner.devicePreferences.isAutoUpdateLocationEnabled(id))
            assertFalse(h.owner.devicePreferences.hasSetGPSSource(other))
            assertFalse(h.owner.preferences.snapshot().contains(AppStorageKey.showInlineImages))
            h.reopen()
            assertEquals(raw, h.owner.preferences.get(AppStorageKey.recentReactionEmojis))
            assertTrue(h.owner.devicePreferences.hasSetGPSSource(id))
            assertFalse(h.owner.devicePreferences.hasSetGPSSource(other))
        }
    }

    @Test
    fun distinctLastConnectionAndBondHoldersClearIndependentlyInTheActualStore() = runBlocking {
        withStorage(temporary.newFolder()) { h ->
            val last = LastConnectionStore(PreferenceAdapter(h.owner.preferences), clock)
            val bonded = UUID.randomUUID(); val wifi = UUID.randomUUID()
            last.persistBondVerification(bonded)
            last.persist(wifi, RadioId(UUID.randomUUID()), "WiFi")
            last.clear(wifi)
            assertNull(last.read().deviceId); assertEquals(clock.instant, last.bondVerificationDate(bonded))
            last.clear(bonded); assertNull(last.bondVerificationDate(bonded))
        }
    }

    @Test
    fun missingPerDeviceDefaultsAreWrittenOnceAndExplicitFalseIsNeverOverwritten() = runBlocking {
        withStorage(temporary.newFolder()) { h ->
            val id = UUID.randomUUID()
            suspend fun initializeMissingDefaults() {
                h.owner.preferences.update {
                    val key = PreferenceKey.StringKey("device.${id.canonicalString()}.gpsSource", null)
                    if (!snapshot.contains(key)) this[key] = GPSSource.PHONE.rawValue
                }
            }
            initializeMissingDefaults(); initializeMissingDefaults()
            assertTrue(h.owner.devicePreferences.hasSetGPSSource(id))
            h.owner.devicePreferences.setGPSSource(GPSSource.DEVICE, id)
            h.owner.devicePreferences.setAutoUpdateLocationEnabled(false, id)
            initializeMissingDefaults(); h.reopen(); initializeMissingDefaults()
            assertEquals(GPSSource.DEVICE, h.owner.devicePreferences.gpsSource(id))
            assertFalse(h.owner.devicePreferences.isAutoUpdateLocationEnabled(id))
        }
    }

    @Test
    fun closedActualStoreErrorsNeverBecomeMissingConnectionSuccess() = runBlocking {
        val h = StorageHarness(temporary.newFolder())
        val last = LastConnectionStore(PreferenceAdapter(h.owner.preferences), clock)
        h.close()
        val failure = assertFailsWith<StorageFailure> { last.read() }
        assertEquals(StorageProblem.OwnerClosed, failure.problem)
        assertTrue(h.reporter.failures.isNotEmpty())
    }

    @Test
    fun queuedBondRefreshCannotRecreateForgottenSlotAfterRealDataStoreClear() = runBlocking {
        withStorage(temporary.newFolder()) { h ->
            val adapter = PreferenceAdapter(h.owner.preferences)
            val id = UUID.randomUUID()
            val release = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            var epoch = 0L
            val queued = object : ProcessConnectionPreferences by adapter {
                override suspend fun update(transform: (MutableMap<String, RuntimePreferenceValue>) -> Unit): RuntimePreferenceSnapshot {
                    entered.complete(Unit)
                    release.await()
                    return adapter.update(transform)
                }
            }
            val last = LastConnectionStore(adapter, clock)
            last.persistBondVerification(id)
            val captured = epoch
            val refresh = async(start = CoroutineStart.UNDISPATCHED) {
                LastConnectionStore(queued, clock).persistBondVerification(id) { epoch == captured }
            }
            entered.await()
            epoch++
            last.clear(id)
            release.complete(Unit); refresh.await()
            assertNull(last.bondVerificationDate(id))
            assertFalse(h.owner.preferences.snapshot().contains(
                PreferenceKey.StringKey(PersistenceKeys.LAST_BOND_VERIFIED_DEVICE_ID, null)))
            h.reopen()
            assertNull(LastConnectionStore(PreferenceAdapter(h.owner.preferences), clock).bondVerificationDate(id))
        }
    }

    private class PreferenceAdapter(private val preferences: PreferenceStore) : ProcessConnectionPreferences {
        private val strings = listOf(
            PersistenceKeys.LAST_CONNECTED_DEVICE_ID, PersistenceKeys.LAST_CONNECTED_RADIO_ID,
            PersistenceKeys.LAST_CONNECTED_DEVICE_NAME, PersistenceKeys.LAST_DISCONNECT_DIAGNOSTIC,
            PersistenceKeys.LAST_BOND_VERIFIED_DEVICE_ID,
        )
        private val flag = PreferenceKey.BooleanKey(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED, null)
        private val date = PreferenceKey.DecimalKey(PersistenceKeys.LAST_BOND_VERIFIED_DATE, 0.0)
        override suspend fun read(): RuntimePreferenceSnapshot = from(preferences.snapshot())
        override suspend fun update(transform: (MutableMap<String, RuntimePreferenceValue>) -> Unit): RuntimePreferenceSnapshot {
            val result = preferences.update {
                val values = from(snapshot).values.toMutableMap()
                transform(values)
                val admitted = strings.toSet() + setOf(flag.rawValue, date.rawValue)
                check(values.keys.all { it in admitted })
                for (name in strings) {
                    val key = PreferenceKey.StringKey(name, null)
                    val value = values[name]
                    if (value == null) remove(key) else this[key] = (value as RuntimePreferenceValue.Text).value
                }
                values[flag.rawValue].let {
                    if (it == null) remove(flag) else this[flag] = (it as RuntimePreferenceValue.Flag).value
                }
                values[date.rawValue].let {
                    if (it == null) remove(date) else {
                        val instant = (it as RuntimePreferenceValue.Date).value
                        this[date] = instant.epochSecond + instant.nano / 1_000_000_000.0
                    }
                }
            }
            return from(result)
        }
        private fun from(snapshot: PreferenceSnapshot): RuntimePreferenceSnapshot {
            val values = mutableMapOf<String, RuntimePreferenceValue>()
            for (name in strings) snapshot.stored(PreferenceKey.StringKey(name, null))?.let { values[name] = RuntimePreferenceValue.Text(it) }
            snapshot.stored(flag)?.let { values[flag.rawValue] = RuntimePreferenceValue.Flag(it) }
            snapshot.stored(date)?.let {
                val seconds = floor(it).toLong()
                values[date.rawValue] = RuntimePreferenceValue.Date(Instant.ofEpochSecond(seconds, ((it - seconds) * 1_000_000_000).toLong()))
            }
            return RuntimePreferenceSnapshot(values)
        }
    }
}
