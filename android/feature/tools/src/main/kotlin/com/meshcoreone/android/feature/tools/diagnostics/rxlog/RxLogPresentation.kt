// PortedFrom: MC1/Views/Tools/RxLogView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.diagnostics.rxlog

import com.meshcoreone.android.feature.tools.diagnostics.FormattedStringIds
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.protocol.parser.RegionMatchResult
import com.meshcoreone.android.core.model.RegionScopeSemantics
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.feature.tools.diagnostics.DiagnosticsText
import java.util.Locale

/** View-local list state from `RxLogView` (expanded rows and duplicate grouping), kept immutable. */
data class RxLogListState(
    val expandedHashes: Set<String> = emptySet(),
    val groupDuplicates: Boolean = false,
) {
    fun isExpanded(hash: String): Boolean = hash in expandedHashes

    fun settingExpanded(hash: String, expanded: Boolean): RxLogListState =
        copy(expandedHashes = if (expanded) expandedHashes + hash else expandedHashes - hash)

    fun togglingGroupDuplicates(): RxLogListState = copy(groupDuplicates = !groupDuplicates)

    /** Clearing the log also collapses every row. */
    fun cleared(): RxLogListState = copy(expandedHashes = emptySet())

    /** Filtered entries, keeping only the newest of each packet hash when grouping. */
    fun displayEntries(state: RxLogState): List<RxLogEntryDTO> {
        val filtered = state.filteredEntries
        return if (groupDuplicates) filtered.distinctBy { it.packetHash } else filtered
    }

    /** The `×N` badge count for a row (1 when not grouping). */
    fun groupCount(state: RxLogState, entry: RxLogEntryDTO): Int =
        if (groupDuplicates) state.groupCounts[entry.packetHash] ?: 1 else 1
}

/** A label/value line in an expanded row. */
data class RxLogDetailRow(val label: String, val value: String, val wrapping: Boolean = false, val truncate: Boolean = false)

/** Everything an RX log row renders, computed off the UI thread's view code. */
data class RxLogRowModel(
    val routeLabel: String,
    val isFlood: Boolean,
    val path: String,
    /** `"<from> → <to>"` for direct text messages. */
    val fromTo: String?,
    /** Quoted decoded text, or null when [packetSummary] is shown instead. */
    val messagePreview: String?,
    val packetSummary: String,
    val snr: String?,
    val groupBadge: String?,
    val groupAccessibility: String?,
    val details: List<RxLogDetailRow>,
    val decodedText: String?,
    val rawPayloadHex: String,
)

/**
 * Row and header text from `RxLogView`/`RxLogRowView`: path chunking, local-node and contact-name
 * resolution, trace routes, region coalescing and the raw-payload hex. Dates and signal bars stay
 * with the UI layer (locale skeleton formatting and SF-Symbol variable values).
 */
class RxLogPresentation(private val text: DiagnosticsText) {
    private companion object {
        const val ARROW = " → "
        const val MAX_UNTRUNCATED_HOPS = 6
        const val TRUNCATED_EDGE = 3
    }

    fun headerCount(state: RxLogState): String = text.format(FormattedStringIds.RX_LOG_PACKETS_COUNT, state.entries.size.toLong())

    fun liveLabel(isConnected: Boolean): String =
        text.string(if (isConnected) AppToolsStrings.toolsRxLogLive else AppToolsStrings.toolsRxLogOffline)

    fun headerAccessibility(isConnected: Boolean, state: RxLogState): String = "${liveLabel(isConnected)}, ${headerCount(state)}"

