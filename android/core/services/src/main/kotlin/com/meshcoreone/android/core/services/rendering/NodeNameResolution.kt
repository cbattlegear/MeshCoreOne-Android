// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/NodeNameResolution.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

/** Whether a name resolution is an exact public-key match, a proximity fallback, or unresolved. */
enum class NodeNameMatchKind { EXACT, FALLBACK, UNRESOLVED }

/** Resolved sender display name paired with the confidence level of the match. */
data class NodeNameResolution(
    val displayName: String,
    val matchKind: NodeNameMatchKind,
    /** Nickname for a channel sender matched by name only (identity unverified); nil otherwise. */
    val unverifiedNickname: String? = null,
) {
    val isFallback: Boolean get() = matchKind == NodeNameMatchKind.FALLBACK
}
