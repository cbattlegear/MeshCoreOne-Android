// AndroidOnly: WP-402 Compile-only checks (no JUnit on the locked androidTest classpath; same convention as WP-401). No execution is claimed.
package com.meshcoreone.android.platform.notifications.status

import com.meshcoreone.android.core.contracts.domain.BleLinkPhase
import com.meshcoreone.android.core.contracts.domain.Capability
import com.meshcoreone.android.core.contracts.domain.ConnectionController
import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.ConnectionIssue
import com.meshcoreone.android.core.contracts.domain.ConnectionSnapshot
import com.meshcoreone.android.core.contracts.domain.ConnectionSubscription
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.DisconnectReason
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.contracts.domain.BluetoothAddress
import com.meshcoreone.android.core.contracts.domain.BluetoothPairingHandle
import com.meshcoreone.android.core.protocol.event.ConnectionState
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ConnectionStatusTests {
    private fun assertEquals(expected: Any?, actual: Any?) = check(expected == actual) { "expected <$expected> but was <$actual>" }

    private fun <T> runNow(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return outcome!!.getOrThrow()
    }

    private fun snapshot(
        state: DeviceConnectionState,
        transport: ConnectionState = ConnectionState.Disconnected,
        phase: BleLinkPhase? = null,
        intent: ConnectionIntent = ConnectionIntent.None,
        issue: ConnectionIssue? = null,
    ) = ConnectionSnapshot(state, transport, phase, intent, token = if (state.isOperational) token else null, issue = issue)

    private val token = SessionToken(ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-0000000000ee")), Generation(1), RadioId(UUID.fromString("00000000-0000-0000-0000-0000000000dd")))

    private class FakeController(initial: ConnectionSnapshot) : ConnectionController {
        val state = MutableStateFlow(initial)
        var connects = 0
        var disconnects = 0
        var failNext = false
        override val snapshot: StateFlow<ConnectionSnapshot> get() = state
        override suspend fun subscribeTransitions(): ConnectionSubscription = error("unused")
        override suspend fun connect(target: ConnectionTarget) {
            if (failNext) throw IllegalStateException("owner rejected")
            connects++
        }
        override suspend fun disconnect(reason: DisconnectReason) {
            check(reason == DisconnectReason.USER_REQUEST)
            disconnects++
        }
    }

    private val target = ConnectionTarget.Bluetooth(
        BluetoothPairingHandle(BluetoothAddress("AA:BB:CC:DD:EE:FF"), null),
        UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
    )
    private val targets = ConnectionTargetProvider { target }

    fun mapperCoversEveryOwnerState() {
        fun kind(s: ConnectionSnapshot) = ConnectionStatusMapper.map(s, "R", userInitiated = false).kind
        assertEquals(ConnectionStatusKind.CONNECTING, kind(snapshot(DeviceConnectionState.CONNECTING)))
        assertEquals(ConnectionStatusKind.RECONNECTING, kind(snapshot(DeviceConnectionState.CONNECTING, ConnectionState.Reconnecting(1))))
        assertEquals(ConnectionStatusKind.RECONNECTING, kind(snapshot(DeviceConnectionState.CONNECTING, phase = BleLinkPhase.AUTO_RECONNECTING)))
        assertEquals(ConnectionStatusKind.SYNCING, kind(snapshot(DeviceConnectionState.CONNECTED)))
        assertEquals(ConnectionStatusKind.DISCONNECTED, kind(snapshot(DeviceConnectionState.DISCONNECTED)))
        assertEquals(ConnectionStatusKind.BLUETOOTH_OFF, kind(snapshot(DeviceConnectionState.DISCONNECTED, issue = ConnectionIssue.BluetoothOff)))
        assertEquals(
            ConnectionStatusKind.PERMISSION_NEEDED,
            kind(snapshot(DeviceConnectionState.DISCONNECTED, issue = ConnectionIssue.PermissionDenied(Capability.BLUETOOTH_CONNECT))),
        )
        assertEquals(
            ConnectionStatusKind.DISCONNECTED,
            kind(snapshot(DeviceConnectionState.DISCONNECTED, issue = ConnectionIssue.PermissionDenied(Capability.NOTIFICATIONS))),
        )
        assertEquals(ConnectionStatusKind.FAILED, kind(snapshot(DeviceConnectionState.DISCONNECTED, issue = ConnectionIssue.Unsupported(Capability.LOCAL_NETWORK))))
    }

    fun actionsFollowStateAndPermission() {
        assertEquals(listOf(ConnectionStatusAction.DISCONNECT), ConnectionStatusMapper.actionsFor(ConnectionStatusKind.READY, true, false))
        assertEquals(listOf(ConnectionStatusAction.DISCONNECT), ConnectionStatusMapper.actionsFor(ConnectionStatusKind.CONNECTING, false, false))
        assertEquals(listOf(ConnectionStatusAction.CONNECT), ConnectionStatusMapper.actionsFor(ConnectionStatusKind.DISCONNECTED, true, false))
        assertEquals(emptyList<ConnectionStatusAction>(), ConnectionStatusMapper.actionsFor(ConnectionStatusKind.DISCONNECTED, false, false))
        assertEquals(
            listOf(ConnectionStatusAction.OPEN_PERMISSION_SETTINGS),
            ConnectionStatusMapper.actionsFor(ConnectionStatusKind.PERMISSION_NEEDED, true, true),
        )
        assertEquals(emptyList<ConnectionStatusAction>(), ConnectionStatusMapper.actionsFor(ConnectionStatusKind.PERMISSION_NEEDED, true, false))
    }

    fun liveUpdateOnlyForUserInitiatedInProgressOnApi36Point1() {
        val connecting = ConnectionStatus(ConnectionStatusKind.CONNECTING, null, userInitiated = true)
        assertEquals(LiveUpdateDecision.Promote, LiveUpdateEligibility.evaluate(connecting, 3_600_001, true))
        assertEquals(
            LiveUpdateDecision.Standard(LiveUpdateDenial.PLATFORM_TOO_OLD),
            LiveUpdateEligibility.evaluate(connecting, 3_600_000, true),
        )
        assertEquals(
            LiveUpdateDecision.Standard(LiveUpdateDenial.PERMISSION_NOT_GRANTED),
            LiveUpdateEligibility.evaluate(connecting, 3_600_001, false),
        )
        assertEquals(
            LiveUpdateDecision.Standard(LiveUpdateDenial.NOT_USER_INITIATED),
            LiveUpdateEligibility.evaluate(connecting.copy(userInitiated = false), 3_600_001, true),
        )
        listOf(
            ConnectionStatusKind.READY, ConnectionStatusKind.DISCONNECTED, ConnectionStatusKind.FAILED,
            ConnectionStatusKind.BLUETOOTH_OFF, ConnectionStatusKind.PERMISSION_NEEDED, ConnectionStatusKind.RECONNECTING,
        ).forEach {
            assertEquals(
                LiveUpdateDecision.Standard(LiveUpdateDenial.NOT_IN_PROGRESS),
                LiveUpdateEligibility.evaluate(connecting.copy(kind = it), 3_600_001, true),
            )
        }
    }

    fun automaticReconnectIsNeverUserInitiated() {
        val status = ConnectionStatusMapper.map(snapshot(DeviceConnectionState.CONNECTING, ConnectionState.Reconnecting(2)), "R", userInitiated = true)
        assertEquals(false, status.userInitiated)
    }

    fun permissionFallbackNeverBlocksButDegradesSurface() {
        assertEquals(StatusSurface.NOTIFICATION, ConnectionStatusAvailability.evaluate(true, true, false, true))
        assertEquals(StatusSurface.ASK_THEN_IN_APP, ConnectionStatusAvailability.evaluate(false, true, false, false))
        assertEquals(StatusSurface.IN_APP_ONLY, ConnectionStatusAvailability.evaluate(false, true, false, true))
        assertEquals(StatusSurface.IN_APP_ONLY, ConnectionStatusAvailability.evaluate(true, false, false, true))
        assertEquals(StatusSurface.IN_APP_ONLY, ConnectionStatusAvailability.evaluate(true, true, true, true))
    }

    fun connectForwardsOnceToTheSingleOwner() {
        val owner = FakeController(snapshot(DeviceConnectionState.DISCONNECTED))
        val router = ConnectionStatusCommandRouter()
        assertEquals(null, runNow { router.attach(owner, targets) })
        assertEquals(CommandDisposition.EXECUTED, runNow { router.dispatch(ConnectionCommand.CONNECT) })
        assertEquals(1, owner.connects)
        owner.state.value = snapshot(DeviceConnectionState.CONNECTING)
        assertEquals(CommandDisposition.IGNORED_REDUNDANT, runNow { router.dispatch(ConnectionCommand.CONNECT) })
        assertEquals(1, owner.connects)
    }

    fun disconnectUsesUserRequestAndIsIdempotent() {
        val owner = FakeController(snapshot(DeviceConnectionState.READY, intent = ConnectionIntent.WantsConnection()))
        val router = ConnectionStatusCommandRouter()
        runNow { router.attach(owner, targets) }
        assertEquals(CommandDisposition.EXECUTED, runNow { router.dispatch(ConnectionCommand.DISCONNECT) })
        assertEquals(1, owner.disconnects)
        owner.state.value = snapshot(DeviceConnectionState.DISCONNECTED, intent = ConnectionIntent.UserDisconnected)
        assertEquals(CommandDisposition.IGNORED_REDUNDANT, runNow { router.dispatch(ConnectionCommand.DISCONNECT) })
        assertEquals(1, owner.disconnects)
    }

    fun disconnectCancelsAPendingReconnect() {
        val owner = FakeController(snapshot(DeviceConnectionState.DISCONNECTED, intent = ConnectionIntent.WantsConnection()))
        val router = ConnectionStatusCommandRouter()
        runNow { router.attach(owner, targets) }
        assertEquals(CommandDisposition.EXECUTED, runNow { router.dispatch(ConnectionCommand.DISCONNECT) })
        assertEquals(1, owner.disconnects)
    }

    fun coldStartKeepsOnlyTheLatestCommandThenReplays() {
        var now = 0L
        val router = ConnectionStatusCommandRouter(clock = { now })
        assertEquals(CommandDisposition.QUEUED_COLD_START, runNow { router.dispatch(ConnectionCommand.CONNECT) })
        assertEquals(CommandDisposition.QUEUED_COLD_START, runNow { router.dispatch(ConnectionCommand.DISCONNECT) })
        assertEquals(ConnectionCommand.DISCONNECT, router.pendingCommand)
        val owner = FakeController(snapshot(DeviceConnectionState.READY, intent = ConnectionIntent.WantsConnection()))
        now = 1_000
        assertEquals(CommandDisposition.EXECUTED, runNow { router.attach(owner, targets) })
        assertEquals(0, owner.connects)
        assertEquals(1, owner.disconnects)
        assertEquals(null, router.pendingCommand)
    }

    fun staleQueuedCommandExpires() {
        var now = 0L
        val router = ConnectionStatusCommandRouter(clock = { now }, ttlMillis = 100)
        runNow { router.dispatch(ConnectionCommand.CONNECT) }
        now = 101
        val owner = FakeController(snapshot(DeviceConnectionState.DISCONNECTED))
        assertEquals(CommandDisposition.EXPIRED, runNow { router.attach(owner, targets) })
        assertEquals(0, owner.connects)
    }

    fun connectWithoutKnownRadioDoesNothing() {
        val owner = FakeController(snapshot(DeviceConnectionState.DISCONNECTED))
        val router = ConnectionStatusCommandRouter()
        runNow { router.attach(owner, ConnectionTargetProvider { null }) }
        assertEquals(CommandDisposition.NO_TARGET, runNow { router.dispatch(ConnectionCommand.CONNECT) })
        assertEquals(0, owner.connects)
    }

    fun ownerFailureIsContainedAndDetachRequeues() {
        val owner = FakeController(snapshot(DeviceConnectionState.DISCONNECTED))
        owner.failNext = true
        val router = ConnectionStatusCommandRouter()
        runNow { router.attach(owner, targets) }
        assertEquals(CommandDisposition.FAILED, runNow { router.dispatch(ConnectionCommand.CONNECT) })
        router.detach(owner)
        assertEquals(CommandDisposition.QUEUED_COLD_START, runNow { router.dispatch(ConnectionCommand.CONNECT) })
    }

    val cases: List<Pair<String, () -> Unit>> = listOf(
        "mapperCoversEveryOwnerState" to ::mapperCoversEveryOwnerState,
        "actionsFollowStateAndPermission" to ::actionsFollowStateAndPermission,
        "liveUpdateOnlyForUserInitiatedInProgressOnApi36Point1" to ::liveUpdateOnlyForUserInitiatedInProgressOnApi36Point1,
        "automaticReconnectIsNeverUserInitiated" to ::automaticReconnectIsNeverUserInitiated,
        "permissionFallbackNeverBlocksButDegradesSurface" to ::permissionFallbackNeverBlocksButDegradesSurface,
        "connectForwardsOnceToTheSingleOwner" to ::connectForwardsOnceToTheSingleOwner,
        "disconnectUsesUserRequestAndIsIdempotent" to ::disconnectUsesUserRequestAndIsIdempotent,
        "disconnectCancelsAPendingReconnect" to ::disconnectCancelsAPendingReconnect,
        "coldStartKeepsOnlyTheLatestCommandThenReplays" to ::coldStartKeepsOnlyTheLatestCommandThenReplays,
        "staleQueuedCommandExpires" to ::staleQueuedCommandExpires,
        "connectWithoutKnownRadioDoesNothing" to ::connectWithoutKnownRadioDoesNothing,
        "ownerFailureIsContainedAndDetachRequeues" to ::ownerFailureIsContainedAndDetachRequeues,
    )
}
