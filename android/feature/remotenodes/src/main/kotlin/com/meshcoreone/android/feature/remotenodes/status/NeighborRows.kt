// PortedFrom: MC1/Views/RemoteNodes/NeighborRow.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/RemoteNodes/DisappearedNeighborRow.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/RemoteNodes/Repeaters/RepeaterStatusContent.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.NeighborSnapshotEntry
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.Neighbour
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.common.SwiftNumberFormat
import com.meshcoreone.android.feature.remotenodes.resolver.NeighborNameResolver
import com.meshcoreone.android.feature.remotenodes.resolver.NodeNameMatchKind
import java.util.Locale
import kotlin.math.abs

/** A current neighbour row (Swift `NeighborRow` inputs plus its derived text). */
data class NeighborRowModel(
    val neighbor: Neighbour,
    val displayName: RemoteNodesText,
    val matchKind: NodeNameMatchKind,
    val keyHex: String,
    val lastSeen: RemoteNodesText,
    val snrText: RemoteNodesText,
    /** Shown when the SNR moved by at least 0.1 dB since the previous neighbour capture. */
    val snrDelta: StatusDelta?,
    val isNew: Boolean,
    val route: NodeStatusRoute.NeighborChart,
)

/** A neighbour present in the previous capture but missing now (Swift `DisappearedNeighborRow`). */
data class DisappearedNeighborRowModel(
    val entry: NeighborSnapshotEntry,
    val displayName: RemoteNodesText,
    val matchKind: NodeNameMatchKind,
    val snrText: RemoteNodesText,
)

/** The resolution inputs every neighbour row shares. */
data class NeighborResolutionContext(
    val contacts: List<ContactDTO>,
    val discoveredNodes: List<DiscoveredNodeDTO>,
    val userLocation: Coordinate?,
    val locale: Locale,
)

object NeighborRows {
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val SNR_FRACTION_DIGITS = 1
    private const val MINIMUM_SNR_DELTA = 0.1

    /** Swift `NeighborRow.lastSeenText`. */
    fun lastSeen(secondsAgo: Long): RemoteNodesText = when {
        secondsAgo < SECONDS_PER_MINUTE ->
            RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_status_secondsago, secondsAgo.toInt())
        secondsAgo < SECONDS_PER_HOUR ->
            RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_status_minutesago, (secondsAgo / SECONDS_PER_MINUTE).toInt())
        else ->
            RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_status_hoursago, (secondsAgo / SECONDS_PER_HOUR).toInt())
    }

    /** `L10n...Status.snrFormat` with one decimal. */
    fun snrText(snr: Double, locale: Locale): RemoteNodesText = RemoteNodesText.resource(
        R.string.l10n_app_remotenodes_remotenodes_status_snrformat, SwiftNumberFormat.fixed(snr, SNR_FRACTION_DIGITS, locale),
    )

    /** Swift `NeighborRow` delta: present only when |current - previous| >= 0.1 dB. */
    fun snrDelta(current: Double, previous: NeighborSnapshotEntry?): StatusDelta? {
        val delta = current - (previous ?: return null).snr
        return if (abs(delta) >= MINIMUM_SNR_DELTA) StatusDelta.snr(delta) else null
    }

    /** Rows for the current neighbours, in response order. */
    fun rows(
        neighbors: List<Neighbour>,
        previousNeighborSnapshot: NodeStatusSnapshotDTO?,
        seenPrefixes: Set<Bytes>,
        keyDisplayByteCount: Int,
        context: NeighborResolutionContext,
    ): List<NeighborRowModel> {
        val previousEntries = previousNeighborSnapshot?.neighborSnapshots
        return neighbors.map { neighbor ->
            val resolution = resolve(neighbor.publicKeyPrefix, context)
            val name = resolution?.displayName?.let { RemoteNodesText.Verbatim(it) }
                ?: RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesStatusUnknown)
            NeighborRowModel(
                neighbor = neighbor,
                displayName = name,
                matchKind = resolution?.matchKind ?: NodeNameMatchKind.UNRESOLVED,
                keyHex = NeighborNameResolver.fallbackName(neighbor.publicKeyPrefix, keyDisplayByteCount),
                lastSeen = lastSeen(neighbor.secondsAgo),
                snrText = snrText(neighbor.snr, context.locale),
                snrDelta = snrDelta(neighbor.snr, previousEntries?.firstOrNull { it.publicKeyPrefix == neighbor.publicKeyPrefix }),
                isNew = previousEntries != null && neighbor.publicKeyPrefix !in seenPrefixes,
                route = NodeStatusRoute.NeighborChart(name, neighbor.publicKeyPrefix),
            )
        }
    }

    /** Previous-capture neighbours absent from [neighbors], named by resolution or hex fallback. */
    fun disappeared(
        neighbors: List<Neighbour>,
        previousNeighborSnapshot: NodeStatusSnapshotDTO?,
        keyDisplayByteCount: Int,
        context: NeighborResolutionContext,
    ): List<DisappearedNeighborRowModel> {
        val previousEntries = previousNeighborSnapshot?.neighborSnapshots ?: return emptyList()
        val current = neighbors.map { it.publicKeyPrefix }.toSet()
        return previousEntries.filter { it.publicKeyPrefix !in current }.map { entry ->
            val resolution = resolve(entry.publicKeyPrefix, context)
            DisappearedNeighborRowModel(
                entry = entry,
                displayName = RemoteNodesText.Verbatim(
                    resolution?.displayName ?: NeighborNameResolver.fallbackName(entry.publicKeyPrefix, keyDisplayByteCount),
                ),
                matchKind = resolution?.matchKind ?: NodeNameMatchKind.UNRESOLVED,
                snrText = snrText(entry.snr, context.locale),
            )
        }
    }

    private fun resolve(prefix: Bytes, context: NeighborResolutionContext) = NeighborNameResolver.resolve(
        prefix, context.contacts, context.discoveredNodes, context.userLocation, context.locale,
    )
}
