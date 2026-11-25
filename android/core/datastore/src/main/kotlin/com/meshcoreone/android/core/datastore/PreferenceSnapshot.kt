// PortedFrom: MC1Services/Sources/MC1Services/Services/AppStorageKey.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Immutable source-key values retain absence and unknown enum strings independently of defaults.
package com.meshcoreone.android.core.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.meshcoreone.android.core.model.SnapshotMap
import com.meshcoreone.android.core.model.snapshotMap
import com.meshcoreone.android.core.protocol.bytes.Bytes

class PreferenceSnapshot internal constructor(values: Map<String, PreferenceValue>) {
    val storedValues: SnapshotMap<String, PreferenceValue> = values.snapshotMap()

    fun contains(key: PreferenceKey<*>): Boolean = storedValues.containsKey(key.rawValue)
    fun <T : Any> stored(key: PreferenceKey<T>): T? = storedValues[key.rawValue]?.let(key::decode)
    operator fun <T : Any> get(key: PreferenceKey<T>): T = stored(key) ?: key.defaultValue
        ?: throw StorageFailure(StorageProblem.PreferenceHasNoDefault(key.rawValue), StorageOperation.READ)

    override fun equals(other: Any?): Boolean = other is PreferenceSnapshot && storedValues == other.storedValues
    override fun hashCode(): Int = storedValues.hashCode()

    companion object {
        internal fun from(preferences: Preferences): PreferenceSnapshot =
            PreferenceSnapshot(preferences.asMap().entries.associate { (key, value) ->
                key.name to when (value) {
                    is Boolean -> PreferenceValue.BooleanValue(value)
                    is String -> PreferenceValue.StringValue(value)
                    is Long -> PreferenceValue.IntegerValue(value)
                    is Int -> PreferenceValue.IntegerValue(value.toLong())
                    is Double -> PreferenceValue.DecimalValue(value)
                    is Float -> PreferenceValue.DecimalValue(value.toDouble())
                    is ByteArray -> if (key.name == AppStorageKey.recentReactionEmojis.rawValue) {
                        PreferenceValue.StringListValue(OrderedStrings.decode(value))
                    } else PreferenceValue.BinaryValue(Bytes(value))
                    else -> throw StorageFailure(
                        StorageProblem.PreferenceTypeMismatch(key.name), StorageOperation.READ,
                    )
                }
            })
    }
}

class PreferenceEditor internal constructor(private val preferences: MutablePreferences) {
    val snapshot: PreferenceSnapshot get() = PreferenceSnapshot.from(preferences)

    operator fun <T : Any> set(key: PreferenceKey<T>, value: T) {
        putRaw(key.rawValue, key.encode(value))
    }

    fun remove(key: PreferenceKey<*>) {
        preferences -= stringPreferencesKey(key.rawValue)
    }

    internal fun putRaw(name: String, value: PreferenceValue) {
        when (value) {
            is PreferenceValue.BooleanValue -> preferences[booleanPreferencesKey(name)] = value.value
            is PreferenceValue.StringValue -> preferences[stringPreferencesKey(name)] = value.value
            is PreferenceValue.IntegerValue -> preferences[longPreferencesKey(name)] = value.value
            is PreferenceValue.DecimalValue -> {
                if (!value.value.isFinite()) {
                    throw StorageFailure(StorageProblem.InvalidPreference(name), StorageOperation.WRITE)
                }
                preferences[doublePreferencesKey(name)] = value.value
            }
            is PreferenceValue.BinaryValue -> preferences[byteArrayPreferencesKey(name)] = value.value.toByteArray()
            is PreferenceValue.StringListValue -> preferences[byteArrayPreferencesKey(name)] = OrderedStrings.encode(value.value)
        }
    }
}

internal fun validateKnownPreferences(snapshot: PreferenceSnapshot) {
    val keys = AppStorageKey.all + listOf(
        AppearanceStorageKey.selectedThemeID, AppearanceStorageKey.appColorSchemePreference,
        BackupPreferenceKeys.regionSelection,
    )
    for (key in keys) {
        snapshot.storedValues[key.rawValue]?.let { key.decode(it) }
    }
    for ((name, value) in snapshot.storedValues) {
        if (value is PreferenceValue.DecimalValue && !value.value.isFinite()) {
            throw StorageFailure(StorageProblem.InvalidPreference(name), StorageOperation.READ)
        }
        val device = Regex("^device\\.[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\.")
        when {
            device.containsMatchIn(name) && name.endsWith(".autoUpdateLocation") ->
                PreferenceKey.BooleanKey(name, false).decode(value)
            device.containsMatchIn(name) && name.endsWith(".gpsSource") ->
                PreferenceKey.StringKey(name, "phone").decode(value)
            name.startsWith("scene.") && name.endsWith(".mapCameraRegion") ->
                PreferenceKey.StringKey(name, "").decode(value)
        }
    }
}
