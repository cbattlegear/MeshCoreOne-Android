// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/GroupingFlags.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

/** Grouping signal — first-in-cluster, show timestamp, show divider. */
data class GroupingFlags(
    val showTimestamp: Boolean,
    val showDirectionGap: Boolean,
    val showSenderName: Boolean,
    val showNewMessagesDivider: Boolean,
    /** True for the first message of a new calendar day; drives the day separator. */
    val showDayDivider: Boolean = false,
)
