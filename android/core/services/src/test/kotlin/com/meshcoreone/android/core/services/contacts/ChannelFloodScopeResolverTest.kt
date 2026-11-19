// PortedFrom: MC1Services/Tests/MC1ServicesTests/ChannelFloodScopeResolverTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.protocol.model.FloodScope
import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ChannelFloodScopeResolverTest {
    @TestFactory
    fun resolution(): List<DynamicTest> = channelsCases(
        "ChannelFloodScopeResolverTests",
        ".inherit with device default set resolves to .scope(.region(default))" to {
            assertEquals(ResolvedFloodScope.Scope(FloodScope.Region("Germany")), resolve(ChannelFloodScope.Inherit, "Germany", true))
        },
        ".inherit with no device default resolves to .scope(.disabled)" to {
            assertEquals(ResolvedFloodScope.Scope(FloodScope.Disabled), resolve(ChannelFloodScope.Inherit, null, true))
        },
        ".inherit with empty-string default treats it as no default" to {
            assertEquals(ResolvedFloodScope.Scope(FloodScope.Disabled), resolve(ChannelFloodScope.Inherit, "", true))
        },
        ".allRegions on firmware v12+ resolves to .unscoped (true override)" to {
            assertEquals(ResolvedFloodScope.Unscoped, resolve(ChannelFloodScope.AllRegions, "Germany", true))
            assertEquals(ResolvedFloodScope.Unscoped, resolve(ChannelFloodScope.AllRegions, null, true))
        },
        ".allRegions on older firmware falls back to .scope(.disabled)" to {
            assertEquals(ResolvedFloodScope.Scope(FloodScope.Disabled), resolve(ChannelFloodScope.AllRegions, "Germany", false))
            assertEquals(ResolvedFloodScope.Scope(FloodScope.Disabled), resolve(ChannelFloodScope.AllRegions, null, false))
        },
        ".region(name) resolves to that region regardless of default or capability" to {
            assertEquals(ResolvedFloodScope.Scope(FloodScope.Region("France")), resolve(ChannelFloodScope.Region("France"), "Germany", true))
            assertEquals(ResolvedFloodScope.Scope(FloodScope.Region("France")), resolve(ChannelFloodScope.Region("France"), null, false))
        },
    )

    private fun resolve(scope: ChannelFloodScope, deviceDefault: String?, supportsUnscoped: Boolean): ResolvedFloodScope =
        ChannelFloodScopeResolver.resolve(scope, deviceDefault, supportsUnscoped)
}
