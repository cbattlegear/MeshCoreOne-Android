// PortedFrom: MC1Services/Sources/MC1Services/Simulator/MockDataProvider.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Mock data provider for the simulator and demo mode. Demo mode is what store reviewers see, so seeded
 * conversations must read as realistic. Per-feature seed data lives in the `MockDataProvider…` files as
 * extensions on this object; this file holds the shared identity constants, the simulated device, and the
 * offline demo image.
 *
 * Swift samples `Date()` inside every builder. Here each builder takes the `now` instant explicitly, and
 * [SimulatorConnectionMode] samples its injected clock once per seed pass, so a fixed clock yields an
 * identical seed every time.
 */
object MockDataProvider {
    // MARK: - Deterministic IDs

    /** Simulator device UUID. */
    val simulatorDeviceID: UUID = uuid("00000000-0000-0000-0000-000000000001")

    /** The simulator's radio scope: Swift uses the device UUID as the radio ID. */
    val simulatorRadioId: RadioId = RadioId(simulatorDeviceID)

    /** Contact UUIDs. */
    val aliceChenID: UUID = uuid("00000000-0000-0000-0000-000000000010")
    val bobMartinezID: UUID = uuid("00000000-0000-0000-0000-000000000020")
    val charlieNodeID: UUID = uuid("00000000-0000-0000-0000-000000000030")
    val dianasRoomID: UUID = uuid("00000000-0000-0000-0000-000000000040")
    val eveThompsonID: UUID = uuid("00000000-0000-0000-0000-000000000050")
    val frankWilsonID: UUID = uuid("00000000-0000-0000-0000-000000000060")
    val ghostNodeID: UUID = uuid("00000000-0000-0000-0000-000000000070")
    val hannahLeeID: UUID = uuid("00000000-0000-0000-0000-000000000080")
    val northRidgeRepeaterID: UUID = uuid("00000000-0000-0000-0000-000000000091")
    val twinPeaksRepeaterID: UUID = uuid("00000000-0000-0000-0000-000000000092")
    val oaklandRepeaterID: UUID = uuid("00000000-0000-0000-0000-000000000093")

    /** Byte 0 of `mockPublicKey(seed)`. A 1-byte hop hash of this value matches that repeater only. */
    internal val northRidgeRepeaterSeed: UByte = 0x91u
    internal val twinPeaksRepeaterSeed: UByte = 0x92u
    internal val oaklandRepeaterSeed: UByte = 0x93u

    /** Channel UUIDs. */
    val publicChannelID: UUID = uuid("000000C0-0000-0000-0000-000000000000")
    val bayAreaChannelID: UUID = uuid("000000C0-0000-0000-0000-000000000001")
    val trailCrewChannelID: UUID = uuid("000000C0-0000-0000-0000-000000000002")
    val meshHQChannelID: UUID = uuid("000000C0-0000-0000-0000-000000000003")

    /** Channel slot indices (mirror firmware slot positions). */
    val publicChannelIndex: UByte = 0u
    val bayAreaChannelIndex: UByte = 1u
    val trailCrewChannelIndex: UByte = 2u
    val meshHQChannelIndex: UByte = 3u

