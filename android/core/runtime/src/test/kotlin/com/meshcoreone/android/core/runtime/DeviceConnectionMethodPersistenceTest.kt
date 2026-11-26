// PortedFrom: MC1Services/Tests/MC1ServicesTests/DevicePublicKeyDeduplicationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.RadioWiFiState
import com.meshcoreone.android.core.contracts.domain.RadioWiFiResetObservation
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.DeviceIdentity
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class DeviceConnectionMethodPersistenceTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `ready successor can confirm reboot only after prior logical link ends`() = runTest {
        withFixture {
            val wifi = ConnectionTarget.WiFi("radio.test", 5001u)
            connect(wifi)
            val first = services.single()
            val state = first.inputs.connection.radioWiFiState
            val mutation = assertNotNull(state.beginMutation(null))
            state.settle(mutation, RadioWiFiState.MutationOutcome.ACCEPTED)
            val candidate = assertNotNull(state.prepareExpectedReboot(
                first.inputs.connection.radioWiFiContextId,
                observation = RadioWiFiResetObservation(1000u, Duration.ZERO, Duration.ZERO),
            ))
            state.completeRebootSubmission(candidate, succeeded = true)
            onFactory = { service, _ ->
                service.wifiConfirmation = { id, whileCurrent ->
                    val revision = assertNotNull(state.stableRevision())
                    whileCurrent {
                        state.clearExpectedReboot(
                            id, service.inputs.connection.radioWiFiContextId, revision,
                            RadioWiFiResetObservation(1u, 1.seconds, 1.seconds),
                        )
                    }
                }
            }
            checkNotNull(links.last().callbacks).onDisconnected(LinkFailure.ConnectionFailed("TCP reset"))
            runCurrent()
            assertEquals(2, services.size)
            assertFalse(state.isRebootPending)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `explicit connection discards reboot proof but retains pending changes`() = runTest {
        withFixture {
            val wifi = ConnectionTarget.WiFi("radio.test", 5001u)
            connect(wifi)
            val first = services.single()
            val state = first.inputs.connection.radioWiFiState
            state.settle(assertNotNull(state.beginMutation(null)), RadioWiFiState.MutationOutcome.ACCEPTED)
            val candidate = assertNotNull(state.prepareExpectedReboot(first.inputs.connection.radioWiFiContextId))
            state.completeRebootSubmission(candidate, succeeded = true)
            manager.connect(wifi)
            assertNull(state.expectedRebootId)
            assertTrue(state.isRebootPending)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `identity commit cancellation retains pending WiFi evidence for the next graph`() = runTest {
        withFixture {
            val ble = platform.target
            connect(ble)
            val old = services.single()
            val state = old.inputs.connection.radioWiFiState
            val mutation = assertNotNull(state.beginMutation(null))
            val canonical = RadioId(UUID.randomUUID())
            devices.ghostResult = canonical
            devices.afterGhostCommit = {
                currentCoroutineContext()[Job]!!.cancel(CancellationException("after commit"))
            }
            val reconciliation = async { manager.reconcileIdentity(old.token) }
            runCurrent()
            assertFailsWith<CancellationException> { reconciliation.await() }
            assertNull(state.beginMutation(null))
            state.settle(mutation, RadioWiFiState.MutationOutcome.UNCERTAIN)
            devices.ghostResult = null
            devices.afterGhostCommit = {}
            manager.disconnect()
            connect(ble)
            val replacement = services.last().inputs.connection.radioWiFiState
            assertEquals(canonical, replacement.radioId)
            assertTrue(replacement.isRebootPending)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `identity reconciliation transfers uncertain WiFi state before graph rebuild`() = runTest {
        withFixture {
            val ble = platform.target
            connect(ble)
            val old = services.single()
            val state = old.inputs.connection.radioWiFiState
            state.settle(assertNotNull(state.beginMutation(null)), RadioWiFiState.MutationOutcome.UNCERTAIN)
            val canonical = RadioId(UUID.randomUUID())
            devices.ghostResult = canonical
            assertEquals(canonical, manager.reconcileIdentity(old.token))
            devices.ghostResult = null
            runCurrent()
            val replacement = services.last().inputs.connection.radioWiFiState
            assertEquals(canonical, replacement.radioId)
            assertTrue(replacement.isRebootPending)
            assertNull(state.beginMutation(null))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `live BLE WiFi BLE handoff retains one saved radio and retires stale loss callbacks`() = runTest {
        withFixture {
            val ble = platform.target
            val wifi = ConnectionTarget.WiFi("radio.test", 5001u)
            connect(ble)
            val first = services.single().token
            val wifiState = services.single().inputs.connection.radioWiFiState
            wifiState.settle(assertNotNull(wifiState.beginMutation(null)), RadioWiFiState.MutationOutcome.UNCERTAIN)

            connect(wifi)
            val second = services.last().token
            val wifiLoss = checkNotNull(links.last().callbacks)
            assertEquals(ble.deviceId, manager.connectedDevice?.id)
            assertEquals(first.radioId, second.radioId)
            assertSame(wifiState, services.last().inputs.connection.radioWiFiState)
            assertTrue(wifiState.isRebootPending)
            assertNotEquals(first, second)
            assertEquals(TransportType.WIFI, links.last().type)

            connect(ble)
            val third = services.last().token
            assertEquals(first.radioId, third.radioId)
            assertSame(wifiState, services.last().inputs.connection.radioWiFiState)
            assertNotEquals(second, third)
            assertEquals(TransportType.BLUETOOTH, links.last().type)
            assertEquals(1, services[0].teardowns)
            assertEquals(1, services[1].teardowns)
            assertEquals(0, services[2].teardowns)
            assertEquals(
                listOf(ConnectionMethod.WiFi(wifi.host, wifi.port), ConnectionMethod.Bluetooth(ble.deviceId)),
                devices.rows.getValue(ble.deviceId).connectionMethods,
            )
            assertEquals(listOf(ble.deviceId), devices.rows.keys.toList())

            connect(ble)
            wifiLoss.onDisconnected(LinkFailure.ConnectionFailed("retired TCP socket"))
            runCurrent()
            assertEquals(0, lossCount)
            assertEquals(3, links.size)
            assertEquals(third, manager.snapshot.value.token)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `WiFi loss recovers the saved radio once and keeps its Bluetooth route`() = runTest {
        withFixture {
            val ble = platform.target
            val wifi = ConnectionTarget.WiFi("radio.test", 5001u)
            connect(ble)
            connect(wifi)
            val previous = services.last().token
            val callbacks = checkNotNull(links.last().callbacks)
            callbacks.onDisconnected(LinkFailure.ConnectionFailed("TCP reset"))
            callbacks.onDisconnected(LinkFailure.ConnectionFailed("duplicate TCP reset"))
            runCurrent()

            assertEquals(1, lossCount)
            assertEquals(3, links.size)
            assertEquals(TransportType.WIFI, links.last().type)
            assertEquals(ble.deviceId, manager.connectedDevice?.id)
            assertEquals(previous.radioId, services.last().token.radioId)
            assertNotEquals(previous, services.last().token)
            assertEquals(
                listOf(ConnectionMethod.Bluetooth(ble.deviceId), ConnectionMethod.WiFi(wifi.host, wifi.port)),
                devices.rows.getValue(ble.deviceId).connectionMethods,
            )
            assertEquals(services.last().token, manager.snapshot.value.token)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `selected radio survives duplicate public keys through handoff and WiFi recovery`() = runTest {
        withFixture {
            val ble = platform.target
            val wifi = ConnectionTarget.WiFi("radio.test", 5001u)
            connect(ble)
            val original = checkNotNull(devices.rows.remove(ble.deviceId))
            val other = original.copy(
                id = UUID.randomUUID(), radioId = RadioId(UUID.randomUUID()), nodeName = "Other saved device",
                connectionMethods = emptyList<ConnectionMethod>().snapshot(), isActive = false,
            )
            devices.rows[other.id] = other
            devices.rows[original.id] = original
            devices.ghostResult = other.radioId

            manager.switchConnectionLink(wifi, original.id, original.radioId)
            runCurrent()
            assertEquals(original.id, manager.connectedDevice?.id)
            assertEquals(original.radioId, manager.connectedDevice?.radioId)
            assertEquals(other.connectionMethods, devices.rows.getValue(other.id).connectionMethods)
            assertEquals(other.nodeName, devices.rows.getValue(other.id).nodeName)

            checkNotNull(links.last().callbacks).onDisconnected(LinkFailure.ConnectionFailed("TCP reset"))
            runCurrent()
            assertEquals(original.id, manager.connectedDevice?.id)
            assertEquals(original.radioId, manager.connectedDevice?.radioId)
            assertEquals(TransportType.WIFI, links.last().type)

            manager.switchConnectionLink(ble, original.id, original.radioId)
            runCurrent()
            assertEquals(original.id, manager.connectedDevice?.id)
            assertEquals(original.radioId, manager.connectedDevice?.radioId)
            assertEquals(setOf(original.id, other.id), devices.rows.keys)
            assertEquals(other.connectionMethods, devices.rows.getValue(other.id).connectionMethods)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `startup restores the selected WiFi radio when another device shares its public key`() = runTest {
        withFixture {
            val wifi = ConnectionMethod.WiFi("radio.test", 5001u)
            val selected = DeviceDTO(
                id = UUID.randomUUID(), radioId = RadioId(UUID.randomUUID()), publicKey = publicKey,
                nodeName = "Selected radio", connectionMethods = listOf<ConnectionMethod>(wifi).snapshot(),
                isActive = true,
            )
            val other = selected.copy(
                id = UUID.randomUUID(), radioId = RadioId(UUID.randomUUID()), nodeName = "Other saved device",
                connectionMethods = emptyList<ConnectionMethod>().snapshot(), isActive = false,
            )
            devices.rows[other.id] = other
            devices.rows[selected.id] = selected
            devices.ghostResult = other.radioId
            last.persist(selected.id, selected.radioId, selected.nodeName)

            manager.activate()
            runCurrent()

            assertEquals(DeviceConnectionState.READY, manager.connectionState)
            assertEquals(selected.id, manager.connectedDevice?.id)
            assertEquals(selected.radioId, manager.connectedDevice?.radioId)
            assertEquals(setOf(selected.id, other.id), devices.rows.keys)
            assertEquals(other, devices.rows.getValue(other.id))
            assertFalse("reconcile" in devices.calls)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a stale link selection cannot undo a user disconnect`() = runTest {
        withFixture {
            connect(platform.target)
            val original = checkNotNull(manager.connectedDevice)
            manager.disconnect()

            assertFailsWith<CancellationException> {
                manager.switchConnectionLink(
                    ConnectionTarget.WiFi("radio.test", 5001u), original.id, original.radioId,
                )
            }
            runCurrent()
            assertEquals(ConnectionIntent.UserDisconnected, manager.connectionIntent)
            assertEquals(ConnectionIntent.UserDisconnected, last.restoredIntent())
            assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
            assertEquals(1, links.size)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `same radio handoff rejects a different peer and preserves the saved device`() = runTest {
        withFixture {
            val ble = platform.target
            connect(ble)
            val original = checkNotNull(manager.connectedDevice)
            createRadio = { TestRadio().apply { key = Bytes(ByteArray(32) { 0x42 }) } }

            assertFailsWith<ConnectionError.ConnectionFailed> {
                manager.switchConnectionLink(ConnectionTarget.WiFi("radio.test", 5001u), original.id, original.radioId)
            }
            assertEquals(original, devices.rows.getValue(original.id))
            assertEquals(setOf(original.id), devices.rows.keys)

            createRadio = { TestRadio() }
            manager.switchConnectionLink(ble, original.id, original.radioId)
            runCurrent()
            assertEquals(original.radioId, manager.connectedDevice?.radioId)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `adopted Bluetooth handoff still rejects a different peer`() = runTest {
        withFixture {
            val ble = platform.target
            connect(ble)
            val original = checkNotNull(manager.connectedDevice)
            manager.switchConnectionLink(ConnectionTarget.WiFi("radio.test", 5001u), original.id, original.radioId)
            runCurrent()
            platform.state = platform.state.copy(systemConnected = true)
            platform.adoptionSucceeds = true
            val wrongKey = Bytes(ByteArray(32) { 0x42 })
            createRadio = { TestRadio().apply { key = wrongKey } }

            manager.switchConnectionLink(ble, original.id, original.radioId)
            assertEquals(1, platform.adopts)
            assertEquals(DeviceConnectionState.CONNECTING, manager.connectionState)
            checkNotNull(links.last().callbacks).onReconnected()
            runCurrent()

            assertTrue(diagnostics.any {
                it is RuntimeDiagnostic.Failure && it.operation == "reconnect.rebuild" &&
                    it.cause is ConnectionError.ConnectionFailed
            })
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(diagnostics.any {
                it is RuntimeDiagnostic.Failure && it.operation == "reconnect.retry" &&
                    it.cause is ConnectionError.ConnectionFailed
            })
            assertNotEquals(DeviceConnectionState.READY, manager.connectionState)
            assertEquals(original.publicKey, devices.rows.getValue(original.id).publicKey)
            assertNull(devices.fetchDevice(wrongKey))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a selected WiFi radio keeps its identity after Bluetooth endpoint migration and rebuild`() = runTest {
        withFixture {
            connect(ConnectionTarget.WiFi("radio.test", 5001u))
            val original = checkNotNull(manager.connectedDevice)
            val ble = platform.target

            manager.switchConnectionLink(ble, original.id, original.radioId)
            runCurrent()
            assertEquals(ble.deviceId, manager.connectedDevice?.id)
            assertEquals(original.radioId, manager.connectedDevice?.radioId)
            assertNull(devices.rows[original.id])

            manager.teardownSessionForReconnect()
            manager.rebuildSession(ble.deviceId)
            runCurrent()
            assertEquals(DeviceConnectionState.READY, manager.connectionState)
            assertEquals(ble.deviceId, manager.connectedDevice?.id)
            assertEquals(original.radioId, manager.connectedDevice?.radioId)
            assertEquals(setOf(ble.deviceId), devices.rows.keys)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stale link selection cannot retire a successor or cancel its reboot proof`() = runTest {
        withFixture {
            connect(platform.target)
            val original = checkNotNull(manager.connectedDevice)
            createRadio = { TestRadio().apply { key = Bytes(ByteArray(32) { 0x42 }) } }
            connect(target())
            val successor = services.last()
            val state = successor.inputs.connection.radioWiFiState
            state.settle(assertNotNull(state.beginMutation(null)), RadioWiFiState.MutationOutcome.ACCEPTED)
            val candidate = assertNotNull(state.prepareExpectedReboot(successor.inputs.connection.radioWiFiContextId))
            state.completeRebootSubmission(candidate, succeeded = true)
            val linksBefore = links.size

            assertFailsWith<CancellationException> {
                manager.switchConnectionLink(ConnectionTarget.WiFi("radio.test", 5001u), original.id, original.radioId)
            }
            assertEquals(successor.token, manager.snapshot.value.token)
            assertEquals(linksBefore, links.size)
            assertEquals(candidate, state.expectedRebootId)
        }
    }

    @TestFactory
    fun sourceCases() = listOf(
        original("DevicePublicKeyDeduplicationTests",
            "createDevice persists the Bluetooth method supplied by the BLE connect path") {
            withFixture {
                val endpoint = target(UUID.fromString("10000000-0000-4000-8000-000000000001"))

                connect(endpoint)

                val saved = devices.rows.values.single()
                assertEquals(endpoint.deviceId, saved.id)
                assertEquals(RadioId(DeviceIdentity.deriveUUID(publicKey)), saved.radioId)
                assertNotEquals(endpoint.deviceId, saved.radioId.value)
                assertEquals(listOf<ConnectionMethod>(ConnectionMethod.Bluetooth(endpoint.deviceId)), saved.connectionMethods)
                assertEquals(publicKey, saved.publicKey)
                assertEquals(saved, manager.connectedDevice)
                assertEquals(saved, services.single().inputs.device)
                assertEquals(saved.radioId, services.single().token.radioId)
                assertEquals(1, devices.calls.count { it == "save" })
                assertEquals(endpoint.deviceId, last.read().deviceId)
                assertEquals(saved.radioId, last.read().radioId)
                assertEquals(endpoint.deviceId, last.read().bondDeviceId)
            }
        },
        original("DevicePublicKeyDeduplicationTests",
            "createDevice merges a Bluetooth method with an existing WiFi method") {
            withFixture {
                val wifi = ConnectionTarget.WiFi("10.0.0.2", 5000u)
                connect(wifi)
                val wifiDevice = devices.rows.values.single()
                val wifiMethod = ConnectionMethod.WiFi(wifi.host, wifi.port)
                assertEquals(listOf<ConnectionMethod>(wifiMethod), wifiDevice.connectionMethods)
                assertEquals(wifiDevice, services.single().inputs.device)
                assertTrue(manager.disconnect().isComplete)
                val endpoint = target(UUID.fromString("10000000-0000-4000-8000-000000000002"))

                connect(endpoint)

                val saved = devices.rows.values.single()
                assertEquals(endpoint.deviceId, saved.id)
                assertEquals(wifiDevice.radioId, saved.radioId)
                assertNotEquals(endpoint.deviceId, saved.radioId.value)
                assertEquals(listOf(wifiMethod, ConnectionMethod.Bluetooth(endpoint.deviceId)), saved.connectionMethods)
                assertEquals(publicKey, saved.publicKey)
                assertEquals(saved, manager.connectedDevice)
                assertEquals(saved, services.last().inputs.device)
                assertEquals(wifiDevice.radioId, services.last().token.radioId)
                assertNull(devices.rows[wifiDevice.id])
                assertEquals(2, devices.calls.count { it == "save" })
                assertEquals(1, devices.calls.count { it == "delete" })
                assertEquals(endpoint.deviceId, last.read().deviceId)
                assertEquals(wifiDevice.radioId, last.read().radioId)
            }
        },
        original("DevicePublicKeyDeduplicationTests",
            "createDevice replaces a stale Bluetooth peripheral UUID instead of accumulating") {
            withFixture {
                val staleId = UUID.fromString("10000000-0000-4000-8000-000000000003")
                val stableRadio = RadioId(UUID.fromString("20000000-0000-4000-8000-000000000001"))
                val wifi = ConnectionMethod.WiFi("10.0.0.2", 5000u, "Saved WiFi")
                val prior = DeviceDTO(
                    id = staleId, radioId = stableRadio, publicKey = publicKey, nodeName = "Old",
                    connectionMethods = listOf(wifi, ConnectionMethod.Bluetooth(staleId, "Old")).snapshot(),
                    lastContactSync = 0xf0000000u, knownRegions = listOf("regionA", "regionB").snapshot(),
                    appliedRadioPresetID = "br", ocvPreset = "custom", customOCVArrayString = "[4200,3300]",
                )
                devices.rows[prior.id] = prior
                val endpoint = target(UUID.fromString("10000000-0000-4000-8000-000000000004"))

                connect(endpoint)

                val saved = devices.rows.values.single()
                assertEquals(endpoint.deviceId, saved.id)
                assertEquals(stableRadio, saved.radioId)
                assertNotEquals(endpoint.deviceId, saved.radioId.value)
                assertEquals(listOf(ConnectionMethod.Bluetooth(endpoint.deviceId)),
                    saved.connectionMethods.filterIsInstance<ConnectionMethod.Bluetooth>())
                assertEquals(listOf(wifi), saved.connectionMethods.filterIsInstance<ConnectionMethod.WiFi>())
                assertEquals(2, saved.connectionMethods.size)
                assertEquals(publicKey, saved.publicKey)
                assertEquals(prior.lastContactSync, saved.lastContactSync)
                assertEquals(prior.knownRegions, saved.knownRegions)
                assertEquals(prior.appliedRadioPresetID, saved.appliedRadioPresetID)
                assertEquals(prior.ocvPreset, saved.ocvPreset)
                assertEquals(prior.customOCVArrayString, saved.customOCVArrayString)
                assertEquals(saved, manager.connectedDevice)
                assertEquals(saved, services.single().inputs.device)
                assertEquals(stableRadio, services.single().token.radioId)
                assertNull(devices.rows[staleId])
                assertEquals(1, devices.calls.count { it == "save" })
                assertEquals(1, devices.calls.count { it == "delete" })
                assertTrue(devices.calls.indexOf("save") < devices.calls.indexOf("delete"))
                assertEquals(endpoint.deviceId, last.read().deviceId)
                assertEquals(stableRadio, last.read().radioId)
            }
        },
    )
}
