// PortedFrom: MC1Tests/Views/Chats/Models/MessageFootprintHashTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.MessageStatus
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every render-affecting DTO field must be encoded into [MessageItem] so equality/hash detect changes.
 * Swift `hashValue` is process-seeded, so neither side pins literal hash values; `hashCode()` is
 * compared here exactly as `hashValue` is in Swift.
 */
class MessageFootprintHashTest {
    @TestFactory
    fun messageFootprintHashTests(): List<DynamicTest> = listOf(
        case("sendCount change flips MessageItem.hashValue") {
            val baseline = RenderingFixtures.makeMessage(text = "hello")
            val bumped = baseline.copy(sendCount = baseline.sendCount + 1)
            val inputs = RenderingFixtures.makeInputs(baseline.id)
            val env = RenderingFixtures.makeEnvInputs(isOutgoing = true)
            val baseItem = MessageFragmentBuilder.makeItem(baseline, inputs, env)
            val bumpedItem = MessageFragmentBuilder.makeItem(bumped, inputs, env)
            assertNotEquals(baseItem, bumpedItem, "sendCount must be encoded into MessageItem; otherwise the status footer can't refresh after channel resend")
            assertNotEquals(baseItem.hashCode(), bumpedItem.hashCode())
        },
        case("heardRepeats change flips MessageItem.hashValue") {
            val baseline = RenderingFixtures.makeMessage(text = "hello", heardRepeats = 0)
            val bumped = baseline.copy(heardRepeats = 1)
            val inputs = RenderingFixtures.makeInputs(baseline.id)
            val env = RenderingFixtures.makeEnvInputs(isOutgoing = true)
            val baseItem = MessageFragmentBuilder.makeItem(baseline, inputs, env)
            val bumpedItem = MessageFragmentBuilder.makeItem(bumped, inputs, env)
            assertNotEquals(baseItem, bumpedItem)
            assertNotEquals(baseItem.hashCode(), bumpedItem.hashCode())
            assertNotEquals(baseItem.footer.heardRepeats, bumpedItem.footer.heardRepeats)
        },
        case("status change flips MessageItem.hashValue") {
            val pending = RenderingFixtures.makeMessage(text = "hello", status = MessageStatus.PENDING)
            val sent = pending.copy(status = MessageStatus.SENT)
            val inputs = RenderingFixtures.makeInputs(pending.id)
            val env = RenderingFixtures.makeEnvInputs(isOutgoing = true)
            val pendingItem = MessageFragmentBuilder.makeItem(pending, inputs, env)
            val sentItem = MessageFragmentBuilder.makeItem(sent, inputs, env)
            assertNotEquals(pendingItem, sentItem)
            assertNotEquals(pendingItem.hashCode(), sentItem.hashCode())
            assertNotEquals(pendingItem.envelope.status, sentItem.envelope.status)
            assertNotEquals(pendingItem.footer.status, sentItem.footer.status)
        },
    )

    private fun case(name: String, body: () -> Unit) = DynamicTest.dynamicTest("MessageFootprintHashTests::$name()", body)
}
