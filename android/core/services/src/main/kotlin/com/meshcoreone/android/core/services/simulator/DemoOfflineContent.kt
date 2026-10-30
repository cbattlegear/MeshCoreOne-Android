// AndroidOnly: WP-217 Identifies the bundled simulator media without trusting arbitrary message URLs.
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.model.MessageDTO
import java.util.UUID

object DemoOfflineContent {
    private val inlineMessageId = UUID.fromString("10000000-0000-0000-0000-00000000000B")

    fun isSimulatorMessage(message: MessageDTO): Boolean = message.radioId == MockDataProvider.simulatorRadioId

    fun preview(message: MessageDTO, url: String): LinkPreviewSeed? {
        if (!isSimulatorMessage(message)) return null
        return MockDataProvider.linkPreviewSeeds.firstOrNull {
            it.messageID == message.id && it.url == url
        }
    }

    fun isInlineImage(message: MessageDTO, url: String): Boolean =
        isSimulatorMessage(message) && message.id == inlineMessageId && url == MockDataProvider.inlineImageURL
}
