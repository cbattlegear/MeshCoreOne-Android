// PortedFrom: MC1/Views/Tools/CLI/CLILocalCommand.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.diagnostics.cli

/**
 * A parsed local-session radio command. Typed reads collapse into one [GetKey] case; a custom-var
 * read carries the raw key ([GetCustomVar]) or dumps every var ([GetCustomVars]).
 */
sealed interface CliLocalCommand {
    data object Clock : CliLocalCommand
    data object ClockSync : CliLocalCommand
    data object Ver : CliLocalCommand
    data object Board : CliLocalCommand
    data class Advert(val flood: Boolean) : CliLocalCommand
    data object Reboot : CliLocalCommand
    data class GetKey(val key: CliLocalKey) : CliLocalCommand
    data class SetName(val name: String) : CliLocalCommand
    data class SetLatitude(val latitude: Double) : CliLocalCommand
    data class SetLongitude(val longitude: Double) : CliLocalCommand
    data class SetTxPower(val power: Byte) : CliLocalCommand
    data class SetRadio(
        val frequencyMHz: Double,
        val bandwidthKHz: Double,
        val spreadingFactor: UByte,
        val codingRate: UByte,
    ) : CliLocalCommand
    data class SetFrequency(val frequencyMHz: Double) : CliLocalCommand
    data class SetMultiAcks(val value: UByte) : CliLocalCommand
    data class SetPathHashMode(val mode: UByte) : CliLocalCommand
    data object GetCustomVars : CliLocalCommand
    data class GetCustomVar(val key: String) : CliLocalCommand
    data class SetCustomVar(val key: String, val value: String) : CliLocalCommand

    /** Commands that drop the link or move the node off-mesh require a terminal confirm step. */
    val requiresConfirmation: Boolean
        get() = this == Reboot || this is SetRadio || this is SetFrequency

    /** The command text shown in the confirm prompt. */
    val displayName: String
        get() = when (this) {
            Reboot -> "reboot"
            is SetRadio -> "set radio"
            is SetFrequency -> "set freq"
            else -> ""
        }
}

/** The `get`/`set` key vocabulary for the local session, mirroring the firmware CLI's dotted names. */
enum class CliLocalKey(val rawValue: String) {
    NAME("name"),
    LAT("lat"),
    LON("lon"),
    TX("tx"),
    RADIO("radio"),
    FREQ("freq"),
    PUBLIC_KEY("public.key"),
    MULTI_ACKS("multi.acks"),
    PATH_HASH_MODE("path.hash.mode"),
    BAT("bat"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): CliLocalKey? = entries.firstOrNull { it.rawValue == rawValue }
    }
}
