// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.claim

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.model.SelfInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

object ClaimFixtures {
    const val BLE_PIN = 482913u
    val nodeKey = Bytes(ByteArray(32) { (it + 1).toByte() })

    val selfInfo = SelfInfo(
        advertisementType = 1u, txPower = 20, maxTxPower = 22, publicKey = nodeKey, latitude = 46.8, longitude = -100.7,
        multiAcks = 0u, advertisementLocationPolicy = 0u, telemetryModeEnvironment = 0u, telemetryModeLocation = 0u,
        telemetryModeBase = 0u, manualAddContacts = false, radioFrequency = 910.525, radioBandwidth = 62.5,
        radioSpreadingFactor = 7u, radioCodingRate = 5u, name = "Josh \"Mobile\" 1",
    )
    val capabilities = DeviceCapabilities(
        firmwareVersion = 11u, maxContacts = 350, maxChannels = 40, blePin = BLE_PIN, firmwareBuild = "27 Nov 2026",
        model = "Heltec V3", version = "v1.11.0", clientRepeat = true, pathHashMode = 1u,
    )
    val deviceInfo = NodeDeviceInfo.from(selfInfo, capabilities)
}

class NodeDeviceInfoTest {
    @Test fun serializesEveryContractDeviceKey() {
        assertEquals(
            "{\"name\":\"Josh \\\"Mobile\\\" 1\",\"model\":\"Heltec V3\",\"fwVersion\":\"v1.11.0\",\"fwBuild\":\"27 Nov 2026\"," +
                "\"fwVerCode\":11,\"txPowerDbm\":20,\"maxTxPowerDbm\":22,\"radio\":{\"freqMHz\":910.525,\"bwKHz\":62.5,\"sf\":7,\"cr\":5}," +
                "\"clientRepeat\":true,\"pathHashMode\":1}",
            ClaimFixtures.deviceInfo.toJson(),
        )
    }

    @Test fun neverContainsTheBlePin() {
        val json = ClaimFixtures.deviceInfo.toJson()
        val pin = ClaimFixtures.BLE_PIN
        for (rendering in listOf(pin.toString(), pin.toString(16), pin.toString(16).uppercase(), pin.toString(16).padStart(8, '0'))) {
            assertFalse(json.contains(rendering), "PIN rendering $rendering leaked")
        }
        assertFalse(json.contains("pin", ignoreCase = true))
        assertFalse(json.contains("private", ignoreCase = true))
    }

    @Test fun pinOrKeyMaterialCannotBeAddedWithoutFailingThis() {
        val allowed = setOf(
            "publicKey", "name", "txPower", "maxTxPower", "radioFreq", "radioBw", "radioSf", "radioCr", "fwVerCode",
            "fwVersion", "fwBuild", "model", "clientRepeat", "pathHashMode",
        )
        val fields = NodeDeviceInfo::class.java.declaredFields.filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertEquals(allowed, fields.map { it.name }.toSet())
        assertTrue(fields.none { it.name.contains("pin", ignoreCase = true) || it.name.contains("private", ignoreCase = true) })
    }

    @Test fun claimFinishBodyCarriesNoPinEither() {
        val request = com.meshcoreone.android.core.observer.prizm.ClaimFinishRequest(
            ClaimFixtures.nodeKey, "nonce-abcdef12", Bytes(ByteArray(64)), NodeType.VEHICLE, ClaimFixtures.deviceInfo, false,
        )
        val json = request.toJson()
        assertFalse(json.contains(ClaimFixtures.BLE_PIN.toString()))
        assertTrue(json.startsWith("{\"pk\":\"${ClaimFixtures.nodeKey.hexString}\",\"nonce\":\"nonce-abcdef12\",\"signature\":\"${"00".repeat(64)}\","))
        assertTrue(json.contains("\"nodeType\":\"vehicle\""))
        assertTrue(json.endsWith("\"takeOver\":false}"))
        for (old in listOf("node_key", "node_type", "confirm_takeover", "public_key")) assertFalse(json.contains(old), old)
        assertEquals(setOf("handheld", "vehicle", "home", "portable"), NodeType.entries.map { it.wireName }.toSet())
    }
}
