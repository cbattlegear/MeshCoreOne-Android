// AndroidOnly: WP-201 Original-dispatch result shape controls, not issuance, gating or wire proof.
package com.meshcoreone.android.core.contracts.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class OriginalChannelDispatchAuthorityTest {
    // Test markers are not accepted production admission issuers.
    private class Admission : OriginalChannelDispatchAdmission

    @Test
    fun executedRetainsTheExactCallbackResultReference() {
        val actual = Any()
        val result = OriginalChannelDispatchResult.Executed(actual)

        assertSame(actual, result.value)
    }

    @Test
    fun nullableCallbackResultIsNotConfusedWithRejection() {
        val result: OriginalChannelDispatchResult<String?> =
            OriginalChannelDispatchResult.Executed(null)

        assertNull(assertIs<OriginalChannelDispatchResult.Executed<String?>>(result).value)
    }

    @Test
    fun staleIsAnExplicitResultForAnyCallbackValueType() {
        val result: OriginalChannelDispatchResult<String> = OriginalChannelDispatchResult.Stale

        assertSame(OriginalChannelDispatchResult.Stale, result)
    }

    @Test
    fun rejectionDoesNotContainAReplacementAdmission() {
        val result: OriginalChannelDispatchResult<String> =
            OriginalChannelDispatchResult.Rejected(OriginalChannelDispatchRejection.INVALID_ADMISSION)

        assertEquals(
            OriginalChannelDispatchRejection.INVALID_ADMISSION,
            assertIs<OriginalChannelDispatchResult.Rejected>(result).reason,
        )
    }

    @Test
    fun distinctOpaqueMarkersDoNotBecomeEqualOriginalAuthority() {
        val first = Admission()
        val second = Admission()

        assertNotSame(first, second)
    }

    @Test
    fun callbackResultCanBeCovariantlyObservedWithoutCreatingAGrant() {
        val result: OriginalChannelDispatchResult<Any> =
            OriginalChannelDispatchResult.Executed("callback result")

        assertEquals(
            "callback result",
            assertIs<OriginalChannelDispatchResult.Executed<Any>>(result).value,
        )
    }
}
