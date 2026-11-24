// AndroidOnly: WP-206 Actual companion producer projections preserve all cases and control-flow cancellation.
package com.meshcoreone.android.core.connectivity

import com.meshcoreone.android.core.connectivity.pairing.*
import com.meshcoreone.android.core.connectivity.support.scenario
import com.meshcoreone.android.core.connectivity.support.settle
import com.meshcoreone.android.core.contracts.domain.errors.CompanionSetupFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFaultCarrier
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import org.junit.Test

class CompanionFaultProjectionTest {
    @Test fun everyCasePreservesDiagnosticCauseAndItsExactNeutralPayload() {
        val reason = "declined \u4e2d\u6587"
        val cases = listOf(
            Triple(CompanionSetupError.SessionNotActive(), CompanionSetupFault.SessionNotActive, "Bluetooth is not ready."),
            Triple(CompanionSetupError.SessionInvalidated(), CompanionSetupFault.SessionInvalidated, "Companion session ended unexpectedly."),
            Triple(CompanionSetupError.PickerDismissed(), CompanionSetupFault.PickerDismissed, "Device selection was cancelled."),
            Triple(CompanionSetupError.PickerRestricted(), CompanionSetupFault.PickerRestricted, "Cannot show device picker."),
            Triple(CompanionSetupError.PickerAlreadyActive(), CompanionSetupFault.PickerAlreadyActive, "Device picker is already showing."),
            Triple(CompanionSetupError.PairingFailed(reason), CompanionSetupFault.PairingFailed(reason), "Pairing failed: $reason"),
            Triple(CompanionSetupError.NoBluetoothIdentifier(), CompanionSetupFault.NoBluetoothIdentifier, "Selected device does not support Bluetooth connection."),
            Triple(CompanionSetupError.DiscoveryTimeout(), CompanionSetupFault.DiscoveryTimeout, "No devices found."),
            Triple(CompanionSetupError.ConnectionFailed(), CompanionSetupFault.ConnectionFailed, "Could not connect to the device."),
            Triple(CompanionSetupError.UserCancelled(), CompanionSetupFault.UserCancelled, "Removal was cancelled."),
        )
        assertEquals(10, cases.size)
        assertEquals(10, cases.map { it.second }.toSet().size)
        for ((producer, expected, message) in cases) {
            assertEquals(expected, (producer as SourceServiceFaultCarrier).sourceServiceFault)
            assertEquals(expected, producer.sourceServiceFault)
            assertEquals(message, producer.message)
            assertNull(producer.cause)
            val cause = IllegalStateException("private cause")
            producer.initCause(cause)
            assertEquals(expected, producer.sourceServiceFault)
            assertSame(cause, producer.cause)
            val throwable: Throwable = producer
            assertFalse(throwable is CancellationException)
        }
        assertSame(reason, assertIs<CompanionSetupFault.PairingFailed>(cases[5].first.sourceServiceFault).reason)
    }

    private class Gateway(override val isSupported: Boolean) : CompanionDeviceGateway {
        var events: CompanionAssociationEvents? = null
        override fun associations(): List<CompanionAssociation> = emptyList()
        override fun associate(events: CompanionAssociationEvents) {
            this.events = events
            events.onChooserPending(Unit, UUID(0, 1))
        }
        override suspend fun disassociate(association: CompanionAssociation) = Unit
        override fun observePresence(association: CompanionAssociation, observe: Boolean) = Unit
    }

    @Test fun actualUnsupportedActivationThrowsTheSameProjectedType() = scenario {
        val service = CompanionSetupService(Gateway(false), scope, clock)
        val error = assertFailsWith<CompanionSetupError.SessionNotActive> { service.activateSession() }
        assertSame(CompanionSetupFault.SessionNotActive, error.sourceServiceFault)
        assertFalse(service.isSessionActive)
    }

    @Test fun actualPickerCancellationRemainsCancellationAndDoesNotAcquireAFaultCarrier() = scenario {
        val gateway = Gateway(true)
        var launches = 0
        val service = CompanionSetupService(gateway, scope, clock, chooserHost = CompanionChooserHost { _, _ -> launches++ })
        service.activateSession()
        val pending = scope.async { service.showPicker() }
        settle()
        assertEquals("presented", service.lastPickerOutcome)
        assertEquals(1, launches)
        pending.cancel()
        val error = assertFailsWith<CancellationException> { pending.await() }
        assertFalse(error is SourceServiceFaultCarrier)
        assertEquals("presented", service.lastPickerOutcome)
        requireNotNull(gateway.events).onChooserPending(Unit, UUID(0, 2))
        assertEquals(1, launches, "An abandoned request must not launch another chooser")
        service.invalidateSession()
    }
}
