// PortedFrom: MC1/Views/Chats/CreatePrivateChannelView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/ChannelOptionsSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/JoinChannelConfirmationSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.channels

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes

interface ChannelSheetDataSource {
    suspend fun fetchChannels(radioId: RadioId): List<ChannelDTO>
    suspend fun fetchChannel(radioId: RadioId, index: UByte): ChannelDTO?
}

interface ChannelSheetService {
    // The nonthrowing observer reports only an actually returned store UUID, even if later work fails.
    suspend fun createChannelWithSecret(
        radioId: RadioId, maxChannels: UByte, name: String, secret: Bytes, regionScope: String? = null,
        onCommitted: (ChannelWriteReceipt) -> Unit,
    ): ChannelDTO
    suspend fun joinHashtagChannel(
        radioId: RadioId, maxChannels: UByte, name: String, onCommitted: (ChannelWriteReceipt) -> Unit,
    ): ChannelDTO
    suspend fun setChannelWithSecret(radioId: RadioId, index: UByte, name: String, secret: Bytes)
    suspend fun setChannel(radioId: RadioId, index: UByte, name: String, passphrase: String)
    suspend fun setupPublicChannel(radioId: RadioId)
    fun exportChannelUri(channel: ChannelDTO): String
}

data class ChannelWriteReceipt(val key: EntityKey, val index: UByte)

fun interface ChannelSheetDiagnostics {
    fun report(operation: String, failure: Throwable)
}

interface ChannelSheetDependencies {
    val data: ChannelSheetDataSource
    val service: ChannelSheetService
    val diagnostics: ChannelSheetDiagnostics get() = ChannelSheetDiagnostics { _, _ -> }
}

data class ChannelLink(
    val name: String,
    val secret: Bytes,
    val regionScope: String? = null,
    val hasHashtagSecretMismatch: Boolean = false,
)
