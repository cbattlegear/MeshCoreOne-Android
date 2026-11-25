// PortedFrom: MC1Services/Tests/MC1ServicesTests/Transport/ReconnectPolicyTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.runtime

import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

private val encryption = LinkErrorInput.Bluetooth(BluetoothErrorKind.ENCRYPTION_TIMED_OUT, "encryption timeout")
private val generic = LinkErrorInput.Bluetooth(BluetoothErrorKind.CONNECTION_TIMEOUT, "connection timeout")
private val definitive = LinkErrorInput.Bluetooth(BluetoothErrorKind.PEER_REMOVED_PAIRING_INFORMATION, "removed pairing")
private val deviceId = UUID.fromString("3438CC01-E8A3-4F65-A15B-830E206F78D3")
private fun ReconnectPolicy.exhaust(
    error: LinkErrorInput = encryption, active: Boolean = true,
): ReconnectPolicy.ConnectFailureDecision {
    var result: ReconnectPolicy.ConnectFailureDecision? = null
    repeat(5) { result = resolveConnectFailure(deviceId, error, epochTime, active) }
    return checkNotNull(result)
}
private fun freshVerified(ageSeconds: Long): ReconnectPolicy = ReconnectPolicy().also {
    it.recordBondVerification(deviceId, epochTime.minusSeconds(ageSeconds))
}
private fun ReconnectPolicy.useDiscoveryBudget() { repeat(2) { resolveServiceDiscoveryStall(true) } }

