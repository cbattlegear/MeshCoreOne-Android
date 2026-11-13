// PortedFrom: MC1/Views/Components/SwipeActionsContainer.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: swiping reveals an accessible native menu; destructive actions never auto-execute.
package com.meshcoreone.android.core.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.meshcoreone.android.core.model.SnapshotList
import kotlin.math.abs

data class RowAction(val id: String, val title: UiText, val destructive: Boolean, val enabled: Boolean = true)

@Composable
fun SwipeActionsContainer(
    rowIdentity: String,
    actions: SnapshotList<RowAction>,
    actionsLabel: UiText,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var open by remember(rowIdentity) { mutableStateOf(false) }
    val names = actions.associate { it.id to uiString(it.title) }
    Row(modifier.fillMaxWidth().pointerInput(rowIdentity, actions) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onDragEnd = { if (abs(distance) >= 48 * density) open = true },
            onHorizontalDrag = { change, amount -> distance += amount; change.consume() },
        )
    }.semantics {
        customActions = actions.filter { it.enabled }.map { action ->
            CustomAccessibilityAction(requireNotNull(names[action.id])) { onAction(action.id); true }
        }
    }) {
        Box(Modifier.weight(1f)) { content() }
        Box {
            IconButton({ open = true }, modifier = Modifier.sharedTouchTarget()) {
                Icon(SharedGlyphs.MoreVertical, uiString(actionsLabel))
            }
            DropdownMenu(open, { open = false }) {
                for (action in actions) DropdownMenuItem(
                    text = {
                        Text(requireNotNull(names[action.id]), color = if (action.destructive)
                            sharedErrorForeground(sharedPaintedSurface()) else MaterialTheme.colorScheme.onSurface)
                    },
                    enabled = action.enabled,
                    onClick = { open = false; onAction(action.id) },
                    modifier = Modifier.sharedTouchTarget(),
                )
            }
        }
    }
}
