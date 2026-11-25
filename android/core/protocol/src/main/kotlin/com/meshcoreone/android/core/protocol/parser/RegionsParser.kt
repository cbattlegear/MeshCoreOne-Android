// PortedFrom: MeshCore/Sources/MeshCore/Protocol/RegionsParser.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.parser

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.EventList

object RegionsParser {
    fun parse(responseData: Bytes): List<String> {
        if (responseData.size < 4) throw MeshCoreException.ParseError("Region response too short (${responseData.size} bytes)")
        val text = responseData.slice(4, responseData.size).exactUtf8()
            ?: throw MeshCoreException.ParseError("Invalid UTF-8 in region response")
        return EventList(text.trimControls().splitOmittingEmpty(',').map { it.trimWhitespaces() }.filter { it.isNotEmpty() && it != "*" })
    }
}
