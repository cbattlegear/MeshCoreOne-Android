// PortedFrom: MeshCore/Sources/MeshCore/Protocol/PacketCodes.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/ErrorCode.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.model

enum class CommandCode(val rawValue: UByte) {
    APP_START(0x01u),
    SEND_MESSAGE(0x02u),
    SEND_CHANNEL_MESSAGE(0x03u),
    GET_CONTACTS(0x04u),
    GET_TIME(0x05u),
    SET_TIME(0x06u),
    SEND_ADVERTISEMENT(0x07u),
    SET_NAME(0x08u),
    UPDATE_CONTACT(0x09u),
    GET_MESSAGE(0x0au),
    SET_RADIO(0x0bu),
    SET_TX_POWER(0x0cu),
    RESET_PATH(0x0du),
    SET_COORDINATES(0x0eu),
    REMOVE_CONTACT(0x0fu),
    SHARE_CONTACT(0x10u),
    EXPORT_CONTACT(0x11u),
    IMPORT_CONTACT(0x12u),
    REBOOT(0x13u),
    GET_BATTERY(0x14u),
    SET_TUNING(0x15u),
    DEVICE_QUERY(0x16u),
    EXPORT_PRIVATE_KEY(0x17u),
    IMPORT_PRIVATE_KEY(0x18u),
    SEND_RAW_DATA(0x19u),
    SEND_LOGIN(0x1au),
    SEND_STATUS_REQUEST(0x1bu),
    HAS_CONNECTION(0x1cu),
    SEND_LOGOUT(0x1du),
    GET_CONTACT_BY_KEY(0x1eu),
    GET_CHANNEL(0x1fu),
    SET_CHANNEL(0x20u),
    SIGN_START(0x21u),
    SIGN_DATA(0x22u),
    SIGN_FINISH(0x23u),
    SEND_TRACE(0x24u),
    SET_DEVICE_PIN(0x25u),
    SET_OTHER_PARAMS(0x26u),
    GET_SELF_TELEMETRY(0x27u),
    GET_CUSTOM_VARS(0x28u),
    SET_CUSTOM_VAR(0x29u),
    GET_ADVERT_PATH(0x2au),
    GET_TUNING_PARAMS(0x2bu),
    BINARY_REQUEST(0x32u),
    FACTORY_RESET(0x33u),
    PATH_DISCOVERY(0x34u),
    SET_FLOOD_SCOPE(0x36u),
    SEND_CONTROL_DATA(0x37u),
    GET_STATS(0x38u),
    SEND_ANON_REQ(0x39u),
    SET_AUTO_ADD_CONFIG(0x3au),
    GET_AUTO_ADD_CONFIG(0x3bu),
    GET_REPEAT_FREQ(0x3cu),
    SET_PATH_HASH_MODE(0x3du),
    SEND_CHANNEL_DATA(0x3eu),
    SET_DEFAULT_FLOOD_SCOPE(0x3fu),
    GET_DEFAULT_FLOOD_SCOPE(0x40u),
    SEND_RAW_PACKET(0x41u);

