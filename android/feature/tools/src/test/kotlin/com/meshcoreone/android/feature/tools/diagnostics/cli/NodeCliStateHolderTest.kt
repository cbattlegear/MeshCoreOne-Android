// PortedFrom: MC1Tests/Views/RemoteNodes/NodeCLIViewModelTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.feature.tools.diagnostics.support.OriginalCase
import com.meshcoreone.android.feature.tools.diagnostics.support.TestScheduler
import com.meshcoreone.android.feature.tools.diagnostics.support.XmlDiagnosticsText
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.Test

class NodeCliStateHolderTest {
    /** Records the commands/timeouts passed to the sender and returns [result] or throws [error]. */
    private class SendSpy : NodeCliSender {
        val calls = mutableListOf<Pair<String, Duration>>()
        var result = "OK"
        var error: Exception? = null

        override suspend fun send(command: String, timeout: Duration): String {
            calls += command to timeout
            error?.let { throw it }
            return result
        }
    }

    private val scheduler = TestScheduler()

    private fun makeHolder(spy: SendSpy): NodeCliStateHolder =
        NodeCliStateHolder(scheduler.scope, XmlDiagnosticsText(), scheduler.clock, FAKE_ERRORS).also {
            it.configure("TestNode", spy)
        }

    private fun NodeCliStateHolder.run(line: String) {
        executeCommand(line)
        scheduler.runCurrent()
    }

    private val NodeCliStateHolder.lines get() = state.value.terminal.outputLines

    @Test @OriginalCase("NodeCLIViewModelTests::Configure shows a banner naming the node()")
    fun `Configure shows a banner naming the node`() {
        val holder = makeHolder(SendSpy())
        assertTrue(holder.lines.joinToString("\n") { it.text }.contains("TestNode"))
    }

    @Test @OriginalCase("NodeCLIViewModelTests::configure is idempotent: re-configuring does not duplicate the banner()")
    fun `configure is idempotent - re-configuring does not duplicate the banner`() {
        val holder = makeHolder(SendSpy())
        val count = holder.lines.size
        holder.configure("TestNode", SendSpy())
        assertEquals(count, holder.lines.size)
    }

    @Test @OriginalCase("NodeCLIViewModelTests::help prints help and does not send()")
    fun `help prints help and does not send`() {
        val spy = SendSpy()
        val holder = makeHolder(spy)
        holder.run("help")
        assertTrue(holder.lines.any { "Available commands" in it.text })
        assertTrue(spy.calls.isEmpty())
    }

    @Test @OriginalCase("NodeCLIViewModelTests::bare clear empties output and does not send()")
    fun `bare clear empties output and does not send`() {
        val spy = SendSpy()
        val holder = makeHolder(spy)
        holder.run("help")
        assertTrue(holder.lines.isNotEmpty())
        holder.run("clear")
        assertTrue(holder.lines.isEmpty())
        assertTrue(spy.calls.isEmpty())
    }

    @Test @OriginalCase("NodeCLIViewModelTests::clear stats sends the raw command()")
    fun `clear stats sends the raw command`() {
        val spy = SendSpy()
        makeHolder(spy).run("clear stats")
        assertTrue(spy.calls.any { it.first == "clear stats" })
    }

    @Test @OriginalCase("NodeCLIViewModelTests::reboot sends with a 2s timeout and renders timeout as success()", "adapted-service-fake")
    fun `reboot sends with a 2s timeout and renders timeout as success`() {
        val spy = SendSpy().apply { error = FakeRemoteTimeout() }
        val holder = makeHolder(spy)
        holder.run("reboot")
        assertTrue(holder.lines.any { it.type == CliOutputType.SUCCESS })
        assertEquals(2.seconds, assertNotNull(spy.calls.firstOrNull { it.first == "reboot" }).second)
    }

    @Test
    fun `contact radio CLI does not treat reboot timeout as success`() {
        val spy = SendSpy().apply { error = FakeRemoteTimeout() }
        val holder = NodeCliStateHolder(
            scheduler.scope, XmlDiagnosticsText(), scheduler.clock, FAKE_ERRORS, rebootTimeoutIsSuccess = false,
        )
        holder.configure("Contact", spy)
        holder.run("reboot")
        assertEquals(10.seconds, spy.calls.single().second)
        assertTrue(holder.lines.any { it.type == CliOutputType.ERROR })
        assertFalse(holder.lines.any { it.type == CliOutputType.SUCCESS })
    }

    @Test @OriginalCase("NodeCLIViewModelTests::reboot now is treated as a reboot()")
    fun `reboot now is treated as a reboot`() {
        val spy = SendSpy()
        makeHolder(spy).run("reboot now")
        assertTrue(spy.calls.contains("reboot now" to 2.seconds))
    }

    @Test @OriginalCase("NodeCLIViewModelTests::a reboot typo is sent as a normal command, not treated as a reboot()")
    fun `a reboot typo is sent as a normal command, not treated as a reboot`() {
        val spy = SendSpy().apply { result = "Unknown command" }
        val holder = makeHolder(spy)
        holder.run("rebootnow")
        assertTrue(spy.calls.contains("rebootnow" to 10.seconds))
        assertTrue(holder.lines.any { it.text == "Unknown command" && it.type == CliOutputType.RESPONSE })
        assertFalse(holder.lines.any { it.type == CliOutputType.SUCCESS })
    }

