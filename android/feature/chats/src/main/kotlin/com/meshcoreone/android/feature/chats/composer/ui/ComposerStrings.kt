// AndroidOnly: WP-308 Resource-backed strings for the composer UI and link action names (core:l10n only).
package com.meshcoreone.android.feature.chats.composer.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.feature.chats.composer.ComposerSendFailure
import com.meshcoreone.android.feature.chats.composer.LinkActionNames
import com.meshcoreone.android.feature.chats.composer.SendHint

internal class ResourceLinkActionNames(private val resources: Resources) : LinkActionNames {
    override fun openLink() = resources.getString(AppChatsStrings.chatsMessageActionOpenLink)
    override fun openWebLink(host: String) = AppChatsStrings.chatsMessageActionOpenWebLink(resources, host)
    override fun openMapLink() = resources.getString(AppChatsStrings.chatsMessageActionOpenMapLink)
    override fun addContact(name: String) = AppChatsStrings.chatsMessageActionAddContact(resources, name)
    override fun openChannel(name: String) = AppChatsStrings.chatsMessageActionOpenChannel(resources, name)
    override fun openMention(name: String) = AppChatsStrings.chatsMessageActionOpenMention(resources, name)
    override fun openHashtag(name: String) = AppChatsStrings.chatsMessageActionOpenHashtag(resources, name)
}

/** Recreated with the configuration so action names follow a locale change. */
@Composable
internal fun rememberLinkActionNames(): LinkActionNames {
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    return remember(configuration, resources) { ResourceLinkActionNames(resources) }
}

internal fun sendHintText(resources: Resources, hint: SendHint, bytesOver: Int): String = when (hint) {
    SendHint.OVER_LIMIT -> AppChatsStrings.chatsInputRemoveCharacters(resources, bytesOver)
    SendHint.REQUIRES_CONNECTION -> resources.getString(AppChatsStrings.chatsInputRequiresConnection)
    SendHint.TAP_TO_SEND -> resources.getString(AppChatsStrings.chatsInputTapToSend)
    SendHint.TYPE_FIRST -> resources.getString(AppChatsStrings.chatsInputTypeFirst)
}

/** The user-facing text of a failed send; every branch reuses an existing localized chats string. */
internal fun sendFailureText(resources: Resources, reason: ComposerSendFailure): String = resources.getString(
    when (reason) {
        ComposerSendFailure.NOT_CONNECTED -> AppChatsStrings.chatsAlertUnableToSendMessage
        ComposerSendFailure.MESSAGE_TOO_LONG -> AppChatsStrings.chatsInputTooLong
        ComposerSendFailure.RECIPIENT_NOT_FOUND -> AppChatsStrings.chatsErrorServicesUnavailable
        ComposerSendFailure.OTHER -> AppChatsStrings.chatsErrorSendQueuePersistFailed
    },
)
