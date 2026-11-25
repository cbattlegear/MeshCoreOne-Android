// PortedFrom: MC1/Views/RemoteNodes/NodeManagementTab.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/RemoteNodes/SettingsLoadPlaceholder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.feature.remotenodes.cli.NodeSettingsResponseParser
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/** Top-of-page segments of the repeater/room management page (in-memory selection, never persisted). */
enum class NodeManagementTab(val labelRes: Int) {
    SETTINGS(AppRemoteNodesStrings.remoteNodesSettingsTabSettings),
    CLI(AppRemoteNodesStrings.remoteNodesSettingsTabCli),
    TELEMETRY(AppRemoteNodesStrings.remoteNodesSettingsTabTelemetry),
}

object NodeSettingsPresentation {
    const val EM_DASH = "—"

    /** "Loading…" while in flight, "Failed to load" on error, an em dash otherwise. */
    fun loadPlaceholder(isLoading: Boolean, hasError: Boolean): RemoteNodesText = when {
        isLoading -> RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsLoading)
        hasError -> RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsFailedToLoad)
        else -> RemoteNodesText.Verbatim(EM_DASH)
    }

    /** The node's clock in local time, or null before a clock reply. */
    fun deviceTime(state: NodeSettingsState, locale: Locale, zone: ZoneId): String? =
        state.deviceTimeUTC?.let { convertUTCToLocal(it, locale, zone) }

    /**
     * Swift: `time(.shortened) + " - " + dateTime.year(.twoDigits).month(.twoDigits).day(.twoDigits)`;
     * unparseable text is shown as received. The date skeleton is approximated with the locale's short
     * date pattern widened to two-digit fields (en_US "08/14/26", de "14.08.26"; swiftc oracle
     * `settings_text.swift.txt`).
     */
    fun convertUTCToLocal(utc: String, locale: Locale, zone: ZoneId): String {
        val instant = NodeSettingsResponseParser.utcDate(utc) ?: return utc
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(instant)
        val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
            .replace(Regex("y+"), "yy").replace(Regex("M+"), "MM").replace(Regex("d+"), "dd")
        val date = DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(instant)
        return "$time - $date"
    }

    /** "12/119" owner-info counter and whether it renders in the error color. */
    fun ownerInfoCounter(state: NodeSettingsState): Pair<String, Boolean> =
        "${state.ownerInfoCharCount}/${NodeSettingsValidation.OWNER_INFO_MAX_LENGTH}" to state.isOwnerInfoTooLong
}
