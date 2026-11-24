// PortedFrom: MC1Services/Sources/MC1Services/Errors/RemoteNodeError.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/RoomServerService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/BinaryProtocolService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/NodeConfigService.swift@db14559b39d32322b06477c6ae676112f583db50
// Neutral typed payload families for the remote-node, room-server, binary-protocol and node-config producers.
package com.meshcoreone.android.core.contracts.domain.errors

import com.meshcoreone.android.core.protocol.config.MeshCoreException

/** Swift `RemoteNodeError`, one case per source case. */
sealed interface RemoteNodeFault : SourceServiceFault {
    /** Swift `RemoteNodeError.isRetryable`: true exactly for `timeout`, `notConnected` and `floodRouted`. */
    val isRetryable: Boolean
        get() = when (this) {
            NotConnected, Timeout, FloodRouted -> true
            is LoginFailed, is SendFailed, InvalidResponse, PermissionDenied, SessionNotFound, PasswordNotFound,
            PathDiscoveryFailed, ContactNotFound, RadioContactsFull, Cancelled, InvalidSensorSetting,
            SensorSettingRejected, SensorOutcomeUnknown, is SessionError,
            -> false
        }

    data object NotConnected : RemoteNodeFault
    data class LoginFailed(val reason: String) : RemoteNodeFault
    data class SendFailed(val reason: String) : RemoteNodeFault
    data object InvalidResponse : RemoteNodeFault
    data object PermissionDenied : RemoteNodeFault
    data object Timeout : RemoteNodeFault
    data object SessionNotFound : RemoteNodeFault
    data object PasswordNotFound : RemoteNodeFault
    data object FloodRouted : RemoteNodeFault
    data object PathDiscoveryFailed : RemoteNodeFault
    data object ContactNotFound : RemoteNodeFault
    data object RadioContactsFull : RemoteNodeFault
    data object Cancelled : RemoteNodeFault
    data object InvalidSensorSetting : RemoteNodeFault
    data object SensorSettingRejected : RemoteNodeFault
    data object SensorOutcomeUnknown : RemoteNodeFault

    /** Carries the producer's identical session exception instance. */
    data class SessionError(val error: MeshCoreException) : RemoteNodeFault
}

/** Swift `RoomServerError`, one case per source case. */
sealed interface RoomServerFault : SourceServiceFault {
    data object NotConnected : RoomServerFault
    data object SessionNotFound : RoomServerFault
    data class SendFailed(val reason: String) : RoomServerFault
    data object PermissionDenied : RoomServerFault
    data object InvalidResponse : RoomServerFault

    /** Carries the producer's identical session exception instance. */
    data class SessionError(val error: MeshCoreException) : RoomServerFault
}

/** Swift `BinaryProtocolError`, one case per source case. */
sealed interface BinaryProtocolFault : SourceServiceFault {
    data object NotConnected : BinaryProtocolFault
    data object SendFailed : BinaryProtocolFault
    data object Timeout : BinaryProtocolFault
    data object InvalidResponse : BinaryProtocolFault

    /** Carries the producer's identical session exception instance. */
    data class SessionError(val error: MeshCoreException) : BinaryProtocolFault
}

/** Swift `RadioField`: which radio parameter failed range validation. */
enum class NodeConfigRadioField { FREQUENCY, BANDWIDTH, SPREADING_FACTOR, CODING_RATE, TX_POWER }

/** Swift `CoordinateField`: which coordinate failed range validation, and on which record. */
sealed interface NodeConfigCoordinateField {
    data object PositionLatitude : NodeConfigCoordinateField
    data object PositionLongitude : NodeConfigCoordinateField
    data class ContactLatitude(val name: String) : NodeConfigCoordinateField
    data class ContactLongitude(val name: String) : NodeConfigCoordinateField
}

/** Swift `NodeConfigServiceError`, one case per source case. */
sealed interface NodeConfigServiceFault : SourceServiceFault {
    data class InvalidChannelSecret(val index: Int, val hexLength: Int) : NodeConfigServiceFault
    data class InvalidContactPublicKey(val name: String) : NodeConfigServiceFault
    data class InvalidPathHashMode(val name: String, val mode: UByte) : NodeConfigServiceFault
    data class InvalidPrivateKey(val hexLength: Int) : NodeConfigServiceFault
    data class InvalidRadioSettings(val field: NodeConfigRadioField) : NodeConfigServiceFault
    data class NoAvailableChannelSlot(val name: String) : NodeConfigServiceFault
    data class InvalidCoordinate(val field: NodeConfigCoordinateField) : NodeConfigServiceFault
    data class InvalidOutPath(val name: String) : NodeConfigServiceFault
    data class ContactCapacityExceeded(val needed: Int, val available: Int) : NodeConfigServiceFault
}
