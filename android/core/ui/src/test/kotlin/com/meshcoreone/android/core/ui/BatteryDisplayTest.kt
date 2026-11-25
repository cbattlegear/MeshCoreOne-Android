// PortedFrom: MC1Tests/Extensions/BatteryInfoDisplayTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Extensions/BatteryPercentageCalculationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ui

import com.meshcoreone.android.core.protocol.model.BatteryInfo
import kotlin.test.*
import org.junit.Test

class BatteryDisplayTest : SourceCaseProof() {
    private val ocv = listOf(4190L, 4050L, 3990L, 3890L, 3800L, 3720L, 3630L, 3530L, 3420L, 3300L, 3100L)

    @OriginalCase("BatteryInfoDisplayTests::voltage converts millivolts correctly()")
    @Test fun voltage() = prove { assertEquals(3.7, BatteryInfo(3700).voltage) }
    @OriginalCase("BatteryInfoDisplayTests::voltage zero millivolts()")
    @Test fun zeroVoltage() = prove { assertEquals(0.0, BatteryInfo(0).voltage) }
    @OriginalCase("BatteryInfoDisplayTests::percentage full battery()")
    @Test fun full() = prove { assertEquals(100, BatteryInfo(4200).percentage) }
    @OriginalCase("BatteryInfoDisplayTests::percentage empty battery()")
    @Test fun empty() = prove { assertEquals(0, BatteryInfo(3000).percentage) }
    @OriginalCase("BatteryInfoDisplayTests::percentage mid range()")
    @Test fun middle() = prove { assertEquals(50, BatteryInfo(3600).percentage) }
    @OriginalCase("BatteryInfoDisplayTests::percentage clamps above 100()")
    @Test fun above() = prove { assertEquals(100, BatteryInfo(4500).percentage) }
    @OriginalCase("BatteryInfoDisplayTests::percentage clamps below 0()")
    @Test fun below() = prove { assertEquals(0, BatteryInfo(2500).percentage) }
    @OriginalCase("BatteryInfoDisplayTests::icon name full battery()")
    @Test fun fullIcon() = prove { assertEquals("battery.100", BatteryInfo(4200).sourceIconName) }
    @OriginalCase("BatteryInfoDisplayTests::icon name 75 percent()")
    @Test fun seventyFiveIcon() = prove { assertEquals("battery.75", BatteryInfo(3900).sourceIconName) }
    @OriginalCase("BatteryInfoDisplayTests::icon name 50 percent()")
    @Test fun fiftyIcon() = prove { assertEquals("battery.50", BatteryInfo(3600).sourceIconName) }
    @OriginalCase("BatteryInfoDisplayTests::icon name 25 percent()")
    @Test fun twentyFiveIcon() = prove { assertEquals("battery.25", BatteryInfo(3300).sourceIconName) }
    @OriginalCase("BatteryInfoDisplayTests::icon name low battery()")
    @Test fun lowIcon() = prove { assertEquals("battery.0", BatteryInfo(3100).sourceIconName) }
    @OriginalCase("BatteryInfoDisplayTests::level color normal level()")
    @Test fun normalRole() = prove { assertEquals(BatteryLevelRole.NORMAL, BatteryInfo(3600).levelRole) }
    @OriginalCase("BatteryInfoDisplayTests::level color warning level()")
    @Test fun warningRole() = prove { assertEquals(BatteryLevelRole.WARNING, BatteryInfo(3180).levelRole) }
    @OriginalCase("BatteryInfoDisplayTests::level color critical level()")
    @Test fun criticalRole() = prove { assertEquals(BatteryLevelRole.CRITICAL, BatteryInfo(3060).levelRole) }
    @OriginalCase("BatteryInfoDisplayTests::is battery present zero millivolts returns false()")
    @Test fun noBattery() = prove { assertFalse(BatteryInfo(0).isBatteryPresent) }
    @OriginalCase("BatteryInfoDisplayTests::is battery present normal voltage returns true()")
    @Test fun presentBattery() = prove { assertTrue(BatteryInfo(3700).isBatteryPresent) }
    @OriginalCase("BatteryInfoDisplayTests::is battery present minimum valid voltage returns true()")
    @Test fun minimumBattery() = prove { assertTrue(BatteryInfo(1).isBatteryPresent) }
    @OriginalCase("BatteryPercentageCalculationTests::Voltage at 100% point returns 100()")
    @Test fun ocvFull() = prove { assertEquals(100, BatteryInfo(4190).percentage(ocv)) }
    @OriginalCase("BatteryPercentageCalculationTests::Voltage at 0% point returns 0()")
    @Test fun ocvEmpty() = prove { assertEquals(0, BatteryInfo(3100).percentage(ocv)) }
    @OriginalCase("BatteryPercentageCalculationTests::Voltage above max returns 100()")
    @Test fun ocvAbove() = prove { assertEquals(100, BatteryInfo(4500).percentage(ocv)) }
    @OriginalCase("BatteryPercentageCalculationTests::Voltage below min returns 0()")
    @Test fun ocvBelow() = prove { assertEquals(0, BatteryInfo(2800).percentage(ocv)) }
    @OriginalCase("BatteryPercentageCalculationTests::Voltage at 50% point returns 50()")
    @Test fun ocvMiddle() = prove { assertEquals(50, BatteryInfo(3720).percentage(ocv)) }
    @OriginalCase("BatteryPercentageCalculationTests::Voltage interpolates between points()")
    @Test fun ocvInterpolation() = prove { assertTrue(BatteryInfo(4120).percentage(ocv) in 94..96) }

    @Test fun allSourceIconAndWarningBoundaryPointsRemainExact() {
        for ((percentage, name) in listOf(12 to "battery.0", 13 to "battery.25", 37 to "battery.25",
            38 to "battery.50", 62 to "battery.50", 63 to "battery.75", 87 to "battery.75", 88 to "battery.100")) {
            assertEquals(name, batterySourceIconName(percentage))
        }
        assertEquals(BatteryLevelRole.CRITICAL, batteryLevelRole(9))
        assertEquals(BatteryLevelRole.WARNING, batteryLevelRole(10))
        assertEquals(BatteryLevelRole.WARNING, batteryLevelRole(19))
        assertEquals(BatteryLevelRole.NORMAL, batteryLevelRole(20))
        assertEquals(BatteryInfo(3600).percentage, BatteryInfo(3600).percentage(listOf(1)))
    }
}
