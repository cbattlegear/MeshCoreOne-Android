// AndroidOnly: WP-306 Lets the shell's single top bar host the Chats compose action instead of a second in-screen header.
package com.meshcoreone.android.core.ui

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** What the Chats top-bar compose menu asked for. */
enum class ChatComposeRequest { NEW_CHAT, NEW_CHANNEL }

/**
 * One-way bridge from the app shell's top bar to the Chats list. The shell emits when the user picks an entry
 * from the compose menu; the visible Chats list collects and opens the matching sheet.
 */
class ChatComposeRequests {
    private val mutableRequests = MutableSharedFlow<ChatComposeRequest>(extraBufferCapacity = 1)

    val requests: Flow<ChatComposeRequest> get() = mutableRequests

    fun request(kind: ChatComposeRequest): Boolean = mutableRequests.tryEmit(kind)
}

/** Null where no shell hosts the compose action (previews, isolated tests). */
val LocalChatComposeRequests = compositionLocalOf<ChatComposeRequests?> { null }