    fun row(entry: RxLogEntryDTO, groupCount: Int, localPublicKeyPrefix: Bytes?, nodeNames: Map<Bytes, String>): RxLogRowModel {
        val resolver = Resolver(localPublicKeyPrefix, nodeNames)
        val directText = isDirectTextMessage(entry)
        val sender = entry.senderPrefix
        val recipient = entry.recipientPrefix
        val fromTo = if (directText && sender != null && recipient != null) {
            "${resolver.label(sender)}$ARROW${resolver.label(recipient)}"
        } else {
            null
        }
        val versionSuffix = if (entry.payloadVersion > 0u) " v${entry.payloadVersion}" else ""
        return RxLogRowModel(
            routeLabel = entry.routeTypeSimple,
            isFlood = entry.isFlood,
            path = pathDisplay(entry, resolver),
            fromTo = fromTo,
            messagePreview = entry.decodedText?.let { "\"$it\"" },
            packetSummary = "${entry.payloadType.displayName}$versionSuffix · ${entry.rawPayload.size} ${text.string(AppToolsStrings.toolsRxLogBytes)}",
            snr = entry.snrDisplayString(text.locale),
            groupBadge = if (groupCount > 1) "×$groupCount" else null,
            groupAccessibility = if (groupCount > 1) text.format(FormattedStringIds.RX_LOG_RECEIVED_TIMES, groupCount.toLong()) else null,
            details = details(entry, resolver, directText),
            decodedText = if (entry.decryptStatus == DecryptStatus.SUCCESS) entry.decodedText else null,
            rawPayloadHex = rawPayloadHex(entry.rawPayload),
        )
    }

    /** `"AA BB CC"` as copied by the raw-payload button. */
    fun rawPayloadHex(payload: Bytes): String = payload.joinToString(" ") { String.format(Locale.ROOT, "%02X", it.toInt()) }

    private fun pathDisplay(entry: RxLogEntryDTO, resolver: Resolver): String {
        if (entry.pathNodes.isEmpty) return text.string(AppToolsStrings.toolsRxLogDirect)
        if (entry.payloadType == PayloadType.TRACE) {
            val route = traceRouteIdParts(entry)
            if (route.isNotEmpty()) return truncatedJoin(route)
            return "${entry.hopCount} ${hopLabel(entry.hopCount)}"
        }
        return truncatedJoin(hopIdParts(entry, resolver))
    }

    private fun pathDetail(entry: RxLogEntryDTO, resolver: Resolver): String {
        if (entry.pathNodes.isEmpty) return text.string(AppToolsStrings.toolsRxLogDirect)
        return "${entry.hopCount} ${hopLabel(entry.hopCount)} [${hopIdParts(entry, resolver).joinToString(", ")}]"
    }

    private fun hopLabel(count: Long): String =
        text.string(if (count == 1L) AppToolsStrings.toolsRxLogHopSingular else AppToolsStrings.toolsRxLogHopPlural)

    /** TRACE: public-key prefix ids from the trace target hashes. */
    private fun traceRouteIdParts(entry: RxLogEntryDTO): List<String> =
        entry.traceTargetHashes?.map { it.uppercaseHexString() } ?: emptyList()

    /** Non-TRACE: one id per hop, chunked by the path hash size; the local node reads as "You". */
    private fun hopIdParts(entry: RxLogEntryDTO, resolver: Resolver): List<String> {
        val hashSize = entry.pathHashSize.toInt().coerceAtLeast(1)
        val nodes = entry.pathNodes
        return (0 until nodes.size step hashSize).map { start ->
            val chunk = nodes.slice(start, minOf(start + hashSize, nodes.size))
            if (resolver.isLocal(chunk)) text.string(AppToolsStrings.toolsRxLogPathYou) else chunk.uppercaseHexString()
        }
    }

    /** Joins parts, eliding the middle as `first3 → … → last3` past six parts. */
    private fun truncatedJoin(parts: List<String>): String {
        if (parts.size <= MAX_UNTRUNCATED_HOPS) return parts.joinToString(ARROW)
        val first = parts.take(TRUNCATED_EDGE).joinToString(ARROW)
        val last = parts.takeLast(TRUNCATED_EDGE).joinToString(ARROW)
        return "$first $ARROW … $ARROW $last"
    }

