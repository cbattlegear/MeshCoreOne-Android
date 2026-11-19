// PortedFrom: MC1/Utilities/ChannelJoinFloodScopeApplier.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.deeplinks

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException

object ChannelJoinFloodScopeApplier {
    private val logger = Logger.getLogger("com.mc1.ChannelJoinFloodScopeApplier")

    fun preferredFloodScope(regionScope: String?): ChannelFloodScope? =
        MeshCoreUriParser.normalizedRegionScope(regionScope)?.let(ChannelFloodScope::Region)

    suspend fun applyIfNeeded(
        channel: ChannelDTO,
        regionScope: String?,
        setFloodScope: suspend (UUID, ChannelFloodScope) -> Unit,
    ): ChannelDTO {
        val preferred = preferredFloodScope(regionScope) ?: return channel
        return try {
            setFloodScope(channel.id, preferred)
            channel.withFloodScope(preferred)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.log(Level.SEVERE, "Failed to persist channel flood scope after join", failure)
            channel
        }
    }
}
