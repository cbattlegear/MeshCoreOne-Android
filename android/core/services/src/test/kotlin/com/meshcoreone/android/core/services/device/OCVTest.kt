// PortedFrom: MC1Tests/Models/OCVPresetTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Models/ContactOCVTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Uses the actual WP-201 model producer; does not copy its OCV implementation or claim its primary ownership.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.OCVPreset
import com.meshcoreone.android.core.model.OCVPresetCategory
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.TestFactory

class OCVTest {
    @TestFactory
    fun parameterFamilies() = OCVPreset.entries.filter { it != OCVPreset.CUSTOM }.flatMap { preset ->
        listOf(
            original("OCVPresetTests", "All presets have exactly 11 values", "(preset : OCVPreset) [${preset.rawValue}]") {
                assertEquals(11, preset.ocvArray.size)
            },
            original("OCVPresetTests", "All preset arrays are descending", "(preset : OCVPreset) [${preset.rawValue}]") {
                for (index in 0 until preset.ocvArray.lastIndex) {
                    assertTrue(preset.ocvArray[index] > preset.ocvArray[index + 1])
                }
            },
            original("OCVPresetTests", "All presets fit within UI validation range", "(preset : OCVPreset) [${preset.rawValue}]") {
                for (value in preset.ocvArray) assertTrue(value in OCVPreset.validMillivoltRange)
            },
        )
    } + OCVPreset.entries.map { preset ->
        original("OCVPresetTests", "All presets have display names", "(preset : OCVPreset) [${preset.rawValue}]") {
            assertTrue(preset.displayName.isNotEmpty())
        }
    }

    @TestFactory
    fun sourceCases() = listOf(
        original("OCVPresetTests", "Selectable presets excludes custom") {
            assertFalse(OCVPreset.CUSTOM in OCVPreset.selectablePresets)
            assertEquals(OCVPreset.entries.size - 1, OCVPreset.selectablePresets.size)
        },
        original("OCVPresetTests", "Li-Ion preset has expected values") {
            assertEquals(listOf(4190L, 4050L, 3990L, 3890L, 3800L, 3720L, 3630L, 3530L, 3420L, 3300L, 3100L),
                OCVPreset.LI_ION.ocvArray)
        },
        original("OCVPresetTests", "WisMesh Tag preset has expected values") {
            assertEquals(listOf(4160L, 4020L, 3940L, 3870L, 3810L, 3760L, 3740L, 3720L, 3680L, 3620L, 2990L),
                OCVPreset.WISMESH_TAG.ocvArray)
        },
        original("OCVPresetTests", "LilyGo T-Beam 1W preset has expected values") {
            assertEquals(listOf(7950L, 7850L, 7750L, 7580L, 7440L, 7310L, 7150L, 7005L, 6860L, 6685L, 6000L),
                OCVPreset.LILYGO_TBEAM_1W.ocvArray)
        },
        original("OCVPresetTests", "ThinkNode M6 preset has expected values") {
            assertEquals(listOf(4080L, 3990L, 3935L, 3880L, 3825L, 3770L, 3715L, 3660L, 3605L, 3550L, 3450L),
                OCVPreset.THINK_NODE_M6.ocvArray)
        },
        original("OCVPresetTests", "Battery chemistry presets include only chemistry types") {
            assertEquals(setOf(OCVPreset.LI_ION, OCVPreset.LI_FE_PO4, OCVPreset.LEAD_ACID, OCVPreset.ALKALINE,
                OCVPreset.NI_MH, OCVPreset.LTO), OCVPreset.batteryChemistryPresets.toSet())
            assertEquals(6, OCVPreset.batteryChemistryPresets.size)
        },
        original("OCVPresetTests", "Battery chemistry presets exclude device-specific presets") {
            for (preset in listOf(OCVPreset.TRACKER_T1000_E, OCVPreset.HELTEC_POCKET_5000, OCVPreset.CUSTOM)) {
                assertFalse(preset in OCVPreset.batteryChemistryPresets)
            }
        },
        original("OCVPresetTests", "Li-Ion is battery chemistry category") {
            assertEquals(OCVPresetCategory.BATTERY_CHEMISTRY, OCVPreset.LI_ION.category)
        },
        original("OCVPresetTests", "Tracker T1000-E is device specific category") {
            assertEquals(OCVPresetCategory.DEVICE_SPECIFIC, OCVPreset.TRACKER_T1000_E.category)
        },
        original("OCVPresetTests", "Custom is device specific category") {
            assertEquals(OCVPresetCategory.DEVICE_SPECIFIC, OCVPreset.CUSTOM.category)
        },
        original("OCVPresetTests", "Seeed Tracker T1000-e maps to trackerT1000E preset") {
            assertEquals(OCVPreset.TRACKER_T1000_E, OCVPreset.presetForManufacturer("Seeed Tracker T1000-e"))
        },
        original("OCVPresetTests", "Seeed Wio Tracker L1 maps to seeedWioTracker preset") {
            assertEquals(OCVPreset.SEEED_WIO_TRACKER, OCVPreset.presetForManufacturer("Seeed Wio Tracker L1"))
        },
        original("OCVPresetTests", "Seeed SenseCap Solar maps to seeedSolarNode preset") {
            assertEquals(OCVPreset.SEEED_SOLAR_NODE, OCVPreset.presetForManufacturer("Seeed SenseCap Solar"))
        },
        original("OCVPresetTests", "RAK WisMesh Tag maps to wisMeshTag preset") {
            assertEquals(OCVPreset.WISMESH_TAG, OCVPreset.presetForManufacturer("RAK WisMesh Tag"))
        },
        original("OCVPresetTests", "LilyGo T-Beam 1W maps to lilyGoTBeam1W preset") {
            assertEquals(OCVPreset.LILYGO_TBEAM_1W, OCVPreset.presetForManufacturer("LilyGo T-Beam 1W"))
        },
        original("OCVPresetTests", "Elecrow ThinkNode M6 maps to thinkNodeM6 preset") {
            assertEquals(OCVPreset.THINK_NODE_M6, OCVPreset.presetForManufacturer("Elecrow ThinkNode M6"))
        },
        original("OCVPresetTests", "Unknown manufacturer returns nil") {
            for (name in listOf("Generic ESP32", "Heltec MeshPocket", "")) assertNull(OCVPreset.presetForManufacturer(name))
        },
        original("OCVPresetTests", "Manufacturer matching is case-sensitive") {
            assertNull(OCVPreset.presetForManufacturer("seeed tracker t1000-e"))
            assertNull(OCVPreset.presetForManufacturer("SEEED TRACKER T1000-E"))
        },
        original("ContactOCVTests", "activeOCVArray returns Li-Ion by default") {
            assertEquals(OCVPreset.LI_ION.ocvArray, contact(null, null).activeOCVArray)
        },
        original("ContactOCVTests", "activeOCVArray returns preset array when set") {
            assertEquals(OCVPreset.LI_FE_PO4.ocvArray, contact(OCVPreset.LI_FE_PO4.rawValue, null).activeOCVArray)
        },
        original("ContactOCVTests", "activeOCVArray returns custom array when valid") {
            val values = listOf(4200L, 4100L, 4000L, 3900L, 3800L, 3700L, 3600L, 3500L, 3400L, 3300L, 3200L)
            assertEquals(values, contact(OCVPreset.CUSTOM.rawValue, values.joinToString(",")).activeOCVArray)
        },
        original("ContactOCVTests", "activeOCVArray falls back to Li-Ion for invalid custom array") {
            assertEquals(OCVPreset.LI_ION.ocvArray, contact(OCVPreset.CUSTOM.rawValue, "invalid").activeOCVArray)
        },
    )

