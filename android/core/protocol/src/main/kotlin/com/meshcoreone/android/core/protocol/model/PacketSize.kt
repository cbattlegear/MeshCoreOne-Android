// PortedFrom: MeshCore/Sources/MeshCore/Protocol/PacketSize.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.model

object PacketSize {
    const val CONTACT = 147
    const val SELF_INFO_MINIMUM = 57
    const val MESSAGE_SENT_MINIMUM = 9
    const val CONTACT_MESSAGE_V1_MINIMUM = 12
    const val CONTACT_MESSAGE_V3_MINIMUM = 15
    const val CHANNEL_MESSAGE_V1_MINIMUM = 7
    const val CHANNEL_MESSAGE_V3_MINIMUM = 10
    const val PRIVATE_KEY_MINIMUM = 64
    const val BATTERY_MINIMUM = 2
    const val BATTERY_EXTENDED = 10
    const val SIGN_START_MINIMUM = 5
    const val DEVICE_INFO_V3_FULL = 79
    const val ACK_MINIMUM = 4
    const val ACK_WITH_TRIP_TIME = 8
    const val CONTACTS_START_MINIMUM = 4
    const val CORE_STATS_MINIMUM = 9
    const val RADIO_STATS_MINIMUM = 12
    const val PACKET_STATS_MINIMUM = 24
    const val PACKET_STATS_WITH_RECEIVE_ERRORS = 28
    const val CHANNEL_INFO_MINIMUM = 49
    const val CONTACT_DELETED_PUBLIC_KEY = 32
    const val STATUS_RESPONSE_MINIMUM = 58
    const val TRACE_DATA_MINIMUM = 11
    const val RAW_DATA_MINIMUM = 3
    const val CONTROL_DATA_MINIMUM = 4
    const val PATH_DISCOVERY_MINIMUM = 9
    const val LOGIN_SUCCESS_MINIMUM = 7
    const val LOGIN_SUCCESS_EXTENDED = 13
    const val BINARY_RESPONSE_STATUS_BASE = 48
    const val BINARY_RESPONSE_STATUS_WITH_RX_AIRTIME = 52
    const val BINARY_RESPONSE_STATUS_WITH_RECEIVE_ERRORS = 56
    const val CHANNEL_DATAGRAM_MINIMUM = 8
    const val DEFAULT_FLOOD_SCOPE_NAME_FIELD = 31
    const val DEFAULT_FLOOD_SCOPE_KEY_BYTES = 16
    const val DEFAULT_FLOOD_SCOPE_SET = DEFAULT_FLOOD_SCOPE_NAME_FIELD + DEFAULT_FLOOD_SCOPE_KEY_BYTES
}
