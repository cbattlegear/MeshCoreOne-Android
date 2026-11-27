// AndroidOnly: WP-101 Prizm Mobile Observer settings port; the app adapts core:observer behind it (features cannot depend on it).
package com.meshcoreone.android.feature.settings.prizm

import kotlinx.coroutines.flow.StateFlow

data class PrizmZone(val id: String, val latitude: Double, val longitude: Double, val radiusMeters: Double)

data class PrizmCoordinate(val latitude: Double, val longitude: Double)

/** Mirrors the observer's node types (server values: handheld, vehicle, home, portable). */
enum class PrizmNodeType { HANDHELD, VEHICLE, HOME_BASE, PORTABLE }

data class PrizmCounters(val heard: Int = 0, val uploaded: Int = 0, val metadataOnly: Int = 0, val queued: Int = 0, val withheld: Int = 0)

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
    /** Upload gate text such as "Paused: outside ND and MN" or "Upload paused: location is off"; null while uploading. */
    val gateText: String? = null,
    val running: Boolean = false,
    /** True while the debug switch has Prizm sign-in and uploads on the offline stand-ins. */
    val standIn: Boolean = false,
    /** True when the connected node is claimed by the signed-in user (so "Stop claiming" applies). */
    val nodeClaimed: Boolean = false,
    /** Only in debuggable builds; null in release builds. */
    val debug: PrizmDebugState? = null,
    /** A sign-in that failed after the browser returned (state mismatch, refused, network); shown once. */
    val authError: String? = null,
) {
    val signedIn: Boolean get() = signedInAs != null
}

/** Debug-only server settings. [serverBaseUrl] is the effective URL (the production default unless overridden). */
data class PrizmDebugState(val serverBaseUrl: String, val overridden: Boolean, val standIn: Boolean)

sealed interface PrizmBaseUrlResult {
    data class Applied(val url: String) : PrizmBaseUrlResult
    data object Reset : PrizmBaseUrlResult
    data object Invalid : PrizmBaseUrlResult
}

sealed interface PrizmRemoveResult {
    data object Removed : PrizmRemoveResult
    data class Failed(val message: String) : PrizmRemoveResult
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
    /** Prizm queued the deletion of everything the user's claimed nodes uploaded; it completes within about a minute. */
    data object Deleted : PrizmDeleteResult
    data class Failed(val message: String) : PrizmDeleteResult
}

/** What the Prizm Observer screens need from the app. Implemented in `app` on top of `core:observer`. */
interface PrizmObserverPort {
    val state: StateFlow<PrizmObserverState>

    suspend fun signIn(): PrizmSignInResult
    suspend fun signOut()
    fun clearAuthError()
    fun setUploadEnabled(enabled: Boolean)
    fun setShareLocation(enabled: Boolean)
    fun addZone(latitude: Double, longitude: Double, radiusMeters: Double)
    fun removeZone(id: String)

    /** One fresh phone location for "use my current location"; null when unavailable or not permitted. */
    suspend fun currentLocation(): PrizmCoordinate?
    suspend fun claim(type: PrizmNodeType, confirmTakeover: Boolean): PrizmClaimResult
    fun dismissClaimPrompt()
    suspend fun deleteMyData(): PrizmDeleteResult

    /** Stops claiming the connected node on Prizm (ends its broker access; its uploaded data stays). */
    suspend fun removeNodeClaim(): PrizmRemoveResult

    /** Debug builds only. Blank resets to the production URL. A change signs the user out (tokens belong to one server). */
    fun setServerBaseUrl(text: String): PrizmBaseUrlResult
    fun setUseStandIn(enabled: Boolean)
}

/** Asks the user for the runtime permissions an action needs; [onResult] says whether all were granted. */
fun interface PrizmPermissionRequester {
    fun request(needsLocation: Boolean, needsNotifications: Boolean, onResult: (Boolean) -> Unit)
}
