// AndroidOnly: WP-306 Chats compose action hosted in the shell's single top bar.
package com.meshcoreone.android.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.ui.ChatComposeRequest

/** New chat / new channel menu shown in the top bar while the Chats list is the visible root. */
@Composable
internal fun ChatComposeMenu(onRequest: (ChatComposeRequest) -> Unit, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("open-chat-compose"),
        ) {
            Icon(MeshSymbol.MESSAGES.vector, stringResource(AppChatsStrings.chatsComposeNewMessage))
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(AppChatsStrings.chatsComposeNewChat)) },
                onClick = { open = false; onRequest(ChatComposeRequest.NEW_CHAT) },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            )
            DropdownMenuItem(
                text = { Text(stringResource(AppChatsStrings.chatsComposeNewChannel)) },
                onClick = { open = false; onRequest(ChatComposeRequest.NEW_CHANNEL) },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            )
        }
    }
}
