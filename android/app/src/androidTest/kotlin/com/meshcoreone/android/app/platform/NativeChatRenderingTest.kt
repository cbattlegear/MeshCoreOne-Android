// PortedFrom: MC1Tests/Views/Chats/Models/MessageFragmentBuilderOffMainTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.platform

import android.content.Context
import android.os.Looper
import android.view.Choreographer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.services.rendering.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires device execution; a JVM executor or Robolectric result is not this evidence. */
@RunWith(AndroidJUnit4::class)
class NativeChatRenderingTest {
    private class BuildGate {
        val started = CompletableDeferred<Thread>()
        val release = CountDownLatch(1)
    }

    @Test fun androidMainRemainsFrameResponsiveAndReleasedBuildCannotCommit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java).build()
        val ownerJob = SupervisorJob()
        val scope = CoroutineScope(ownerJob + Dispatchers.Main)
        val store = RoomPersistenceStore(database, scope)
        val nextGate = AtomicReference<BuildGate?>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                Dispatchers.Default.dispatch(context) {
                    nextGate.getAndSet(null)?.let { gate ->
                        gate.started.complete(Thread.currentThread())
                        gate.release.await(10, TimeUnit.SECONDS)
                    }
                    block.run()
                }
            }
        }
        val registry = ChatCoordinatorRegistry(store, scope, buildDispatcher = dispatcher)
        val radio = RadioId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
        val coordinator = registry.coordinator(ChatConversationID.dm(radio,
            UUID.fromString("22222222-2222-2222-2222-222222222222")))
        val messages = List(1_000) { index ->
            MessageDTO(radioId = radio, text = "original $index", timestamp = index.toUInt(),
                direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED)
        }
        fun pairs(values: List<MessageDTO>) = values.map { message ->
            message to MessageBuildInputs(
                messageID = message.id, previewState = PreviewLoadState.IDLE,
                loadedPreview = null, cachedURL = null, isInlineImageURL = false,
                hasInlineImageRef = false, hasPreviewImageRef = false, hasPreviewIconRef = false,
                imageIsGIF = false, formattedText = null, baseColor = BaseColorSlot.INCOMING,
                formattedPath = null, senderResolution = NodeNameResolution("", NodeNameMatchKind.UNRESOLVED),
                showTimestamp = false, showDirectionGap = false, showSenderName = false,
                showNewMessagesDivider = false,
            )
        }
        val inputs = pairs(messages)
        val firstGate = BuildGate()
        val cancelledGate = BuildGate()
        try {
            withTimeout(30_000) {
                val owner = Any()
                val writer = withContext(Dispatchers.Main) {
                    assertSame(Looper.getMainLooper().thread, Thread.currentThread())
                    requireNotNull(coordinator.bindWriter(owner, ChatWriterRole.INTERACTIVE)).also {
                        it.replaceAll(messages)
                        nextGate.set(firstGate)
                        it.rebuildItems(inputs, EnvInputs.DEFAULT)
                    }
                }
                assertNotSame(Looper.getMainLooper().thread, firstGate.started.await())
                val frame = CompletableDeferred<Long>()
                withContext(Dispatchers.Main) {
                    Choreographer.getInstance().postFrameCallback { frame.complete(it) }
                }
                withTimeout(5_000) { assertTrue(frame.await() > 0) }
                assertEquals(1L, firstGate.release.count)
                firstGate.release.countDown()
                requireNotNull(coordinator.buildItemsTask).join()
                assertEquals(messages.map { it.id }, coordinator.renderState.items.map { it.id })
                assertEquals("original 0",
                    (coordinator.renderState.items.first().content.first() as MessageFragment.Text).payload.raw)

                withContext(Dispatchers.Main) {
                    nextGate.set(cancelledGate)
                    writer.rebuildItems(inputs, EnvInputs.DEFAULT)
                }
                assertNotSame(Looper.getMainLooper().thread, cancelledGate.started.await())
                val abandoned = requireNotNull(coordinator.buildItemsTask)
                val successor = Any()
                val replacement = listOf(messages.first().copy(text = "successor"))
                withContext(Dispatchers.Main) {
                    coordinator.releaseWriter(owner)
                    assertFalse(writer.isCurrent)
                    val next = requireNotNull(coordinator.bindWriter(successor, ChatWriterRole.INTERACTIVE))
                    next.replaceAll(replacement)
                    next.rebuildItems(pairs(replacement), EnvInputs.DEFAULT)
                }
                assertTrue(abandoned.isCancelled)
                cancelledGate.release.countDown()
                abandoned.join()
                requireNotNull(coordinator.buildItemsTask).join()
                assertEquals("successor",
                    (coordinator.renderState.items.single().content.first() as MessageFragment.Text).payload.raw)
                coordinator.releaseWriter(successor)
            }
        } finally {
            firstGate.release.countDown()
            cancelledGate.release.countDown()
            registry.clear()
            ownerJob.cancelAndJoin()
            try { store.close() } finally { database.close() }
        }
    }
}
