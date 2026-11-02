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
}

interface AppDeepLinkEnvironment {
    var selectedTab: com.meshcoreone.android.core.contracts.AppTab
    val currentRadioId: RadioId?
    val connectedDevicePublicKey: Bytes?

    fun navigateToMap(latitude: Double, longitude: Double)
    fun navigateToContactDetail(contact: ContactDTO)
    fun navigateToChannel(channel: ChannelDTO)
    fun stageContactConfirmation(link: MeshCoreDeepLink.Contact, selectChats: Boolean = false)
    fun stageChannelConfirmation(link: MeshCoreDeepLink.Channel, selectChats: Boolean = false)
    fun stageHashtagConfirmation(link: MeshCoreDeepLink.Hashtag, selectChats: Boolean = false)
    suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO?
    suspend fun fetchChannels(radioId: RadioId): List<ChannelDTO>
}
