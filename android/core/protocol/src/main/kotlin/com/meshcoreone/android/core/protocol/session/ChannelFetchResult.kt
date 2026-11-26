// PortedFrom: MeshCore/Sources/MeshCore/Session/MeshCoreSession+Channels.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.event.EventList

data class ChannelFetchResult(val received: EventList<ChannelInfo>, val missing: EventList<UByte>) {
    constructor(received: Collection<ChannelInfo>, missing: Collection<UByte>) : this(EventList(received), EventList(missing))
}
