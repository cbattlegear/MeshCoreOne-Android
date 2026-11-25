// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/NotificationStringProviderTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.protocol.model.ContactType
import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class NotificationStringProviderTests {
    @TestFactory
    fun defaults(): List<DynamicTest> = listOf(
        originalCase("NotificationStringProviderTests", "Default fallback titles are English") {
            val service = NotificationHarness(this).service
            assertEquals("New Contact Discovered", service.defaultDiscoveryTitle(ContactType.CHAT))
            assertEquals("New Repeater Discovered", service.defaultDiscoveryTitle(ContactType.REPEATER))
            assertEquals("New Room Discovered", service.defaultDiscoveryTitle(ContactType.ROOM))
        },
    )
}