    @Test @OriginalCase("NodeCLIViewModelTests::a cancelled reboot does not surface a raw CancellationError()")
    fun `a cancelled reboot does not surface a raw CancellationError`() {
        val spy = SendSpy().apply { error = CancellationException("cancelled") }
        val holder = makeHolder(spy)
        holder.run("reboot")
        assertTrue(spy.calls.any { it.first == "reboot" })
        assertFalse(holder.lines.any { it.type == CliOutputType.ERROR })
        assertFalse(holder.lines.any { it.type == CliOutputType.SUCCESS })
        assertFalse(holder.state.value.isWaitingForResponse)
    }

    @Test @OriginalCase("NodeCLIViewModelTests::arbitrary command sends with default timeout and appends response()")
    fun `arbitrary command sends with default timeout and appends response`() {
        val spy = SendSpy().apply { result = "af: 1" }
        val holder = makeHolder(spy)
        holder.run("get af")
        assertTrue(holder.lines.any { it.text == "af: 1" && it.type == CliOutputType.RESPONSE })
        assertEquals(10.seconds, assertNotNull(spy.calls.firstOrNull { it.first == "get af" }).second)
    }

    @Test @OriginalCase("NodeCLIViewModelTests::a thrown error appends an .error line()")
    fun `a thrown error appends an error line`() {
        val spy = SendSpy().apply { error = IllegalStateException("Permission denied") }
        val holder = makeHolder(spy)
        holder.run("set tx 22")
        assertTrue(holder.lines.any { it.type == CliOutputType.ERROR && it.text == "Permission denied" })
    }

    @Test @OriginalCase("NodeCLIViewModelTests::Two view models with two closures each only invoke their own()")
    fun `Two view models with two closures each only invoke their own`() {
        val spyA = SendSpy()
        val spyB = SendSpy()
        val holderA = makeHolder(spyA)
        val holderB = makeHolder(spyB)
        holderA.executeCommand("ver")
        holderB.executeCommand("reboot")
        scheduler.runCurrent()
        assertTrue(spyA.calls.isNotEmpty() && spyA.calls.all { it.first == "ver" })
        assertTrue(spyB.calls.isNotEmpty() && spyB.calls.all { it.first == "reboot" })
    }

    @Test @OriginalCase("NodeCLIViewModelTests::Output is capped at 1000 lines()")
    fun `Output is capped at 1000 lines`() {
        val holder = NodeCliStateHolder(scheduler.scope, XmlDiagnosticsText(), scheduler.clock)
        holder.configure("N") { _, _ -> "" }
        repeat(1001) { holder.appendOutput("line$it", CliOutputType.RESPONSE) }
        assertEquals(1000, holder.lines.size)
    }

    @Test @OriginalCase("NodeCLIViewModelTests::History is capped at 100 entries()")
    fun `History is capped at 100 entries`() {
        val holder = makeHolder(SendSpy())
        repeat(150) { holder.executeCommand("command$it") }
        repeat(100) { holder.historyUp() }
        assertEquals("command50", holder.state.value.terminal.currentInput)
    }

    @Test @OriginalCase("NodeCLIViewModelTests::getResponseBlock walks the full multi-line response block, not just one line()")
    fun `getResponseBlock walks the full multi-line response block, not just one line`() {
        val holder = makeHolder(SendSpy())
        holder.run("help")
        val helpLine = assertNotNull(holder.lines.firstOrNull { "Available commands" in it.text })
        val block = holder.getResponseBlock(helpLine)
        assertTrue("Available commands" in block)
        assertTrue("Reset node statistics" in block)
    }

    // Native WP-316 boundary cases.

    @Test
    fun `waiting flag holds while a send is pending and the prompt hides`() {
        val gate = kotlinx.coroutines.CompletableDeferred<String>()
        val holder = NodeCliStateHolder(scheduler.scope, XmlDiagnosticsText(), scheduler.clock)
        holder.configure("Node") { _, _ -> gate.await() }
        holder.run("ver")
        assertTrue(holder.state.value.isWaitingForResponse)
        assertEquals("", holder.promptText)
        holder.executeCommand("ignored while waiting")
        assertFalse("ignored while waiting" in holder.state.value.terminal.commandHistory)
        gate.complete("1.13.0")
        scheduler.runCurrent()
        assertFalse(holder.state.value.isWaitingForResponse)
        assertEquals("@Node> ", holder.promptText)
        assertEquals("1.13.0", holder.lines.last().text)
    }

    @Test
    fun `cancel during a pending send reports cancelled and drops the late reply`() {
        val gate = kotlinx.coroutines.CompletableDeferred<String>()
        val holder = NodeCliStateHolder(scheduler.scope, XmlDiagnosticsText(), scheduler.clock)
        holder.configure("Node") { _, _ -> gate.await() }
        holder.run("ver")
        holder.cancelCurrentCommand()
        scheduler.runCurrent()
        gate.complete("late")
        scheduler.runCurrent()
        assertEquals("Command cancelled", holder.lines.last().text)
        assertFalse(holder.lines.any { it.text == "late" })
    }

    @Test
    fun `node CLI completion excludes app session commands`() {
        val holder = makeHolder(SendSpy())
        holder.updateInput("log")
        // Only `log` matches (no `logout`), so a single tab applies it.
        kotlin.test.assertNull(holder.tabComplete())
        assertEquals("log ", holder.state.value.terminal.currentInput)
    }
}
