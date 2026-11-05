// AndroidOnly: WP-311 Actual process composition of authorized phone fixes and connected-radio fallback.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NodesLocationIntegrationTest : RoomProcessTest() {
    @Test
    fun actualNodesAdapterPrefersAuthorizedPhoneThenLiveRadioAndWithdrawsDisconnectedFallback() = runTest {
        val phone = MutableStateFlow<Coordinate?>(null)
        val h = ContainerHarness(this, store, nodesLocations = phone)
        val observed = mutableListOf<Coordinate?>()
        val collecting = backgroundScope.launch { h.container.nodesFeature.locations.collect { observed += it } }
        try {
            h.settle()
            assertNull(observed.last())
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val device = assertNotNull(h.appState.connectedDevice)
            h.container.deviceEditor.updateDevice(device.copy(latitude = 10.0, longitude = 10.0))
            h.settle()
            assertEquals(Coordinate(10.0, 10.0), observed.last())
            phone.value = Coordinate(20.0, 20.0)
            h.settle()
            assertEquals(Coordinate(20.0, 20.0), observed.last())
            h.container.deviceEditor.updateDevice(device.copy(latitude = 30.0, longitude = 30.0))
            h.settle()
            assertEquals(Coordinate(20.0, 20.0), observed.last(), "Phone fix still takes precedence")
            phone.value = null
            h.settle()
            assertEquals(Coordinate(30.0, 30.0), observed.last(), "Unavailable phone uses current radio, not an old sample")
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.settle()
            assertNull(observed.last(), "Cached offline device coordinates are not a connected-radio fix")
        } finally {
            collecting.cancel()
            h.container.close()
        }
    }
}
