// AndroidOnly: WP-101 Prizm Mobile Observer privacy page: zones, what uploads and never uploads, delete my data.
package com.meshcoreone.android.feature.settings.prizm.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.settings.device.ui.ActionRow
import com.meshcoreone.android.feature.settings.device.ui.ConfirmDialog
import com.meshcoreone.android.feature.settings.device.ui.RowDivider
import com.meshcoreone.android.feature.settings.device.ui.SettingsScreenFrame
import com.meshcoreone.android.feature.settings.device.ui.SettingsSection
import com.meshcoreone.android.feature.settings.prizm.PrizmDeleteResult
import com.meshcoreone.android.feature.settings.prizm.PrizmDeleteStatus
import com.meshcoreone.android.feature.settings.prizm.PrizmPrivacyStateHolder
import com.meshcoreone.android.feature.settings.prizm.PrizmZone
import com.meshcoreone.android.feature.settings.prizm.PrizmZoneError

/** What the filter lets out, in plain words. Kept next to the filter table in docs/android/deviations/PRIZM-OBSERVER.md. */
internal val UPLOAD_ITEMS = listOf(
    R.string.prizm_upload_item_adverts, R.string.prizm_upload_item_channels, R.string.prizm_upload_item_metadata,
    R.string.prizm_upload_item_location, R.string.prizm_upload_item_health, R.string.prizm_upload_item_node,
)
internal val NEVER_ITEMS = listOf(
    R.string.prizm_never_item_messages, R.string.prizm_never_item_contacts, R.string.prizm_never_item_keys,
    R.string.prizm_never_item_zones, R.string.prizm_never_item_outside,
)

@Composable
fun PrizmPrivacyScreen(holder: PrizmPrivacyStateHolder, onDone: () -> Unit) {
    val state by holder.state.collectAsState()
    val form by holder.form.collectAsState()
    val delete by holder.delete.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    SettingsScreenFrame(UiText.Resource(R.string.prizm_privacy_title), onDone) {
        SettingsSection(UiText.Resource(R.string.prizm_zones_header), listOf(UiText.Resource(R.string.prizm_zones_footer))) {
            if (state.zones.isEmpty()) {
                Text(stringResource(R.string.prizm_zones_none), Modifier.padding(vertical = 12.dp))
            }
            state.zones.forEach { zone -> ZoneRow(zone) { holder.removeZone(zone.id) } }
        }
        SettingsSection(UiText.Resource(R.string.prizm_zone_add_header)) {
            ZoneForm(holder)
        }
        SettingsSection(UiText.Resource(R.string.prizm_uploads_header)) { BulletList(UPLOAD_ITEMS) }
        SettingsSection(UiText.Resource(R.string.prizm_never_header)) { BulletList(NEVER_ITEMS) }
        SettingsSection(null) {
            ActionRow(UiText.Resource(R.string.prizm_delete_data), delete != PrizmDeleteStatus.Working, { confirmDelete = true }, destructive = true)
        }
    }
    ConfirmDialog(
        confirmDelete, UiText.Resource(R.string.prizm_delete_title), UiText.Resource(R.string.prizm_delete_message),
        UiText.Resource(R.string.prizm_delete_confirm), onConfirm = { confirmDelete = false; holder.deleteMyData() },
        onDismiss = { confirmDelete = false },
    )
    (delete as? PrizmDeleteStatus.Finished)?.let { finished ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = holder::acknowledgeDelete,
            text = {
                Text(
                    when (val result = finished.result) {
                        PrizmDeleteResult.Deleted -> stringResource(R.string.prizm_delete_done)
                        PrizmDeleteResult.Unavailable -> stringResource(R.string.prizm_delete_unavailable)
                        is PrizmDeleteResult.Failed -> result.message
                    },
                )
            },
            confirmButton = { TextButton(holder::acknowledgeDelete, Modifier.sharedTouchTarget()) { Text(stringResource(R.string.prizm_dismiss)) } },
        )
    }
}

@Composable
private fun ZoneRow(zone: PrizmZone, onRemove: () -> Unit) {
    val lat = "%.5f".format(java.util.Locale.ROOT, zone.latitude)
    val lon = "%.5f".format(java.util.Locale.ROOT, zone.longitude)
    val summary = stringResource(R.string.prizm_zone_row, lat, lon, zone.radiusMeters.toInt())
    val removeLabel = stringResource(R.string.prizm_zone_remove, lat, lon)
    Row(Modifier.fillMaxWidth().sharedTouchTarget(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onRemove, Modifier.sharedTouchTarget().semantics { contentDescription = removeLabel }) {
            Text(stringResource(R.string.prizm_zone_remove_action))
        }
    }
    RowDivider()
}

@Composable
private fun ZoneForm(holder: PrizmPrivacyStateHolder) {
    val form by holder.form.collectAsState()
    ActionRow(UiText.Resource(R.string.prizm_zone_use_current), true, holder::useCurrentLocation)
    if (form.locationUnavailable) {
        Text(stringResource(R.string.prizm_zone_location_unavailable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    ZoneField(R.string.prizm_zone_latitude, form.latitude, holder::setLatitude, PrizmZoneError.LATITUDE in form.errors, R.string.prizm_zone_error_latitude, KeyboardType.Decimal)
    ZoneField(R.string.prizm_zone_longitude, form.longitude, holder::setLongitude, PrizmZoneError.LONGITUDE in form.errors, R.string.prizm_zone_error_longitude, KeyboardType.Decimal)
    ZoneField(R.string.prizm_zone_radius, form.radius, holder::setRadius, PrizmZoneError.RADIUS in form.errors, R.string.prizm_zone_error_radius, KeyboardType.Number)
    ActionRow(UiText.Resource(R.string.prizm_zone_add), true, { holder.addZone() })
}

@Composable
private fun ZoneField(label: Int, value: String, onChange: (String) -> Unit, isError: Boolean, errorText: Int, keyboard: KeyboardType) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, isError = isError,
        label = { Text(stringResource(label)) },
        supportingText = if (isError) ({ Text(stringResource(errorText)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().sharedTouchTarget(),
    )
}

@Composable
private fun BulletList(items: List<Int>) {
    items.forEachIndexed { index, id ->
        Text(stringResource(id), Modifier.fillMaxWidth().padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        if (index != items.lastIndex) RowDivider()
    }
}
