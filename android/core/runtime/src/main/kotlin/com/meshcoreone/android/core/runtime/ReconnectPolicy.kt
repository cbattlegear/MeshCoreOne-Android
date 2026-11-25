// PortedFrom: MC1Services/Sources/MC1Services/Transport/ReconnectPolicy.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.runtime

import java.time.Duration
import java.time.Instant
import java.util.UUID

sealed class LinkFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthenticationFailed(cause: Throwable? = null) : LinkFailure("Authentication failed", cause)
    class ConnectionTimeout(cause: Throwable? = null) : LinkFailure("Connection timed out", cause)
    class ConnectionFailed(val detail: String, cause: Throwable? = null) : LinkFailure(detail, cause)
    class BluetoothPoweredOff : LinkFailure("Bluetooth is powered off")
    class BluetoothUnavailable : LinkFailure("Bluetooth is unavailable")
    class BluetoothUnauthorized : LinkFailure("Bluetooth authorization is missing")
    class DeviceConnectedToOtherApp : LinkFailure("Device connected to another app")
}

sealed interface LinkErrorInput {
    val description: String
    data class Att(val code: Int, override val description: String) : LinkErrorInput
    data class Bluetooth(val kind: BluetoothErrorKind, override val description: String) : LinkErrorInput
    data class Other(override val description: String) : LinkErrorInput
}
enum class BluetoothErrorKind { ENCRYPTION_TIMED_OUT, PEER_REMOVED_PAIRING_INFORMATION, CONNECTION_TIMEOUT, OTHER }

class ReconnectPolicy {
    var autoReconnectConnectFailures = 0L
        private set
    var encryptionTimedOutConnectFailures = 0L
        private set
    var discoveryTimeoutExtensions = 0L
        private set
    private val verifications = mutableMapOf<UUID, Instant>()
    val bondVerificationDates: Map<UUID, Instant> get() = verifications.toMap()

    fun linkReestablished() = resetFailures()
    fun episodeBegan() = resetFailures()
    fun generationAdvanced() { discoveryTimeoutExtensions = 0 }
    fun recordBondVerification(deviceId: UUID, at: Instant) { verifications[deviceId] = at }
    fun clearBondVerification(deviceId: UUID) { verifications.remove(deviceId) }
    fun refreshBondVerification(deviceId: UUID, at: Instant): Boolean {
        if (deviceId !in verifications) return false
        verifications[deviceId] = at
        return true
    }

    sealed interface ConnectFailureDecision {
        data class RetryPendingConnect(val failureCount: Long, val budget: Long) : ConnectFailureDecision
        data class ContinueEpisodeAfterBudget(val reason: BudgetHoldReason) : ConnectFailureDecision
        data class TearDown(val error: LinkFailure, val reason: TeardownReason) : ConnectFailureDecision
    }
    sealed interface BudgetHoldReason {
        data class FringeEncryptionGraced(val verifiedAge: Duration?) : BudgetHoldReason
        data object BackgroundHold : BudgetHoldReason
    }
    sealed interface TeardownReason {
        data object DefinitiveBondFailure : TeardownReason
        data class BondSuspect(val verifiedAge: Duration?) : TeardownReason
        data object RetryBudgetExhausted : TeardownReason
    }
    sealed interface DiscoveryDecision {
        data object WaitForPendingConnect : DiscoveryDecision
        data class ExtendDiscoveryWindow(val extensionCount: Long, val budget: Long) : DiscoveryDecision
        data class TearDown(val error: LinkFailure) : DiscoveryDecision
    }

    fun resolveConnectFailure(
        deviceId: UUID, error: LinkErrorInput?, now: Instant, appActive: Boolean,
    ): ConnectFailureDecision {
        if (isDefinitiveAuthFailure(error)) {
            resetFailures()
            return ConnectFailureDecision.TearDown(LinkFailure.AuthenticationFailed(), TeardownReason.DefinitiveBondFailure)
        }
        autoReconnectConnectFailures = Math.incrementExact(autoReconnectConnectFailures)
        if (isEncryptionTimedOut(error)) encryptionTimedOutConnectFailures++
        if (autoReconnectConnectFailures < MAX_CONNECT_FAILURES) {
            return ConnectFailureDecision.RetryPendingConnect(autoReconnectConnectFailures, MAX_CONNECT_FAILURES)
        }
        val majority = encryptionTimedOutConnectFailures * 2 > autoReconnectConnectFailures
        resetFailures()
        val verified = verifications[deviceId]
        val age = verified?.let { Duration.between(it, now) }
        return when {
            majority && isBondRecentlyVerified(verified, now) ->
                ConnectFailureDecision.ContinueEpisodeAfterBudget(BudgetHoldReason.FringeEncryptionGraced(age))
            !appActive -> ConnectFailureDecision.ContinueEpisodeAfterBudget(BudgetHoldReason.BackgroundHold)
            majority -> ConnectFailureDecision.TearDown(LinkFailure.AuthenticationFailed(), TeardownReason.BondSuspect(age))
            else -> ConnectFailureDecision.TearDown(makeConnectionError(error), TeardownReason.RetryBudgetExhausted)
        }
    }

    fun resolveServiceDiscoveryStall(peripheralConnected: Boolean): DiscoveryDecision {
        if (!peripheralConnected) return DiscoveryDecision.TearDown(LinkFailure.ConnectionTimeout())
        return extensionOrTeardown()
    }

    fun resolveAutoReconnectStall(peripheralConnected: Boolean): DiscoveryDecision {
        if (!peripheralConnected) return DiscoveryDecision.WaitForPendingConnect
        return extensionOrTeardown()
    }

    private fun extensionOrTeardown(): DiscoveryDecision {
        if (discoveryTimeoutExtensions < MAX_DISCOVERY_EXTENSIONS) {
            discoveryTimeoutExtensions++
            return DiscoveryDecision.ExtendDiscoveryWindow(discoveryTimeoutExtensions, MAX_DISCOVERY_EXTENSIONS)
        }
        return DiscoveryDecision.TearDown(LinkFailure.AuthenticationFailed())
    }
    private fun resetFailures() { autoReconnectConnectFailures = 0; encryptionTimedOutConnectFailures = 0 }

    companion object {
        const val MAX_DISCOVERY_EXTENSIONS = 2L
        const val MAX_CONNECT_FAILURES = 5L
        val BOND_GRACE: Duration = Duration.ofHours(6)
        fun makeConnectionError(error: LinkErrorInput?, fallback: String = "Unknown error"): LinkFailure =
            if (isDefinitiveAuthFailure(error)) LinkFailure.AuthenticationFailed()
            else LinkFailure.ConnectionFailed(error?.description ?: fallback)
        fun isDefinitiveAuthFailure(error: LinkErrorInput?): Boolean =
            error is LinkErrorInput.Att && error.code in setOf(5, 8, 12, 15) ||
                error is LinkErrorInput.Bluetooth && error.kind == BluetoothErrorKind.PEER_REMOVED_PAIRING_INFORMATION
        fun isEncryptionTimedOut(error: LinkErrorInput?): Boolean =
            error is LinkErrorInput.Bluetooth && error.kind == BluetoothErrorKind.ENCRYPTION_TIMED_OUT
        fun isBondRecentlyVerified(lastVerified: Instant?, now: Instant, grace: Duration = BOND_GRACE): Boolean =
            lastVerified != null && Duration.between(lastVerified, now) < grace
    }
}
