// PortedFrom: MC1Services/Tests/MC1ServicesTests/RegionSelectionTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/RepeatPresetTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.model.RegionSelection
import kotlin.test.*
import org.junit.jupiter.api.TestFactory

class RegionSelectionRepeatTest {
    @TestFactory
    fun sourceCases() = listOf(
        original("RegionSelectionTests", "afterChoosingCountry skips a re-tap and otherwise writes a manual country") {
            val current = RegionSelection("US", RegionSelection.Source.LOCATION, "US-CA", "los angeles")
            assertNull(RegionSelection.afterChoosingCountry("US", current))
            assertEquals(RegionSelection("PT", RegionSelection.Source.MANUAL), RegionSelection.afterChoosingCountry("PT", current))
            assertEquals(RegionSelection("US", RegionSelection.Source.MANUAL), RegionSelection.afterChoosingCountry("US", null))
        },
        original("RegionSelectionTests", "afterChoosingSubdivision skips a re-tap, drops county on change, and needs a country") {
            val current = RegionSelection("US", RegionSelection.Source.LOCATION, "US-CA", "los angeles")
            assertNull(RegionSelection.afterChoosingSubdivision("US-CA", current))
            assertEquals(
                RegionSelection("US", RegionSelection.Source.MANUAL, "US-TX"),
                RegionSelection.afterChoosingSubdivision("US-TX", current),
            )
            assertNull(RegionSelection.afterChoosingSubdivision("US-CA", null))
        },
        original("RepeatPresetTests", "repeat preset frequencies match the firmware's allowed set exactly") {
            val expected = mapOf("repeat-433" to 433_000u, "repeat-869" to 869_495u, "repeat-918" to 918_000u)
            assertEquals(expected.size, RadioPresets.repeatPresets.size)
            for (preset in RadioPresets.repeatPresets) {
                assertTrue(preset.id in expected)
                assertEquals(expected.getValue(preset.id), preset.frequencyKHz)
            }
        },
        original("RepeatPresetTests", "matchingRepeatPreset resolves by frequency only") {
            assertEquals("repeat-869", RadioPresets.matchingRepeatPreset(869_495u)?.id)
            assertEquals("repeat-433", RadioPresets.matchingRepeatPreset(433_000u)?.id)
            assertEquals("repeat-918", RadioPresets.matchingRepeatPreset(918_000u)?.id)
        },
        original("RepeatPresetTests", "matchingRepeatPreset returns nil for a non-repeat frequency") {
            assertNull(RadioPresets.matchingRepeatPreset(869_000u))
            assertNull(RadioPresets.matchingRepeatPreset(915_000u))
        },
        original("RepeatPresetTests", "nearestRepeatPreset snaps an off-band frequency to the closest allowed one") {
            for (frequency in listOf(869_000u, 868_000u)) {
                assertEquals("repeat-869", RadioPresets.nearestRepeatPreset(frequency)?.id)
            }
            assertEquals("repeat-918", RadioPresets.nearestRepeatPreset(915_000u)?.id)
            assertEquals("repeat-433", RadioPresets.nearestRepeatPreset(500_000u)?.id)
        },
        original("RepeatPresetTests", "nearestRepeatPreset returns an already-valid frequency unchanged") {
            assertEquals("repeat-869", RadioPresets.nearestRepeatPreset(869_495u)?.id)
            assertEquals("repeat-918", RadioPresets.nearestRepeatPreset(918_000u)?.id)
        },
    )

    @TestFactory
    fun nativeCases() = listOf(
        nativeCase("region selection uses original persisted raw values") {
            assertEquals("location", RegionSelection.Source.LOCATION.rawValue)
            assertEquals("manual", RegionSelection.Source.MANUAL.rawValue)
        },
        nativeCase("repeat grouping exact frequency eligibility and unsigned extremes remain distinct from RF tuples") {
            assertEquals(listOf("EU/Asia", "EU", "US/AU/NZ"), RadioPresets.repeatPresets.map { it.repeatSectionHeader })
            assertEquals("repeat-433", RadioPresets.nearestRepeatPreset(0u)?.id)
            assertEquals("repeat-918", RadioPresets.nearestRepeatPreset(UInt.MAX_VALUE)?.id)
            assertNull(RadioPresets.matchingRepeatPreset(869_494u))
            assertNull(RadioPresets.matchingRepeatPreset(869_496u))
            assertEquals("repeat-433", RadioPresets.nearestRepeatPreset(651_247u)?.id)
            assertEquals("repeat-869", RadioPresets.nearestRepeatPreset(651_248u)?.id)
        },
    )
}
