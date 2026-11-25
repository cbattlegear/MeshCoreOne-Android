// PortedFrom: MC1/Views/Settings/NodeConfig/NodeConfigImportViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/NodeConfig/NodeConfigExportViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.app.nodeconfig

import com.meshcoreone.android.core.model.ConfigSections
import com.meshcoreone.android.core.model.MeshCoreNodeConfig

/** A captured connection, never a lookup that can redirect an operation to another radio. */
interface NodeConfigSession {
    val deviceName: String
    val connectionIdentity: Any get() = this
    val reportsWriteOutcomes: Boolean get() = false
    fun requireCurrent()
    suspend fun export(sections: ConfigSections): MeshCoreNodeConfig
    suspend fun current(): MeshCoreNodeConfig
    suspend fun preview(config: MeshCoreNodeConfig, sections: ConfigSections): Boolean
    suspend fun apply(config: MeshCoreNodeConfig, sections: ConfigSections, progress: (NodeConfigProgress) -> Unit)
    suspend fun applyTracked(
        config: MeshCoreNodeConfig,
        sections: ConfigSections,
        onWriteStatus: (NodeConfigWriteStatus) -> Unit,
        progress: (NodeConfigProgress) -> Unit,
    ) = apply(config, sections, progress)
    suspend fun refresh()
    /** Always called after apply/refresh, including failure or cancellation, to release an intentional identity handoff. */
    fun finishImport() = Unit
}

data class NodeConfigProgress(val current: Int, val total: Int, val description: String)
data class NodeConfigWriteStatus(val confirmed: Boolean, val uncertain: Boolean)

interface NodeConfigCodec {
    fun decode(bytes: ByteArray): MeshCoreNodeConfig
    fun encode(config: MeshCoreNodeConfig): ByteArray
}

interface NodeConfigDocuments {
    /** Null means the user dismissed the picker. */
    suspend fun pick(): ByteArray?
    /** True only after the selected document was written and closed. */
    suspend fun save(name: String, bytes: ByteArray): Boolean
}

interface NodeConfigMessages {
    fun error(failure: Throwable): String
    fun cancelled(partial: Boolean): String
    fun failed(failure: Throwable, partial: Boolean): String
    fun uncertain(failure: Throwable?): String = failure?.let { failed(it, true) } ?: cancelled(true)
}
