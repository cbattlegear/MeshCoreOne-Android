// PortedFrom: MC1Services/Sources/MC1Services/RF/ClearanceStatus.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.diagnostics.rf

/** Clearance status at worst point along path. Declaration order is severity order (best to worst). */
enum class ClearanceStatus(val rawValue: String) {
    CLEAR("Clear"),
    MARGINAL("Marginal"),
    PARTIAL_OBSTRUCTION("Partial obstruction"),
    BLOCKED("Blocked"),
    ;

    companion object {
        /** Mirrors Swift `ClearanceStatus(rawValue:)`; returns null for unknown raw values. */
        fun fromRawValue(rawValue: String): ClearanceStatus? = entries.firstOrNull { it.rawValue == rawValue }
    }
}
