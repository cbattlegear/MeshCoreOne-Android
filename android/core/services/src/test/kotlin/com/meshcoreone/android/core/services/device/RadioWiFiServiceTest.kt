// AndroidOnly: WP-211 Real-session companion Wi-Fi operation ownership regressions.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.RadioWiFiState
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class RadioWiFiServiceTest {
    private fun cliCommand(text: String) = Bytes.of(0x42) + Bytes.utf8(text)
    private fun cliReply(text: String) = Bytes.of(0x1d) + Bytes.utf8(text)

    private suspend fun TestScope.baseline(
        fixture: SettingsFixture,
        service: RadioWiFiService,
        editor: UUID,
    ): RadioWiFiCredentialBaseline {
        val request = request { service.readCredentials(editor) }
        reply(fixture, 2, cliCommand("00|get wifi.ssid"), cliReply("00|> old"))
        reply(fixture, 3, cliCommand("01|get wifi.pwd"), cliReply("01|> original"))
        return assertNotNull(request.await())
    }

    @Test
    fun partialCredentialSaveKeepsAcceptedSsidAndAllowsOwnedRetry() = runTest {
        val fixture = settingsFixture()
        val state = RadioWiFiState(RADIO)
        val service = RadioWiFiService(fixture.session, fixture.context, state)
        val editor = UUID.randomUUID()
        try {
            val baseline = baseline(fixture, service, editor)
            assertFalse(baseline.toString().contains("original"))
            val save = request { service.saveCredentials("new", "secret", baseline, editor) { true } }
            reply(fixture, 4, cliCommand("02|set wifi.ssid new"), cliReply("02|OK"))
            reply(fixture, 5, cliCommand("03|set wifi.pwd secret"), cliReply("03|Error: rejected"))
            val partial = save.await()
            assertTrue(partial.ssidAccepted)
            assertFalse(partial.passwordAccepted)
            assertFalse(partial.uncertain)
            assertEquals(SettingsServiceError.RadioCLIRejected("Error: rejected"),
                (partial.error as com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException).error)
            assertTrue(state.isRebootPending)
            val updated = assertNotNull(partial.updatedBaseline)
            assertEquals("new", updated.ssid)
            assertEquals("original", updated.password)
            val retry = request { service.saveCredentials("new", "secret", updated, editor) { true } }
            reply(fixture, 6, cliCommand("04|set wifi.ssid new"), cliReply("04|OK"))
            reply(fixture, 7, cliCommand("05|set wifi.pwd secret"), cliReply("05|OK"))
            assertTrue(retry.await().passwordAccepted)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun lostReplyIsUncertainAndRetiresEditorBaseline() = runTest {
        val fixture = settingsFixture()
        val state = RadioWiFiState(RADIO)
        val service = RadioWiFiService(fixture.session, fixture.context, state)
        val editor = UUID.randomUUID()
        try {
            val baseline = baseline(fixture, service, editor)
            val save = request { service.saveCredentials("new", baseline.password, baseline, editor) { true } }
            runCurrent()
            assertEquals(cliCommand("02|set wifi.ssid new"), fixture.radio.sent.last())
            advanceTimeBy(1100)
            runCurrent()
            val receipt = save.await()
            assertTrue(receipt.uncertain)
            assertFalse(receipt.ssidAccepted)
            assertNull(receipt.updatedBaseline)
            assertTrue(state.isRebootPending)
            assertIs<SettingsServiceError.SessionError>(
                assertIs<com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException>(receipt.error).error,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun replacingEditorDuringReadDiscardsOldResultBeforePasswordQuery() = runTest {
        val fixture = settingsFixture()
        val service = RadioWiFiService(fixture.session, fixture.context, RadioWiFiState(RADIO))
        val oldEditor = UUID.randomUUID()
        val newEditor = UUID.randomUUID()
        try {
            val old = request { service.readCredentials(oldEditor) }
            runCurrent()
            val current = request { service.readCredentials(newEditor) }
            runCurrent()
            reply(fixture, 2, cliCommand("00|get wifi.ssid"), cliReply("00|> old"))
            assertNull(old.await())
            reply(fixture, 3, cliCommand("01|get wifi.ssid"), cliReply("01|> new"))
            reply(fixture, 4, cliCommand("02|get wifi.pwd"), cliReply("02|> secret"))
            assertEquals("new", assertNotNull(current.await()).ssid)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun observationKeepsConfirmedFieldsWhenLaterReadFails() = runTest {
        val fixture = settingsFixture()
        val service = RadioWiFiService(fixture.session, fixture.context, RadioWiFiState(RADIO))
        try {
            val probe = request { service.probe() }
            reply(fixture, 2, cliCommand("00|get wifi.enabled"), cliReply("00|> 1"))
            val supported = assertIs<RadioWiFiCapability.Supported>(probe.await()).probe
            val observation = request { service.observe(supported) }
            reply(fixture, 3, cliCommand("01|get wifi.ssid"), cliReply("01|> home"))
            reply(fixture, 4, cliCommand("02|get wifi.status"), cliReply("02|Error: unavailable"))
            val result = assertNotNull(observation.await())
            assertEquals("home", result.networkName)
            assertTrue(result.networkNameWasRead)
            assertNull(result.associated)
            assertFalse(result.ipAddressWasRead)
            assertEquals(SettingsServiceError.RadioCLIRejected("Error: unavailable"), assertNotNull(result.error).error)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun enabledAndClearCommandsTrackAcceptedAndUncertainOutcomes() = runTest {
        val fixture = settingsFixture()
        val state = RadioWiFiState(RADIO)
        val service = RadioWiFiService(fixture.session, fixture.context, state)
        try {
            val first = request { service.probe() }
            reply(fixture, 2, cliCommand("00|get wifi.enabled"), cliReply("00|> 1"))
            val probe = assertIs<RadioWiFiCapability.Supported>(first.await()).probe
            val disable = request { service.changeEnabled(probe, false) }
            reply(fixture, 3, cliCommand("01|set wifi.enabled 0"), cliReply("01|OK"))
            val accepted = disable.await()
            assertEquals(RadioWiFiState.MutationOutcome.ACCEPTED, accepted.outcome)
            assertTrue(state.isRebootPending)
            assertFailsWith<RadioWiFiOwnershipLost> { service.changeEnabled(probe, true) }

            val next = request { service.probe() }
            reply(fixture, 4, cliCommand("02|get wifi.enabled"), cliReply("02|> 0"))
            val current = assertIs<RadioWiFiCapability.Supported>(next.await()).probe
            val clear = request { service.clear(current) }
            runCurrent()
            assertEquals(cliCommand("03|set wifi.clear"), fixture.radio.sent.last())
            advanceTimeBy(1100)
            runCurrent()
            assertEquals(RadioWiFiState.MutationOutcome.UNCERTAIN, clear.await().outcome)
            assertTrue(state.isRebootPending)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun replacingOwnerAfterSsidAcceptanceDoesNotWritePassword() = runTest {
        val fixture = settingsFixture()
        val state = RadioWiFiState(RADIO)
        val service = RadioWiFiService(fixture.session, fixture.context, state)
        val editor = UUID.randomUUID()
        var current = true
        try {
            val baseline = baseline(fixture, service, editor)
            fixture.radio.onSend = { if (it == cliCommand("02|set wifi.ssid changed")) current = false }
            val save = request { service.saveCredentials("changed", "secret", baseline, editor) { current } }
            reply(fixture, 4, cliCommand("02|set wifi.ssid changed"), cliReply("02|OK"))
            val receipt = save.await()
            assertTrue(receipt.ssidAccepted)
            assertFalse(receipt.passwordAccepted)
            assertIs<RadioWiFiOwnershipLost>(receipt.error)
            assertTrue(state.isRebootPending)
            assertEquals(4, fixture.radio.sent.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancellationAfterDispatchRetainsUncertainRebootEvidence() = runTest {
        val fixture = settingsFixture()
        val state = RadioWiFiState(RADIO)
        val service = RadioWiFiService(fixture.session, fixture.context, state)
        val editor = UUID.randomUUID()
        try {
            val baseline = baseline(fixture, service, editor)
            val save = request { service.saveCredentials("new", baseline.password, baseline, editor) { true } }
            runCurrent()
            assertEquals(cliCommand("02|set wifi.ssid new"), fixture.radio.sent.last())
            save.cancel()
            runCurrent()
            assertNull(state.stableRevision())
            advanceTimeBy(1100)
            runCurrent()
            assertTrue(state.isRebootPending)
            assertNotNull(state.stableRevision())
        } finally {
            fixture.close()
        }

        @Test
        fun cancelledMutationDoesNotSettleUntilNoncooperativeSendHasEnded() = runTest {
            val fixture = settingsFixture()
            val state = RadioWiFiState(RADIO)
            val service = RadioWiFiService(fixture.session, fixture.context, state)
            val editor = UUID.randomUUID()
            val sendGate = CompletableDeferred<Unit>()
            try {
                val baseline = baseline(fixture, service, editor)
                fixture.radio.onSend = { frame ->
                    if (frame == cliCommand("02|set wifi.ssid new")) {
                        withContext(NonCancellable) { sendGate.await() }
                    }
                }
                val save = request { service.saveCredentials("new", baseline.password, baseline, editor) { true } }
                runCurrent()
                save.cancel()
                runCurrent()
                assertNull(state.stableRevision())
                assertFalse(state.isRebootPending)
                sendGate.complete(Unit)
                runCurrent()
                assertTrue(state.isRebootPending)
                assertNotNull(state.stableRevision())
            } finally {
                sendGate.complete(Unit)
                fixture.close()
            }
        }
    }

    @Test
    fun rejectedWriteDoesNotInventRebootEvidence() = runTest {
        val fixture = settingsFixture()
        val state = RadioWiFiState(RADIO)
        val service = RadioWiFiService(fixture.session, fixture.context, state)
        try {
            val result = request { service.probe() }
            reply(fixture, 2, cliCommand("00|get wifi.enabled"), cliReply("00|> 1"))
            val probe = assertIs<RadioWiFiCapability.Supported>(result.await()).probe
            val change = request { service.changeEnabled(probe, false) }
            reply(fixture, 3, cliCommand("01|set wifi.enabled 0"), cliReply("01|Error: unsupported"))
            val receipt = change.await()
            assertEquals(RadioWiFiState.MutationOutcome.REJECTED, receipt.outcome)
            assertFalse(state.isRebootPending)
        } finally {
            fixture.close()
        }
    }
}
