// PortedFrom: MC1Services/Sources/MC1Services/Services/ChannelFloodScopeResolver.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.protocol.model.FloodScope

/**
 * Resolves the [ResolvedFloodScope] to push to the radio for a conversation, combining the
 * per-channel [ChannelFloodScope] preference with the device-wide default flood scope name and
 * the device's un-scoped-send capability.
 *
 * - AllRegions + un-scoped send supported -> Unscoped (true override; firmware v12+)
 * - AllRegions + no un-scoped send -> Scope(Disabled) (best-effort; the radio still floods on
 *   its configured default on older firmware)
 * - Inherit + non-empty device default -> Scope(Region(default))
 * - Inherit + no device default -> Scope(Disabled)
 * - Region(name) -> Scope(Region(name)) (explicit per-channel override)
 */
object ChannelFloodScopeResolver {
    fun resolve(
        channelFloodScope: ChannelFloodScope,
        deviceDefaultFloodScopeName: String?,
        supportsUnscopedFloodSend: Boolean,
    ): ResolvedFloodScope = when (channelFloodScope) {
        ChannelFloodScope.Inherit ->
            if (!deviceDefaultFloodScopeName.isNullOrEmpty()) ResolvedFloodScope.Scope(FloodScope.Region(deviceDefaultFloodScopeName))
            else ResolvedFloodScope.Scope(FloodScope.Disabled)
        ChannelFloodScope.AllRegions ->
            if (supportsUnscopedFloodSend) ResolvedFloodScope.Unscoped else ResolvedFloodScope.Scope(FloodScope.Disabled)
        is ChannelFloodScope.Region -> ResolvedFloodScope.Scope(FloodScope.Region(channelFloodScope.name))
    }
}
