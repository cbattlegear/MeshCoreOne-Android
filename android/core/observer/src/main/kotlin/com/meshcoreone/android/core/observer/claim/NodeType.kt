// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.claim

/** How the observer node is used. Stored on Prizm, never written to the node. */
enum class NodeType(val wireName: String, val displayName: String) {
    HANDHELD("handheld", "Handheld"),
    MOBILE("mobile", "Mobile"),
    VEHICLE("vehicle", "Vehicle"),
    HOME_BASE("home_base", "Home base"),
    PORTABLE("portable", "Portable"),
}
