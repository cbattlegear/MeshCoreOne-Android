// AndroidOnly: WP-208 Proves the shared channel fence spans channel SET persistence and runtime graph replacement.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.ChannelIdentityWriteScope
import com.meshcoreone.android.core.contracts.domain.ChannelPersisting
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFence
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFailure
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelFenceRuntimeIntegrationTest : RoomProcessTest() {
    @Test
    fun successorGraphWaitsForAdmittedChannelWriteAndPersistence() = runTest {
        val persistEntered = CompletableDeferred<Unit>()
        val releasePersist = CompletableDeferred<Unit>()
        val holdNextChannelSave = AtomicBoolean(false)
        val radioChannelName = AtomicReference("Original")
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun <T> withChannelOperation(
                block: suspend (ChannelPersisting, ChannelIdentityWriteScope) -> T,
            ): T = store.withChannelOperation { scoped, scope ->
                val held = object : ChannelPersisting by scoped {
                    override suspend fun saveChannel(radioId: RadioId, info: ChannelInfo): UUID {
                        if (holdNextChannelSave.compareAndSet(true, false)) {
                            persistEntered.complete(Unit)
                            releasePersist.await()
                        }
                        return scoped.saveChannel(radioId, info)
                    }
                }
                block(held, scope)
            }
        }
        val harness = ContainerHarness(
            this,
            store,
            persistenceOverride = persistence,
            sessionConfiguration = SessionConfiguration(defaultTimeout = 5.0),
        )
        harness.links.configureRadio = { radio ->
            radio.respond = { command ->
                when (command[0].toInt()) {
                    0x1f -> {
                        val index = command[1].toInt()
                        val name = if (index == 0) radioChannelName.get() else ""
                        val secret = if (index == 0) Bytes.fromHex("11".repeat(16)) else Bytes(ByteArray(16))
                        listOf(Bytes.of(0x12, index) + Bytes.utf8(name).paddedOrTruncated(32) + secret)
                    }
                    0x20 -> {
                        val bytes = command.toByteArray()
                        radioChannelName.set(
                            String(bytes.copyOfRange(2, 34), StandardCharsets.UTF_8).trimEnd('\u0000'),
                        )
                        listOf(Bytes.of(0))
                    }
                    else -> null
                }
            }
        }

        try {
            harness.manager.connect(harness.target(UUID.fromString("D0000000-0000-0000-0000-000000000001")))
            harness.settle()
            harness.assertReady()
            val original = assertNotNull(harness.sessions.current)
            holdNextChannelSave.set(true)
            val oldWrite = backgroundScope.async {
                original.channelService.setChannelWithSecret(
                    original.token.radioId, 0u, "Updated", Bytes.fromHex("22".repeat(16)),
                )
            }
            persistEntered.await()

            val replacementConnect = backgroundScope.async {
                harness.manager.connect(harness.target(UUID.fromString("D0000000-0000-0000-0000-000000000002")))
            }
            harness.eventually("replacement graph publication", virtualBudgetMillis = 100) {
                harness.sessions.current !== original
            }
            val replacement = assertNotNull(harness.sessions.current)
            assertNotSame(original, replacement)
            assertTrue(original.isTornDown)
            assertTrue(replacement.token.generation.value > original.token.generation.value)

            val replacementRadio = harness.links.radios.last()
            assertEquals(
                0,
                replacementRadio.sent.count { it[0].toInt() == 0x1f },
                "the new graph must not read channel identity before the old SET is persisted",
            )
            val replacementLeaseProbe = backgroundScope.async {
                ChannelDispatchFence.PROCESS.withMutation(replacement.channelDispatchLease) { Unit }
            }
            harness.scheduler.runCurrent()
            assertTrue(!replacementLeaseProbe.isCompleted, "the successor lease must share the process dispatch fence")
            assertEquals(
                0,
                replacementRadio.sent.count { it[0].toInt() == 0x1f },
                "the successor graph must wait for the prior graph's SET-to-persist interval",
            )

            releasePersist.complete(Unit)
            assertFailsWith<ChannelDispatchFailure.RetiredSession> { oldWrite.await() }
            replacementConnect.await()
            replacementLeaseProbe.await()
            harness.settle()
            harness.assertReady()
            assertTrue(replacementRadio.sent.any { it[0].toInt() == 0x1f })
            assertEquals("Updated", store.fetchChannel(replacement.token.radioId, 0u)?.name)
            assertFailsWith<ChannelDispatchFailure.RetiredSession> {
                ChannelDispatchFence.PROCESS.withMutation(original.channelDispatchLease) { Unit }
            }
        } finally {
            releasePersist.complete(Unit)
            harness.container.close()
        }
    }
}
