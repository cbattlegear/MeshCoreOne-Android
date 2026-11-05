// AndroidOnly: WP-002 Disposable hosted quiet-logging probe; never merge this diagnostic ref.
package com.meshcoreone.android.feature.tools.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class QuietFailureDiagnosticProbeTest {
    @Test
    fun companionTestStillPasses() {
        assertEquals(2, 1 + 1)
    }

    @Test
    fun intentionalFailureRetainsAssertionCauseAndStack() {
        distinguishingStackFrame()
    }

    private fun distinguishingStackFrame(): Nothing {
        throw AssertionError("WP002_QUIET_PROBE_ASSERTION_e9287928").apply {
            initCause(IllegalStateException("WP002_QUIET_PROBE_CAUSE_e9287928"))
        }
    }
}
