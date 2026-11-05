// AndroidOnly: WP-305 Committed region notifications may re-enter Room, runtime and editor after all write locks release.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.connectivity.device.ConnectedDeviceEditor
import com.meshcoreone.android.core.connectivity.device.RadioPresetMatcher
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.protocol.model.SelfInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceNotificationAuthorityRoomTest : RoomProcessTest() {
    private class CancellingStore(private val actual: RoomPersistenceStore) : PersistenceStoreProtocol by actual {
        var afterCommit: () -> Unit = {}

        override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean =
            actual.saveDeviceIfUnchanged(dto, expected).also { accepted -> if (accepted) afterCommit() }
    }

    @Test fun tokenBoundAppPortAcknowledgesRoomAndNeverRetargetsTheSameRadioSuccessor() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val original = assertNotNull(h.manager.snapshot.value.token)
            val port = h.container.connectionPort
            assertTrue(port.updateDevice(before.info(), "acknowledged-preset", original))
            assertTrue(port.updatePathHashMode(2u, original))
            assertEquals("acknowledged-preset", store.fetchDevice(before.id)!!.appliedRadioPresetID)
            assertEquals(2u.toUByte(), store.fetchDevice(before.id)!!.pathHashMode)
            assertEquals(store.fetchDevice(before.id), port.connectedDevice)
            h.manager.disconnect()
            h.manager.connect(h.target(before.id))
            h.settle()
            val successor = assertNotNull(h.manager.snapshot.value.token)
            val saved = assertNotNull(store.fetchDevice(before.id))
            assertEquals(original.radioId, successor.radioId)
            assertTrue(original != successor)
            assertFalse(port.updateDevice(before.info(), "retired-preset", original))
            assertFalse(port.updatePathHashMode(1u, original))
            assertEquals(saved, store.fetchDevice(before.id))
            assertEquals(saved, port.connectedDevice)
            assertTrue(port.updatePathHashMode(1u, successor))
            assertEquals(1u.toUByte(), store.fetchDevice(before.id)!!.pathHashMode)
            assertEquals(store.fetchDevice(before.id), port.connectedDevice)
        } finally { h.container.close() }
    }

    @Test fun committedNoticeCanReenterRoomRuntimeAndTheSameEditor() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val token = assertNotNull(h.manager.snapshot.value.token)
            var notifications = 0
            lateinit var editor: ConnectedDeviceEditor
            editor = ConnectedDeviceEditor(
                h.container.deviceAccess, store, store, { null },
                RadioPresetMatcher { _, _, _, _ -> emptySet() }, backgroundScope, { java.time.Instant.EPOCH },
                onKnownRegionsChanged = { edit, regions ->
                    assertEquals(token, edit.token)
                    assertEquals(listOf("EU868"), regions)
                    notifications++
                    runBlocking(Dispatchers.IO) {
                        withTimeout(5_000) {
                            assertEquals(regions, store.fetchDevice(before.id)!!.knownRegions.toList())
                            assertTrue(h.manager.withDeviceWriteAuthority(token) { true })
                            assertTrue(editor.updatePathHashMode(2u, token), "The originating editor mutex is released")
                        }
                    }
                },
            )
            assertTrue(editor.addKnownRegion("EU868", token))
            assertEquals(1, notifications)
            assertEquals(2u.toUByte(), store.fetchDevice(before.id)!!.pathHashMode)
            assertEquals(store.fetchDevice(before.id), h.manager.connectedDevice)
        } finally { h.container.close() }
    }

    @Test fun lateCallerCancellationStillDeliversExactlyOneCommittedNoticeOutsideLocks() = runTest {
        val cancelling = CancellingStore(store)
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val token = assertNotNull(h.manager.snapshot.value.token)
            var notifications = 0
            val editor = ConnectedDeviceEditor(
                h.container.deviceAccess, cancelling, store, { null },
                RadioPresetMatcher { _, _, _, _ -> emptySet() }, backgroundScope, { java.time.Instant.EPOCH },
                onKnownRegionsChanged = { edit, regions ->
                    assertEquals(token, edit.token)
                    notifications++
                    runBlocking(Dispatchers.IO) {
                        withTimeout(5_000) {
                            assertEquals(regions, store.fetchDevice(before.id)!!.knownRegions.toList())
                            assertTrue(h.manager.withDeviceWriteAuthority(token) { true })
                        }
                    }
                },
            )
            val update = async {
                val caller = currentCoroutineContext()[Job]!!
                cancelling.afterCommit = { caller.cancel(CancellationException("after real Room commit")) }
                editor.addKnownRegion("EU868", token)
            }
            update.join()
            assertTrue(update.isCancelled)
            assertEquals(1, notifications)
            assertEquals(listOf("EU868"), store.fetchDevice(before.id)!!.knownRegions.toList())
            assertEquals(store.fetchDevice(before.id), h.manager.connectedDevice)
            cancelling.afterCommit = {}
            assertTrue(editor.updatePathHashMode(1u, token))
            assertFalse(h.manager.connectedDevice!!.knownRegions.isEmpty())
        } finally { h.container.close() }
    }

    private fun DeviceDTO.info() = SelfInfo(
        advertisementType = 0u, txPower = txPower, maxTxPower = maxTxPower, publicKey = publicKey,
        latitude = latitude, longitude = longitude, multiAcks = multiAcks,
        advertisementLocationPolicy = advertLocationPolicy, telemetryModeEnvironment = telemetryModeEnv,
        telemetryModeLocation = telemetryModeLoc, telemetryModeBase = telemetryModeBase,
        manualAddContacts = manualAddContacts, radioFrequency = 915.0, radioBandwidth = 250.0,
        radioSpreadingFactor = 10u, radioCodingRate = 5u, name = nodeName,
    )
}
