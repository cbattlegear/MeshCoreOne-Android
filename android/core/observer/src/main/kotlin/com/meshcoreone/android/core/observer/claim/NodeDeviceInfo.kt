// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.claim

import com.meshcoreone.android.core.observer.json.jsonObject
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.model.SelfInfo

/**
 * The only node facts sent to Prizm. Built field by field from SELF_INFO and DEVICE_INFO.
 * It deliberately has no field for the BLE PIN (DEVICE_INFO bytes 4-7, [DeviceCapabilities.blePin]) and nothing
 * from CMD_EXPORT_PRIVATE_KEY; adding a field here is a privacy decision, see NodeDeviceInfoTest.
 */
data class NodeDeviceInfo(
    val publicKey: Bytes,
    val name: String,
    val txPower: Int,
    val maxTxPower: Int,
    val radioFreq: Double,
    val radioBw: Double,
    val radioSf: Int,
    val radioCr: Int,
    val fwVerCode: Int,
    val fwVersion: String,
    val fwBuild: String,
    val model: String,
    val clientRepeat: Boolean,
    val pathHashMode: Int,
) {
    fun toJson(): String = jsonObject {
        string("public_key", publicKey.hexString)
        string("name", name)
        int("tx_power", txPower)
        int("max_tx_power", maxTxPower)
        double("radio_freq", radioFreq)
        double("radio_bw", radioBw)
        int("radio_sf", radioSf)
        int("radio_cr", radioCr)
        int("fw_ver_code", fwVerCode)
        string("fw_version", fwVersion)
        string("fw_build", fwBuild)
        string("model", model)
        bool("client_repeat", clientRepeat)
        int("path_hash_mode", pathHashMode)
    }

    companion object {
        fun from(selfInfo: SelfInfo, capabilities: DeviceCapabilities): NodeDeviceInfo = NodeDeviceInfo(
            publicKey = selfInfo.publicKey,
            name = selfInfo.name,
            txPower = selfInfo.txPower.toInt(),
            maxTxPower = selfInfo.maxTxPower.toInt(),
            radioFreq = selfInfo.radioFrequency,
            radioBw = selfInfo.radioBandwidth,
            radioSf = selfInfo.radioSpreadingFactor.toInt(),
            radioCr = selfInfo.radioCodingRate.toInt(),
            fwVerCode = capabilities.firmwareVersion.toInt(),
            fwVersion = capabilities.version,
            fwBuild = capabilities.firmwareBuild,
            model = capabilities.model,
            clientRepeat = capabilities.clientRepeat,
            pathHashMode = capabilities.pathHashMode.toInt(),
        )
    }
}
