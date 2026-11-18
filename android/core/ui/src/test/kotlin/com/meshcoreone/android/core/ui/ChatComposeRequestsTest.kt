// AndroidOnly: WP-306 The shell's single top bar forwards compose-menu picks to the visible Chats list.
package com.meshcoreone.android.core.ui

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatComposeRequestsTest {
    @Test
    fun `collector receives the requested compose action`() = runTest(UnconfinedTestDispatcher()) {
        val bridge = ChatComposeRequests()
        val received = mutableListOf<ChatComposeRequest>()
        val job = launch { bridge.requests.collect { received += it } }
        assertTrue(bridge.request(ChatComposeRequest.NEW_CHANNEL))
        assertEquals(listOf(ChatComposeRequest.NEW_CHANNEL), received)
        job.cancel()
    }

    @Test
    fun `first request after subscription is delivered once`() = runTest(UnconfinedTestDispatcher()) {
        val bridge = ChatComposeRequests()
        var first: ChatComposeRequest? = null
        val job = launch { first = bridge.requests.first() }
        bridge.request(ChatComposeRequest.NEW_CHAT)
        assertEquals(ChatComposeRequest.NEW_CHAT, first)
        job.cancel()
    }
}
