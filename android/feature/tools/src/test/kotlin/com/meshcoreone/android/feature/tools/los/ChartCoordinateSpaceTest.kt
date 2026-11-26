// PortedFrom: MC1Tests/Views/ChartCoordinateSpaceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.los

import java.util.Locale
import kotlin.test.assertEquals
import org.junit.Test

class ChartCoordinateSpaceTest {
    private val space = ChartCoordinateSpace(
        canvasWidth = 400.0,
        canvasHeight = 200.0,
        padding = ChartInsets(top = 20.0, leading = 40.0, bottom = 30.0, trailing = 50.0),
        xRange = 0.0..10000.0,
        yRange = 0.0..500.0,
    )

    @Test @OriginalCase("ChartCoordinateSpaceTests::xPixel returns leading padding at xRange.lowerBound()")
    fun `xPixel returns leading padding at xRange lowerBound`() {
        assertEquals(40.0, space.xPixel(0.0))
    }

    @Test @OriginalCase("ChartCoordinateSpaceTests::xPixel returns width minus trailing padding at xRange.upperBound()")
    fun `xPixel returns width minus trailing padding at xRange upperBound`() {
        assertEquals(350.0, space.xPixel(10000.0))
    }

    @Test @OriginalCase("ChartCoordinateSpaceTests::yPixel returns height minus bottom padding at yRange.lowerBound (inverted)()")
    fun `yPixel returns height minus bottom padding at yRange lowerBound inverted`() {
        assertEquals(170.0, space.yPixel(0.0))
    }

    @Test @OriginalCase("ChartCoordinateSpaceTests::yPixel returns top padding at yRange.upperBound (inverted)()")
    fun `yPixel returns top padding at yRange upperBound inverted`() {
        assertEquals(20.0, space.yPixel(500.0))
    }

    @Test @OriginalCase("ChartCoordinateSpaceTests::point combines xPixel and yPixel()")
    fun `point combines xPixel and yPixel`() {
        val point = space.point(x = 5000.0, y = 250.0)
        assertEquals(195.0, point.x)
        assertEquals(95.0, point.y)
        // Oracle space.x3333.3 / space.y123.456: non-trivial fractions keep Swift operand order.
        assertBits("4061eaa2339c0ebf", space.xPixel(3333.3), "space.x3333.3")
        assertBits("40609ed288ce703b", space.yPixel(123.456), "space.y123.456")
    }

    @Test @OriginalCase(
        "ChartCoordinateSpaceTests::xLabel converts meters to km string()",
        disposition = "platform-adaptation",
    )
    fun `xLabel converts meters to km string`() {
        assertEquals("0.0", space.xLabel(0.0, Locale.US))
        assertEquals("5.0", space.xLabel(5000.0, Locale.US))
        assertEquals("10.0", space.xLabel(10000.0, Locale.US))
        assertEquals("5,0", space.xLabel(5000.0, Locale.GERMANY))
    }

    /** Android-only: ICU half-even on the shortest decimal, grouping and signed zero (Swift oracle xLabel.*). */
    @Test
    fun `xLabel rounding grouping and sign match Foundation`() {
        val expectations = mapOf(
            250.0 to ("0.2" to "0,2"), 350.0 to ("0.4" to "0,4"), 150.0 to ("0.2" to "0,2"),
            1250.0 to ("1.2" to "1,2"), 2050.0 to ("2.0" to "2,0"), 999.95 to ("1.0" to "1,0"),
            1_234_567.0 to ("1,234.6" to "1.234,6"), 49.999 to ("0.0" to "0,0"), 50.0 to ("0.0" to "0,0"),
            0.04 to ("0.0" to "0,0"),
        )
        expectations.forEach { (meters, labels) ->
            assertEquals(labels.first, space.xLabel(meters, Locale.US), "en $meters")
            assertEquals(labels.second, space.xLabel(meters, Locale.GERMANY), "de $meters")
        }
        assertEquals("-0.0", space.xLabel(-40.0, Locale.US))
        assertEquals("-0.0", space.xLabel(-0.0, Locale.US))
    }
}
