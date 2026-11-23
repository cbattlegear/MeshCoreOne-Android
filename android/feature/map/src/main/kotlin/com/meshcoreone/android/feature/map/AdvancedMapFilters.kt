// AndroidOnly: WP-312 Native controls for post-pin main-map advanced filters.
package com.meshcoreone.android.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.maps.MapDuration
import com.meshcoreone.android.core.maps.MapDurationUnit
import com.meshcoreone.android.core.maps.MapFilterState
import com.meshcoreone.android.core.maps.MapHopRange
import com.meshcoreone.android.core.maps.MapLastHeardRange

private val touchTarget = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)

@Composable
internal fun ActiveAdvancedFilters(
    filter: MapFilterState,
    onFilter: ((MapFilterState) -> MapFilterState) -> Unit,
    onOpen: () -> Unit,
) {
    val noLimit = stringResource(R.string.map_advanced_no_limit)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (filter.lastHeard.isActive) {
            val name = stringResource(R.string.map_advanced_last_heard)
            val min = filter.lastHeard.minAge?.let { durationLabel(it) } ?: noLimit
            val max = filter.lastHeard.maxAge?.let { durationLabel(it) } ?: noLimit
            ActiveDimension(name, stringResource(R.string.map_advanced_range, name, min, max), onOpen) {
                onFilter { it.copy(lastHeard = MapLastHeardRange.of()) }
            }
        }
        if (filter.hops.isActive) {
            val name = stringResource(R.string.map_advanced_hops)
            ActiveDimension(name, stringResource(R.string.map_advanced_range, name, filter.hops.min.toString(),
                filter.hops.max?.toString() ?: noLimit), onOpen) {
                onFilter { it.copy(hops = MapHopRange.of()) }
            }
        }
    }
}

@Composable
private fun ActiveDimension(name: String, summary: String, onOpen: () -> Unit, onClear: () -> Unit) {
    AssistChip(onClick = onOpen, modifier = touchTarget, label = { Text(summary) })
    TextButton(onClick = onClear, modifier = touchTarget, content = {
        Text(stringResource(R.string.map_advanced_clear, name))
    })
}

@Composable
internal fun AdvancedMapFilters(
    filter: MapFilterState,
    onFilter: ((MapFilterState) -> MapFilterState) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map_advanced_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()).testTag("map:advanced-scroll"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.map_advanced_last_heard), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val presets = listOf(null, MapDuration.of(15, MapDurationUnit.minutes),
                        MapDuration.of(1, MapDurationUnit.hours), MapDuration.of(2, MapDurationUnit.hours),
                        MapDuration.of(6, MapDurationUnit.hours), MapDuration.of(24, MapDurationUnit.hours),
                        MapDuration.of(3, MapDurationUnit.days), MapDuration.of(7, MapDurationUnit.days))
                    presets.forEach { duration ->
                        val range = MapLastHeardRange.of(maxAge = duration)
                        FilterChip(
                            selected = filter.lastHeard == range,
                            onClick = { onFilter { it.copy(lastHeard = range) } },
                            modifier = touchTarget,
                            label = { Text(duration?.let { durationLabel(it) } ?: stringResource(R.string.map_advanced_any_time)) },
                        )
                    }
                }
                DurationBound(R.string.map_advanced_minimum, filter.lastHeard.minAge, "min-age") { value ->
                    onFilter { it.copy(lastHeard = it.lastHeard.withMinAge(value)) }
                }
                DurationBound(R.string.map_advanced_maximum, filter.lastHeard.maxAge, "max-age") { value ->
                    onFilter { it.copy(lastHeard = it.lastHeard.withMaxAge(value)) }
                }
                Text(stringResource(R.string.map_advanced_heard_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.map_advanced_hops), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(null, 0, 1, 2, 3).forEach { upper ->
                        val range = MapHopRange.of(max = upper)
                        FilterChip(
                            selected = filter.hops == range,
                            onClick = { onFilter { it.copy(hops = range) } },
                            modifier = touchTarget,
                            label = { Text(when (upper) {
                                null -> stringResource(R.string.map_advanced_any)
                                0 -> stringResource(R.string.map_advanced_direct)
                                else -> "≤ $upper"
                            }) },
                        )
                    }
                }
                NumberBound(R.string.map_advanced_minimum, filter.hops.min.takeIf { it != 0 }, "min-hops", 63) { value ->
                    onFilter { it.copy(hops = it.hops.withMin(value ?: 0)) }
                }
                NumberBound(R.string.map_advanced_maximum, filter.hops.max, "max-hops", 63) { value ->
                    onFilter { it.copy(hops = it.hops.withMax(value)) }
                }
                Text(stringResource(R.string.map_advanced_hops_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = touchTarget) {
                Text(stringResource(R.string.l10n_app_map_map_common_done))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onFilter { it.copy(lastHeard = MapLastHeardRange.of(), hops = MapHopRange.of()) }
            }, modifier = touchTarget.testTag("map:advanced-reset")) {
                Text(stringResource(R.string.map_advanced_reset))
            }
        },
    )
}

@Composable
private fun DurationBound(label: Int, value: MapDuration?, tag: String, onChange: (MapDuration?) -> Unit) {
    var unit by rememberSaveable { mutableStateOf(MapDurationUnit.hours) }
    LaunchedEffect(value?.unit) { if (value != null) unit = value.unit }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberBound(label, value?.value, tag, 9999, Modifier.weight(1f), min = 1) {
            onChange(it?.let { number -> MapDuration.of(number, unit) })
        }
        var open by remember { mutableStateOf(false) }
        Column {
            AssistChip(onClick = { open = true }, modifier = touchTarget, label = { Text(unitLabel(unit)) })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                MapDurationUnit.entries.forEach { choice ->
                    DropdownMenuItem(text = { Text(unitLabel(choice)) }, modifier = touchTarget, onClick = {
                        unit = choice
                        open = false
                        if (value != null) onChange(MapDuration.of(value.value, choice))
                    })
                }
            }
        }
    }
}

@Composable
private fun NumberBound(
    label: Int, value: Int?, tag: String, max: Int, modifier: Modifier = Modifier, min: Int = 0,
    onChange: (Int?) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(value?.toString() ?: "") }
    LaunchedEffect(value) { text = value?.toString() ?: "" }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            if (input.isEmpty()) {
                text = input
                onChange(null)
            } else if (input.all(Char::isDigit)) {
                val parsed = input.toIntOrNull()
                if (parsed != null && parsed in min..max) {
                    text = input
                    onChange(parsed)
                }
            }
        },
        label = { Text(stringResource(label)) },
        placeholder = { Text(stringResource(R.string.map_advanced_no_limit)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier.fillMaxWidth().testTag("map:$tag"),
    )
}

@Composable
private fun unitLabel(unit: MapDurationUnit): String = stringResource(when (unit) {
    MapDurationUnit.minutes -> R.string.map_advanced_minutes
    MapDurationUnit.hours -> R.string.map_advanced_hours
    MapDurationUnit.days -> R.string.map_advanced_days
})

@Composable
private fun durationLabel(duration: MapDuration): String =
    stringResource(R.string.map_advanced_duration, duration.value, unitLabel(duration.unit)).trim()
