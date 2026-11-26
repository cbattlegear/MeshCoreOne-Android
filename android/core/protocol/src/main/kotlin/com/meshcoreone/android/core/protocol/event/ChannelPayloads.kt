// PortedFrom: MeshCore/Sources/MeshCore/Events/ChannelPayloads.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.event

import com.meshcoreone.android.core.protocol.bytes.Bytes

data class DefaultFloodScope(val name: String, val scopeKey: Bytes)
data class ChannelInfo(val index: UByte, val name: String, val secret: Bytes)
