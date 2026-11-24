// PortedFrom: MC1Services/Sources/MC1Services/Services/ReactionParser.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.model

import java.text.Normalizer
import java.time.Instant

fun buildReactionSummary(reactions: Collection<ReactionDTO>): String {
    val groups = reactions.groupBy { Normalizer.normalize(it.emoji, Normalizer.Form.NFC) }.values
    return groups
        .map { SummaryGroup(it.first().emoji, it.size, it.minOf { reaction -> reaction.receivedAt }) }
        .sortedWith(compareByDescending<SummaryGroup> { it.count }.thenBy { it.earliest })
        .joinToString(",") { "${it.emoji}:${it.count}" }
}

private data class SummaryGroup(val emoji: String, val count: Int, val earliest: Instant)
