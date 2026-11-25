// PortedFrom: MC1Services/Sources/MC1Services/Services/MessageDTO+ReactionVisibility.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus

/**
 * A sent outgoing reaction renders as a badge, so the timeline hides the row. Failed reactions stay visible
 * so the user can retry them. Only the PocketMesh wire formats count (DM format for DMs, channel format for
 * channels); meshcore-open reactions are receive-only and never outgoing.
 */
fun MessageDTO.isHiddenOutgoingReaction(isDM: Boolean): Boolean {
    if (direction != MessageDirection.OUTGOING) return false
    val isReaction = if (isDM) ReactionParser.parseDM(text) != null else ReactionParser.parse(text) != null
    if (!isReaction) return false
    return status != MessageStatus.FAILED
}