class SourceReconnectPolicyTest {
    @TestFactory
    fun sourceCases() = listOf(
        original("ReconnectPolicyErrorMappingTests", "CBATT auth/encryption codes map to BLEError.authenticationFailed") {
            for (code in listOf(5, 8, 12, 15)) assertIs<LinkFailure.AuthenticationFailed>(
                ReconnectPolicy.makeConnectionError(LinkErrorInput.Att(code, "auth")),
            )
        },
        original("ReconnectPolicyErrorMappingTests", "CBError.encryptionTimedOut maps to .connectionFailed, not authenticationFailed") {
            assertIs<LinkFailure.ConnectionFailed>(ReconnectPolicy.makeConnectionError(encryption))
            assertFalse(ReconnectPolicy.isDefinitiveAuthFailure(encryption))
        },
        original("ReconnectPolicyErrorMappingTests", "CBError.peerRemovedPairingInformation maps to BLEError.authenticationFailed") {
            assertIs<LinkFailure.AuthenticationFailed>(ReconnectPolicy.makeConnectionError(definitive))
        },
        original("ReconnectPolicyErrorMappingTests", "Non-auth CBATT codes fall through to .connectionFailed") {
            assertEquals("Request not supported", assertIs<LinkFailure.ConnectionFailed>(
                ReconnectPolicy.makeConnectionError(LinkErrorInput.Att(6, "Request not supported")),
            ).detail)
        },
        original("ReconnectPolicyErrorMappingTests", "Detection survives a localized description") {
            assertIs<LinkFailure.AuthenticationFailed>(
                ReconnectPolicy.makeConnectionError(LinkErrorInput.Att(5, "Authentifizierung ist unzureichend.")),
            )
        },
        original("ReconnectPolicyErrorMappingTests", "nil error uses fallback message") {
            assertEquals("Disconnected during setup", assertIs<LinkFailure.ConnectionFailed>(
                ReconnectPolicy.makeConnectionError(null, "Disconnected during setup"),
            ).detail)
        },
        original("ReconnectPolicyDiscoveryStallTests", "auto-reconnect with the link down waits for the pending connect") {
            val policy = ReconnectPolicy()
            assertEquals(ReconnectPolicy.DiscoveryDecision.WaitForPendingConnect, policy.resolveAutoReconnectStall(false))
            assertEquals(0L, policy.discoveryTimeoutExtensions)
        },
        original("ReconnectPolicyDiscoveryStallTests", "waiting never exhausts into teardown while the link is down") {
            val policy = ReconnectPolicy(); policy.useDiscoveryBudget()
            repeat(10) { assertEquals(ReconnectPolicy.DiscoveryDecision.WaitForPendingConnect, policy.resolveAutoReconnectStall(false)) }
            assertEquals(2L, policy.discoveryTimeoutExtensions)
        },
        original("ReconnectPolicyDiscoveryStallTests", "connected peripheral with budget extends the auto-reconnect window and consumes it") {
            val policy = ReconnectPolicy()
            for (count in 1L..2L) assertEquals(ReconnectPolicy.DiscoveryDecision.ExtendDiscoveryWindow(count, 2), policy.resolveAutoReconnectStall(true))
            assertEquals(2L, policy.discoveryTimeoutExtensions)
        },
        original("ReconnectPolicyDiscoveryStallTests", "connected peripheral with exhausted budget escalates to an auth failure") {
            val policy = ReconnectPolicy(); policy.useDiscoveryBudget()
            assertIs<LinkFailure.AuthenticationFailed>(assertIs<ReconnectPolicy.DiscoveryDecision.TearDown>(policy.resolveAutoReconnectStall(true)).error)
        },
        original("ReconnectPolicyDiscoveryStallTests", "service discovery stall on a link that never reached connected is a plain timeout") {
            val policy = ReconnectPolicy(); policy.useDiscoveryBudget()
            assertIs<LinkFailure.ConnectionTimeout>(assertIs<ReconnectPolicy.DiscoveryDecision.TearDown>(policy.resolveServiceDiscoveryStall(false)).error)
        },
        original("ReconnectPolicyDiscoveryStallTests", "service discovery stall on a connected peripheral extends until the budget is spent, then escalates") {
            val policy = ReconnectPolicy()
            for (count in 1L..2L) assertEquals(ReconnectPolicy.DiscoveryDecision.ExtendDiscoveryWindow(count, 2), policy.resolveServiceDiscoveryStall(true))
            assertIs<LinkFailure.AuthenticationFailed>(assertIs<ReconnectPolicy.DiscoveryDecision.TearDown>(policy.resolveServiceDiscoveryStall(true)).error)
        },
        original("ReconnectPolicyDiscoveryStallTests", "a new generation resets the extension budget") {
            val policy = ReconnectPolicy(); policy.useDiscoveryBudget(); policy.generationAdvanced()
            assertEquals(0L, policy.discoveryTimeoutExtensions)
            assertEquals(ReconnectPolicy.DiscoveryDecision.ExtendDiscoveryWindow(1, 2), policy.resolveServiceDiscoveryStall(true))
        },
        original("ReconnectPolicyConnectFailureTests", "failures below the budget retry the pending connect") {
            val policy = ReconnectPolicy()
            for (count in 1L..4L) assertEquals(
                ReconnectPolicy.ConnectFailureDecision.RetryPendingConnect(count, 5),
                policy.resolveConnectFailure(deviceId, encryption, epochTime, true),
            )
        },
        original("ReconnectPolicyConnectFailureTests", "a definitive bond error escalates immediately even with a recent verification") {
            val policy = freshVerified(60)
            val result = assertIs<ReconnectPolicy.ConnectFailureDecision.TearDown>(
                policy.resolveConnectFailure(deviceId, definitive, epochTime, true),
            )
            assertIs<LinkFailure.AuthenticationFailed>(result.error)
            assertEquals(ReconnectPolicy.TeardownReason.DefinitiveBondFailure, result.reason)
            assertEquals(0L, policy.autoReconnectConnectFailures)
        },
        original("ReconnectPolicyConnectFailureTests", "exhausted encryption-timeout budget with a recently verified bond keeps the pending connect") {
            val policy = freshVerified(60)
            val result = assertIs<ReconnectPolicy.ConnectFailureDecision.ContinueEpisodeAfterBudget>(policy.exhaust())
            assertEquals(ReconnectPolicy.BudgetHoldReason.FringeEncryptionGraced(Duration.ofSeconds(60)), result.reason)
            assertEquals(0L, policy.autoReconnectConnectFailures)
        },
        original("ReconnectPolicyConnectFailureTests", "out-of-range encryption timeouts with a live bond keep retrying the pending connect") {
            val result = assertIs<ReconnectPolicy.ConnectFailureDecision.ContinueEpisodeAfterBudget>(freshVerified(60).exhaust(active = false))
            assertIs<ReconnectPolicy.BudgetHoldReason.FringeEncryptionGraced>(result.reason)
        },
        original("ReconnectPolicyConnectFailureTests", "a mixed majority of encryption timeouts with a recent bond keeps the pending connect") {
            val policy = freshVerified(60); var result: ReconnectPolicy.ConnectFailureDecision? = null
            listOf(encryption, generic, encryption, generic, encryption).forEach {
                result = policy.resolveConnectFailure(deviceId, it, epochTime, true)
            }
            assertIs<ReconnectPolicy.BudgetHoldReason.FringeEncryptionGraced>(
                assertIs<ReconnectPolicy.ConnectFailureDecision.ContinueEpisodeAfterBudget>(result).reason,
            )
        },
        original("ReconnectPolicyConnectFailureTests", "exhausted budget with a stale bond verification escalates to bond-suspect") {
            val age = 6 * 3600L + 60
            val result = assertIs<ReconnectPolicy.ConnectFailureDecision.TearDown>(freshVerified(age).exhaust())
            assertIs<LinkFailure.AuthenticationFailed>(result.error)
            assertEquals(ReconnectPolicy.TeardownReason.BondSuspect(Duration.ofSeconds(age)), result.reason)
        },
        original("ReconnectPolicyConnectFailureTests", "exhausted budget with no bond verification record escalates to bond-suspect") {
            val result = assertIs<ReconnectPolicy.ConnectFailureDecision.TearDown>(ReconnectPolicy().exhaust())
            assertIs<LinkFailure.AuthenticationFailed>(result.error)
            assertEquals(ReconnectPolicy.TeardownReason.BondSuspect(null), result.reason)
        },
        original("ReconnectPolicyConnectFailureTests", "a verification for a different radio gives no shield") {
            val policy = ReconnectPolicy(); policy.recordBondVerification(UUID.randomUUID(), epochTime)
            assertIs<ReconnectPolicy.TeardownReason.BondSuspect>(assertIs<ReconnectPolicy.ConnectFailureDecision.TearDown>(policy.exhaust()).reason)
        },
        original("ReconnectPolicyConnectFailureTests", "a cleared verification stops shielding") {
            val policy = freshVerified(60); policy.clearBondVerification(deviceId)
            assertEquals(ReconnectPolicy.TeardownReason.BondSuspect(null), assertIs<ReconnectPolicy.ConnectFailureDecision.TearDown>(policy.exhaust()).reason)
        },
        original("ReconnectPolicyConnectFailureTests", "refreshBondVerification updates an existing stamp and never creates") {
            val policy = ReconnectPolicy(); val other = UUID.randomUUID()
            assertFalse(policy.refreshBondVerification(deviceId, epochTime)); assertNull(policy.bondVerificationDates[deviceId])
            policy.recordBondVerification(deviceId, epochTime.minusSeconds(3600))
            assertTrue(policy.refreshBondVerification(deviceId, epochTime)); assertEquals(epochTime, policy.bondVerificationDates[deviceId])
            assertFalse(policy.refreshBondVerification(other, epochTime)); assertNull(policy.bondVerificationDates[other])
            policy.clearBondVerification(deviceId); assertFalse(policy.refreshBondVerification(deviceId, epochTime))
        },
        original("ReconnectPolicyConnectFailureTests", "an exhausted budget without an encryption-timeout majority surfaces the mapped error") {
            val result = assertIs<ReconnectPolicy.ConnectFailureDecision.TearDown>(ReconnectPolicy().exhaust(generic))
            assertIs<LinkFailure.ConnectionFailed>(result.error)
            assertEquals(ReconnectPolicy.TeardownReason.RetryBudgetExhausted, result.reason)
        },
        original("ReconnectPolicyConnectFailureTests", "exhausted encryption-timeout budget with a stale bond while inactive keeps the pending connect") {
            val policy = freshVerified(6 * 3600L + 60)
            assertEquals(ReconnectPolicy.BudgetHoldReason.BackgroundHold,
                assertIs<ReconnectPolicy.ConnectFailureDecision.ContinueEpisodeAfterBudget>(policy.exhaust(active = false)).reason)
            assertEquals(0L, policy.autoReconnectConnectFailures)
        },
        original("ReconnectPolicyConnectFailureTests", "exhausted generic budget while inactive keeps the pending connect") {
            assertEquals(ReconnectPolicy.BudgetHoldReason.BackgroundHold,
                assertIs<ReconnectPolicy.ConnectFailureDecision.ContinueEpisodeAfterBudget>(ReconnectPolicy().exhaust(generic, false)).reason)
        },
        original("ReconnectPolicyConnectFailureTests", "a definitive bond error escalates immediately while inactive") {
            val result = assertIs<ReconnectPolicy.ConnectFailureDecision.TearDown>(
                freshVerified(0).resolveConnectFailure(deviceId, definitive, epochTime, false),
            )
            assertIs<LinkFailure.AuthenticationFailed>(result.error)
            assertEquals(ReconnectPolicy.TeardownReason.DefinitiveBondFailure, result.reason)
        },
        original("ReconnectPolicyConnectFailureTests", "a re-established link clears the failure tally mid-episode") {
            val policy = ReconnectPolicy(); repeat(4) { policy.resolveConnectFailure(deviceId, encryption, epochTime, true) }
            assertEquals(4L, policy.autoReconnectConnectFailures); policy.linkReestablished()
            assertEquals(0L, policy.autoReconnectConnectFailures); assertEquals(0L, policy.encryptionTimedOutConnectFailures)
        },
        original("ReconnectPolicyConnectFailureTests", "a teardown resets the tallies so the next episode starts fresh") {
            val policy = ReconnectPolicy(); policy.exhaust()
            assertEquals(0L, policy.autoReconnectConnectFailures); assertEquals(0L, policy.encryptionTimedOutConnectFailures)
            policy.episodeBegan(); assertEquals(ReconnectPolicy.ConnectFailureDecision.RetryPendingConnect(1, 5),
                policy.resolveConnectFailure(deviceId, generic, epochTime, true))
        },
        original("ReconnectPolicyGracePredicateTests", "bond verification recency predicate") {
            assertFalse(ReconnectPolicy.isBondRecentlyVerified(null, epochTime))
            assertTrue(ReconnectPolicy.isBondRecentlyVerified(epochTime.minusSeconds(1), epochTime))
            assertTrue(ReconnectPolicy.isBondRecentlyVerified(epochTime.minusSeconds(6 * 3600 - 1L), epochTime))
            assertFalse(ReconnectPolicy.isBondRecentlyVerified(epochTime.minusSeconds(6 * 3600L), epochTime))
            assertTrue(ReconnectPolicy.isBondRecentlyVerified(epochTime.plusSeconds(60), epochTime))
        },
    )
}
