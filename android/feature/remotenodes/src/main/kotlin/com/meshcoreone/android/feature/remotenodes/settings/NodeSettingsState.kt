// PortedFrom: MC1/Views/RemoteNodes/NodeSettingsViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.feature.remotenodes.cli.RemoteSwiftText
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText

/** Snapshot of the shared repeater/room settings sections (Swift `NodeSettingsViewModel` stored properties). */
data class NodeSettingsState(
    val session: RemoteNodeSessionDTO? = null,

    // Device info
    val firmwareVersion: String? = null,
    /** Raw UTC clock text from the node ("06:40 - 18/4/2025 UTC"); private in Swift. */
    val deviceTimeUTC: String? = null,
    /** Seconds; positive means the node's clock is ahead of the reference clock. */
    val clockDrift: Double? = null,
    val isLoadingDeviceInfo: Boolean = false,
    val deviceInfoError: Boolean = false,

    // Identity
    val name: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val originalName: String? = null,
    val originalLatitude: Double? = null,
    val originalLongitude: Double? = null,
    val isLoadingIdentity: Boolean = false,
    val identityError: Boolean = false,
    val nameError: RemoteNodesText? = null,
    val latitudeError: RemoteNodesText? = null,
    val longitudeError: RemoteNodesText? = null,

    // Radio
    val frequency: Double? = null,
    val bandwidth: Double? = null,
    val spreadingFactor: Long? = null,
    val codingRate: Long? = null,
    val isLoadingRadio: Boolean = false,
    val radioError: Boolean = false,
    val radioSettingsModified: Boolean = false,

    // Contact info
    val ownerInfo: String? = null,
    val originalOwnerInfo: String? = null,
    val isLoadingContactInfo: Boolean = false,
    val contactInfoError: Boolean = false,

    // Security
    val newPassword: String = "",
    val confirmPassword: String = "",

    // Expansion
    val isDeviceInfoExpanded: Boolean = false,
    val isRadioExpanded: Boolean = false,
    val isIdentityExpanded: Boolean = false,
    val isContactInfoExpanded: Boolean = false,
    val isSecurityExpanded: Boolean = false,

    // Global
    val isApplying: Boolean = false,
    val isRebooting: Boolean = false,
    val errorMessage: RemoteNodesText? = null,
    val successMessage: RemoteNodesText? = null,
    val showSuccessAlert: Boolean = false,
    val identityApplySuccess: Boolean = false,
    val contactInfoApplySuccess: Boolean = false,
    val changePasswordSuccess: Boolean = false,
    val isSendingAdvert: Boolean = false,
) {
    val deviceInfoLoaded: Boolean get() = deviceTimeUTC != null
    val identityLoaded: Boolean get() = originalLatitude != null || originalLongitude != null
    val identitySettingsModified: Boolean
        get() = (name != null && name != originalName) ||
            (latitude != null && latitude != originalLatitude) ||
            (longitude != null && longitude != originalLongitude)
    val radioLoaded: Boolean get() = frequency != null
    val contactInfoLoaded: Boolean get() = originalOwnerInfo != null

    /**
     * Gated on [contactInfoLoaded] so the empty pre-fetch field can't enable Apply and wipe the
     * node's owner info before the current value arrives.
     */
    val contactInfoSettingsModified: Boolean get() = contactInfoLoaded && ownerInfo != originalOwnerInfo

    /** Swift `String.count`: grapheme clusters, not UTF-16 units. */
    val ownerInfoCharCount: Int get() = RemoteSwiftText.characterCount(ownerInfo ?: "")
    val isOwnerInfoTooLong: Boolean get() = ownerInfoCharCount > NodeSettingsValidation.OWNER_INFO_MAX_LENGTH

    /** Apply-button enablement in the Contact Info section. */
    val canApplyContactInfo: Boolean get() = contactInfoSettingsModified && !isApplying && !isOwnerInfoTooLong

    internal val identitySectionComplete: Boolean
        get() = originalName != null && originalLatitude != null && originalLongitude != null
}
