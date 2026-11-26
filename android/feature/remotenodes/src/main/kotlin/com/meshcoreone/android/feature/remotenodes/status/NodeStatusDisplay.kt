// PortedFrom: MC1/Views/RemoteNodes/NodeStatusViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.common.SwiftNumberFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Swift `airtimeDisplay`: "TX <duration> / RX <duration>" where each duration is localized text. The
 * literal frame is not localized in Swift either; [compose] joins the resolved parts.
 */
data class AirtimeText(val tx: RemoteNodesText, val rx: RemoteNodesText) {
    fun compose(resolve: (RemoteNodesText) -> String): String = "TX ${resolve(tx)} / RX ${resolve(rx)}"
}

/** The status section's display formatters (Swift `NodeStatusViewModel` display properties). */
object NodeStatusDisplay {
    /** Placeholder for a missing value. */
    const val EM_DASH = "—"
    val emDashText: RemoteNodesText = RemoteNodesText.Verbatim(EM_DASH)

    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_DAY = 86_400L
    private const val AIRTIME_PERCENT_FRACTION_DIGITS = 1
    private const val BATTERY_FRACTION_DIGITS = 3
    private const val SNR_FRACTION_DIGITS = 1
    private const val MILLIVOLTS_PER_VOLT = 1000.0
    private const val PERCENT = 100.0
    private const val NANOS_PER_SECOND = 1_000_000_000.0

    fun uptime(status: StatusResponse?): RemoteNodesText = status?.let { formatDuration(it.uptime) } ?: emDashText

    fun airtime(status: StatusResponse?): AirtimeText? =
        status?.let { AirtimeText(formatDuration(it.airtime), formatDuration(it.rxAirtime)) }

    fun airtimePercent(status: StatusResponse?, locale: Locale): String {
        if (status == null || status.uptime == 0u) return EM_DASH
        val denominator = status.uptime.toDouble()
        val tx = status.airtime.toDouble() / denominator * PERCENT
        val rx = status.rxAirtime.toDouble() / denominator * PERCENT
        return "TX ${formatPercent(tx, locale)} / RX ${formatPercent(rx, locale)}"
    }

    /** Swift `formatDuration`: days/hours/minutes text, picking the 1-day, days, hours or minutes form. */
    fun formatDuration(seconds: UInt): RemoteNodesText {
        val total = seconds.toLong()
        val days = (total / SECONDS_PER_DAY).toInt()
        val hours = ((total % SECONDS_PER_DAY) / SECONDS_PER_HOUR).toInt()
        val minutes = ((total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toInt()
        return when {
            days == 1 -> RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_status_uptime1day, hours, minutes)
            days > 0 -> RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_status_uptimedays, days, hours, minutes)
            hours > 0 -> RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_status_uptimehours, hours, minutes)
            else -> RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_status_uptimeminutes, minutes)
        }
    }

    fun battery(status: StatusResponse?, ocvValues: List<Long>, locale: Locale): String {
        val millivolts = status?.batteryMillivolts ?: return EM_DASH
        val volts = millivolts.toDouble() / MILLIVOLTS_PER_VOLT
        val percent = OcvBatteryPercentage.percentage(millivolts.toLong(), ocvValues)
        return "${SwiftNumberFormat.fixed(volts, BATTERY_FRACTION_DIGITS, locale)}V ($percent%)"
    }

    fun lastRssi(status: StatusResponse?): String = status?.let { "${it.lastRSSI} dBm" } ?: EM_DASH

    fun lastSnr(status: StatusResponse?, locale: Locale): String =
        status?.let { "${SwiftNumberFormat.fixed(it.lastSNR, SNR_FRACTION_DIGITS, locale)} dB" } ?: EM_DASH

    fun noiseFloor(status: StatusResponse?): String = status?.let { "${it.noiseFloor} dBm" } ?: EM_DASH

    fun packetsSent(status: StatusResponse?, locale: Locale): String = count(status?.packetsSent, locale)
    fun packetsReceived(status: StatusResponse?, locale: Locale): String = count(status?.packetsReceived, locale)
    fun sentDirect(status: StatusResponse?, locale: Locale): String = count(status?.sentDirect, locale)
    fun sentFlood(status: StatusResponse?, locale: Locale): String = count(status?.sentFlood, locale)
    fun receivedDirect(status: StatusResponse?, locale: Locale): String = count(status?.receivedDirect, locale)
    fun receivedFlood(status: StatusResponse?, locale: Locale): String = count(status?.receivedFlood, locale)

    /** Direct plus flood duplicates as one total. */
    fun duplicates(status: StatusResponse?, locale: Locale): String =
        status?.let { SwiftNumberFormat.integer(it.directDuplicates + it.floodDuplicates, locale) } ?: EM_DASH

    /**
     * Swift `previousSnapshotTimestamp`: "vs N min ago" under an hour, "vs N h ago" under a day, else
     * "vs <month day>" in [zone]. Elapsed whole units truncate toward zero, as Swift `Int(Double)` does.
     */
    fun previousSnapshotTimestamp(
        previous: NodeStatusSnapshotDTO?,
        now: Instant,
        zone: ZoneId,
        locale: Locale,
    ): RemoteNodesText? {
        val timestamp = previous?.timestamp ?: return null
        val interval = java.time.Duration.between(timestamp, now).toNanos() / NANOS_PER_SECOND
        return when {
            interval < SECONDS_PER_HOUR -> RemoteNodesText.resource(
                R.string.l10n_app_remotenodes_remotenodes_history_vsminutesago, (interval / SECONDS_PER_MINUTE).toInt(),
            )
            interval < SECONDS_PER_DAY -> RemoteNodesText.resource(
                R.string.l10n_app_remotenodes_remotenodes_history_vshoursago, (interval / SECONDS_PER_HOUR).toInt(),
            )
            else -> RemoteNodesText.resource(
                R.string.l10n_app_remotenodes_remotenodes_history_vsdate, MonthDayFormat.format(timestamp, zone, locale),
            )
        }
    }

    /** Swift `Int.formatted()` of an optional counter, or the em dash. */
    fun count(value: UInt?, locale: Locale): String = value?.let { SwiftNumberFormat.integer(it.toLong(), locale) } ?: EM_DASH

    /** Swift `UInt16.formatted()` of an optional counter, or the em dash. */
    fun count(value: UShort?, locale: Locale): String = value?.let { SwiftNumberFormat.integer(it.toLong(), locale) } ?: EM_DASH

    private fun formatPercent(value: Double, locale: Locale): String =
        SwiftNumberFormat.fixed(value, AIRTIME_PERCENT_FRACTION_DIGITS, locale) + "%"
}
