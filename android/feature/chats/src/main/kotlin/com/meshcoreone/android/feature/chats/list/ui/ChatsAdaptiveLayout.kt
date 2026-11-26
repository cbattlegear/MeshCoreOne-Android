// PortedFrom: MC1/Views/Chats/Navigation/ChatsSplitSidebarContent.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// HistoricalPortedFrom: MC1/Views/Chats/Navigation/ChatsStackLayout.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.feature.chats.list.ChatRoute

/** Width at which the list and a detail pane sit side by side (matches the shell's rail breakpoint). */
val ChatsListDetailMinWidth: Dp = 600.dp
private val ListPaneWidth: Dp = 360.dp

/**
 * Adaptive list-detail host. Below [ChatsListDetailMinWidth], or without a [detail] slot, only the
 * list shows and navigation pushes through `onOpenChat`. At or above it the list takes a fixed pane
 * and the timeline pane (WP-307/308) fills the rest, receiving the selected route (null = empty state).
 */
@Composable
fun ChatsAdaptiveLayout(
    selectedRoute: ChatRoute?,
    list: @Composable (Modifier) -> Unit,
    detail: (@Composable (ChatRoute?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (detail != null && maxWidth >= ChatsListDetailMinWidth) {
            Row(Modifier.fillMaxSize()) {
                list(Modifier.width(ListPaneWidth).fillMaxHeight())
                VerticalDivider()
                androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxHeight()) { detail(selectedRoute) }
            }
        } else {
            list(Modifier.fillMaxSize())
        }
    }
}
