// PortedFrom: MC1/Views/Components/ExpandableSettingsSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Components/GlassFilterBar.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Components/ConversationQuickActionsSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Components/NotificationLevelPicker.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Components/TintedLabel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/View+RadioDisabled.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.RetainedUiStrings
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.SnapshotList

data class ExpandableSectionState(
    val isExpanded: Boolean,
    val isLoaded: Boolean,
    val isLoading: Boolean,
    val hasError: Boolean,
) {
    val needsInitialLoad: Boolean get() = isExpanded && !isLoaded && !isLoading
    fun needsLoadAfterExpansion(expanded: Boolean): Boolean = expanded && !isLoaded && !isLoading
}

class InitialSectionLoad(private val state: () -> ExpandableSectionState, private val requestLoad: () -> Unit) {
    private val entered = java.util.concurrent.atomic.AtomicBoolean(false)
    fun onEnter() {
        if (entered.compareAndSet(false, true)) {
            if (state().needsInitialLoad) requestLoad()
        }
    }
}

@Composable
fun ExpandableSettingsSection(
    title: UiText,
    icon: MeshSymbol,
    state: ExpandableSectionState,
    onExpandedChange: (Boolean) -> Unit,
    onLoad: () -> Unit,
    modifier: Modifier = Modifier,
    footer: UiText? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolved = uiString(title)
    val expansionDescription = expandedDescription(state.isExpanded)
    val background = sharedPaintedSurface()
    val errorColor = sharedErrorForeground(background)
    Surface(modifier.fillMaxWidth().semantics { sharedPaintedSurface = background },
        shape = MaterialTheme.shapes.medium, color = background) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).sharedTouchTarget().clickable(role = Role.Button) {
                    val expanded = !state.isExpanded
                    onExpandedChange(expanded)
                    if (state.needsLoadAfterExpansion(expanded)) onLoad()
                }.semantics {
                    heading()
                    stateDescription = expansionDescription
                    if (state.isExpanded) collapse { onExpandedChange(false); true }
                    else expand {
                        onExpandedChange(true)
                        if (state.needsLoadAfterExpansion(true)) onLoad()
                        true
                    }
                }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon.vector, null, tint = MaterialTheme.colorScheme.primary)
                    Text(resolved, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                }
                SectionReloadButton(state.isLoading, state.isExpanded && state.isLoaded,
                    false, false, title, onLoad)
            }
            if (state.isExpanded) {
                content()
                if (state.hasError && !state.isLoaded) {
                    Text(uiString(UiText.Resource(AppLocalizableStrings.commonErrorFailedToLoad)),
                        modifier = Modifier.semantics { sharedEmittedForeground = errorColor },
                        color = errorColor)
                    TextButton(onClick = onLoad, enabled = !state.isLoading, modifier = Modifier.sharedTouchTarget()) {
                        Text(uiString(UiText.Resource(AppLocalizableStrings.commonTryAgain)))
                    }
                }
            }
            if (footer != null) Text(uiString(footer), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun expandedDescription(expanded: Boolean): String = uiString(UiText.Resource(
    if (expanded) AppChatsStrings.chatsMessageActionExpanded else AppChatsStrings.chatsMessageActionCollapsed,
))

@Immutable
data class FilterChoice<Id : Any>(val id: Id, val title: UiText)

@Composable
fun <Id : Any> GlassFilterBar(
    choices: SnapshotList<FilterChoice<Id>>,
    selected: Id,
    isSearching: Boolean,
    pickerLabel: UiText,
    onSelect: (Id) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = uiString(pickerLabel)
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (choice in choices) {
            FilterChip(selected = choice.id == selected, onClick = { onSelect(choice.id) },
                enabled = !isSearching,
                label = { Text(uiString(choice.title), style = MaterialTheme.typography.labelLarge) })
        }
    }
}

@Composable
fun NotificationLevelPicker(
    selection: NotificationLevel,
    onSelectionChange: (NotificationLevel) -> Unit,
    modifier: Modifier = Modifier,
    availableLevels: SnapshotList<NotificationLevel> = NotificationLevel.channelLevels,
) {
    val group = uiString(UiText.Resource(AppChatsStrings.chatsNotificationLevelLabel))
    val description = uiString(UiText.Resource(selection.accessibilityResource))
    val hint = uiString(UiText.Resource(AppChatsStrings.chatsNotificationLevelHint))
    Column(modifier.fillMaxWidth().semantics {
        contentDescription = group
        stateDescription = description
    }) {
        Text(group, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (level in availableLevels) {
                FilterChip(
                    selected = level == selection,
                    onClick = { onSelectionChange(level) },
                    modifier = Modifier.sharedTouchTarget().semantics { onClick(label = hint, action = null) },
                    label = { Text(uiString(UiText.Resource(level.labelResource))) },
                    leadingIcon = {
                        Icon(when (level) {
                            NotificationLevel.MUTED -> MeshSymbol.WARNING
                            NotificationLevel.MENTIONS_ONLY -> MeshSymbol.HASHTAG
                            NotificationLevel.ALL -> MeshSymbol.MESSAGES
                        }.vector, null)
                    },
                )
            }
        }
    }
}

@Composable
fun ConversationQuickActionsSection(
    isFavorite: Boolean,
    notificationLevel: NotificationLevel,
    onFavoriteChange: (Boolean) -> Unit,
    onNotificationLevelChange: (NotificationLevel) -> Unit,
    modifier: Modifier = Modifier,
    availableLevels: SnapshotList<NotificationLevel> = NotificationLevel.channelLevels,
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NotificationLevelPicker(notificationLevel, onNotificationLevelChange, availableLevels = availableLevels)
            val label = uiString(UiText.Resource(RetainedUiStrings.chatsActionFavorite))
            Row(Modifier.fillMaxWidth().sharedTouchTarget(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f))
                Switch(isFavorite, onFavoriteChange, modifier = Modifier.semantics { contentDescription = label })
            }
        }
    }
}

@Composable
fun TintedLabel(title: UiText, symbol: MeshSymbol, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(symbol.vector, null, tint = MaterialTheme.colorScheme.primary)
        Text(uiString(title), Modifier.weight(1f))
    }
}

fun radioActionEnabled(connectionState: DeviceConnectionState, otherCondition: Boolean = false): Boolean =
    connectionState == DeviceConnectionState.READY && !otherCondition

@Composable
fun Modifier.radioDisabledHint(connectionState: DeviceConnectionState): Modifier {
    val hint = uiString(UiText.Resource(AppLocalizableStrings.accessibilityRequiresRadioConnection))
    return if (connectionState != DeviceConnectionState.READY) semantics { stateDescription = hint } else this
}
