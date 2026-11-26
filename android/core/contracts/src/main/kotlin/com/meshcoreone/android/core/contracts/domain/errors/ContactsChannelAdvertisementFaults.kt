// PortedFrom: MC1Services/Sources/MC1Services/Services/ContactService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/ChannelService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/AdvertisementService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Neutral typed fault carrier and immutable payload; projections belong to the owning producer exceptions.
package com.meshcoreone.android.core.contracts.domain.errors

import com.meshcoreone.android.core.protocol.config.MeshCoreException

/**
 * Implemented by an existing producer exception to expose its exact source case as a typed, immutable
 * payload. The producer keeps its own name, constructor, message, raw fields and cause; consumers keep
 * the original throwable and read only [sourceServiceFault].
 */
interface SourceServiceFaultCarrier {
    val sourceServiceFault: SourceServiceFault
}

/**
 * Root of every source service fault family. Families are sealed sub-interfaces declared in this
 * package and module; sibling declaration files may add further families.
 */
sealed interface SourceServiceFault

/** Swift `ContactServiceError`, one case per source case. */
sealed interface ContactServiceFault : SourceServiceFault {
    data object NotConnected : ContactServiceFault
    data object SendFailed : ContactServiceFault
    data object InvalidResponse : ContactServiceFault
    data object SyncInterrupted : ContactServiceFault
    data object ContactNotFound : ContactServiceFault
    data object ContactTableFull : ContactServiceFault
    data object ShareContactUnavailable : ContactServiceFault

    /** Carries the producer's identical session exception instance. */
    data class SessionError(val error: MeshCoreException) : ContactServiceFault
}

/** Swift `ChannelServiceError`, one case per source case. */
sealed interface ChannelServiceFault : SourceServiceFault {
    data object NotConnected : ChannelServiceFault
    data object ChannelNotFound : ChannelServiceFault
    data object InvalidChannelIndex : ChannelServiceFault
    data object SecretHashingFailed : ChannelServiceFault
    data class SaveFailed(val reason: String) : ChannelServiceFault
    data class SendFailed(val reason: String) : ChannelServiceFault

    /** Carries the producer's identical session exception instance. */
    data class SessionError(val error: MeshCoreException) : ChannelServiceFault
    data object SyncAlreadyInProgress : ChannelServiceFault
    data class CircuitBreakerOpen(val consecutiveFailures: Int) : ChannelServiceFault
}

/** Swift `AdvertisementError`, one case per source case. */
sealed interface AdvertisementFault : SourceServiceFault {
    data object NotConnected : AdvertisementFault
    data object SendFailed : AdvertisementFault
    data object InvalidResponse : AdvertisementFault

    /** Carries the producer's identical session exception instance. */
    data class SessionError(val error: MeshCoreException) : AdvertisementFault
}
