// PortedFrom: MC1Services/Sources/MC1Services/Models/OCVPreset.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.model

enum class OCVPresetCategory { BATTERY_CHEMISTRY, DEVICE_SPECIFIC }

enum class OCVPreset(
    val rawValue: String,
    val displayName: String,
    private val millivolts: List<Long>,
    val category: OCVPresetCategory = OCVPresetCategory.DEVICE_SPECIFIC,
) {
    LI_ION("liIon", "Li-Ion (Default)", listOf(4190, 4050, 3990, 3890, 3800, 3720, 3630, 3530, 3420, 3300, 3100), OCVPresetCategory.BATTERY_CHEMISTRY),
    LI_FE_PO4("liFePO4", "LiFePO4", listOf(3400, 3350, 3320, 3290, 3270, 3260, 3250, 3230, 3200, 3120, 3000), OCVPresetCategory.BATTERY_CHEMISTRY),
    LEAD_ACID("leadAcid", "Lead Acid", listOf(2120, 2090, 2070, 2050, 2030, 2010, 1990, 1980, 1970, 1960, 1950), OCVPresetCategory.BATTERY_CHEMISTRY),
    ALKALINE("alkaline", "Alkaline", listOf(1580, 1400, 1350, 1300, 1280, 1250, 1230, 1190, 1150, 1100, 1000), OCVPresetCategory.BATTERY_CHEMISTRY),
    NI_MH("niMH", "NiMH", listOf(1400, 1300, 1280, 1270, 1260, 1250, 1240, 1230, 1210, 1150, 1000), OCVPresetCategory.BATTERY_CHEMISTRY),
    LTO("lto", "LTO", listOf(2770, 2650, 2540, 2420, 2300, 2180, 2060, 1940, 1800, 1680, 1550), OCVPresetCategory.BATTERY_CHEMISTRY),
    TRACKER_T1000_E("trackerT1000E", "Tracker T1000-E", listOf(4190, 4042, 3957, 3885, 3820, 3776, 3746, 3725, 3696, 3644, 3100)),
    HELTEC_POCKET_5000("heltecPocket5000", "Heltec Pocket 5000", listOf(4300, 4240, 4120, 4000, 3888, 3800, 3740, 3698, 3655, 3580, 3400)),
    HELTEC_POCKET_10000("heltecPocket10000", "Heltec Pocket 10000", listOf(4100, 4060, 3960, 3840, 3729, 3625, 3550, 3500, 3420, 3345, 3100)),
    SEEED_WIO_TRACKER("seeedWioTracker", "Seeed WIO Tracker", listOf(4200, 3876, 3826, 3763, 3713, 3660, 3573, 3485, 3422, 3359, 3300)),
    SEEED_SOLAR_NODE("seeedSolarNode", "Seeed Solar Node", listOf(4200, 3986, 3922, 3812, 3734, 3645, 3527, 3420, 3281, 3087, 2786)),
    R1_NEO("r1Neo", "R1 Neo", listOf(4120, 4020, 4000, 3940, 3870, 3820, 3750, 3630, 3550, 3450, 3100)),
    WISMESH_TAG("wisMeshTag", "WisMesh Tag", listOf(4160, 4020, 3940, 3870, 3810, 3760, 3740, 3720, 3680, 3620, 2990)),
    LILYGO_TBEAM_1W("lilyGoTBeam1W", "LilyGo T-Beam 1W", listOf(7950, 7850, 7750, 7580, 7440, 7310, 7150, 7005, 6860, 6685, 6000)),
    THINK_NODE_M6("thinkNodeM6", "ThinkNode M6", listOf(4080, 3990, 3935, 3880, 3825, 3770, 3715, 3660, 3605, 3550, 3450)),
    CUSTOM("custom", "Custom", emptyList());

    val ocvArray: SnapshotList<Long> get() = if (this == CUSTOM) LI_ION.ocvArray else millivolts.snapshot()

    companion object {
        val validMillivoltRange: LongRange = 1000L..99999L
        val selectablePresets: SnapshotList<OCVPreset> get() = entries.filter { it != CUSTOM }.snapshot()
        val batteryChemistryPresets: SnapshotList<OCVPreset>
            get() = entries.filter { it.category == OCVPresetCategory.BATTERY_CHEMISTRY }.snapshot()
        val nodePresets: SnapshotList<OCVPreset> get() = (batteryChemistryPresets + SEEED_SOLAR_NODE).snapshot()
        fun fromRawValue(value: String): OCVPreset? = entries.firstOrNull { it.rawValue == value }
        fun presetForManufacturer(name: String): OCVPreset? = when (name) {
            "Seeed Tracker T1000-e", "Seeed Tracker T1000-E" -> TRACKER_T1000_E
            "Seeed Wio Tracker L1" -> SEEED_WIO_TRACKER
            "Seeed SenseCap Solar" -> SEEED_SOLAR_NODE
            "RAK WisMesh Tag" -> WISMESH_TAG
            "LilyGo T-Beam 1W" -> LILYGO_TBEAM_1W
            "Elecrow ThinkNode M6" -> THINK_NODE_M6
            else -> null
        }
    }
}

internal fun activeOCVArray(presetName: String?, customString: String?): SnapshotList<Long> {
    if (presetName == OCVPreset.CUSTOM.rawValue && customString != null) {
        val parsed = customString.split(',').filter { it.isNotEmpty() }
            .mapNotNull { it.trim { char -> char.isWhitespace() && char != '\n' && char != '\r' }.toLongOrNull() }
        if (parsed.size == 11) return parsed.snapshot()
    }
    return presetName?.let(OCVPreset::fromRawValue)?.ocvArray ?: OCVPreset.LI_ION.ocvArray
}
