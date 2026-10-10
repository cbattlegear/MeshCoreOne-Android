// PortedFrom: MC1/Views/RemoteNodes/NodeSettingsViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/NodeSettingsError.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText

/** Shared error for the repeater and room settings screens (`NodeSettingsError.noService`). */
class NodeSettingsNoServiceException : Exception("No service available") {
    val text: RemoteNodesText get() = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsNoService)
}

/** Inline errors for the repeater/room behavior fields. */
data class BehaviorValidationErrors(
    val advertInterval: RemoteNodesText? = null,
    val floodInterval: RemoteNodesText? = null,
    val floodMaxHops: RemoteNodesText? = null,
) {
    val hasErrors: Boolean get() = advertInterval != null || floodInterval != null || floodMaxHops != null
}

/** Inline errors for the identity fields. */
data class IdentityValidationErrors(
    val name: RemoteNodesText? = null,
    val latitude: RemoteNodesText? = null,
    val longitude: RemoteNodesText? = null,
) {
    val hasErrors: Boolean get() = name != null || latitude != null || longitude != null
}

object NodeSettingsValidation {
    /** Firmware-accepted ranges; 0 disables the two intervals and is accepted separately. */
    val advertIntervalMinutesRange: LongRange = 60L..240L
    val floodIntervalHoursRange: LongRange = 3L..168L
    val floodMaxHopsRange: LongRange = 0L..64L

    /** Firmware limit on the `set owner.info` value length. */
    const val OWNER_INFO_MAX_LENGTH = 119

    fun validRadioFields(frequency: Double, bandwidth: Double, spreadingFactor: Long, codingRate: Long): Boolean =
        frequency.isFinite() && bandwidth.isFinite() &&
            frequency * 1000 in PacketBuilder.FREQUENCY_RANGE_KHZ.let { it.first.toDouble()..it.last.toDouble() } &&
            bandwidth * 1000 in PacketBuilder.BANDWIDTH_RANGE_HZ.let { it.first.toDouble()..it.last.toDouble() } &&
            spreadingFactor in PacketBuilder.SPREADING_FACTOR_RANGE.first.toLong()..PacketBuilder.SPREADING_FACTOR_RANGE.last.toLong() &&
            codingRate in PacketBuilder.CODING_RATE_RANGE.first.toLong()..PacketBuilder.CODING_RATE_RANGE.last.toLong()

    fun validateBehaviorFields(advertInterval: Long?, floodInterval: Long?, floodMaxHops: Long?): BehaviorValidationErrors =
        BehaviorValidationErrors(
            advertInterval = if (advertInterval != null && advertInterval != 0L && advertInterval !in advertIntervalMinutesRange) {
                RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsAdvertIntervalValidation)
            } else null,
            floodInterval = if (floodInterval != null && floodInterval != 0L && floodInterval !in floodIntervalHoursRange) {
                RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsFloodIntervalValidation)
            } else null,
            floodMaxHops = if (floodMaxHops != null && floodMaxHops !in floodMaxHopsRange) {
                RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsFloodMaxValidation)
            } else null,
        )

    /**
     * Rejects out-of-range coordinates rather than clamping; ranges and the name byte cap match the
     * binary write path (`PacketBuilder`, `ProtocolLimits`). Null fields were never loaded/edited.
     */
    fun validateIdentityFields(name: String?, latitude: Double?, longitude: Double?): IdentityValidationErrors =
        IdentityValidationErrors(
            name = if (name != null && name.toByteArray(Charsets.UTF_8).size > ProtocolLimits.MAX_USABLE_NAME_BYTES) {
                RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_settings_namevalidation, ProtocolLimits.MAX_USABLE_NAME_BYTES)
            } else null,
            latitude = if (latitude != null && (!latitude.isFinite() || latitude !in PacketBuilder.LATITUDE_RANGE)) {
                RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsLatitudeValidation)
            } else null,
            longitude = if (longitude != null && (!longitude.isFinite() || longitude !in PacketBuilder.LONGITUDE_RANGE)) {
                RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsLongitudeValidation)
            } else null,
        )
}
