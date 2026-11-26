// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/NotificationActionHandlerTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.notifications

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Original NotificationActionHandlerTests. The Swift cases built through `ServiceContainer.forTesting`;
 * here the handler is constructed directly with fakes ([NotificationHarness]).
 */
class NotificationActionHandlerTests {
    private fun case(name: String, body: suspend CoroutineScope.() -> Unit) =
        originalCase("NotificationActionHandlerTests", name, body)

    @TestFactory
    fun configureGuard(): List<DynamicTest> = listOf(
        case("Handler is not configured before configure() is called") {
            assertFalse(NotificationHarness(this).handler.isConfigured)
        },
        case("Handler is configured after configure() is called") {
            val handler = NotificationHarness(this).handler
            handler.configure(isConnectionReady = { true }, localNodeName = { null })
            assertTrue(handler.isConfigured)
        },
    )

    @TestFactory
    fun reactionPreviewTruncation(): List<DynamicTest> = listOf(
        case("Text at 49 characters is returned unchanged") {
            val text = "a".repeat(49)
            assertEquals(text, NotificationActionHandler.reactionPreview(text))
        },
        case("Text at exactly 50 characters is returned unchanged") {
            val text = "b".repeat(50)
            assertEquals(text, NotificationActionHandler.reactionPreview(text))
        },
        case("Text at 51 characters is truncated to 47 plus ellipsis") {
            val text = "c".repeat(51)
            assertEquals("c".repeat(47) + "...", NotificationActionHandler.reactionPreview(text))
        },
    )

    @TestFactory
    fun channelDisplayNameFallback(): List<DynamicTest> = listOf(
        case("Stored channel name wins over the localized fallback") {
            val harness = NotificationHarness(this)
            harness.service.setStringProvider(MockStringProvider())
            assertEquals("Rescue Net", harness.handler.channelDisplayName("Rescue Net", 2u))
        },
        case("Missing name falls back to the string provider") {
            val harness = NotificationHarness(this)
            harness.service.setStringProvider(MockStringProvider())
            assertEquals("Localized Channel 3", harness.handler.channelDisplayName(null, 3u))
        },
        case("Missing name and provider fall back to the English literal") {
            assertEquals("Channel 7", NotificationHarness(this).handler.channelDisplayName(null, 7u))
        },
    )
}
