// PortedFrom: MC1Services/Sources/MC1Services/Connection/LastConnectionStore.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionIntent.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotMap
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.model.snapshotMap
import java.time.Instant
import java.util.UUID

sealed interface RuntimePreferenceValue {
    data class Text(val value: String) : RuntimePreferenceValue
    data class Flag(val value: Boolean) : RuntimePreferenceValue
    data class Date(val value: Instant) : RuntimePreferenceValue
}

class RuntimePreferenceSnapshot(values: Map<String, RuntimePreferenceValue>) {
    val values: SnapshotMap<String, RuntimePreferenceValue> = values.snapshotMap()
    fun text(key: String): String? = value<RuntimePreferenceValue.Text>(key)?.value
    fun flag(key: String): Boolean? = value<RuntimePreferenceValue.Flag>(key)?.value
    fun date(key: String): Instant? = value<RuntimePreferenceValue.Date>(key)?.value
    private inline fun <reified T : RuntimePreferenceValue> value(key: String): T? {
        val value = values[key] ?: return null
        return value as? T ?: throw RuntimePreferenceFailure(key)
    }
}

class RuntimePreferenceFailure(val key: String) : Exception("Stored connection preference has an invalid type: $key")

/** The adapter performs an atomic edit on the existing process-owned preference store. */
interface ProcessConnectionPreferences {
    suspend fun read(): RuntimePreferenceSnapshot
    suspend fun update(transform: (MutableMap<String, RuntimePreferenceValue>) -> Unit): RuntimePreferenceSnapshot
}

data class LastConnection(
    val deviceId: UUID?,
    val radioId: RadioId?,
    val deviceName: String?,
    val bondDeviceId: UUID?,
    val bondDate: Instant?,
    val diagnostic: String?,
)

class LastConnectionStore(
    private val preferences: ProcessConnectionPreferences,
    private val clock: RuntimeClock,
) {
    suspend fun read(): LastConnection {
        val value = preferences.read()
        return LastConnection(
            parseUUID(value.text(PersistenceKeys.LAST_CONNECTED_DEVICE_ID)),
            parseUUID(value.text(PersistenceKeys.LAST_CONNECTED_RADIO_ID))?.let(::RadioId),
            value.text(PersistenceKeys.LAST_CONNECTED_DEVICE_NAME),
            parseUUID(value.text(PersistenceKeys.LAST_BOND_VERIFIED_DEVICE_ID)),
            value.date(PersistenceKeys.LAST_BOND_VERIFIED_DATE),
            value.text(PersistenceKeys.LAST_DISCONNECT_DIAGNOSTIC),
        )
    }

    suspend fun persist(deviceId: UUID, radioId: RadioId, deviceName: String) {
        preferences.update {
            it[PersistenceKeys.LAST_CONNECTED_DEVICE_ID] = RuntimePreferenceValue.Text(deviceId.canonicalString())
            it[PersistenceKeys.LAST_CONNECTED_RADIO_ID] = RuntimePreferenceValue.Text(radioId.canonicalString)
            it[PersistenceKeys.LAST_CONNECTED_DEVICE_NAME] = RuntimePreferenceValue.Text(deviceName)
        }
    }

    suspend fun clear(deviceId: UUID): Boolean {
        var wasHolder = false
        preferences.update {
            if (parseUUID((it[PersistenceKeys.LAST_CONNECTED_DEVICE_ID] as? RuntimePreferenceValue.Text)?.value) == deviceId) {
                wasHolder = true
                it.remove(PersistenceKeys.LAST_CONNECTED_DEVICE_ID)
                it.remove(PersistenceKeys.LAST_CONNECTED_RADIO_ID)
                it.remove(PersistenceKeys.LAST_CONNECTED_DEVICE_NAME)
            }
            if (parseUUID((it[PersistenceKeys.LAST_BOND_VERIFIED_DEVICE_ID] as? RuntimePreferenceValue.Text)?.value) == deviceId) {
                it.remove(PersistenceKeys.LAST_BOND_VERIFIED_DEVICE_ID)
                it.remove(PersistenceKeys.LAST_BOND_VERIFIED_DATE)
            }
        }
        return wasHolder
    }

    suspend fun persistBondVerification(deviceId: UUID, stillCurrent: () -> Boolean = { true }) {
        preferences.update {
            if (!stillCurrent()) return@update
            it[PersistenceKeys.LAST_BOND_VERIFIED_DEVICE_ID] = RuntimePreferenceValue.Text(deviceId.canonicalString())
            it[PersistenceKeys.LAST_BOND_VERIFIED_DATE] = RuntimePreferenceValue.Date(clock.instant)
        }
    }

    suspend fun bondVerificationDate(deviceId: UUID): Instant? {
        val values = preferences.read()
        return if (values.text(PersistenceKeys.LAST_BOND_VERIFIED_DEVICE_ID) == deviceId.canonicalString())
            values.date(PersistenceKeys.LAST_BOND_VERIFIED_DATE) else null
    }

    suspend fun persistDisconnectDiagnostic(summary: String) {
        preferences.update {
            it[PersistenceKeys.LAST_DISCONNECT_DIAGNOSTIC] =
                RuntimePreferenceValue.Text("${clock.instant} $summary")
        }
    }

    suspend fun restoredIntent(): ConnectionIntent =
        ConnectionIntent.restored(preferences.read().flag(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED) == true)

    suspend fun persistIntent(intent: ConnectionIntent, stillCurrent: () -> Boolean = { true }) {
        preferences.update {
            if (!stillCurrent()) return@update
            if (intent == ConnectionIntent.UserDisconnected) {
                it[PersistenceKeys.USER_EXPLICITLY_DISCONNECTED] = RuntimePreferenceValue.Flag(true)
            } else it.remove(PersistenceKeys.USER_EXPLICITLY_DISCONNECTED)
        }
    }

    companion object {
        fun parseUUID(raw: String?): UUID? {
            if (raw == null || !UUID_TEXT.matches(raw)) return null
            return UUID.fromString(raw)
        }
        private val UUID_TEXT = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
    }
}
