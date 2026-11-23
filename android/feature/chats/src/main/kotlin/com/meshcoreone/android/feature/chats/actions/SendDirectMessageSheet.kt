// PortedFrom: MC1/Views/Chats/Sheets/SendDMSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as Chats
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.chats.list.SenderContactMatcher

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendDirectMessageSheet(
    senderName: String,
    radioId: RadioId,
    contacts: List<ContactDTO>,
    onSelect: (ContactDTO) -> Unit,
    onDismiss: () -> Unit,
    unverifiedNickname: String? = null,
    isLoading: Boolean = false,
    failure: String? = null,
    onRetry: (() -> Unit)? = null,
    isSelecting: Boolean = false,
) {
    val resources = LocalResources.current
    val matching = SenderContactMatcher.filter(
        contacts.filter { it.radioId == radioId }, senderName, excludeBlocked = true,
    )
    val titleName = unverifiedNickname?.let {
        "$it ${Chats.chatsMessageSenderUnverifiedNicknameFormat(resources, senderName)}"
    } ?: senderName
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(Chats.chatsSendDMTitle(resources, titleName), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            if (unverifiedNickname != null) {
                Text(stringResource(Chats.chatsMessageSenderUnverifiedNicknameExplanation))
            }
            Text(stringResource(Chats.chatsSendDMLimitation))
            when {
                isLoading -> CircularProgressIndicator()
                failure != null -> {
                    Text(failure)
                    onRetry?.let { retry ->
                        TextButton(retry, Modifier.sharedTouchTarget()) {
                            Text(stringResource(AppLocalizableStrings.commonTryAgain))
                        }
                    }
                }
                matching.isEmpty() -> Text(Chats.chatsSendDMNoMatches(resources, senderName))
                else -> {
                    Text(stringResource(Chats.chatsSendDMMatchingContacts))
                    matching.forEach { contact ->
                        TextButton(
                            { onSelect(contact) },
                            Modifier.fillMaxWidth().sharedTouchTarget(),
                            enabled = !isSelecting,
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(contact.displayName)
                                Text(contact.publicKey.hexString, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            if (isSelecting) CircularProgressIndicator()
            TextButton(onDismiss, Modifier.sharedTouchTarget()) { Text(stringResource(Chats.chatsSendDMCancel)) }
        }
    }
}
