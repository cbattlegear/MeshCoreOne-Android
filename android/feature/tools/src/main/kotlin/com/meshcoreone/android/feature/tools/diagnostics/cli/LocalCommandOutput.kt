// PortedFrom: MC1/Views/Tools/CLI/LocalCommandOutput.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.feature.tools.diagnostics.text.SwiftNumbers
import com.meshcoreone.android.feature.tools.diagnostics.text.SwiftStrings
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * Firmware-parity output strings for local radio commands. Deliberately unlocalized: this output
 * is copy-pasted into bug reports and compared against meshcore-cli.
 */
object LocalCommandOutput {
    const val OK = "OK"
    const val OK_REBOOTING = "OK - rebooting"
    const val ZERO_HOP_ADVERT = "OK - zero-hop advert sent"
    const val FLOOD_ADVERT = "OK - flood advert sent"
    const val UNKNOWN_CUSTOM_VAR = "can't find custom var"
    const val NO_CUSTOM_VARS = "no custom var"

    fun value(text: String): String = "> $text"

    fun unknownVar(key: String): String = "Unknown var $key"

    fun customVarSet(key: String, value: String): String = "Var $key set to $value"

    /** `N vars` header then one `name=value` line per var with keys in Swift string order. */
    fun customVarList(vars: Map<String, String>): String {
        if (vars.isEmpty()) return NO_CUSTOM_VARS
        val lines = SwiftStrings.sorted(vars.keys).map { "$it=${vars[it] ?: ""}" }
        return "${vars.size} vars\n" + lines.joinToString("\n")
    }

    fun clock(date: Instant): String = formatClock(date)

    fun clockSet(date: Instant): String = "OK - clock set: ${formatClock(date)}"

    fun radio(info: SelfInfo): String =
        "${decimal(info.radioFrequency)},${decimal(info.radioBandwidth)},${info.radioSpreadingFactor},${info.radioCodingRate}"

    fun hex(data: Bytes): String = data.joinToString("") { String.format(Locale.ROOT, "%02X", it.toInt()) }

    /** kHz from MHz for `setRadioParamsVerified(frequencyKHz:)`. */
    fun freqKHz(mhz: Double): UInt = toUInt32(SwiftNumbers.roundedHalfAwayFromZero(mhz * 1000).toLong())

    /** Hz from kHz for `setRadioParamsVerified(bandwidthKHz:)` (the parameter is Hz despite its name). */
    fun bandwidthHz(khz: Double): UInt = toUInt32(SwiftNumbers.roundedHalfAwayFromZero(khz * 1000).toLong())

    fun decimal(value: Double): String = trimmed(SwiftNumbers.printfFixed(value, 3))

    fun coordinate(value: Double): String = trimmed(SwiftNumbers.printfFixed(value, 6))

    /** Swift `UInt32(_:)` traps outside its range; the parser's bounds keep callers inside it. */
    private fun toUInt32(value: Long): UInt {
        require(value in 0..UInt.MAX_VALUE.toLong()) { "Value $value does not fit UInt32" }
        return value.toUInt()
    }

    private fun trimmed(formatted: String): String {
        if (!formatted.contains('.')) return formatted
        return formatted.trimEnd('0').removeSuffix(".")
    }

    private fun formatClock(date: Instant): String {
        val parts = date.atZone(ZoneOffset.UTC)
        return String.format(
            Locale.ROOT, "%02d:%02d - %d/%d/%d UTC",
            parts.hour, parts.minute, parts.dayOfMonth, parts.monthValue, parts.year,
        )
    }
}
