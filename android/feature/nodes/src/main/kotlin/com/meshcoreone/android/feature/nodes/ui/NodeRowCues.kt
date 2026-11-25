// PortedFrom: MC1/Views/Contacts/ContactRowView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Contacts/DiscoveryView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.ui

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.maps.MapRoadDistanceMeasure
import com.meshcoreone.android.core.maps.MapRoadUnit
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.ui.RelativeTimestampText
import com.meshcoreone.android.feature.nodes.deps.StringListPreferences
import com.meshcoreone.android.feature.nodes.model.GeoDistance
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import java.time.Clock
import java.time.Instant

internal const val CONTACTS_SORT_KEY = "contactsSortOrder"
internal const val DISCOVERY_SORT_KEY = "discoverySortOrder"

internal fun StringListPreferences.nodeSort(key: String): NodeSortOrder =
    NodeSortOrder.entries.firstOrNull { it.rawValue == stringList(key)?.singleOrNull() }
        ?: NodeSortOrder.LAST_HEARD

internal fun StringListPreferences.setNodeSort(key: String, order: NodeSortOrder) =
    setStringList(key, listOf(order.rawValue))

@Composable
internal fun NodeFreshness(date: Instant?) {
    if (date != null && date > Instant.EPOCH) {
        RelativeTimestampText(date = date, clock = Clock.systemDefaultZone())
    }
}

@Composable
internal fun NodeLocationCue(node: Coordinate, userLocation: Coordinate?) {
    if (!node.isValidFix) return
    val locale = LocalConfiguration.current.locales[0]
    val location = stringResource(C.contactsRowLocation)
    val distance = userLocation?.takeIf { it.isValidFix }?.let {
        val meters = GeoDistance.meters(node, it)
        val road = MapRoadDistanceMeasure.measure(meters, MapRoadDistanceMeasure.measurementSystem(locale))
        val unit = when (road.unit) {
            MapRoadUnit.METER -> MeasureUnit.METER
            MapRoadUnit.KILOMETER -> MeasureUnit.KILOMETER
            MapRoadUnit.FOOT -> MeasureUnit.FOOT
            MapRoadUnit.YARD -> MeasureUnit.YARD
            MapRoadUnit.MILE -> MeasureUnit.MILE
        }
        MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT).formatMeasures(Measure(road.value, unit))
    }
    val text = if (distance == null) location else "$location · ${C.contactsRowAway(LocalContext.current.resources, distance)}"
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
