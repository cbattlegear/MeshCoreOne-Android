// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatCoordinatorRegistryTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatCoordinatorRegistryOfflineTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ChatCoordinatorRegistryTest {
    @TestFactory
    fun chatCoordinatorRegistryTests(): List<DynamicTest> = listOf(
        case("coordinator(for:) returns the same instance on repeat calls") {
            runRendering {
                val registry = renderingRegistry()
                val id = ChatConversationID.dm(RadioId(UUID.randomUUID()), UUID.randomUUID())
                assertSame(registry.coordinator(id), registry.coordinator(id))
            }
        },
        case("Distinct conversation IDs yield distinct coordinators") {
            runRendering {
                val registry = renderingRegistry()
                val radioId = RadioId(UUID.randomUUID())
                val dm = registry.coordinator(ChatConversationID.dm(radioId, UUID.randomUUID()))
                val channel = registry.coordinator(ChatConversationID.channel(radioId, 0u))
                assertNotSame(dm, channel)
            }
        },
        case("coordinator exceeding cap evicts least recently used") {
            runRendering {
                val registry = renderingRegistry(capacity = 2)
                val radioId = RadioId(UUID.randomUUID())
                val idA = ChatConversationID.dm(radioId, UUID.randomUUID())
                val idB = ChatConversationID.dm(radioId, UUID.randomUUID())
                val idC = ChatConversationID.dm(radioId, UUID.randomUUID())
                val firstA = registry.coordinator(idA)
                registry.coordinator(idB)
                registry.coordinator(idC) // evicts A
                assertNotSame(firstA, registry.coordinator(idA), "Evicted entry should be reconstructed")
            }
        },
        case("coordinator touching entry promotes it to most recently used") {
            runRendering {
                val registry = renderingRegistry(capacity = 2)
                val radioId = RadioId(UUID.randomUUID())
                val idA = ChatConversationID.dm(radioId, UUID.randomUUID())
                val idB = ChatConversationID.dm(radioId, UUID.randomUUID())
                val idC = ChatConversationID.dm(radioId, UUID.randomUUID())
                val firstA = registry.coordinator(idA)
                registry.coordinator(idB)
                registry.coordinator(idA) // touch: promotes A
                registry.coordinator(idC) // evicts B, not A
                assertSame(firstA, registry.coordinator(idA), "Touched entry should survive eviction")
            }
        },
        case("remove(for:) evicts only that conversation so the next lookup starts fresh") {
            runRendering {
                val registry = renderingRegistry()
                val radioId = RadioId(UUID.randomUUID())
                val channelID = ChatConversationID.channel(radioId, 3u)
                val dmID = ChatConversationID.dm(radioId, UUID.randomUUID())
                val staleChannel = registry.coordinator(channelID)
                val dm = registry.coordinator(dmID)
                registry.remove(channelID)
                registry.remove(ChatConversationID.channel(radioId, 7u)) // absent: no-op
                assertNotSame(staleChannel, registry.coordinator(channelID))
                assertSame(dm, registry.coordinator(dmID))
            }
        },
    ).map { (name, body) -> DynamicTest.dynamicTest("ChatCoordinatorRegistryTests::$name()", body) }

    /**
     * Adaptation: Swift seeds a contact and a message into an offline (no connection) store. The contact
     * row is irrelevant to coordinator binding and `ContactPersisting` is outside `MessagePersisting`, so
     * only the message is saved; the assertions (store contents and the coordinator bound to that very
     * store) are unchanged.
     */
    @TestFactory
    fun chatCoordinatorRegistryOfflineTests(): List<DynamicTest> = listOf(
        DynamicTest.dynamicTest("ChatCoordinatorRegistryOfflineTests::coordinator against offline store returns coordinator bound to store()") {
            runRendering {
                val radioId = RadioId(UUID.randomUUID())
                val contactID = UUID.randomUUID()
                val store = RenderingInMemoryMessageStore()
                val message = RenderingFixtures.testDirectMessage(
                    radioId = radioId, contactID = contactID, text = "hello", status = MessageStatus.DELIVERED,
                )
                store.saveMessage(message)
                val registry = renderingRegistry(store)
                val coordinator = registry.coordinator(ChatConversationID.dm(radioId, contactID))
                val messages = store.fetchMessages(EntityKey(radioId, contactID))
                assertEquals(1, messages.size)
                assertEquals("hello", messages.first().text)
                assertSame(store, coordinator.dataStore)
            }
        },
    )

    @TestFactory
    fun nativeRegistryCases(): List<DynamicTest> = listOf(
        native("existingCoordinator neither creates nor promotes an entry") {
            runRendering {
                val registry = renderingRegistry(capacity = 2)
                val radioId = RadioId(UUID.randomUUID())
                val idA = ChatConversationID.dm(radioId, UUID.randomUUID())
                val idB = ChatConversationID.dm(radioId, UUID.randomUUID())
                val idC = ChatConversationID.dm(radioId, UUID.randomUUID())
                assertNull(registry.existingCoordinator(idA))
                val firstA = registry.coordinator(idA)
                registry.coordinator(idB)
                assertSame(firstA, registry.existingCoordinator(idA)) // no promotion
                registry.coordinator(idC) // still evicts A
                assertNull(registry.existingCoordinator(idA))
            }
        },
        native("eviction, remove and clear cancel the dropped coordinator's in-flight work") {
            runRendering {
                val store = RenderingInMemoryMessageStore()
                val registry = renderingRegistry(store, capacity = 1)
                val radioId = RadioId(UUID.randomUUID())
                val first = registry.coordinator(ChatConversationID.dm(radioId, UUID.randomUUID()))
                first.rebuildItems(emptyList(), EnvInputs.DEFAULT)
                val evictedBuild = first.buildItemsTask
                registry.coordinator(ChatConversationID.dm(radioId, UUID.randomUUID())) // evicts first
                assertTrue(evictedBuild?.isCancelled == true)

                val removedID = ChatConversationID.channel(radioId, 1u)
                val removed = registry.coordinator(removedID)
                removed.rebuildItems(emptyList(), EnvInputs.DEFAULT)
                registry.remove(removedID)
                assertTrue(removed.buildItemsTask?.isCancelled == true)

                val cleared = registry.coordinator(ChatConversationID.channel(radioId, 2u))
                cleared.rebuildItems(emptyList(), EnvInputs.DEFAULT)
                registry.clear()
                assertTrue(cleared.buildItemsTask?.isCancelled == true)
                assertNull(registry.existingCoordinator(ChatConversationID.channel(radioId, 2u)))
                assertNotSame(cleared, registry.coordinator(ChatConversationID.channel(radioId, 2u)), "registry stays usable")
            }
        },
        native("capacity zero evicts the new coordinator immediately, as Swift's while-loop does") {
            runRendering {
                val registry = renderingRegistry(capacity = 0)
                val id = ChatConversationID.dm(RadioId(UUID.randomUUID()), UUID.randomUUID())
                val first = registry.coordinator(id)
                assertNull(registry.existingCoordinator(id))
                assertNotSame(first, registry.coordinator(id))
            }
        },
        native("negative capacity is rejected (Swift traps on removeFirst of an empty array)") {
            runRendering { assertFailsWith<IllegalArgumentException> { renderingRegistry(capacity = -1) } }
        },
    )

    private fun case(name: String, body: () -> Unit) = name to body

    private fun native(name: String, body: () -> Unit) = DynamicTest.dynamicTest("WP-213::$name", body)
}
