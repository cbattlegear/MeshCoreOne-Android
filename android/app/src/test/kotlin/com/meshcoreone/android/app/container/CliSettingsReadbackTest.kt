// AndroidOnly: WP-316 TS-34 CLI consumer through production adapters, SettingsService, protocol and Room.
// ReferenceInput: MC1Tests/Views/Tools/CLI/CLIToolViewModelLocalCommandsTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.tools.diagnostics.ResourcesDiagnosticsText
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliOutputType
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliToolStateHolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
class CliSettingsReadbackTest : RoomProcessTest() {
    @Test fun nameWriteWaitsForVerifiedReadbackBeforeChangingPromptAndRoom() = runTest {
        withCli { h, radio, holder ->
            val original = assertNotNull(h.appState.connectedDevice)
            var readbackRequested = false
            radio.respond = { command ->
                when (command[0].toInt()) {
                    0x08 -> {
                        assertEquals(Bytes.of(0x08) + Bytes.utf8("New Name"), command)
                        listOf(Bytes.of(0))
                    }
                    0x01 -> {
                        readbackRequested = true
                        emptyList()
                    }
                    else -> null
                }
            }
            holder.executeCommand("set name New Name")
            h.eventually("name verification readback") { readbackRequested }
            assertTrue(holder.isWaitingForResponse)
            assertEquals("TestNode", holder.state.value.localDeviceName)
            assertEquals(original.nodeName, assertNotNull(store.fetchDevice(original.id)).nodeName)
            assertFalse(holder.state.value.terminal.outputLines.any {
                it.type == CliOutputType.RESPONSE && it.text == "OK"
            })

            val packet = radio.selfPacket()
            radio.push(packet.prefix(packet.size - Bytes.utf8("TestNode").size) + Bytes.utf8("New Name"))
            h.eventually("verified name reaches CLI and connected device") {
                !holder.isWaitingForResponse &&
                    holder.state.value.localDeviceName == "New Name" &&
                    h.appState.connectedDevice?.nodeName == "New Name"
            }
            assertEquals("New Name> ", holder.promptText)
            awaitPersisted(h, original.id) { it.nodeName == "New Name" }
            assertEquals("New Name", assertNotNull(store.fetchDevice(original.id)).nodeName)
        }
    }

    @Test fun txWriteUsesTypedWireCommandAndActualReadbackBeforePrintingOK() = runTest {
        withTxReadback(readbackPower = 17) { h, holder, originalId, saves ->
            assertEquals("OK", holder.state.value.terminal.outputLines.last().text)
            assertEquals(CliOutputType.RESPONSE, holder.state.value.terminal.outputLines.last().type)
            h.eventually("verified power reaches connected device") { h.appState.connectedDevice?.txPower == 17.toByte() }
            awaitPersisted(h, originalId) { it.txPower == 17.toByte() }
            assertEquals(17.toByte(), assertNotNull(store.fetchDevice(originalId)).txPower)
            h.eventually("committed settings edit is observed") {
                saves.any { it.id == originalId && it.txPower == 17.toByte() }
            }
            assertTrue(saves.any { it.id == originalId && it.txPower == 17.toByte() })
        }
    }

    @Test fun acknowledgedButUnchangedTxReadbackProducesErrorWithoutUpdatingRoom() = runTest {
        withTxReadback(readbackPower = 22) { h, holder, originalId, saves ->
            assertEquals(CliOutputType.ERROR, holder.state.value.terminal.outputLines.last().type)
            assertEquals(
                "Setting was not saved. Expected '17' but device reports '22'.",
                holder.state.value.terminal.outputLines.last().text,
            )
            assertFalse(holder.state.value.terminal.outputLines.any {
                it.type == CliOutputType.RESPONSE && it.text == "OK"
            })
            assertEquals(22.toByte(), h.appState.connectedDevice?.txPower)
            assertEquals(22.toByte(), assertNotNull(store.fetchDevice(originalId)).txPower)

            h.eventually("committed readback value is observed") {
                saves.any { it.id == originalId && it.txPower == 22.toByte() }
            }
            // Closing drains already-published events; both their consumer and save launches use this scheduler.
            assertNotNull(h.sessions.current).settingsService.finishEvents()
            testScheduler.runCurrent()
            assertFalse(saves.any { it.id == originalId && it.txPower != 22.toByte() })
            assertEquals(22.toByte(), h.appState.connectedDevice?.txPower)
            assertEquals(22.toByte(), assertNotNull(store.fetchDevice(originalId)).txPower)
        }
    }

    private suspend fun TestScope.withTxReadback(
        readbackPower: Int,
        assertions: suspend (ContainerHarness, CliToolStateHolder, java.util.UUID, List<DeviceDTO>) -> Unit,
    ) {
        val saves = mutableListOf<DeviceDTO>()
        withCli(onCommittedDeviceWrite = { saves += it }) { h, radio, holder ->
            val originalId = assertNotNull(h.appState.connectedDevice).id
            val commands = mutableListOf<Int>()
            radio.respond = { command ->
                commands += command[0].toInt()
                when (command[0].toInt()) {
                    0x0c -> {
                        assertEquals(Bytes.of(0x0c, 17), command)
                        listOf(Bytes.of(0))
                    }
                    0x01 -> {
                        val packet = radio.selfPacket().toByteArray()
                        packet[2] = readbackPower.toByte()
                        listOf(Bytes(packet))
                    }
                    else -> null
                }
            }
            holder.executeCommand("set tx 17")
            h.eventually("verified tx command finishes") { !holder.isWaitingForResponse }
            assertEquals(listOf(0x0c, 0x01), commands)
            assertions(h, holder, originalId, saves)
        }
    }

    private suspend fun TestScope.awaitPersisted(
        h: ContainerHarness,
        id: java.util.UUID,
        matches: (DeviceDTO) -> Boolean,
    ) {
        val saved = backgroundScope.async {
            while (!matches(assertNotNull(store.fetchDevice(id)))) delay(1)
        }
        try {
            h.eventually("verified settings reach Room") { saved.isCompleted }
            saved.await()
        } finally {
            saved.cancel()
        }
    }

    private suspend fun TestScope.withCli(
        onCommittedDeviceWrite: (DeviceDTO) -> Unit = {},
        assertions: suspend (ContainerHarness, FirmwareRadio, CliToolStateHolder) -> Unit,
    ) {
        val observedStore = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                val committed = store.saveDeviceIfUnchanged(dto, expected)
                if (committed) onCommittedDeviceWrite(dto)
                return committed
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = observedStore)
        var holder: CliToolStateHolder? = null
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val radio = h.links.radios.single()
            var prefetched = false
            radio.respond = { command ->
                if (command[0].toInt() == 0x28) {
                    prefetched = true
                    listOf(Bytes.of(0x15))
                } else null
            }
            val dependencies = h.container.createToolsDiagnosticsDependencies()
            val cli = CliToolStateHolder(
                backgroundScope,
                ResourcesDiagnosticsText(RuntimeEnvironment.getApplication().resources),
                errors = dependencies.cliErrors,
            )
            holder = cli
            cli.configure(dependencies.cli, assertNotNull(h.appState.connectedDevice).nodeName)
            h.eventually("CLI custom-variable prefetch") { prefetched }
            h.settle()
            assertions(h, radio, cli)
        } finally {
            holder?.cleanup()
            h.container.close()
        }
    }
}
