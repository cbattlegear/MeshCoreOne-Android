// AndroidOnly: WP-311 Nodes sort settings survive reopening the app preferences adapter.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class NodesPreferencesPersistenceTest {
    @Test
    fun distinctSortsSurviveAdapterRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("nodes-feature", Context.MODE_PRIVATE)
        val contacts = "wp311-test-contacts-sort"
        val discovery = "wp311-test-discovery-sort"
        try {
            SharedPreferencesStringLists(context).apply {
                setStringList(contacts, listOf("distance"))
                setStringList(discovery, listOf("name"))
            }
            val reopened = SharedPreferencesStringLists(context)
            assertEquals(listOf("distance"), reopened.stringList(contacts))
            assertEquals(listOf("name"), reopened.stringList(discovery))
        } finally {
            preferences.edit().remove(contacts).remove(discovery).commit()
        }
    }
}
