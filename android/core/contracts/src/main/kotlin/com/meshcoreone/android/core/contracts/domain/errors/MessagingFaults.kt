// PortedFrom: MC1Services/Sources/MC1Services/Services/MessageServiceError.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/MessagePollingService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/ChatSendQueueService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.contracts.domain.errors

import com.meshcoreone.android.core.protocol.config.MeshCoreException

sealed interface MessageServiceError {
    data object NotConnected : MessageServiceError
    data object ContactNotFound : MessageServiceError
    data object ChannelNotFound : MessageServiceError
    data class SendFailed(val reason: String) : MessageServiceError
    data object InvalidRecipient : MessageServiceError
    data object AmbiguousRecipient : MessageServiceError
    data object MessageTooLong : MessageServiceError
    data class SessionError(val underlying: MeshCoreException) : MessageServiceError
}

class MessageServiceException(val error: MessageServiceError, cause: Throwable? = null) :
    Exception(error.javaClass.simpleName, cause ?: (error as? MessageServiceError.SessionError)?.underlying)

sealed interface MessagePollingError {
    data object NotConnected : MessagePollingError
    data object PollingFailed : MessagePollingError
    data class SessionError(val underlying: MeshCoreException) : MessagePollingError
}

class MessagePollingException(val error: MessagePollingError, cause: Throwable? = null) :
    Exception(error.javaClass.simpleName, cause ?: (error as? MessagePollingError.SessionError)?.underlying)

sealed interface ChatSendQueueServiceError {
    data class PersistFailed(val underlying: Throwable) : ChatSendQueueServiceError
    data object NotConnected : ChatSendQueueServiceError
}

class ChatSendQueueServiceException(val error: ChatSendQueueServiceError) :
    Exception(error.javaClass.simpleName, (error as? ChatSendQueueServiceError.PersistFailed)?.underlying)
