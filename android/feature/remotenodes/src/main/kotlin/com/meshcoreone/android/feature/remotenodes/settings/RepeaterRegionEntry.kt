// PortedFrom: MC1/Views/RemoteNodes/Repeaters/RepeaterRegionEntry.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.settings

/** One row of a repeater's region tree as dumped by the `region` CLI command. */
data class RepeaterRegionEntry(
    val name: String,
    /** Null for the Unscoped root. Children of Unscoped store [UNSCOPED_NAME], not null. */
    val parentName: String?,
    val depth: Int,
    val floodAllowed: Boolean,
    val isHome: Boolean,
) {
    val id: String get() = name
    val isUnscoped: Boolean get() = name == UNSCOPED_NAME

    /** The immediate named parent; null for the root and for children of Unscoped. */
    val namedParent: String? get() = parentName?.takeIf { it != UNSCOPED_NAME }

    /** Where a new region is attached. */
    sealed interface Parent {
        val id: String

        data object Unscoped : Parent {
            override val id: String get() = UNSCOPED_NAME
        }

        data class Named(val name: String) : Parent {
            override val id: String get() = name
        }
    }

    companion object {
        const val UNSCOPED_NAME = "*"
    }
}
