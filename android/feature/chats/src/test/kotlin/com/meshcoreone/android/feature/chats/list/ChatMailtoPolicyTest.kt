// AndroidOnly: WP-306 Mailto fallback validation, tokenizer and unchanged web-only policy controls.
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.feature.chats.composer.LinkOpenPolicy
import com.meshcoreone.android.feature.chats.composer.LinkDetection
import com.meshcoreone.android.feature.chats.composer.MessageTextFormatter
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatMailtoPolicyTest {
    @Test fun `explicit mailto links retain recipients and query without widening web policy`() {
        for (url in listOf(
            "mailto:test@example.com", "MAILTO:test@example.com",
            "mailto:one@example.com,two@example.com?subject=Hello%20Mesh&body=One%0ATwo",
        )) {
            assertTrue(ChatMailtoPolicy.isOpenable(url), url)
            assertFalse(LinkOpenPolicy.isOpenable(url), "mailto is never a web/preview URL")
            assertEquals(listOf(url), MessageTextFormatter.format("Contact $url").links)
            assertEquals(emptyList(), LinkDetection.detectUrls(url), "shared web scan must remain web-only")
        }
    }

    @Test fun `unsupported and malformed links never qualify for native mailto`() {
        for (url in listOf(
            "javascript:alert(1)", "intent://x#Intent;end", "file:///etc/passwd", "content://private/x",
            "tel:123", "custom://x", "https://example.com", "//example.com",
            "mailto:", "mailto:?subject=x", "mailto://test@example.com", "mailto:test@example.com#fragment",
            "mailto:test%0d%0abcc:other@example.com", "mailto:test%00@example.com", "mailto:bad%ZZ",
            "mailto:test @example.com",
        )) assertFalse(ChatMailtoPolicy.isOpenable(url), url)
    }

    @Test fun `mail tokens skip fragments and web overlaps while bare emails remain unchanged`() {
        val mail = "mailto:test@example.com?subject=Hi%20Mesh"
        assertEquals(listOf(mail), MessageTextFormatter.format("($mail).").links)
        assertEquals(listOf("https://example.com/mailto:test@example.com"),
            MessageTextFormatter.format("https://example.com/mailto:test@example.com").links)
        assertEquals(emptyList(), MessageTextFormatter.format("test@example.com").links)
    }
}
