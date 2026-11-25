// PortedFrom: MeshCore/Sources/MeshCore/Session/ContactFetchResult.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.event.EventList
import com.meshcoreone.android.core.protocol.model.MeshContact

data class ContactFetchResult(val contacts: EventList<MeshContact>, val reportedTotal: Long?) {
    constructor(contacts: Collection<MeshContact>, reportedTotal: Long?) : this(EventList(contacts), reportedTotal)
}