    private fun isDirectTextMessage(entry: RxLogEntryDTO): Boolean =
        (entry.routeType == RouteType.DIRECT || entry.routeType == RouteType.TC_DIRECT) && entry.payloadType == PayloadType.TEXT_MESSAGE

    private fun details(entry: RxLogEntryDTO, resolver: Resolver, directText: Boolean): List<RxLogDetailRow> = buildList {
        entry.rssi?.let { add(RxLogDetailRow(label(AppToolsStrings.toolsRxLogRssiLabel), "$it dBm")) }
        entry.snrDisplayString(text.locale)?.let { add(RxLogDetailRow(label(AppToolsStrings.toolsRxLogSnrLabel), it)) }
        add(RxLogDetailRow(label(AppToolsStrings.toolsRxLogTypeLabel), entry.payloadType.displayName))
        add(RxLogDetailRow(label(AppToolsStrings.toolsRxLogSizeLabel), "${entry.rawPayload.size} ${text.string(AppToolsStrings.toolsRxLogBytes)}"))
        if (entry.payloadType == PayloadType.TRACE) {
            val route = traceRouteIdParts(entry)
            if (route.isNotEmpty()) add(RxLogDetailRow(label(AppToolsStrings.toolsRxLogTraceRouteLabel), route.joinToString(ARROW), wrapping = true))
        } else {
            add(RxLogDetailRow(label(AppToolsStrings.toolsRxLogPathLabel), pathDetail(entry, resolver), wrapping = true))
        }
        add(RxLogDetailRow(label(AppToolsStrings.toolsRxLogHashLabel), entry.packetHash, truncate = true))
        val sender = entry.senderPrefix
        val recipient = entry.recipientPrefix
        if (directText && sender != null && recipient != null) {
            add(RxLogDetailRow(label(AppToolsStrings.toolsRxLogFromLabel), resolver.label(sender)))
            add(RxLogDetailRow(label(AppToolsStrings.toolsRxLogToLabel), resolver.label(recipient)))
        }
        if (entry.decryptStatus == DecryptStatus.SUCCESS) addAll(channelDetails(entry))
    }

    private fun channelDetails(entry: RxLogEntryDTO): List<RxLogDetailRow> = buildList {
        if (entry.channelIndex != null && !entry.packetPayload.isEmpty) {
            add(RxLogDetailRow(label(AppToolsStrings.toolsRxLogChannelHashLabel), String.format(Locale.ROOT, "%02x", entry.packetPayload[0].toInt())))
        }
        entry.channelName?.let { add(RxLogDetailRow(label(AppToolsStrings.toolsRxLogChannelNameLabel), it)) }
        val transportCode = entry.transportCode
        if (transportCode != null && !transportCode.isEmpty) {
            val region = when (val match = RegionScopeSemantics.coalesce(entry.regionScope, entry.regionScopeMatches, text.locale)) {
                RegionMatchResult.None -> text.string(AppToolsStrings.toolsRxLogRegionUnresolved)
                is RegionMatchResult.Unique -> match.name
                is RegionMatchResult.Ambiguous -> text.joinList(match.names)
            }
            add(RxLogDetailRow(label(AppToolsStrings.toolsRxLogRegionLabel), region))
        }
    }

    private fun label(id: Int): String = text.string(id)

    /** Hash-to-label resolution: the local node first, then a unique contact name, then hex. */
    private inner class Resolver(private val localPrefix: Bytes?, private val nodeNames: Map<Bytes, String>) {
        fun isLocal(hash: Bytes): Boolean = localPrefix != null && localPrefix.prefix(hash.size) == hash

        fun label(hash: Bytes): String = when {
            isLocal(hash) -> text.string(AppToolsStrings.toolsRxLogPathYou)
            else -> nodeNames[hash] ?: hash.uppercaseHexString()
        }
    }
}
