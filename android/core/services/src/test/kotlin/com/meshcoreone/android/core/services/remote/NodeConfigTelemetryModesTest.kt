// PortedFrom: MC1Services/Sources/MC1Services/Services/TelemetryModes.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.model.TelemetryModes
import kotlin.test.*
import org.junit.jupiter.api.TestFactory

/**
 * TelemetryModes.swift (WP-210-owned) is already ported as `core.model.TelemetryModes` and is reused
 * by `NodeConfigService.importOtherParams` rather than redefined; these cases pin its Swift encoding.
 * The Swift sources have no dedicated TelemetryModes test file, so these are native cases.
 */
class NodeConfigTelemetryModesTest {
    @TestFactory
    fun nativeCases() = nodeConfigNativeCases(
        "TelemetryModes packs environment<<4 | location<<2 | base" to {
            assertEquals(0b00_11_10_01.toUByte(), TelemetryModes.of(base = 1u, location = 2u, environment = 3u).packed)
            assertEquals(0.toUByte(), TelemetryModes.of().packed)
        },
        "TelemetryModes masks each mode to two bits like the Swift initializer" to {
            val modes = TelemetryModes.of(base = 7u, location = 6u, environment = 5u)
            assertEquals(Triple<UByte, UByte, UByte>(3u, 2u, 1u), Triple(modes.base, modes.location, modes.environment))
        },
        "TelemetryModes(packed:) unpacks every two-bit field and round-trips" to {
            for (packed in 0..0x3F) {
                val modes = TelemetryModes.fromPacked(packed.toUByte())
                assertEquals(packed.toUByte(), modes.packed)
                assertEquals((packed and 3).toUByte(), modes.base)
                assertEquals(((packed shr 2) and 3).toUByte(), modes.location)
                assertEquals(((packed shr 4) and 3).toUByte(), modes.environment)
            }
            assertEquals(TelemetryModes.fromPacked(0x3Fu), TelemetryModes.fromPacked(0xFFu))
        },
    )
}
