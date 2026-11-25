// PortedFrom: MeshCore/Sources/MeshCore/Session/OtherParamsConfig.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.config

import com.meshcoreone.android.core.protocol.model.SelfInfo

data class OtherParamsConfig(
    val manualAddContacts: Boolean = false,
    val telemetryModeBase: UByte = 0u,
    val telemetryModeLocation: UByte = 0u,
    val telemetryModeEnvironment: UByte = 0u,
    val advertisementLocationPolicy: UByte = 0u,
    val multiAcks: UByte = 0u,
) {
    constructor(info: SelfInfo) : this(
        info.manualAddContacts,
        info.telemetryModeBase,
        info.telemetryModeLocation,
        info.telemetryModeEnvironment,
        info.advertisementLocationPolicy,
        info.multiAcks,
    )
}
