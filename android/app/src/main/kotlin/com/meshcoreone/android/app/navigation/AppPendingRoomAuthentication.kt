// PortedFrom: MC1/Views/Chats/Sheets/ChatsConversationSheets.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Room/RoomAuthenticationSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.app.container.AppChatsFeature
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.chats.rooms.RoomAuthenticationSheet
import com.meshcoreone.android.feature.chats.rooms.RoomConversationStateHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun AppPendingRoomAuthentication(
    feature: AppChatsFeature,
    coordinator: NavigationCoordinator,
    navigation: NavigationState,
) {
    val session = navigation.pendingRoomAuthentication ?: return
    val deliveryId = navigation.roomAuthenticationDeliveryId ?: return
    val generation = navigation.roomAuthenticationGeneration ?: return
    val token = navigation.roomAuthenticationSessionToken
    key(deliveryId) {
        val scope = rememberCoroutineScope()
        val isCurrentSession = remember(feature, token, deliveryId) {
            feature.captureNavigationSession(token)
        }
        val dependencies = remember(feature, deliveryId) {
            feature.roomForAuthentication(session, token, isCurrentSession)
        }
        val holder = remember(dependencies) { RoomConversationStateHolder(session, dependencies, scope) }
        val state by holder.state.collectAsStateWithLifecycle()
        var authenticationJob by remember { mutableStateOf<Job?>(null) }
        var retryJob by remember { mutableStateOf<Job?>(null) }

        LaunchedEffect(holder) { holder.prepareAuthentication() }
        DisposableEffect(holder) {
            onDispose {
                authenticationJob?.cancel()
                retryJob?.cancel()
            }
        }
        RoomAuthenticationSheet(
            state = state,
            onDismiss = {
                authenticationJob?.cancel()
                retryJob?.cancel()
                coordinator.dismissRoomAuthentication(deliveryId)
            },
            onAuthenticate = { password, rememberPassword ->
                if (authenticationJob?.isActive != true) {
                    authenticationJob = scope.launch {
                        val authenticated = holder.authenticateForNavigation(password, rememberPassword)
                        val context = currentCoroutineContext()
                        context.ensureActive()
                        if (authenticated != null) {
                            val committed = coordinator.completeRoomAuthentication(deliveryId, generation, authenticated) {
                                context.isActive && isCurrentSession()
                            }
                            if (!committed) {
                                context.ensureActive()
                                holder.reportAuthenticationRetired()
                            }
                        }
                    }
                }
            },
            failureText = { uiString(UiErrorMapper().message(it)) },
            onRetryLookup = {
                if (retryJob?.isActive != true) retryJob = scope.launch { holder.prepareAuthentication() }
            },
        )
    }
}
