// PortedFrom: MC1Tests/Views/Chats/Models/MessageFragmentBuilderOffMainTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Adaptation: Swift proves the builder is not `@MainActor` by calling it from `Task.detached` and
 * checking `Thread.isMainThread`. Here a dedicated single thread plays the main thread; the builder is
 * invoked from a `Dispatchers.Default` worker (the coordinator's default build dispatcher) and must run
 * there without hopping back. The coordinator-level property is covered by the WP-213 rebuild cases.
 */
class MessageFragmentBuilderOffMainTest {
    @TestFactory
    fun messageFragmentBuilderOffMainTests(): List<DynamicTest> = listOf(
        DynamicTest.dynamicTest("MessageFragmentBuilderOffMainTests::Builder is callable from a detached task off the main actor()") {
            val mainExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "rendering-main") }
            try {
                mainExecutor.asCoroutineDispatcher().use { main ->
                    runBlocking(main) {
                        val mainThread = Thread.currentThread()
                        val message = RenderingFixtures.makePlainTextMessage(0)
                        val inputs = RenderingFixtures.makeMinimalInputs(message.id)
                        val (item, buildThread) = withContext(Dispatchers.Default) {
                            MessageFragmentBuilder.makeItem(message, inputs, EnvInputs.DEFAULT) to Thread.currentThread()
                        }
                        assertNotSame(mainThread, buildThread, "Builder must execute off the main thread")
                        assertEquals(message.id, item.id)
                    }
                }
            } finally {
                mainExecutor.shutdown()
            }
        },
    )
}
