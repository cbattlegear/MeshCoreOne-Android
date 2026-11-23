// PortedFrom: MC1/Views/Chats/Sheets/SendDMSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: retryable directory reads and original-channel navigation ownership.
package com.meshcoreone.android.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.meshcoreone.android.app.container.AppChatsFeature
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.chats.actions.SendDirectMessageSheet
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
internal fun AppChannelSenderDirectMessageSheet(
    feature: AppChatsFeature,
    message: MessageDTO,
    conversation: TimelineConversation,
    navigation: NavigationCoordinator,
    onDismiss: () -> Unit,
) {
    val channel = conversation as TimelineConversation.Channel
    val sender = requireNotNull(message.senderNodeName)
    val navigate = remember(navigation, message.id, channel) {
        navigation.captureChannelSenderNavigation(channel.channel)
    }
    val scope = rememberCoroutineScope()
    var contacts by remember(message.id, channel) { mutableStateOf<List<ContactDTO>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selecting by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var request by remember { mutableStateOf(0) }
    LaunchedEffect(feature, message.id, channel, request) {
        loading = true
        failure = null
        contacts = emptyList()
        try {
            val loaded = feature.actions(channel.channel.radioId).contacts(channel.channel.radioId)
            ensureActive()
            contacts = loaded.filter { it.radioId == channel.channel.radioId }
            loading = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            ensureActive()
            feature.report("Sender contact matching failed", problem)
            failure = problem
            loading = false
        }
    }
    val nickname = remember(contacts, sender) {
        MessageBubbleConfiguration.buildNicknameLookup(contacts)[MessageBubbleConfiguration.loweredName(sender)]
    }
    SendDirectMessageSheet(
        sender, channel.channel.radioId, contacts,
        onSelect = { contact ->
            if (!selecting) {
                selecting = true
                scope.launch {
                    try {
                        feature.selectChannelSender(message, channel, contact, navigate)
                        onDismiss()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (problem: Exception) {
                        feature.report("Sender DM navigation failed", problem)
                        failure = problem
                    } finally {
                        selecting = false
                    }
                }
            }
        },
        onDismiss = onDismiss,
        unverifiedNickname = nickname,
        isLoading = loading,
        failure = failure?.let { uiString(UiErrorMapper().message(it)) },
        onRetry = { request++ },
        isSelecting = selecting,
    )
}
