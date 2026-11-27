// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.location

import com.meshcoreone.android.core.observer.upload.RecordLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

fun fix(
    atMs: Long, lat: Double = 46.0, lon: Double = -97.0, hAcc: Double = 5.0, alt: Double? = null, vAcc: Double? = null,
    speed: Double? = null, bearing: Double? = null, provider: FixProvider = FixProvider.GPS,
) = LocationFix(lat, lon, hAcc, alt, vAcc, speed, bearing, atMs, provider)

class FixBufferTest {
    @Test fun keepsOnlyTheLastTwoMinutesRelativeToTheNewestFix() {
        val buffer = FixBuffer()
        buffer.add(fix(0)); buffer.add(fix(60_000)); buffer.add(fix(121_000))
        assertEquals(listOf(60_000L, 121_000L), buffer.snapshot().map { it.fixElapsedMs })
        buffer.add(fix(180_001))
        assertEquals(listOf(121_000L, 180_001L), buffer.snapshot().map { it.fixElapsedMs })
    }

    @Test fun anOutOfOrderFixIsInsertedInTimeOrderAndAnOldDuplicateTimeReplaces() {
        val buffer = FixBuffer()
        buffer.add(fix(2_000)); buffer.add(fix(1_000)); buffer.add(fix(3_000, lat = 1.0)); buffer.add(fix(3_000, lat = 2.0))
        assertEquals(listOf(1_000L, 2_000L, 3_000L), buffer.snapshot().map { it.fixElapsedMs })
        assertEquals(2.0, buffer.snapshot().last().latitude)
    }

    @Test fun beforeIsInclusiveAndAfterIsInclusive() {
        val buffer = FixBuffer()
        buffer.add(fix(1_000)); buffer.add(fix(2_000))
        assertEquals(1_000L, buffer.atOrBefore(1_500)?.fixElapsedMs)
        assertEquals(2_000L, buffer.atOrBefore(2_000)?.fixElapsedMs)
        assertEquals(2_000L, buffer.atOrAfter(1_500)?.fixElapsedMs)
        assertEquals(1_000L, buffer.atOrAfter(1_000)?.fixElapsedMs)
        assertNull(buffer.atOrBefore(999)); assertNull(buffer.atOrAfter(2_001))
    }

    @Test fun nonFiniteFixesAreRejected() {
        val buffer = FixBuffer()
        assertFalse(buffer.add(fix(1, lat = Double.NaN)))
        assertFalse(buffer.add(fix(1, hAcc = Double.POSITIVE_INFINITY)))
        assertFalse(buffer.add(fix(1, lat = 91.0)))
        assertFalse(buffer.add(fix(1, lon = 181.0)))
        assertTrue(buffer.snapshot().isEmpty())
    }
}

class PositionResolverTest {
    private val resolver = PositionResolver()

    private fun resolved(rx: Long): RecordLocation =
        assertIs<PositionResult.Resolved>(resolver.resolve(rx, 1_700_000_000_000L + rx)).location

    @Test fun interpolatesLinearlyBetweenFixesLessThanFiveSecondsApart() {
        resolver.add(fix(10_000, lat = 46.0, lon = -97.0, hAcc = 5.0, alt = 200.0, vAcc = 3.0, speed = 10.0, bearing = 90.0))
        resolver.add(fix(14_000, lat = 46.4, lon = -96.6, hAcc = 8.0, alt = 204.0, vAcc = 6.0, speed = 14.0, bearing = 100.0))
        val at = resolved(11_000)
        assertEquals(46.1, at.latitude, 1e-9); assertEquals(-96.9, at.longitude, 1e-9)
        assertEquals(201.0, at.altitudeMeters!!, 1e-9); assertEquals(11.0, at.speedMps!!, 1e-9)
        assertEquals(92.5, at.bearingDegrees!!, 1e-9)
        assertTrue(at.interpolated)
        assertNull(at.fixAgeMs)
        // Never claim better accuracy than the worse of the two fixes.
        assertEquals(8.0, at.hAccuracyMeters); assertEquals(6.0, at.vAccuracyMeters)
    }

    @Test fun interpolatedOptionalFieldsNeedBothFixes() {
        resolver.add(fix(1_000, alt = 10.0, speed = 1.0))
        resolver.add(fix(3_000, alt = null, speed = 3.0))
        val at = resolved(2_000)
        assertNull(at.altitudeMeters); assertEquals(2.0, at.speedMps!!, 1e-9); assertNull(at.bearingDegrees)
    }

    @Test fun interpolationWrapsLongitudeAcrossTheAntimeridian() {
        resolver.add(fix(0, lon = 179.5)); resolver.add(fix(2_000, lon = -179.5))
        assertEquals(180.0, Math.abs(resolved(1_000).longitude), 1e-9)
        resolver.add(fix(10_000, lon = -179.8)); resolver.add(fix(12_000, lon = 179.8))
        assertEquals(180.0, Math.abs(resolved(11_000).longitude), 1e-9)
    }

    @Test fun interpolationWrapsBearingTheShortWayRound() {
        resolver.add(fix(0, bearing = 350.0)); resolver.add(fix(2_000, bearing = 10.0))
        assertEquals(0.0, resolved(1_000).bearingDegrees!!, 1e-9)
        resolver.add(fix(10_000, bearing = 10.0)); resolver.add(fix(12_000, bearing = 350.0))
        assertEquals(0.0, resolved(11_000).bearingDegrees!!, 1e-9)
        resolver.add(fix(20_000, bearing = 350.0)); resolver.add(fix(24_000, bearing = 30.0))
        assertEquals(10.0, resolved(22_000).bearingDegrees!!, 1e-9) // 350 -> 30 is +40, half of it
    }

