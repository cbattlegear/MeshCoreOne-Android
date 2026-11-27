// AndroidOnly: WP-101 Prizm Mobile Observer settings port; the app adapts core:observer behind it (features cannot depend on it).
package com.meshcoreone.android.feature.settings.prizm

import kotlinx.coroutines.flow.StateFlow

data class PrizmZone(val id: String, val latitude: Double, val longitude: Double, val radiusMeters: Double)

data class PrizmCoordinate(val latitude: Double, val longitude: Double)

/** Mirrors the observer's node types; stored on Prizm only. */
enum class PrizmNodeType { HANDHELD, MOBILE, VEHICLE, HOME_BASE, PORTABLE }

data class PrizmCounters(val heard: Int = 0, val uploaded: Int = 0, val metadataOnly: Int = 0, val queued: Int = 0)

/** The node connected right now, as shown in the claim prompt. */
data class PrizmNode(val name: String, val model: String)

data class PrizmObserverState(
    /** Display name of the signed-in Prizm user; null when signed out. */
    val signedInAs: String? = null,
    val uploadEnabled: Boolean = false,
    val shareLocation: Boolean = false,
    val zones: List<PrizmZone> = emptyList(),
    val node: PrizmNode? = null,
    /** True when the connected node still needs the claim prompt (signed in, not claimed, not dismissed). */
    val claimPending: Boolean = false,
    val counters: PrizmCounters = PrizmCounters(),
    /** Upload gate text such as "Paused: outside ND and MN"; null while uploading. */
    val gateText: String? = null,
    val running: Boolean = false,
    /** True while Prizm sign-in and uploads are the offline stand-ins. */
    val standIn: Boolean = true,
) {
    val signedIn: Boolean get() = signedInAs != null
}

sealed interface PrizmSignInResult {
    /** The browser was opened; the result arrives through [PrizmObserverState.signedInAs]. */
    data object BrowserOpened : PrizmSignInResult
    data object SignedIn : PrizmSignInResult
    data class Failed(val message: String) : PrizmSignInResult
}

sealed interface PrizmClaimResult {
    data object Linked : PrizmClaimResult
    data object InfoUpdated : PrizmClaimResult
    /** Linked to someone else; call again with `confirmTakeover = true` after asking. */
    data class NeedsConfirmation(val ownerLabel: String?) : PrizmClaimResult
    data object Expired : PrizmClaimResult
    data class Rejected(val reason: String) : PrizmClaimResult
    data object SigningFailed : PrizmClaimResult
    data class NetworkFailed(val message: String) : PrizmClaimResult
}

sealed interface PrizmDeleteResult {
    data object Deleted : PrizmDeleteResult
    /** No deletion service exists yet (stand-in). */
    data object Unavailable : PrizmDeleteResult
    data class Failed(val message: String) : PrizmDeleteResult
}

/** What the Prizm Observer screens need from the app. Implemented in `app` on top of `core:observer`. */
interface PrizmObserverPort {
    val state: StateFlow<PrizmObserverState>

    suspend fun signIn(): PrizmSignInResult
    suspend fun signOut()
    fun setUploadEnabled(enabled: Boolean)
    fun setShareLocation(enabled: Boolean)
    fun addZone(latitude: Double, longitude: Double, radiusMeters: Double)
    fun removeZone(id: String)

    /** One fresh phone location for "use my current location"; null when unavailable or not permitted. */
    suspend fun currentLocation(): PrizmCoordinate?
    suspend fun claim(type: PrizmNodeType, confirmTakeover: Boolean): PrizmClaimResult
    fun dismissClaimPrompt()
    suspend fun deleteMyData(): PrizmDeleteResult
}

/** Asks the user for the runtime permissions an action needs; [onResult] says whether all were granted. */
fun interface PrizmPermissionRequester {
    fun request(needsLocation: Boolean, needsNotifications: Boolean, onResult: (Boolean) -> Unit)
}
