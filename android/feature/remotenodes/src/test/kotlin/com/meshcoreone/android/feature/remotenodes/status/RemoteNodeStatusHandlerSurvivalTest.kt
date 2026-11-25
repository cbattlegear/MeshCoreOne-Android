// PortedFrom: MC1Tests/ViewModels/RemoteNodeStatusHandlerSurvivalTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ContactMessage
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import com.meshcoreone.android.feature.remotenodes.support.VirtualClock
import com.meshcoreone.android.feature.remotenodes.support.runSuspend
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

/**
 * The Swift suite builds a real `ServiceContainer` and uses its admin services' handler slots. The
 * feature cannot construct core:services, so [FakeStatusAdmin] reproduces the WP-210 slot semantics
 * (set replaces, clearStatusHandlers keeps the CLI slot, clearHandlers drops it, invoke ignores empty).
 */
class RemoteNodeStatusHandlerSurvivalTest {
    private val clock = VirtualClock()
    private val contactPublicKey = Bytes(ByteArray(32) { 0x7A })
    private val snapshots = SnapshotServiceFake(WindowedSnapshotPersister(clock), clock)

    private fun message() = ContactMessage(
        senderPublicKeyPrefix = contactPublicKey.prefix(6), pathLength = 0u, textType = 0u,
        senderTimestamp = Instant.EPOCH, signature = null, text = "cli response", snr = null,
    )

    private fun contact() = contact(publicKey = contactPublicKey, typeRawValue = 2u)

    private fun zeroStatus() = StatusResponse(
        publicKeyPrefix = contactPublicKey.prefix(6), battery = 0, txQueueLength = 0, noiseFloor = 0, lastRSSI = 0,
        packetsReceived = 0u, packetsSent = 0u, airtime = 0u, uptime = 0u, sentFlood = 0u, sentDirect = 0u,
        receivedFlood = 0u, receivedDirect = 0u, fullEvents = 0, lastSNR = 0.0, directDuplicates = 0,
        floodDuplicates = 0, rxAirtime = 0u,
    )

    private fun repeater(service: FakeStatusAdmin, withServices: Boolean) =
        RepeaterStatusStateHolder(clock, FakeFaults, CoroutineScope(Dispatchers.Unconfined)).apply {
            configure(
                repeaterAdmin = { service },
                contactOcv = { if (withServices) FakeContactOcv() else null },
                nodeSnapshots = { if (withServices) snapshots else null },
                deviceHashSize = { null },
            )
        }

    private fun room(service: FakeStatusAdmin, withServices: Boolean) = RoomStatusStateHolder(clock, FakeFaults).apply {
        configure(
            roomAdmin = { service },
            contactOcv = { if (withServices) FakeContactOcv() else null },
            nodeSnapshots = { if (withServices) snapshots else null },
        )
    }

    @Test
    @OriginalCase("RemoteNodeStatusHandlerSurvivalTests::Repeater registerHandlers preserves a previously-set CLI handler()", "platform-adaptation")
    fun `repeater registerHandlers preserves the CLI handler`() = runSuspend {
        val service = FakeStatusAdmin()
        val flag = AtomicBoolean(false)
        service.setCLIHandler { _, _ -> flag.set(true) }

        repeater(service, withServices = false).registerHandlers()
        service.invokeCLIHandler(message(), contact())

        assertTrue(flag.get(), "CLI handler should survive the status view model registering its own handlers")
    }

    @Test
    @OriginalCase("RemoteNodeStatusHandlerSurvivalTests::Room registerHandlers preserves a previously-set CLI handler()", "platform-adaptation")
    fun `room registerHandlers preserves the CLI handler`() = runSuspend {
        val service = FakeStatusAdmin()
        val flag = AtomicBoolean(false)
        service.setCLIHandler { _, _ -> flag.set(true) }

        room(service, withServices = false).registerHandlers()
        service.invokeCLIHandler(message(), contact())

        assertTrue(flag.get(), "CLI handler should survive the status view model registering its own handlers")
    }

    @Test
    @OriginalCase("RemoteNodeStatusHandlerSurvivalTests::Repeater clearStatusHandlers leaves the CLI handler firing()", "platform-adaptation")
    fun `repeater clearStatusHandlers leaves the CLI handler firing`() = runSuspend {
        val service = FakeStatusAdmin()
        val cliFlag = AtomicBoolean(false)
        val statusFlag = AtomicBoolean(false)
        service.setCLIHandler { _, _ -> cliFlag.set(true) }

        val model = repeater(service, withServices = true)
        model.registerHandlers()
        service.setStatusHandler { statusFlag.set(true) }
        model.clearStatusHandlers()

        service.invokeCLIHandler(message(), contact())
        service.invokeStatusHandler(zeroStatus())

        assertTrue(cliFlag.get(), "CLI handler should survive clearing the status-surface handlers")
        assertFalse(statusFlag.get(), "status handler should no longer fire after clearStatusHandlers")
    }

    @Test
    @OriginalCase("RemoteNodeStatusHandlerSurvivalTests::Room clearStatusHandlers leaves the CLI handler firing()", "platform-adaptation")
    fun `room clearStatusHandlers leaves the CLI handler firing`() = runSuspend {
        val service = FakeStatusAdmin()
        val cliFlag = AtomicBoolean(false)
        val statusFlag = AtomicBoolean(false)
        service.setCLIHandler { _, _ -> cliFlag.set(true) }

        val model = room(service, withServices = true)
        model.registerHandlers()
        service.setStatusHandler { statusFlag.set(true) }
        model.clearStatusHandlers()

        service.invokeCLIHandler(message(), contact())
        service.invokeStatusHandler(zeroStatus())

        assertTrue(cliFlag.get(), "CLI handler should survive clearing the status-surface handlers")
        assertFalse(statusFlag.get(), "status handler should no longer fire after clearStatusHandlers")
    }

    @Test
    @OriginalCase("RemoteNodeStatusHandlerSurvivalTests::Repeater cleanup clears the CLI handler on true teardown()", "platform-adaptation")
    fun `repeater cleanup clears the CLI handler`() = runSuspend {
        val service = FakeStatusAdmin()
        val flag = AtomicBoolean(false)
        service.setCLIHandler { _, _ -> flag.set(true) }

        repeater(service, withServices = true).cleanup()
        service.invokeCLIHandler(message(), contact())

        assertFalse(flag.get(), "cleanup should clear every handler slot including the CLI handler")
    }
}
