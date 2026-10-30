// PortedFrom: MC1/Views/Chats/CreatePrivateChannelView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/JoinPrivateChannelView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/JoinPublicChannelView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Sheets/JoinHashtagChannelView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.channels

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.security.SecureRandom
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class ChannelSheetPage { OPTIONS, CREATE_PRIVATE, JOIN_PRIVATE, JOIN_PUBLIC, JOIN_HASHTAG, SCAN_QR, SHARE }

data class ChannelSheetsState(
    val page: ChannelSheetPage = ChannelSheetPage.OPTIONS,
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val availableSlots: List<UByte> = emptyList(),
    val hasPublicChannel: Boolean = false,
    val channels: List<ChannelDTO> = emptyList(),
    val name: String = "",
    val secretHex: String = "",
    val generatedSecret: Bytes? = null,
    val createdChannel: ChannelDTO? = null,
    val shareUri: String? = null,
    val failure: Throwable? = null,
)

class ChannelSheetsStateHolder(
    private val radioId: RadioId?,
    private val maxChannels: UByte,
    private val dependencies: ChannelSheetDependencies,
    private val random: SecureRandom = SecureRandom(),
) {
    private val mutableState = MutableStateFlow(ChannelSheetsState())
    val state: StateFlow<ChannelSheetsState> = mutableState

    suspend fun load() {
        if (radioId == null) {
            mutableState.update { it.copy(isLoading = false, failure = ChannelSheetError.NoDeviceConnected) }
            return
        }
        mutableState.update { it.copy(isLoading = true, failure = null) }
        try {
            val channels = dependencies.data.fetchChannels(radioId)
            val used = channels.map(ChannelDTO::index).toSet()
            val available = (1 until maxChannels.toInt()).map(Int::toUByte).filterNot(used::contains)
            mutableState.update {
                it.copy(
                    isLoading = false,
                    channels = channels,
                    availableSlots = available,
                    hasPublicChannel = 0.toUByte() in used,
                )
            }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(isLoading = false) }
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(isLoading = false, failure = failure) }
            dependencies.diagnostics.report("load channel sheets", failure)
        }
    }

    fun open(page: ChannelSheetPage) {
        mutableState.update {
            val secret = if (page == ChannelSheetPage.CREATE_PRIVATE) {
                Bytes(ByteArray(ProtocolLimits.CHANNEL_SECRET_SIZE).also(random::nextBytes))
            } else {
                it.generatedSecret
            }
            it.copy(page = page, generatedSecret = secret, failure = null, createdChannel = null, shareUri = null)
        }
    }

    fun back() = mutableState.update {
        it.copy(page = ChannelSheetPage.OPTIONS, name = "", secretHex = "", failure = null, createdChannel = null, shareUri = null)
    }

    fun updateName(value: String) = mutableState.update { it.copy(name = utf8Prefix(value, ProtocolLimits.MAX_USABLE_NAME_BYTES)) }

    fun updateHashtag(value: String) = mutableState.update { it.copy(name = sanitizeHashtag(value)) }

    fun updateSecret(value: String) = mutableState.update {
        it.copy(
            secretHex = value.uppercase(Locale.ROOT)
                .filter { character -> character.digitToIntOrNull(16) != null }
                .take(ProtocolLimits.CHANNEL_SECRET_SIZE * 2),
        )
    }

    suspend fun createPrivate(): ChannelDTO? {
        val secret = mutableState.value.generatedSecret ?: return fail(ChannelSheetError.InvalidSecret)
        return writeWithSecret(mutableState.value.name, secret, shareAfter = true)
    }

    suspend fun joinPrivate(): ChannelDTO? {
        val secret = parseSecret(mutableState.value.secretHex) ?: return fail(ChannelSheetError.InvalidSecret)
        return writeWithSecret(mutableState.value.name, secret)
    }

    suspend fun joinPublic(): ChannelDTO? = submit {
        val radio = requireRadio()
        dependencies.service.setupPublicChannel(radio)
        dependencies.data.fetchChannel(radio, 0u) ?: throw ChannelSheetError.CreatedChannelMissing
    }

    suspend fun joinHashtag(): ChannelDTO? {
        val name = mutableState.value.name
        if (!isValidHashtag(name)) return fail(ChannelSheetError.InvalidHashtag)
        mutableState.value.channels.firstOrNull { it.name.equals("#$name", ignoreCase = true) }?.let { return it }
        val slot = firstSlot() ?: return fail(ChannelSheetError.NoAvailableSlots)
        return submit {
            val radio = requireRadio()
            dependencies.service.setChannel(radio, slot, "#$name", "#$name")
            dependencies.data.fetchChannel(radio, slot) ?: throw ChannelSheetError.CreatedChannelMissing
        }
    }

    suspend fun joinLink(link: ChannelLink): ChannelDTO? {
        if (link.name.isEmpty()) return fail(ChannelSheetError.InvalidName)
        val result = writeWithSecret(link.name, link.secret) ?: return null
        val region = link.regionScope?.takeIf(String::isNotEmpty) ?: return result
        return try {
            dependencies.data.setFloodScope(result, ChannelFloodScope.Region(region))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            dependencies.diagnostics.report("set joined channel region", failure)
            result
        }
    }

    fun clearFailure() = mutableState.update { it.copy(failure = null) }

    private suspend fun writeWithSecret(name: String, secret: Bytes, shareAfter: Boolean = false): ChannelDTO? {
        if (name.isEmpty()) return fail(ChannelSheetError.InvalidName)
        if (secret.size != ProtocolLimits.CHANNEL_SECRET_SIZE) return fail(ChannelSheetError.InvalidSecret)
        val slot = firstSlot() ?: return fail(ChannelSheetError.NoAvailableSlots)
        return submit {
            val radio = requireRadio()
            dependencies.service.setChannelWithSecret(radio, slot, name, secret)
            val channel = dependencies.data.fetchChannel(radio, slot) ?: throw ChannelSheetError.CreatedChannelMissing
            if (shareAfter) {
                mutableState.update {
                    it.copy(
                        page = ChannelSheetPage.SHARE,
                        createdChannel = channel,
                        shareUri = dependencies.service.exportChannelUri(channel),
                    )
                }
            }
            channel
        }
    }

    private suspend fun submit(operation: suspend () -> ChannelDTO): ChannelDTO? {
        mutableState.update { it.copy(isSubmitting = true, failure = null) }
        return try {
            operation().also { result ->
                mutableState.update { it.copy(isSubmitting = false, createdChannel = result) }
            }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(isSubmitting = false) }
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(isSubmitting = false, failure = failure) }
            dependencies.diagnostics.report("write channel", failure)
            null
        }
    }

    private fun requireRadio(): RadioId = radioId ?: throw ChannelSheetError.NoDeviceConnected
    private fun firstSlot(): UByte? = mutableState.value.availableSlots.firstOrNull()
    private fun fail(error: ChannelSheetError): ChannelDTO? {
        mutableState.update { it.copy(failure = error) }
        return null
    }

    companion object {
        fun parseSecret(value: String): Bytes? {
            val cleaned = value.filterNot(Char::isWhitespace)
            if (cleaned.length != ProtocolLimits.CHANNEL_SECRET_SIZE * 2 ||
                !cleaned.all { it.digitToIntOrNull(16) != null }
            ) return null
            return Bytes(ByteArray(ProtocolLimits.CHANNEL_SECRET_SIZE) { index ->
                cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            })
        }

        fun sanitizeHashtag(value: String): String = value.lowercase(Locale.ROOT)
            .filter { it.isAsciiAlphanumeric() || it == '-' }.trimStart('-')

        fun isValidHashtag(value: String): Boolean =
            value.isNotEmpty() && value.first().isAsciiAlphanumeric() && value.all { it.isAsciiAlphanumeric() || it == '-' }

        private fun Char.isAsciiAlphanumeric() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

        fun utf8Prefix(value: String, maxBytes: Int): String {
            var bytes = 0
            val result = StringBuilder()
            value.codePoints().forEach { codePoint ->
                val text = String(Character.toChars(codePoint))
                val encoded = text.toByteArray(Charsets.UTF_8).size
                if (bytes + encoded <= maxBytes) {
                    result.append(text)
                    bytes += encoded
                }
            }
            return result.toString()
        }
    }
}

sealed class ChannelSheetError(message: String) : Exception(message) {
    data object NoDeviceConnected : ChannelSheetError("No device connected")
    data object NoAvailableSlots : ChannelSheetError("No available channel slots")
    data object InvalidName : ChannelSheetError("Invalid channel name")
    data object InvalidSecret : ChannelSheetError("Secret key must be exactly 32 hexadecimal characters")
    data object InvalidHashtag : ChannelSheetError("Invalid hashtag channel name")
    data object CreatedChannelMissing : ChannelSheetError("Channel created but could not be loaded")
}
