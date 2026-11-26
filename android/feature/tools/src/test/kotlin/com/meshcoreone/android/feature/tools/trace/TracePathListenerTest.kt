// PortedFrom: MC1Tests/ViewModels/TracePathListenerTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The listener must follow the current session graph: each graph finishes its event stream on
 * teardown. The fixture broadcaster has WP-209's subscribe-time registration contract; the source
 * built a real ServiceContainer over a mock transport, which lives outside this feature module.
 */
class TracePathListenerTest {
    private val harness = TraceHarness(configureDependencies = true)
    private var currentServices: TraceResponseBroadcaster? = null

    init {
        harness.deps.responses = { currentServices?.subscribe() }
    }

    @AfterTest
    fun tearDown() = harness.holder.stopListening()

    private fun yieldResponse(services: TraceResponseBroadcaster) {
        harness.holder.setPendingTagForTesting(TEST_TAG)
        services.yield(TraceResponse(traceInfo(TEST_TAG, listOf(node(0xAB, 5.0), node(null, 3.0))), RadioId(UUID.randomUUID())))
        harness.main.runCurrent()
    }

    @Test
    @OriginalCase("TracePathListenerTests::Listener established after a late connect receives trace responses()", "fixture-graph-equivalent")
    fun `Listener established after a late connect receives trace responses`() {
        harness.holder.startListening()
        val services = TraceResponseBroadcaster()
        currentServices = services
        harness.holder.startListening()
        harness.main.runCurrent()
        yieldResponse(services)
        assertNotNull(harness.state.result, "Trace response after a late connect should produce a result")
    }

    @Test
    @OriginalCase("TracePathListenerTests::Listener re-established after a container rebuild receives trace responses()", "fixture-graph-equivalent")
    fun `Listener re-established after a container rebuild receives trace responses`() {
        val oldServices = TraceResponseBroadcaster()
        currentServices = oldServices
        harness.holder.startListening()
        harness.main.runCurrent()
        oldServices.finish()
        val newServices = TraceResponseBroadcaster()
        currentServices = newServices
        harness.holder.startListening()
        harness.main.runCurrent()
        yieldResponse(newServices)
        assertNotNull(harness.state.result, "Trace response after a rebuild should reach the re-subscribed listener")
    }

    @Test
    fun `a listener that is not re-established misses responses from the rebuilt graph`() {
        val oldServices = TraceResponseBroadcaster()
        currentServices = oldServices
        harness.holder.startListening()
        harness.main.runCurrent()
        oldServices.finish()
        val newServices = TraceResponseBroadcaster()
        currentServices = newServices
        yieldResponse(newServices)
        assertNull(harness.state.result)
    }

    private companion object {
        val TEST_TAG: UInt = 0x00C0FFEEu
    }
}
