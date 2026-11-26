// PortedFrom: MC1Services/Tests/MC1ServicesTests/BondLossPairingRecoveryTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.connectivity

import com.meshcoreone.android.core.connectivity.support.OriginalCase
import com.meshcoreone.android.core.connectivity.support.contact
import com.meshcoreone.android.core.connectivity.support.runtimeScenario
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.runtime.BluetoothErrorKind
import com.meshcoreone.android.core.runtime.LinkErrorInput
import com.meshcoreone.android.core.runtime.LinkFailure
import com.meshcoreone.android.core.runtime.ReconnectPolicy
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * "Remove and retry" after bond loss demotes the row to a ghost, so re-pairing the same radio over
 * a new endpoint id resolves the original radio id and its children.
 */
class BondLossPairingRecoveryTest {
    @Test @OriginalCase("BondLossPairingRecoveryTests::removeFailedPairing keeps the radio's data reachable when the same radio re-pairs()")
    fun `removeFailedPairing keeps the radio's data reachable when the same radio re-pairs`() = runtimeScenario {
        val firstEndpoint = connectReady()
        val originalRadio = checkNotNull(manager.connectedDevice).radioId
        val fieldContact = contact(originalRadio, "FieldContact")
        devices.contacts += fieldContact
        devices.messages += MessageDTO(radioId = originalRadio, contactID = fieldContact.id, text = "message before bond loss", timestamp = 1_700_000_000u)
        devices.channels += ChannelDTO(radioId = originalRadio, index = 3u, name = "FieldChannel")

        coordinator().removeFailedPairing(firstEndpoint)

        assertTrue(contacts.fetchContacts(originalRadio).any { it.id == fieldContact.id })
        assertTrue(devices.messages.any { it.contactID == fieldContact.id })
        assertTrue(devices.channels.any { it.radioId == originalRadio && it.name == "FieldChannel" })

        val secondEndpoint = UUID.randomUUID()
        assertNotEquals(firstEndpoint, secondEndpoint)
        connectReady(secondEndpoint)
        val reattached = checkNotNull(manager.connectedDevice).radioId
        assertEquals(originalRadio, reattached)
        assertTrue(contacts.fetchContacts(reattached).any { it.id == fieldContact.id })
        assertTrue(devices.channels.any { it.radioId == reattached && it.name == "FieldChannel" })
    }

    @Test @OriginalCase("BondLossPairingRecoveryTests::clearPersistedConnection clears forgotten device bond and leaves the other intact()", "native-equivalent")
    fun `clearPersistedConnection clears forgotten device bond and leaves the other intact`() = runtimeScenario {
        val deviceA = connectReady()
        val deviceB = UUID.randomUUID()
        val link = links.last()
        link.recordBondVerification(deviceB, clock.instant)
        last.persistBondVerification(deviceB)
        assertNotNull(link.bondVerifications[deviceA], "Connect recorded A's verification on the live link")

        manager.clearPersistedConnection(deviceA)

        assertNull(link.bondVerifications[deviceA])
        assertNotNull(link.bondVerifications[deviceB])
        val stored = last.read()
        assertNull(stored.deviceId)
        assertNotNull(last.bondVerificationDate(deviceB))
        assertEquals(deviceB, stored.bondDeviceId)
    }

    @Test @OriginalCase("BondLossPairingRecoveryTests::holder bond slot survives forgetting a non-holder()")
    fun `holder bond slot survives forgetting a non-holder`() = runtimeScenario {
        val deviceA = UUID.randomUUID(); val deviceB = UUID.randomUUID()
        last.persist(deviceA, RadioId(UUID.randomUUID()), "Radio A")
        last.persistBondVerification(deviceB)
        manager.clearPersistedConnection(deviceA)
        assertEquals(deviceB, last.read().bondDeviceId)
        assertNotNull(last.bondVerificationDate(deviceB))
    }

    @Test @OriginalCase("BondLossPairingRecoveryTests::empty bond slot still clears forgotten device in-memory verification()", "native-equivalent")
    fun `empty bond slot still clears forgotten device in-memory verification`() = runtimeScenario {
        val deviceA = connectReady()
        val link = links.last()
        last.read().bondDeviceId?.let { last.clear(it) }
        assertNull(last.read().bondDeviceId)
        manager.clearPersistedConnection(deviceA)
        assertNull(link.bondVerifications[deviceA])
        assertTrue(deviceA in link.clearedBonds)
    }

    @Test @OriginalCase("BondLossPairingRecoveryTests::forgetting a non-last-connected device still clears its bond verification()", "native-equivalent")
    fun `forgetting a non-last-connected device still clears its bond verification`() = runtimeScenario {
        val deviceA = connectReady()
        val deviceB = UUID.randomUUID()
        val link = links.last()
        link.recordBondVerification(deviceB, clock.instant)
        last.persistBondVerification(deviceB)
        coordinator().removeFailedPairing(deviceB)
        assertNull(link.bondVerifications[deviceB])
        val stored = last.read()
        assertEquals(deviceA, stored.deviceId)
        assertNull(last.bondVerificationDate(deviceB))
        assertNull(stored.bondDeviceId)
    }

    @Test @OriginalCase("BondLossPairingRecoveryTests::awaited forget clears bond before encryption-timeout budget classifies as bondSuspect()", "native-equivalent")
    fun `awaited forget clears bond before encryption-timeout budget classifies as bondSuspect`() = runtimeScenario {
        // Single owner: the policy's verification map is cleared through the awaited forget path,
        // then the same policy classifies the auto-reconnect budget.
        val deviceId = connectReady()
        val policy = links.last().policy
        assertNotNull(policy.bondVerificationDates[deviceId], "Connect seeded the verification")
        coordinator().removeFailedPairing(deviceId)
        assertNull(policy.bondVerificationDates[deviceId])

        val timedOut = LinkErrorInput.Bluetooth(BluetoothErrorKind.ENCRYPTION_TIMED_OUT, "encryption timed out")
        var decision: ReconnectPolicy.ConnectFailureDecision? = null
        repeat(ReconnectPolicy.MAX_CONNECT_FAILURES.toInt()) {
            decision = policy.resolveConnectFailure(deviceId, timedOut, clock.instant, appActive = true)
        }
        val teardown = assertIs<ReconnectPolicy.ConnectFailureDecision.TearDown>(decision)
        assertIs<LinkFailure.AuthenticationFailed>(teardown.error)
        assertIs<ReconnectPolicy.TeardownReason.BondSuspect>(teardown.reason)
    }
}
