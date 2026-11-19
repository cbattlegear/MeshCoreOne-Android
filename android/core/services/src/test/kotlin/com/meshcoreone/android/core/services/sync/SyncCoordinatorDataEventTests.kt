// PortedFrom: MC1Services/Tests/MC1ServicesTests/SyncCoordinatorDataEventTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.sync

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Original SyncCoordinatorDataEventTests: multicast data events. */
class SyncCoordinatorDataEventTests {
    private val suite = "SyncCoordinatorDataEventTests"

    @TestFactory
    fun cases(): List<DynamicTest> = listOf(
        syncCase(suite, "two subscribers both observe contactsChanged after notifyContactsChanged") {
            val coordinator = coordinator()
            val streamA = coordinator.dataEvents()
            val streamB = coordinator.dataEvents()
            coordinator.notifyContactsChanged()
            coordinator.notifyConversationsChanged()
            coordinator.finishDataEvents()
            val expected = listOf<SyncDataEvent>(SyncDataEvent.ContactsChanged, SyncDataEvent.ConversationsChanged)
            assertEquals(expected, streamA.toList())
            assertEquals(expected, streamB.toList())
            assertEquals(1, coordinator.contactsVersion)
            assertEquals(1, coordinator.conversationsVersion)
        },
        syncCase(suite, "finishDataEvents ends every subscriber's iteration") {
            val coordinator = coordinator()
            val streamA = coordinator.dataEvents()
            val streamB = coordinator.dataEvents()
            val consumerA = task { streamA.collect {}; true }
            val consumerB = task { streamB.collect {}; true }
            settle()
            coordinator.finishDataEvents()
            assertTrue(consumerA.await())
            assertTrue(consumerB.await())
            assertEquals(0, coordinator.dataEventBroadcaster.subscriberCount)
        },
        syncCase(suite, "tryYield rejects events after finishDataEvents") {
            val coordinator = coordinator()
            coordinator.finishDataEvents()
            assertFalse(coordinator.dataEventBroadcaster.tryYield(SyncDataEvent.ContactsChanged))
        },
    )
}
