// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.claim

/** How the observer node is used (server values: handheld, vehicle, home, portable). Stored on Prizm, never written to the node. */
enum class NodeType(val wireName: String, val displayName: String) {
    HANDHELD("handheld", "Handheld"),
    VEHICLE("vehicle", "Vehicle"),
    HOME_BASE("home", "Home base"),
    PORTABLE("portable", "Portable"),
}
