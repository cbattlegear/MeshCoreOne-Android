// PortedFrom: MC1Services/Tests/MC1ServicesTests/Mocks/MockMessagePollingService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.MessagePollingServiceProtocol
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.event.ChannelMessage
import com.meshcoreone.android.core.protocol.event.ContactMessage
import java.time.Duration

internal class MockMessagePollingService : MessagePollingServiceProtocol {
    var result: Result<Long> = Result.success(0)
    var pollCalls = 0
    var waitCalls = 0
    val startRadios = mutableListOf<RadioId>()
    var pauseCalls = 0
    var resumeCalls = 0
    var contactHandler: (suspend (ContactMessage, ContactDTO?, DeliveryContext) -> Unit)? = null
    var channelHandler: (suspend (ChannelMessage, ChannelDTO?, DeliveryContext) -> Unit)? = null
    var signedHandler: (suspend (ContactMessage, ContactDTO?) -> Unit)? = null
    var cliHandler: (suspend (ContactMessage, ContactDTO?) -> Unit)? = null
    override suspend fun pollAllMessages(): Long { pollCalls++; return result.getOrThrow() }
    override suspend fun waitForPendingHandlers(timeout: Duration): Boolean { waitCalls++; return true }
    override suspend fun startAutoFetch(radioId: RadioId) { startRadios += radioId }
    override suspend fun pauseAutoFetch() { pauseCalls++ }
    override suspend fun resumeAutoFetch() { resumeCalls++ }
    override suspend fun setContactMessageHandler(handler: suspend (ContactMessage, ContactDTO?, DeliveryContext) -> Unit) { contactHandler = handler }
    override suspend fun setChannelMessageHandler(handler: suspend (ChannelMessage, ChannelDTO?, DeliveryContext) -> Unit) { channelHandler = handler }
    override suspend fun setSignedMessageHandler(handler: suspend (ContactMessage, ContactDTO?) -> Unit) { signedHandler = handler }
    override suspend fun setCLIMessageHandler(handler: suspend (ContactMessage, ContactDTO?) -> Unit) { cliHandler = handler }
    override suspend fun clearMessageHandlers() { contactHandler = null; channelHandler = null; signedHandler = null; cliHandler = null }
    fun reset() {
        pollCalls = 0; waitCalls = 0; startRadios.clear(); pauseCalls = 0; resumeCalls = 0
        contactHandler = null; channelHandler = null; signedHandler = null; cliHandler = null
    }
}