    @TestFactory
    fun nativeCases() = listOf(
        nativeCase("OCV raw names manufacturer alias and multi-cell voltage bounds remain source-correct") {
            assertEquals("liIon", OCVPreset.LI_ION.rawValue)
            assertEquals("liFePO4", OCVPreset.LI_FE_PO4.rawValue)
            assertEquals(1000L..99999L, OCVPreset.validMillivoltRange)
            assertEquals(OCVPreset.TRACKER_T1000_E, OCVPreset.presetForManufacturer("Seeed Tracker T1000-E"))
            assertEquals(OCVPreset.LI_ION.ocvArray, OCVPreset.CUSTOM.ocvArray)
            assertEquals(OCVPreset.SEEED_SOLAR_NODE, OCVPreset.nodePresets.last())
        },
        nativeCase("OCV custom arrays preserve source compact parsing and genuine invalid fallback") {
            assertEquals(OCVPreset.LI_ION.ocvArray, contact("unknownPreset", null).activeOCVArray)
            assertEquals(OCVPreset.LI_ION.ocvArray, contact("custom", "1,2,3").activeOCVArray)
            assertEquals((1L..11L).toList(), contact("custom", " 1,2,3,4,5,6,7,8,9,10,11 ").activeOCVArray)
            assertEquals((1L..11L).toList(), contact("custom", "1,2,3,4,5,6,7,8,9,10,invalid,11").activeOCVArray)
        },
    )

    private fun contact(preset: String?, custom: String?) = ContactDTO(
        UUID.fromString("00000000-0000-0000-0000-000000000011"),
        RadioId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
        Bytes(ByteArray(32) { 0x42 }), "Test", typeRawValue = ContactType.REPEATER.rawValue,
        lastHeardTimestamp = null, ocvPreset = preset, customOCVArrayString = custom,
    )
}