    companion object {
        fun fromRawValue(rawValue: UByte): CommandCode? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

enum class ResponseCategory {
    SIMPLE, DEVICE, CONTACT, MESSAGE, PUSH, LOGIN, SIGNING, MISC,
}

enum class ResponseCode(val rawValue: UByte, val category: ResponseCategory) {
    OK(0x00u, ResponseCategory.SIMPLE),
    ERROR(0x01u, ResponseCategory.SIMPLE),
    CONTACT_START(0x02u, ResponseCategory.CONTACT),
    CONTACT(0x03u, ResponseCategory.CONTACT),
    CONTACT_END(0x04u, ResponseCategory.CONTACT),
    SELF_INFO(0x05u, ResponseCategory.DEVICE),
    MESSAGE_SENT(0x06u, ResponseCategory.MESSAGE),
    CONTACT_MESSAGE_RECEIVED(0x07u, ResponseCategory.MESSAGE),
    CHANNEL_MESSAGE_RECEIVED(0x08u, ResponseCategory.MESSAGE),
    CURRENT_TIME(0x09u, ResponseCategory.DEVICE),
    NO_MORE_MESSAGES(0x0au, ResponseCategory.MESSAGE),
    CONTACT_URI(0x0bu, ResponseCategory.CONTACT),
    BATTERY(0x0cu, ResponseCategory.DEVICE),
    DEVICE_INFO(0x0du, ResponseCategory.DEVICE),
    PRIVATE_KEY(0x0eu, ResponseCategory.DEVICE),
    DISABLED(0x0fu, ResponseCategory.DEVICE),
    CONTACT_MESSAGE_RECEIVED_V3(0x10u, ResponseCategory.MESSAGE),
    CHANNEL_MESSAGE_RECEIVED_V3(0x11u, ResponseCategory.MESSAGE),
    CHANNEL_INFO(0x12u, ResponseCategory.MISC),
    SIGN_START(0x13u, ResponseCategory.SIGNING),
    SIGNATURE(0x14u, ResponseCategory.SIGNING),
    CUSTOM_VARS(0x15u, ResponseCategory.MISC),
    ADVERT_PATH(0x16u, ResponseCategory.DEVICE),
    TUNING_PARAMS(0x17u, ResponseCategory.DEVICE),
    STATS(0x18u, ResponseCategory.MISC),
    AUTO_ADD_CONFIG(0x19u, ResponseCategory.DEVICE),
    ALLOWED_REPEAT_FREQ(0x1au, ResponseCategory.DEVICE),
    CHANNEL_DATA_RECEIVED(0x1bu, ResponseCategory.MESSAGE),
    DEFAULT_FLOOD_SCOPE(0x1cu, ResponseCategory.DEVICE),
    ADVERTISEMENT(0x80u, ResponseCategory.PUSH),
    PATH_UPDATE(0x81u, ResponseCategory.PUSH),
    ACK(0x82u, ResponseCategory.PUSH),
    MESSAGES_WAITING(0x83u, ResponseCategory.PUSH),
    RAW_DATA(0x84u, ResponseCategory.MISC),
    LOGIN_SUCCESS(0x85u, ResponseCategory.LOGIN),
    LOGIN_FAILED(0x86u, ResponseCategory.LOGIN),
    STATUS_RESPONSE(0x87u, ResponseCategory.PUSH),
    LOG_DATA(0x88u, ResponseCategory.MISC),
    TRACE_DATA(0x89u, ResponseCategory.MISC),
    NEW_ADVERTISEMENT(0x8au, ResponseCategory.PUSH),
    TELEMETRY_RESPONSE(0x8bu, ResponseCategory.PUSH),
    BINARY_RESPONSE(0x8cu, ResponseCategory.PUSH),
    PATH_DISCOVERY_RESPONSE(0x8du, ResponseCategory.PUSH),
    CONTROL_DATA(0x8eu, ResponseCategory.PUSH),
    CONTACT_DELETED(0x8fu, ResponseCategory.PUSH),
    CONTACTS_FULL(0x90u, ResponseCategory.PUSH);

    companion object {
        fun fromRawValue(rawValue: UByte): ResponseCode? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

enum class BinaryRequestType(val rawValue: UByte) {
    STATUS(0x01u), KEEP_ALIVE(0x02u), TELEMETRY(0x03u), MMA(0x04u),
    ACL(0x05u), NEIGHBOURS(0x06u), OWNER_INFO(0x07u);

    companion object {
        fun fromRawValue(rawValue: UByte): BinaryRequestType? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

enum class AnonRequestType(val rawValue: UByte) {
    REGIONS(0x01u), OWNER(0x02u), BASIC(0x03u);

    companion object {
        fun fromRawValue(rawValue: UByte): AnonRequestType? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

enum class ControlType(val rawValue: UByte) {
    NODE_DISCOVER_REQUEST(0x80u), NODE_DISCOVER_RESPONSE(0x90u);

    companion object {
        fun fromRawValue(rawValue: UByte): ControlType? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

enum class StatsType(val rawValue: UByte) {
    CORE(0x00u), RADIO(0x01u), PACKETS(0x02u);

    companion object {
        fun fromRawValue(rawValue: UByte): StatsType? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

enum class TextType(val rawValue: UByte) {
    PLAIN_TEXT(0x00u), CLI_DATA(0x01u), SIGNED(0x02u);

    companion object {
        fun fromRawValue(rawValue: UByte): TextType? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

enum class ErrorCode(val rawValue: UByte) {
    UNSUPPORTED_COMMAND(1u), NOT_FOUND(2u), TABLE_FULL(3u),
    BAD_STATE(4u), FILE_IO_ERROR(5u), ILLEGAL_ARGUMENT(6u);

    companion object {
        fun fromRawValue(rawValue: UByte): ErrorCode? = entries.firstOrNull { it.rawValue == rawValue }
    }
}
