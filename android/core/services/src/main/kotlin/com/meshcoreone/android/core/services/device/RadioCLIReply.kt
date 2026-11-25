// AndroidOnly: WP-211 Companion CLI reply and Wi-Fi credential framing compared with current upstream.
package com.meshcoreone.android.core.services.device

internal sealed interface RadioCLIReply {
    data object UnknownCommand : RadioCLIReply
    data class Error(val text: String) : RadioCLIReply {
        override fun toString(): String = "Error(<redacted>)"
    }
    data class Value(val text: String) : RadioCLIReply {
        override fun toString(): String = "Value(<redacted>)"
    }
    data class Ok(val text: String) : RadioCLIReply {
        override fun toString(): String = "Ok(<redacted>)"
    }
    data class Other(val text: String) : RadioCLIReply {
        override fun toString(): String = "Other(<redacted>)"
    }

    companion object {
        const val NOT_SET = "(not set)"
        const val NOT_CONNECTED = "(not connected)"

        fun parse(text: String): RadioCLIReply {
            val marker = text.trimEnd('\r', '\n')
            return when {
                marker == "Unknown command" -> UnknownCommand
                marker.startsWith("Error") || marker.startsWith("ERROR") || marker.startsWith("ERR") -> Error(marker)
                text.startsWith("> ") -> Value(text.substring(2))
                marker.startsWith("OK") -> Ok(marker)
                else -> Other(marker)
            }
        }
    }
}

internal object RadioWiFiCredentials {
    private fun isRepresentable(value: String): Boolean =
        '\u0000' !in value && Charsets.UTF_8.newEncoder().canEncode(value)

    fun valid(ssid: String, password: String): Boolean =
        isRepresentable(ssid) && isRepresentable(password) &&
            ssid.toByteArray(Charsets.UTF_8).size in 1..32 && password.toByteArray(Charsets.UTF_8).size <= 63

    fun hasSameBytes(first: String, second: String): Boolean =
        first.toByteArray(Charsets.UTF_8).contentEquals(second.toByteArray(Charsets.UTF_8))
}
