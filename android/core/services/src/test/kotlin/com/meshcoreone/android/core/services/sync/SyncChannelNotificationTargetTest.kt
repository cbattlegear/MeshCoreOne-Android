// AndroidOnly: WP-214 Preserve the displayed channel snapshot without asserting RF receive provenance.
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.notifications.ChannelNotificationTarget
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncChannelNotificationTargetTest {
    @TestFactory
    fun displayedTargets(): List<DynamicTest> = listOf(
        nativeCase("channel notification uses the displayed name and level without inventing occupant proof") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val channel = ChannelDTO(
                radioId = radioId, index = 2u, name = "Displayed",
                notificationLevel = NotificationLevel.MENTIONS_ONLY,
            )
            store.saveChannel(channel)
            val services = SyncTestServices(store)
            val coordinator = coordinator()
            coordinator.wireMessageHandlers(services.dependencies(), radioId)
            checkNotNull(services.polling.capturedChannelMessageHandler)(
                channelMessage("Peer: hello @[TestDevice]", clock.now(), channel.index), channel, DeliveryContext.Live,
            )

            val post = services.notifications.posts.single()
            assertEquals(channel.name, post.title)
            assertEquals(channel.notificationLevel, post.notificationLevel)
            assertEquals(true, post.hasSelfMention)
            assertEquals(ChannelNotificationTarget.Unproved, post.displayTarget)
            assertEquals(listOf("hello @[TestDevice]"), store.allMessages().map { it.text })
        },
        nativeCase("unresolved receive remains saved without inventing a display target or notification") {
            val radioId = newRadio()
            val services = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId))
            val coordinator = coordinator()
            coordinator.wireMessageHandlers(services.dependencies(), radioId)
            checkNotNull(services.polling.capturedChannelMessageHandler)(
                channelMessage("Peer: unresolved", clock.now(), 3u), null, DeliveryContext.Live,
            )

            assertEquals(listOf("unresolved"), services.dataStore.allMessages().map { it.text })
            assertTrue(services.notifications.posts.isEmpty())
            assertEquals(setOf<UByte>(3u), coordinator.unresolvedChannelIndexSnapshot)
        },
        nativeCase("cancellation during the pre-post wait does not publish a stale display target") {
            val radioId = newRadio()
            val channel = ChannelDTO(radioId = radioId, index = 1u, name = "Cancelled")
            val services = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId))
            services.dataStore.saveChannel(channel)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val notifications = object : SyncNotificationServicing by services.notifications {
                override suspend fun activeChannelIndex(): UByte? {
                    entered.complete(Unit)
                    release.await()
                    return null
                }
            }
            val coordinator = coordinator()
            coordinator.wireMessageHandlers(services.dependencies().copy(notificationService = notifications), radioId)
            val delivery = launch {
                checkNotNull(services.polling.capturedChannelMessageHandler)(
                    channelMessage("Peer: persisted", clock.now(), channel.index), channel, DeliveryContext.Live,
                )
            }
            entered.await()
            assertFalse(delivery.isCompleted)
            delivery.cancelAndJoin()
            release.complete(Unit)

            assertTrue(delivery.isCancelled)
            assertEquals(listOf("persisted"), services.dataStore.allMessages().map { it.text })
            assertTrue(services.notifications.posts.isEmpty())
        },
    ) + listOf(false, true).map { sameKey ->
        nativeCase("held and reordered notifications remain unproved across ${if (sameKey) "same-key ABA" else "replacement"}") {
            val radioId = newRadio()
            val old = ChannelDTO(radioId = radioId, index = 2u, name = "Old", secret = Bytes(ByteArray(16) { 1 }))
            val middle = old.copy(
                name = if (sameKey) "Middle" else "Replacement",
                secret = Bytes(ByteArray(16) { 2 }),
                notificationLevel = NotificationLevel.MENTIONS_ONLY,
            )
            val replacement = if (sameKey) {
                middle.copy(name = "Replacement", secret = old.secret)
            } else middle
            assertEquals(old.id, middle.id)
            assertEquals(old.id, replacement.id)
            assertNotEquals(old.secret, middle.secret)
            if (sameKey) assertEquals(old.secret, replacement.secret)
            val services = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId))
            services.dataStore.saveChannel(old)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var first = true
            val notifications = object : SyncNotificationServicing by services.notifications {
                override suspend fun activeChannelIndex(): UByte? {
                    if (first) {
                        first = false
                        entered.complete(Unit)
                        release.await()
                    }
                    return null
                }
            }
            val coordinator = coordinator()
            coordinator.wireMessageHandlers(services.dependencies().copy(notificationService = notifications), radioId)
            val handler = checkNotNull(services.polling.capturedChannelMessageHandler)
            val held = launch {
                handler(channelMessage("Peer: old", clock.now(), old.index), old, DeliveryContext.Live)
            }
            entered.await()
            assertFalse(held.isCompleted)
            services.dataStore.replaceChannelSlotForTest(middle)
            assertEquals(listOf(middle), services.dataStore.fetchChannels(radioId).toList())
            if (sameKey) services.dataStore.replaceChannelSlotForTest(replacement)
            assertEquals(listOf(replacement), services.dataStore.fetchChannels(radioId).toList())
            handler(
                channelMessage("Peer: replacement @[TestDevice]", clock.now().plusSeconds(1), replacement.index),
                replacement, DeliveryContext.Live,
            )
            release.complete(Unit)
            held.join()

            val posts = services.notifications.posts
            assertEquals(listOf(replacement.name, old.name), posts.map { it.title })
            assertEquals(
                listOf(
                    ChannelNotificationTarget.Unproved,
                    ChannelNotificationTarget.Unproved,
                ),
                posts.map { it.displayTarget },
            )
            assertEquals(listOf(replacement.notificationLevel, old.notificationLevel), posts.map { it.notificationLevel })
            assertEquals(listOf(true, false), posts.map { it.hasSelfMention })
            assertEquals(setOf("old", "replacement @[TestDevice]"), services.dataStore.allMessages().map { it.text }.toSet())
        }
    }
}
