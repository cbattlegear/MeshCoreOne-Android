// AndroidOnly: WP-314 Persist source-compatible per-radio recent hop keys in app preferences.
package com.meshcoreone.android.feature.tools.trace

import android.content.SharedPreferences

class AndroidRecentHopsStorage(private val preferences: SharedPreferences) : RecentHopsStorage {
    override fun stringList(key: String): List<String>? =
        preferences.getString(key, null)?.takeIf(String::isNotEmpty)?.split(',')

    override fun setStringList(key: String, value: List<String>) {
        preferences.edit().putString(key, value.joinToString(",")).apply()
    }
}
