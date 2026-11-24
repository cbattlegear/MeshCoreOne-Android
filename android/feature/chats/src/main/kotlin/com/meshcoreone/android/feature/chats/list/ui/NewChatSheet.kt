// PortedFrom: MC1/Views/Chats/NewChatView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ContactMatchRow.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.ui.AvatarImageCache
import com.meshcoreone.android.core.ui.ContactAvatar
import com.meshcoreone.android.core.ui.RelativeTimestampText
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.chats.list.NewChatContactKind
import com.meshcoreone.android.feature.chats.list.NewChatStateHolder
import com.meshcoreone.android.feature.chats.list.newChatKind
import java.time.Clock

/** New Chat bottom sheet: contact picker for a direct message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatSheet(
    holder: NewChatStateHolder,
    radioId: RadioId?,
    onSelectContact: (ContactDTO) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    avatarCache: AvatarImageCache? = null,
) {
    val state by holder.state.collectAsState()
    LaunchedEffect(radioId) { holder.load(radioId) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(AppChatsStrings.chatsNewChatTitle), style = MaterialTheme.typography.titleLarge)
                TextButton(onDismiss, Modifier.sharedTouchTarget()) { Text(stringResource(AppChatsStrings.chatsCommonCancel)) }
            }
            OutlinedTextField(
                state.searchText, holder::setSearchText, Modifier.fillMaxWidth().sharedTouchTarget(), singleLine = true,
                placeholder = { Text(stringResource(AppChatsStrings.chatsNewChatSearchPlaceholder)) },
            )
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                state.showsEmptyState -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(AppChatsStrings.chatsNewChatEmptyStateTitle), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(AppChatsStrings.chatsNewChatEmptyStateDescription), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn {
                    items(state.filteredContacts(), key = { it.id }) { contact ->
                        NewChatRow(contact, avatarCache) { onSelectContact(contact) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewChatRow(contact: ContactDTO, avatarCache: AvatarImageCache?, onClick: () -> Unit) {
    val label = when (contact.newChatKind()) {
        NewChatContactKind.FLOOD_ROUTING -> stringResource(AppChatsStrings.chatsConnectionStatusFloodRouting)
        NewChatContactKind.DIRECT -> stringResource(AppChatsStrings.chatsNewChatContactTypeDirect)
        NewChatContactKind.REPEATER -> stringResource(AppChatsStrings.chatsNewChatContactTypeRepeater)
        NewChatContactKind.NONE -> ""
    }
    Row(
        Modifier.fillMaxWidth().sharedTouchTarget().clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${contact.displayName}, $label" }.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(contact.displayName, 40.dp)
        Column {
            Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** How a [ContactMatchRow] reports selection. */
sealed interface ContactMatchSelection {
    data class Toggle(val isSelected: Boolean) : ContactMatchSelection
    data object Tap : ContactMatchSelection
}

/**
 * A contact matched by a channel sender's name. The timestamp is the on-air last advert only:
 * `recencyTimestamp` includes lastModified (path updates, favorite toggles) and would claim a
 * freshness the node did not earn. [distanceText] is preformatted by the caller (needs the user location).
 */
@Composable
fun ContactMatchRow(
    contact: ContactDTO,
    selection: ContactMatchSelection,
    clock: Clock,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    distanceText: String? = null,
) {
    LocalConfiguration.current
    val resources = LocalContext.current.resources
    val selectedLabel = stringResource(AppChatsStrings.chatsContactMatchAccessibilitySelected)
    val notSelectedLabel = stringResource(AppChatsStrings.chatsContactMatchAccessibilityNotSelected)
    val typeLabel = stringResource(
        when (contact.type) {
            ContactType.CHAT -> AppContactsStrings.contactsNodeKindChat
            ContactType.REPEATER -> AppContactsStrings.contactsNodeKindRepeater
            ContactType.ROOM -> AppContactsStrings.contactsNodeKindRoom
            ContactType.SENSOR -> com.meshcoreone.android.core.l10n.R.string.l10n_app_remotenodes_remotenodes_status_sensor_genericsensor
            ContactType.UNKNOWN -> com.meshcoreone.android.core.l10n.R.string.l10n_app_remotenodes_remotenodes_status_unknown
        },
    )
    val key = AppChatsStrings.chatsContactMatchKey(resources, contact.publicKey.hexString.uppercase().chunked(2).joinToString(" "))
    Row(
        modifier.fillMaxWidth().sharedTouchTarget().clickable(role = if (selection is ContactMatchSelection.Toggle) Role.Checkbox else Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = contact.displayName
                if (selection is ContactMatchSelection.Toggle) {
                    stateDescription = if (selection.isSelected) selectedLabel else notSelectedLabel
                    selected = selection.isSelected
                }
            }.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ContactAvatar(contact.displayName, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(contact.displayName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
            RelativeTimestampText(contact.lastAdvertTimestamp, clock)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(typeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (contact.hasLocation) {
                    Icon(MeshSymbol.SIGNAL.vector, stringResource(AppContactsStrings.contactsRowLocation), Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    distanceText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Text(key, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline, maxLines = 2)
        }
    }
}
