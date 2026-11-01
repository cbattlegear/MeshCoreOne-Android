// PortedFrom: MC1/State/AppState.swift@db14559b39d32322b06477c6ae676112f583db50
// AndroidOnly: WP-312 Real App graph and trace consumer over the existing Room/fake-radio harness.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.feature.tools.trace.RecentHopsStorage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MapLocationCompositionTest : RoomProcessTest() {
    @Test fun traceConsumerReadsCurrentPhoneThenLocatedRadioWithoutCachingAPriorGeneration() = runTest {
        var phone: Coordinate? = null
        val h = ContainerHarness(this, store, phoneLocation = { phone })
        val recent = object : RecentHopsStorage {
            override fun stringList(key: String): List<String>? = null
            override fun setStringList(key: String, value: List<String>): Unit =
                error("This location test must not write recent-hop preferences")
        }
        val trace = h.container.createToolsFeatureDependencies(
            recent, h.container.createToolsDiagnosticsDependencies(),
        ).trace
        val remote = h.container.createRemoteNodesFeatureDependencies()
        try {
            assertNull(trace.bestAvailableLocation())
            assertNull(remote.bestAvailableLocation)
            phone = Coordinate(35.0, -120.0)
            assertEquals(phone, trace.bestAvailableLocation())
            assertEquals(phone, remote.bestAvailableLocation)
            phone = null
            assertNull(trace.bestAvailableLocation())
            h.links.configureRadio = { radio ->
                radio.respond = { frame -> if (frame[0].toInt() in 2..3) emptyList() else null }
            }
            h.manager.connect(h.target())
            h.eventually("trace location graph READY") { h.manager.connectionState == DeviceConnectionState.READY }
            val device = checkNotNull(h.appState.connectedDevice)
            h.manager.replaceConnectedDevice(device.copy(latitude = 37.0, longitude = -122.0))
            h.eventually("current radio fallback") { trace.bestAvailableLocation() == Coordinate(37.0, -122.0) }
            assertEquals(Coordinate(37.0, -122.0), remote.bestAvailableLocation)
            phone = Coordinate(36.0, -121.0)
            assertEquals(phone, trace.bestAvailableLocation())
            assertEquals(phone, remote.bestAvailableLocation)
            phone = Coordinate(34.0, -119.0)
            assertEquals(phone, trace.bestAvailableLocation())
            phone = null
            assertEquals(Coordinate(37.0, -122.0), trace.bestAvailableLocation())
            h.manager.replaceConnectedDevice(device.copy(latitude = 0.0, longitude = 0.0))
            h.eventually("unlocated radio is not a fix") { trace.bestAvailableLocation() == null }
            h.manager.disconnect()
            h.eventually("trace location graph disconnected") { h.manager.connectionState == DeviceConnectionState.DISCONNECTED }
            assertNull(trace.bestAvailableLocation())
            assertNull(remote.bestAvailableLocation)
            phone = Coordinate(33.0, -118.0)
            assertEquals(phone, trace.bestAvailableLocation())
        } finally {
            h.container.close()
        }
    }
}
