// PortedFrom: MC1Tests/ViewModels/PathManagementViewModelDiscoveryTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.path

import com.meshcoreone.android.feature.nodes.support.Harness
import com.meshcoreone.android.feature.nodes.support.OriginalCase
import com.meshcoreone.android.feature.nodes.support.scenario
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PathManagementViewModelDiscoveryTest {
    @Test @OriginalCase("PathManagementViewModelDiscoveryTests::Response with hops presents a success result()")
    fun `Response with hops presents a success result`() = scenario {
        val vm = PathManagementStateHolder(Harness(clock).dependencies, scope)
        vm.seed { it.copy(isDiscovering = true) }
        vm.handleDiscoveryResponse(2)
        val state = vm.state.value
        assertEquals(PathDiscoveryResult.Success(2), state.discoveryResult)
        assertTrue(state.showDiscoveryResult)
        assertFalse(state.isDiscovering)
    }

    @Test @OriginalCase("PathManagementViewModelDiscoveryTests::Response without a decodable path presents no-path-found()")
    fun `Response without a decodable path presents no-path-found`() = scenario {
        val vm = PathManagementStateHolder(Harness(clock).dependencies, scope)
        vm.seed { it.copy(isDiscovering = true) }
        vm.handleDiscoveryResponse(null)
        val state = vm.state.value
        assertEquals(PathDiscoveryResult.NoPathFound, state.discoveryResult)
        assertTrue(state.showDiscoveryResult)
        assertFalse(state.isDiscovering)
    }

    @Test @OriginalCase("PathManagementViewModelDiscoveryTests::Response signals contact refresh while discovering()")
    fun `Response signals contact refresh while discovering`() = scenario {
        val vm = PathManagementStateHolder(Harness(clock).dependencies, scope)
        vm.seed { it.copy(isDiscovering = true) }
        var refreshed = false
        vm.onContactNeedsRefresh = { refreshed = true }
        vm.handleDiscoveryResponse(1)
        assertTrue(refreshed)
    }

    @Test @OriginalCase("PathManagementViewModelDiscoveryTests::Late response after timeout is ignored and does not flip the failure()")
    fun `Late response after timeout is ignored and does not flip the failure`() = scenario {
        val vm = PathManagementStateHolder(Harness(clock).dependencies, scope)
        // Timeout already fired: the failure alert is showing and discovery ended.
        vm.seed { it.copy(isDiscovering = false, discoveryResult = PathDiscoveryResult.NoPathFound, showDiscoveryResult = true) }
        var refreshed = false
        vm.onContactNeedsRefresh = { refreshed = true }
        vm.handleDiscoveryResponse(3)
        val state = vm.state.value
        assertTrue(state.showDiscoveryResult)
        assertEquals(PathDiscoveryResult.NoPathFound, state.discoveryResult)
        assertFalse(state.isDiscovering)
        assertFalse(refreshed)
    }

    @Test @OriginalCase("PathManagementViewModelDiscoveryTests::Late response after cancel is ignored()")
    fun `Late response after cancel is ignored`() = scenario {
        val vm = PathManagementStateHolder(Harness(clock).dependencies, scope)
        vm.seed { it.copy(isDiscovering = true) }
        vm.cancelDiscovery()
        var refreshed = false
        vm.onContactNeedsRefresh = { refreshed = true }
        vm.handleDiscoveryResponse(3)
        val state = vm.state.value
        assertFalse(state.isDiscovering)
        assertNull(state.discoveryResult)
        assertFalse(state.showDiscoveryResult)
        assertFalse(refreshed)
    }
}