    /**
     * Message IDs referenced by more than one seed helper (reaction / repeat / link-preview targets) so the
     * builders and the post-save mutators agree.
     */
    internal val aliceReactedMessageID: UUID = uuid("10000000-0000-0000-0000-000000000002")
    internal val aliceLinkPreviewMessageID: UUID = uuid("10000000-0000-0000-0000-00000000000A")
    internal val frankRepeatMessageID: UUID = uuid("60000000-0000-0000-0000-000000000002")
    internal val aliceMultiPathMessageID: UUID = uuid("10000000-0000-0000-0000-000000000010")
    internal val publicMultiPathMessageID: UUID = uuid("C0000000-0000-0000-0000-000000000006")
    internal val frankFloodUniqueMessageID: UUID = uuid("60000000-0000-0000-0000-000000000004")
    internal val frankFloodAmbiguousMessageID: UUID = uuid("60000000-0000-0000-0000-000000000005")
    internal val bayAreaReactedMessageID: UUID = uuid("C1000000-0000-0000-0000-000000000002")
    internal val bayAreaMentionMessageID: UUID = uuid("C1000000-0000-0000-0000-000000000003")
    internal val publicAmbiguousRegionMessageID: UUID = uuid("C0000000-0000-0000-0000-000000000005")
    internal val uniqueRxLogEntryID: UUID = uuid("A0000000-0000-0000-0000-000000000001")
    internal val ambiguousRxLogEntryID: UUID = uuid("A0000000-0000-0000-0000-000000000002")

    /**
     * Seed values matching `RegionScopeSemantics.storageFields`: unique is `(name, [name])`, ambiguous is
     * `(nil, sorted names)`.
     */
    internal val uniqueRegionName: String = "US915"
    internal val ambiguousRegionNames: List<String> = listOf("de-by", "de-hh")

    // MARK: - Mock Public Keys

    /**
     * Deterministic 32-byte public key from a seed (`UInt8(i) &+ seed`). Internal so the per-feature builders
     * in sibling files can resolve sender key prefixes.
     */
    internal fun mockPublicKey(seed: UByte): Bytes =
        Bytes(ByteArray(ProtocolLimits.PUBLIC_KEY_SIZE) { index -> (index + seed.toInt()).toByte() })

    // MARK: - Offline Demo Image

    /**
     * URL placed in a seeded DM body so the inline-image render path has a target. The pixels are pre-seeded
     * into the app-layer image cache on demo connect ([DemoInlineImageSeeder]), so the bubble renders with no
     * network fetch.
     */
    const val inlineImageURL = "https://meshcoreone.com/summit.jpg"

    /**
     * A small embedded gradient PNG (see `MockDataProviderDemoImage.kt`). Doubles as the offline link-preview
     * hero blob and, decoded by the app layer, as the offline inline image. The MIME decoder skips the line
     * breaks like Foundation's `.ignoreUnknownCharacters`; an undecodable literal yields empty bytes, as the
     * Swift `?? Data()` fallback does.
     */
    val demoImageData: Bytes by lazy {
        try {
            Bytes(Base64.getMimeDecoder().decode(demoImageBase64))
        } catch (malformed: IllegalArgumentException) {
            Bytes.EMPTY
        }
    }

    // MARK: - Simulator Device

    /** Mock simulator device with realistic configuration, last connected at [now]. */
    fun simulatorDevice(now: Instant): DeviceDTO = DeviceDTO(
        id = simulatorDeviceID,
        radioId = simulatorRadioId,
        publicKey = mockPublicKey(1u),
        nodeName = "Sim",
        firmwareVersion = 8u,
        firmwareVersionString = "v1.11.0",
        manufacturerName = "Mock Device",
        buildDate = "2025-12-20",
        maxContacts = 100u,
        maxChannels = 8u,
        frequency = 915_000u, // 915 MHz
        bandwidth = 250_000u, // 250 kHz
        spreadingFactor = 10u, // SF10
        codingRate = 5u, // 4/5
        txPower = 20, // 20 dBm
        maxTxPower = 20,
        latitude = 37.7749, // San Francisco
        longitude = -122.4194,
        blePin = 0u, // Disabled
        manualAddContacts = false,
        multiAcks = 2u,
        telemetryModeBase = 2u,
        telemetryModeLoc = 0u,
        telemetryModeEnv = 0u,
        advertLocationPolicy = 0u,
        lastConnected = now,
        lastContactSync = 0u,
        isActive = true,
        ocvPreset = null,
        customOCVArrayString = null,
    )

    internal fun uuid(text: String): UUID = UUID.fromString(text)
}
