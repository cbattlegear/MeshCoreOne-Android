// PortedFrom: MC1Tests/Utilities/FirmwareSuggestedTimeoutTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.diagnostics

import com.meshcoreone.android.feature.tools.diagnostics.FirmwareSuggestedTimeout.Profile
import com.meshcoreone.android.feature.tools.diagnostics.support.OriginalCase
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.Test

class FirmwareSuggestedTimeoutTest {
    private val tolerance = 0.0001

    private fun assertClose(expected: Double, actual: Double) =
        assertTrue(abs(actual - expected) < tolerance, "expected $expected, got $actual")

    @Test @OriginalCase("FirmwareSuggestedTimeoutTests::Zero-hop honors a small valid hint instead of inflating it()")
    fun `Zero-hop honors a small valid hint instead of inflating it`() {
        val timeout = FirmwareSuggestedTimeout.sanitizedSeconds(3200u, Profile.ZERO_HOP)
        assertClose(3.84, timeout)
        assertTrue(timeout < 5.0)
    }

    @Test @OriginalCase("FirmwareSuggestedTimeoutTests::Zero-hop honors a fast-preset hint()")
    fun `Zero-hop honors a fast-preset hint`() = assertClose(1.56, FirmwareSuggestedTimeout.sanitizedSeconds(1300u, Profile.ZERO_HOP))

    @Test @OriginalCase("FirmwareSuggestedTimeoutTests::Zero-hop floors a tiny hint()")
    fun `Zero-hop floors a tiny hint`() = assertEquals(1.0, FirmwareSuggestedTimeout.sanitizedSeconds(500u, Profile.ZERO_HOP))

    @Test @OriginalCase("FirmwareSuggestedTimeoutTests::Zero-hop honors a slow max-range preset hint up to the ceiling()")
    fun `Zero-hop honors a slow max-range preset hint up to the ceiling`() =
        assertEquals(24.0, FirmwareSuggestedTimeout.sanitizedSeconds(20_000u, Profile.ZERO_HOP))

    @Test @OriginalCase("FirmwareSuggestedTimeoutTests::Zero-hop defaults on a missing hint()")
    fun `Zero-hop defaults on a missing hint`() = assertEquals(5.0, FirmwareSuggestedTimeout.sanitizedSeconds(0u, Profile.ZERO_HOP))

    @Test @OriginalCase("FirmwareSuggestedTimeoutTests::Zero-hop caps an absurd hint()")
    fun `Zero-hop caps an absurd hint`() = assertEquals(30.0, FirmwareSuggestedTimeout.sanitizedSeconds(68_719_800u, Profile.ZERO_HOP))

    @Test @OriginalCase("FirmwareSuggestedTimeoutTests::Flood adds return-leg grace on top of a sane hint()")
    fun `Flood adds return-leg grace on top of a sane hint`() =
        assertEquals(14.0, FirmwareSuggestedTimeout.sanitizedSeconds(5000u, Profile.FLOOD))

    @Test @OriginalCase("FirmwareSuggestedTimeoutTests::Flood grace lifts a small hint above the floor()")
    fun `Flood grace lifts a small hint above the floor`() = assertClose(11.6, FirmwareSuggestedTimeout.sanitizedSeconds(3000u, Profile.FLOOD))

    @Test @OriginalCase("FirmwareSuggestedTimeoutTests::Flood defaults on a missing hint()")
    fun `Flood defaults on a missing hint`() = assertEquals(30.0, FirmwareSuggestedTimeout.sanitizedSeconds(0u, Profile.FLOOD))

    @Test @OriginalCase("FirmwareSuggestedTimeoutTests::Flood caps an absurd hint()")
    fun `Flood caps an absurd hint`() = assertEquals(60.0, FirmwareSuggestedTimeout.sanitizedSeconds(68_719_800u, Profile.FLOOD))

    @Test @OriginalCase("FirmwareSuggestedTimeoutTests::Path discovery floors a fast-preset hint to the multi-hop minimum()")
    fun `Path discovery floors a fast-preset hint to the multi-hop minimum`() {
        val timeout = FirmwareSuggestedTimeout.pathDiscoverySeconds(5000u)
        assertEquals(FirmwareSuggestedTimeout.PATH_DISCOVERY_MINIMUM_OVERALL_SECONDS, timeout)
        assertEquals(20.0, timeout)
    }

    @Test @OriginalCase("FirmwareSuggestedTimeoutTests::Path discovery honors a larger flood budget above the minimum()")
    fun `Path discovery honors a larger flood budget above the minimum`() =
        assertEquals(32.0, FirmwareSuggestedTimeout.pathDiscoverySeconds(20_000u))

    @Test @OriginalCase("FirmwareSuggestedTimeoutTests::Path discovery missing hint uses the flood default above the minimum()")
    fun `Path discovery missing hint uses the flood default above the minimum`() =
        assertEquals(30.0, FirmwareSuggestedTimeout.pathDiscoverySeconds(0u))

    @Test @OriginalCase("FirmwareSuggestedTimeoutTests::Path discovery retransmit interval uses double firmware est with a five second floor()")
    fun `Path discovery retransmit interval uses double firmware est with a five second floor`() {
        assertNull(FirmwareSuggestedTimeout.pathDiscoveryRetransmitInterval(0u))
        assertEquals(5.seconds, FirmwareSuggestedTimeout.pathDiscoveryRetransmitInterval(1000u))
        assertEquals(10.seconds, FirmwareSuggestedTimeout.pathDiscoveryRetransmitInterval(5000u))
    }
}
