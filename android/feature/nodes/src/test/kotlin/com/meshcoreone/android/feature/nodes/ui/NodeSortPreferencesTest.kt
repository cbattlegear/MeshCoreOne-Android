// AndroidOnly: WP-311 Independent persistent node sort selections.
package com.meshcoreone.android.feature.nodes.ui

import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import com.meshcoreone.android.feature.nodes.support.MemoryPreferences
import kotlin.test.assertEquals
import org.junit.Test

class NodeSortPreferencesTest {
    @Test
    fun independentChoicesSurvivePreferenceRecreationAndUnknownValuesFallBack() {
        val original = MemoryPreferences()
        original.setNodeSort(CONTACTS_SORT_KEY, NodeSortOrder.DISTANCE)
        original.setNodeSort(DISCOVERY_SORT_KEY, NodeSortOrder.NAME)
        val recreated = MemoryPreferences().apply { values.putAll(original.values) }
        assertEquals(NodeSortOrder.DISTANCE, recreated.nodeSort(CONTACTS_SORT_KEY))
        assertEquals(NodeSortOrder.NAME, recreated.nodeSort(DISCOVERY_SORT_KEY))
        recreated.setStringList(DISCOVERY_SORT_KEY, listOf("unknown"))
        assertEquals(NodeSortOrder.LAST_HEARD, recreated.nodeSort(DISCOVERY_SORT_KEY))
        assertEquals(NodeSortOrder.DISTANCE, recreated.nodeSort(CONTACTS_SORT_KEY))
    }
}
