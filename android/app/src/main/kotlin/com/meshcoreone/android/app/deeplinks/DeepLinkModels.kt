// AndroidOnly: WP-405 Typed Android URI routing and confirmation staging.
package com.meshcoreone.android.app.deeplinks

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType

sealed interface MeshCoreDeepLink {
    data class Map(val latitude: Double, val longitude: Double) : MeshCoreDeepLink
    data class Contact(val name: String, val publicKey: Bytes, val contactType: ContactType) : MeshCoreDeepLink
    data class Channel(
        val name: String,
        val secret: Bytes,
        val regionScope: String? = null,
        val hasHashtagSecretMismatch: Boolean = false,
    ) : MeshCoreDeepLink
    data class Hashtag(val fullName: String) : MeshCoreDeepLink
}

sealed interface DeepLinkRouteOutcome {
    data object Rejected : DeepLinkRouteOutcome
    data object IgnoredSelfContact : DeepLinkRouteOutcome
    data object Navigated : DeepLinkRouteOutcome
    data object ConfirmationStaged : DeepLinkRouteOutcome
    data object ContactLookupRejected : DeepLinkRouteOutcome
}

interface AppDeepLinkEnvironment {
    var selectedTab: com.meshcoreone.android.core.contracts.AppTab
    val currentRadioId: RadioId?
    val contactLinkGeneration: Long get() = 0
    fun captureContactLinkSession(generation: Long): () -> Boolean = { true }
    val connectedDevicePublicKey: Bytes?

    fun navigateToMap(latitude: Double, longitude: Double)
    fun navigateToContactDetail(contact: ContactDTO)
    fun navigateToChannel(channel: ChannelDTO)
    fun stageContactConfirmation(link: MeshCoreDeepLink.Contact)
    fun completeContactLookup(
        link: MeshCoreDeepLink.Contact, existing: ContactDTO?, radioId: RadioId?, generation: Long,
        isCurrentSession: () -> Boolean = { true },
    ): Boolean {
        if (!isCurrentSession() || currentRadioId != radioId || contactLinkGeneration != generation) return false
        if (existing != null) navigateToContactDetail(existing) else stageContactConfirmation(link)
        return true
    }
    fun stageChannelConfirmation(link: MeshCoreDeepLink.Channel)
    fun stageHashtagConfirmation(link: MeshCoreDeepLink.Hashtag)
    suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO?
    suspend fun fetchChannels(radioId: RadioId): List<ChannelDTO>
}
