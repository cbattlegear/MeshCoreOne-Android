// AndroidOnly: WP-407 Channel/hashtag links stage a user confirmation; they never write a radio during composition.
package com.meshcoreone.android.app.navigation

import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.meshcoreone.android.app.container.AppChatsFeature
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L

@Composable
internal fun AppPendingChannel(feature: AppChatsFeature, navigation: NavigationCoordinator, visible: Boolean = true) {
    val pending by navigation.state.collectAsState()
    feature.releaseInactiveChannelConfirmations(navigation)
    val request = pending.pendingChannelConfirmation ?: pending.pendingHashtagConfirmation ?: return
    val link = request.link
    val hashtag = request.hashtag
    val radio by feature.currentRadioId.collectAsState()
    val submission = feature.channelConfirmation(navigation, request.deliveryId, radio)
    val holder = submission.holder
    val state by holder.state.collectAsState()
    val clear: () -> Unit = { navigation.completeChannelConfirmation(request.deliveryId) }
    LaunchedEffect(submission) { feature.loadChannelConfirmation(submission) }
    if (!visible) return
    AlertDialog(onDismissRequest = clear,
        text = {
            Column {
                Text(link?.name ?: requireNotNull(hashtag).id)
                link?.regionScope?.let {
                    Text(stringResource(com.meshcoreone.android.core.l10n.R.string.l10n_app_chats_chats_joinfrommessage_regionscope, it))
                }
                if (link?.hasHashtagSecretMismatch == true) {
                    Text(stringResource(com.meshcoreone.android.core.l10n.R.string.l10n_app_chats_chats_joinfrommessage_hashtagsecretmismatch))
                }
                state.failure?.let { Text(com.meshcoreone.android.core.ui.uiString(channelFailureText(it))) }
            }
        },
        confirmButton = {
            val connection by feature.connectionState.collectAsState()
            TextButton(enabled = radio != null && connection == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY &&
                !state.isLoading && !state.isSubmitting && state.committedWrite == null, onClick = {
                feature.confirmChannel(navigation, request, submission)
            }) { Text(stringResource(L.commonOk)) }
        },
        dismissButton = { TextButton(onClick = clear) { Text(stringResource(L.commonCancel)) } },
    )
}