    @Test fun fixesFiveSecondsOrMoreApartAreNotInterpolated() {
        resolver.add(fix(0, lat = 46.0)); resolver.add(fix(5_000, lat = 47.0))
        val near = resolved(1_000)
        assertFalse(near.interpolated); assertEquals(46.0, near.latitude); assertEquals(1_000L, near.fixAgeMs)
        val later = resolved(4_000)
        assertFalse(later.interpolated); assertEquals(47.0, later.latitude); assertEquals(1_000L, later.fixAgeMs)
    }

    @Test fun justUnderFiveSecondsIsStillInterpolated() {
        resolver.add(fix(0, lat = 46.0)); resolver.add(fix(4_999, lat = 47.0))
        assertTrue(resolved(2_000).interpolated)
    }

    @Test fun aFixAtTheExactRxInstantIsUsedAsIs() {
        resolver.add(fix(1_000, lat = 46.0)); resolver.add(fix(2_000, lat = 47.0)); resolver.add(fix(3_000, lat = 48.0))
        val at = resolved(2_000)
        assertEquals(47.0, at.latitude); assertEquals(0L, at.fixAgeMs); assertFalse(at.interpolated)
    }

    @Test fun onlyAFixBeforeUsesItWithItsAge() {
        resolver.add(fix(1_000, lat = 46.5, provider = FixProvider.FUSED))
        val at = resolved(9_000)
        assertEquals(46.5, at.latitude); assertEquals(8_000L, at.fixAgeMs); assertEquals("fused", at.provider)
        assertFalse(at.interpolated)
    }

    @Test fun onlyAFixAfterIsUsedToo() {
        resolver.add(fix(10_000, lat = 46.5))
        assertEquals(3_000L, resolved(7_000).fixAgeMs)
    }

    @Test fun aFixOlderThanThirtySecondsGivesNoPosition() {
        resolver.add(fix(0))
        assertNotNull(resolved(30_000))
        assertIs<PositionResult.NoFix>(resolver.resolve(30_001, 0))
    }

    @Test fun noFixReportsTheLastGoodFixAndItsAge() {
        resolver.add(fix(0, lat = 46.9))
        val result = assertIs<PositionResult.NoFix>(resolver.resolve(400_000, 0))
        assertEquals(46.9, result.lastGood!!.latitude); assertEquals(400_000L, result.lastGoodAgeMs)
        // The 2 minute buffer has long forgotten it, but the last good fix is kept for the 10 minute rule.
        assertTrue(resolver.buffer.snapshot().size <= 1)
    }

    @Test fun noFixAtAllHasNoLastGood() {
        val result = assertIs<PositionResult.NoFix>(resolver.resolve(1_000, 0))
        assertNull(result.lastGood); assertNull(result.lastGoodAgeMs)
    }

    @Test fun nearestChoosesTheCloserNeighbour() {
        resolver.add(fix(0, lat = 46.0)); resolver.add(fix(20_000, lat = 47.0))
        assertEquals(46.0, resolved(9_000).latitude)
        assertEquals(47.0, resolved(11_000).latitude)
    }
}

class SamplingRatePolicyTest {
    private fun still(vararg times: Long) = times.map { fix(it, speed = 0.2) }

    @Test fun movingSamplesEverySecond() {
        assertEquals(1_000L, SamplingRatePolicy.intervalMs(listOf(fix(0, speed = 5.0), fix(1_000, speed = 5.0)), 1_000))
    }

    @Test fun stillForThirtySecondsSamplesEveryTenSeconds() {
        assertEquals(10_000L, SamplingRatePolicy.intervalMs(still(0, 10_000, 20_000, 30_000), 30_000))
    }

    @Test fun lessThanThirtySecondsOfStillnessStaysAtOneSecond() {
        assertEquals(1_000L, SamplingRatePolicy.intervalMs(still(0, 1_000, 2_000, 29_000), 29_000))
    }

    @Test fun anyFixOverOneMetreAPerSecondInTheWindowKeepsItMoving() {
        val fixes = still(0, 10_000, 20_000) + fix(25_000, speed = 1.0) + still(30_000)
        assertEquals(1_000L, SamplingRatePolicy.intervalMs(fixes, 30_000))
    }

    @Test fun speedJustUnderOneIsStill() {
        val fixes = listOf(fix(0, speed = 0.99), fix(15_000, speed = 0.99), fix(30_000, speed = 0.99))
        assertEquals(10_000L, SamplingRatePolicy.intervalMs(fixes, 30_000))
    }

    @Test fun unknownSpeedIsTreatedAsMovingAndNoFixesIsMoving() {
        assertEquals(1_000L, SamplingRatePolicy.intervalMs(listOf(fix(0), fix(30_000)), 30_000))
        assertEquals(1_000L, SamplingRatePolicy.intervalMs(emptyList(), 30_000))
    }

    @Test fun aFastFixBeforeTheWindowDoesNotCountButTheAnchorFixDoes() {
        // Anchor (last fix at or before now-30s) is still; the older fast fix is outside the window.
        val fixes = listOf(fix(0, speed = 9.0), fix(5_000, speed = 0.1), fix(20_000, speed = 0.1), fix(35_000, speed = 0.1))
        assertEquals(10_000L, SamplingRatePolicy.intervalMs(fixes, 35_000))
    }
}
