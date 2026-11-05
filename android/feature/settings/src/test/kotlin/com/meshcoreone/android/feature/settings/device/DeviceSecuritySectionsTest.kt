// AndroidOnly: WP-317 Behavior tests for path hash, BLE PIN, battery curve, flood scope and identity regeneration (expectations follow the Swift sections).
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.OCVPreset
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.settings.device.support.FakeConnection
import com.meshcoreone.android.feature.settings.device.support.FakeDiscovery
import com.meshcoreone.android.feature.settings.device.support.FakeSettingsPort
import com.meshcoreone.android.feature.settings.device.support.FakeStore
import com.meshcoreone.android.feature.settings.device.support.TestScheduler
import com.meshcoreone.android.feature.settings.device.support.device
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

class PathHashModeStateHolderTest {
    private val scheduler = TestScheduler()
    private val connection = FakeConnection(device(firmwareVersion = 10u, pathHashMode = 1u))
    private val service = FakeSettingsPort()
    private val holder = PathHashModeStateHolder(scheduler.environment(), connection, { service })
    private val state get() = holder.state.value

    @Test
    fun `teardown acquisition failure cannot escape a path hash click or bind a retry to a successor`() {
        val bound = PathHashModeStateHolder(scheduler.environment(), connection, { throw SettingsNotConnectedException() })
        bound.start(); scheduler.runCurrent()
        bound.onModeSelected(2u)
        scheduler.runCurrent()
        assertFalse(bound.state.value.isApplying)
        assertTrue(bound.state.value.errorMessage != null)
        assertTrue(service.calls.isEmpty())
        bound.stop()
    }

    @Test
    fun `shows the radio's mode and writes a new pick verified`() {
        holder.start(); scheduler.runCurrent()
        assertEquals(1u.toUByte(), state.displayedMode)
        holder.onModeSelected(2u)
        assertTrue(state.isApplying)
        scheduler.runCurrent()
        assertEquals("setPathHashModeVerified(2)", service.calls.single())
        assertFalse(state.isApplying)
    }

    @Test
    fun `picking the same mode again writes nothing`() {
        holder.start(); scheduler.runCurrent()
        holder.onModeSelected(1u)
        scheduler.runCurrent()
        assertTrue(service.calls.isEmpty())
    }

    @Test
    fun `failures revert the picker to the radio and retry repeats the write`() {
        holder.start(); scheduler.runCurrent()
        service.failNext("setPathHashModeVerified", SettingsServiceException(SettingsServiceError.SendFailed))
        holder.onModeSelected(2u)
        scheduler.runCurrent()
        assertEquals(1u.toUByte(), state.selectedMode)
        assertTrue(holder.retryAlertState.value.isPresented)
        holder.retry.retry()
        scheduler.runCurrent()
        assertEquals(2, service.calls.size)
        service.failNext("setPathHashModeVerified", SettingsServiceException(SettingsServiceError.VerificationFailed("0", "1")))
        holder.onModeSelected(0u)
        scheduler.runCurrent()
        assertTrue(state.errorMessage != null)
    }

    @Test
    fun `a missing service reports the error and reverts`() {
        val orphan = PathHashModeStateHolder(scheduler.environment(), connection, { null })
        orphan.start(); scheduler.runCurrent()
        orphan.onModeSelected(2u)
        scheduler.runCurrent()
        assertEquals(UiText.Verbatim("Device not connected"), orphan.state.value.errorMessage)
        assertEquals(1u.toUByte(), orphan.state.value.selectedMode)
    }

    @Test
    fun `persistence failure is visible and reselecting a radio-reported failed mode retries`() {
        holder.start(); scheduler.runCurrent()
        service.failNext("setPathHashModeVerified", IllegalStateException("Storage write failed"))
        holder.onModeSelected(2u)
        scheduler.runCurrent()
        assertEquals(UiText.Verbatim("Storage write failed"), state.errorMessage)
        connection.deviceFlow.value = connection.deviceFlow.value!!.copy(pathHashMode = 2u)
        scheduler.runCurrent()
        holder.onModeSelected(2u)
        scheduler.runCurrent()
        assertEquals(2, service.calls.size)
        assertNull(state.errorMessage)
        assertFalse(state.isApplying)
    }

    @Test
    fun `after persistence failure explicitly selecting the previous mode restores the radio`() {
        holder.start(); scheduler.runCurrent()
        service.failNext("setPathHashModeVerified", IllegalStateException("Storage write failed"))
        holder.onModeSelected(2u)
        scheduler.runCurrent()
        assertEquals(1u.toUByte(), state.displayedMode)
        holder.onModeSelected(1u)
        scheduler.runCurrent()
        assertEquals(listOf("setPathHashModeVerified(2)", "setPathHashModeVerified(1)"), service.calls)
        assertNull(state.errorMessage)
        holder.onModeSelected(1u)
        scheduler.runCurrent()
        assertEquals(2, service.calls.size)
    }

    @Test
    fun `the action stays busy until persistence acknowledges and ignores another selection`() {
        val persistence = CompletableDeferred<Unit>()
        service.onCall = { persistence.await() }
        holder.start(); scheduler.runCurrent()
        holder.onModeSelected(2u)
        scheduler.runCurrent()
        assertTrue(state.isApplying)
        holder.onModeSelected(0u)
        scheduler.runCurrent()
        assertEquals(1, service.calls.size)
        persistence.complete(Unit)
        scheduler.runCurrent()
        assertFalse(state.isApplying)
        assertEquals(2u.toUByte(), state.displayedMode)
    }

    @Test
    fun `retry retains the original service instead of rebinding to a successor`() {
        val successor = FakeSettingsPort()
        var current = service
        val bound = PathHashModeStateHolder(scheduler.environment(), connection, { current })
        bound.start(); scheduler.runCurrent()
        service.failNext("setPathHashModeVerified", SettingsServiceException(SettingsServiceError.SendFailed))
        bound.onModeSelected(2u)
        scheduler.runCurrent()
        current = successor
        bound.retry.retry()
        scheduler.runCurrent()
        assertEquals(2, service.calls.size)
        assertTrue(successor.calls.isEmpty())
    }

    @Test
    fun `cancelled waiting leaves a recoverable selection rather than assuming radio rollback`() {
        holder.start(); scheduler.runCurrent()
        service.failNext("setPathHashModeVerified", CancellationException("Waiting cancelled after radio verification"))
        holder.onModeSelected(2u)
        scheduler.runCurrent()
        assertFalse(state.isApplying)
        assertEquals(1u.toUByte(), state.displayedMode)
        assertNull(state.errorMessage)
        holder.onModeSelected(1u)
        scheduler.runCurrent()
        assertEquals(listOf("setPathHashModeVerified(2)", "setPathHashModeVerified(1)"), service.calls)
    }
}

class BluetoothPinStateHolderTest {
    private val scheduler = TestScheduler()
    private val connection = FakeConnection(device(blePin = 0u))
    private val service = FakeSettingsPort()
    private val holder = BluetoothPinStateHolder(scheduler.environment(), connection) { service }
    private val state get() = holder.state.value
    private val invalidPin = UiText.Resource(AppSettingsStrings.bluetoothErrorInvalidPin)

    private fun started(pin: UInt = 0u): BluetoothPinStateHolder {
        connection.deviceFlow.value = device(blePin = pin)
        holder.start(); scheduler.runCurrent(); return holder
    }

    @Test
    fun `unset and the firmware default both read as the default type`() {
        for (pin in listOf(0u, 123_456u)) assertEquals(BluetoothPinType.DEFAULT, BluetoothPinState(device = device(blePin = pin)).currentPinType)
        assertEquals(BluetoothPinType.CUSTOM, BluetoothPinState(device = device(blePin = 654_321u)).currentPinType)
    }

    @Test
    fun `choosing custom asks for a PIN and cancelling reverts the picker`() {
        started()
        holder.onPinTypeSelected(BluetoothPinType.CUSTOM)
        assertTrue(state.showingPinEntry)
        holder.cancelPinDialog()
        assertEquals(BluetoothPinType.DEFAULT, state.pinType)
        assertFalse(state.showingPinEntry)
        assertTrue(service.calls.isEmpty())
    }

    @Test
    fun `a valid PIN is written to RAM and applied by a reboot even when the reboot times out`() {
        started()
        holder.onPinTypeSelected(BluetoothPinType.CUSTOM)
        service.failNext("reboot", SettingsOperationTimeoutException())
        holder.submitCustomPin("654321")
        assertTrue(state.isChangingPin)
        scheduler.runCurrent()
        assertEquals(listOf("setBlePin(654321)", "reboot"), service.calls)
        assertFalse(state.isChangingPin)
        assertNull(state.errorMessage)
        assertEquals(BluetoothPinType.CUSTOM, state.pinType)
    }

    @Test
    fun `invalid PINs report the error, write nothing and revert`() {
        started()
        for (text in listOf("", "12345", "1234567", "abcdef", "099999", "１２３４５６", " 123456", "-123456")) {
            holder.onPinTypeSelected(BluetoothPinType.CUSTOM)
            holder.dismissError()
            holder.submitCustomPin(text)
            assertEquals(invalidPin, state.errorMessage, "[$text]")
            assertEquals(BluetoothPinType.DEFAULT, state.pinType)
        }
        assertTrue(service.calls.isEmpty())
    }

    @Test
    fun `range edges 100000 and 999999 are accepted`() {
        started()
        for (pin in listOf("100000", "999999", "+123456")) {
            service.calls.clear()
            holder.submitCustomPin(pin)
            scheduler.runCurrent()
            assertEquals("setBlePin(${pin.removePrefix("+")})", service.calls.first())
        }
    }

    @Test
    fun `going back to the default needs confirmation and writes the firmware default`() {
        started(654_321u)
        assertEquals(BluetoothPinType.CUSTOM, state.pinType)
        holder.onPinTypeSelected(BluetoothPinType.DEFAULT)
        assertTrue(state.showingRemoveConfirmation)
        assertTrue(service.calls.isEmpty())
        holder.confirmRemoveCustomPin()
        scheduler.runCurrent()
        assertEquals(listOf("setBlePin(123456)", "reboot"), service.calls)
    }

    @Test
    fun `declining the removal keeps the custom type`() {
        started(654_321u)
        holder.onPinTypeSelected(BluetoothPinType.DEFAULT)
        holder.cancelPinDialog()
        assertEquals(BluetoothPinType.CUSTOM, state.pinType)
    }

    @Test
    fun `a write failure shows the error and reverts to the radio's type`() {
        started()
        service.failNext("setBlePin", SettingsServiceException(SettingsServiceError.InvalidResponse))
        holder.onPinTypeSelected(BluetoothPinType.CUSTOM)
        holder.submitCustomPin("654321")
        scheduler.runCurrent()
        assertTrue(state.errorMessage != null)
        assertEquals(BluetoothPinType.DEFAULT, state.pinType)
        assertFalse(state.isChangingPin)
    }

    @Test
    fun `the current PIN row shows only for a custom PIN and hides again on reload`() {
        started(654_321u)
        assertTrue(state.showsCurrentPin)
        assertFalse(state.isPinVisible)
        holder.togglePinVisible()
        assertTrue(state.isPinVisible)
    }
}

class BatteryCurveStateHolderTest {
    private val scheduler = TestScheduler()
    private val connection = FakeConnection(device())
    private val store = FakeStore()
    private val holder = BatteryCurveStateHolder(scheduler.environment(), connection, store)
    private val state get() = holder.state.value

    private fun started(): BatteryCurveStateHolder { holder.start(); scheduler.runCurrent(); return holder }

    @Test
    fun `loads li-ion by default and a stored preset by name`() {
        started()
        assertEquals(OCVPreset.LI_ION, state.selectedPreset)
        connection.deviceFlow.value = device(ocvPreset = OCVPreset.LI_FE_PO4.rawValue)
        scheduler.runCurrent()
        assertEquals(OCVPreset.LI_FE_PO4, state.selectedPreset)
        assertEquals(OCVPreset.LI_FE_PO4.ocvArray.toList(), state.voltages)
    }

    @Test
    fun `a stored custom curve needs exactly eleven integers and the parse tolerates spaces and junk like Swift`() {
        val values = (0 until 11).map { 4200L - it * 100 }
        connection.deviceFlow.value = device(ocvPreset = OCVPreset.CUSTOM.rawValue, customOCVArrayString = values.joinToString(", "))
        started()
        assertEquals(OCVPreset.CUSTOM, state.selectedPreset)
        assertEquals(values, state.voltages)
        connection.deviceFlow.value = device(id = java.util.UUID.randomUUID(), ocvPreset = OCVPreset.CUSTOM.rawValue, customOCVArrayString = "1,2,3")
        scheduler.runCurrent()
        // `OCVPreset(rawValue: "custom")` still resolves, so a short custom list shows the custom row with the li-ion table.
        assertEquals(OCVPreset.CUSTOM, state.selectedPreset)
        assertEquals(OCVPreset.LI_ION.ocvArray.toList(), state.voltages)
    }

    @Test
    fun `picking a preset saves it immediately and custom does not save`() {
        started()
        holder.onPresetSelected(OCVPreset.LI_FE_PO4)
        scheduler.runCurrent()
        assertEquals(listOf("ocv(${OCVPreset.LI_FE_PO4.rawValue},null)"), store.calls)
        holder.onPresetSelected(OCVPreset.CUSTOM)
        scheduler.runCurrent()
        assertEquals(1, store.calls.size)
    }

    @Test
    fun `validation reports the out of range row first then ordering`() {
        val base = (0 until 11).map { 4200L - it * 100 }
        assertNull(BatteryCurveStateHolder.validate(base))
        assertEquals(BatteryCurveValidationError.OutOfRange(100), BatteryCurveStateHolder.validate(base.toMutableList().also { it[0] = 999 }))
        assertEquals(BatteryCurveValidationError.OutOfRange(0), BatteryCurveStateHolder.validate(base.toMutableList().also { it[10] = 100_000 }))
        assertEquals(BatteryCurveValidationError.NotDescending, BatteryCurveStateHolder.validate(base.toMutableList().also { it[4] = it[3] }))
        assertNull(BatteryCurveStateHolder.validate(listOf(99_999L, 4200, 4100, 4000, 3900, 3800, 3700, 3600, 3500, 3400, 1000L)))
    }

    @Test
    fun `typing commits on focus loss, saves as custom, and never per keystroke`() {
        started()
        holder.onFieldFocusChanged(3, true)
        holder.onVoltageTextChanged(3, "3850")
        scheduler.runCurrent()
        assertTrue(store.calls.isEmpty())
        holder.onFieldFocusChanged(3, false)
        scheduler.runCurrent()
        assertEquals(OCVPreset.CUSTOM, state.selectedPreset)
        assertTrue(store.calls.single().startsWith("ocv(custom,"))
        assertNull(state.validationError)
    }

    @Test
    fun `an invalid edit shows the error flags the fields and saves nothing`() {
        started()
        holder.onFieldFocusChanged(5, true)
        holder.onVoltageTextChanged(5, "9999999")
        holder.onFieldFocusChanged(5, false)
        scheduler.runCurrent()
        assertEquals(BatteryCurveValidationError.OutOfRange(50), state.validationError)
        assertTrue(state.fieldHasError(5))
        assertTrue(store.calls.isEmpty())
        assertEquals(OCVPreset.LI_ION, state.selectedPreset)
    }

    @Test
    fun `focus without a change and values equal to the preset are no-ops`() {
        started()
        holder.onFieldFocusChanged(2, true)
        holder.onFieldFocusChanged(2, false)
        holder.onFieldFocusChanged(2, true)
        holder.onVoltageTextChanged(2, state.voltages[2].toString())
        holder.onFieldSubmitted(2)
        scheduler.runCurrent()
        assertTrue(store.calls.isEmpty())
        assertEquals(OCVPreset.LI_ION, state.selectedPreset)
    }

    @Test
    fun `unparseable text leaves the value and a failed save is silent`() {
        started()
        val before = state.voltages
        holder.onVoltageTextChanged(1, "abc")
        assertEquals(before, state.voltages)
        store.ocvError = IllegalStateException("db")
        holder.onPresetSelected(OCVPreset.LI_FE_PO4)
        scheduler.runCurrent()
        assertEquals(OCVPreset.LI_FE_PO4, state.selectedPreset)
    }

    @Test
    fun `the editor is disabled unless the radio is ready`() {
        started()
        assertFalse(state.isDisabled)
        connection.stateFlow.value = com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.CONNECTED
        scheduler.runCurrent()
        assertTrue(state.isDisabled)
    }
}

class DefaultFloodScopeStateHolderTest {
    private val scheduler = TestScheduler()
    private val connection = FakeConnection(device(firmwareVersion = 11u, knownRegions = listOf("Zone 10", "zone 2", "Alpha"), defaultFloodScopeName = "Alpha"))
    private val service = FakeSettingsPort()
    private val discovery = FakeDiscovery()
    private val store = FakeStore()
    private val holder = DefaultFloodScopeStateHolder(scheduler.environment(), connection, { service }, discovery, store)
    private val state get() = holder.state.value

    private fun started(): DefaultFloodScopeStateHolder { holder.start(); scheduler.runCurrent(); return holder }

    @Test
    fun `lists known regions in natural order with the current scope marked`() {
        started()
        assertEquals(listOf("Alpha", "zone 2", "Zone 10"), state.sortedKnownRegions)
        assertEquals("Alpha", state.currentScope)
    }

    @Test
    fun `sorting matches the localizedStandardCompare oracle`() {
        val input = listOf("Zone 10", "Zone 2", "zone 1", "Île", "Ile", "alpha", "Alpha", "beta", "Beta2", "Beta10", "é", "e", "f")
        assertEquals(
            listOf("alpha", "Alpha", "beta", "Beta2", "Beta10", "e", "é", "f", "Ile", "Île", "zone 1", "Zone 2", "Zone 10"),
            DefaultFloodScopeStateHolder.sortStandard(input, Locale.US),
        )
    }

    @Test
    fun `selecting disabled or a region writes verified and retries or errors on failure`() {
        started()
        holder.select(null)
        scheduler.runCurrent()
        holder.select("zone 2")
        scheduler.runCurrent()
        assertEquals(listOf("setDefaultFloodScopeVerified(null)", "setDefaultFloodScopeVerified(zone 2)"), service.calls)
        service.failNext("setDefaultFloodScopeVerified", SettingsServiceException(SettingsServiceError.SendFailed))
        holder.select("Alpha")
        scheduler.runCurrent()
        assertTrue(holder.retryAlertState.value.isPresented)
        service.failNext("setDefaultFloodScopeVerified", SettingsServiceException(SettingsServiceError.InvalidResponse))
        holder.select("Alpha")
        scheduler.runCurrent()
        assertTrue(state.errorMessage != null)
        assertFalse(state.isApplying)
    }

    @Test
    fun `scope stays busy through acknowledgment and rejects an overlapping selection`() {
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String?>()
        val held = object : SettingsRadioPort by service {
            override suspend fun setDefaultFloodScopeVerified(name: String?): String? {
                calls += name
                release.await()
                return name
            }
        }
        val bound = DefaultFloodScopeStateHolder(scheduler.environment(), connection, { held }, discovery, store)
        bound.start(); scheduler.runCurrent()
        bound.select("zone 2")
        scheduler.runCurrent()
        assertTrue(bound.state.value.isApplying)
        bound.select(null)
        scheduler.runCurrent()
        assertEquals(listOf<String?>("zone 2"), calls)
        release.complete(Unit)
        scheduler.runCurrent()
        assertFalse(bound.state.value.isApplying)
        assertNull(bound.state.value.errorMessage)
        bound.stop()
    }

    @Test
    fun `scope retry keeps the original service instead of acquiring a successor`() {
        val successor = FakeSettingsPort()
        var current = service
        val bound = DefaultFloodScopeStateHolder(scheduler.environment(), connection, { current }, discovery, store)
        bound.start(); scheduler.runCurrent()
        service.failNext("setDefaultFloodScopeVerified", SettingsServiceException(SettingsServiceError.SendFailed))
        bound.select("Alpha")
        scheduler.runCurrent()
        assertTrue(bound.retryAlertState.value.isPresented)
        current = successor
        bound.retry.retry()
        scheduler.runCurrent()
        assertEquals(2, service.calls.size)
        assertTrue(successor.calls.isEmpty())
        assertFalse(bound.state.value.isApplying)
        bound.stop()
    }

    @Test
    fun `initial scope click captures its service before the action dispatcher executes`() {
        val successor = FakeSettingsPort()
        var current = service
        val bound = DefaultFloodScopeStateHolder(scheduler.environment(), connection, { current }, discovery, store)
        bound.start(); scheduler.runCurrent()
        bound.select("Alpha")
        current = successor
        scheduler.runCurrent()
        assertEquals(listOf("setDefaultFloodScopeVerified(Alpha)"), service.calls)
        assertTrue(successor.calls.isEmpty())
        bound.stop()
    }

    @Test
    fun `a stale displayed scope cannot acquire a successor even for the same device`() {
        val first = SessionToken(ProcessEpoch(UUID(0, 1)), Generation(1), connection.deviceFlow.value!!.radioId)
        val second = first.copy(generation = Generation(2))
        val observations = MutableStateFlow(SettingsDeviceObservation(connection.deviceFlow.value, first))
        val observed = object : SettingsConnection by connection {
            override val deviceObservations = observations
        }
        val firstService = object : SettingsRadioPort by service { override val sessionToken = first }
        val successor = FakeSettingsPort()
        val secondService = object : SettingsRadioPort by successor { override val sessionToken = second }
        var current: SettingsRadioPort = firstService
        val bound = DefaultFloodScopeStateHolder(scheduler.environment(), observed, { current }, discovery, store)
        bound.start(); scheduler.runCurrent()
        current = secondService
        bound.select("Alpha")
        scheduler.runCurrent()
        assertTrue(service.calls.isEmpty())
        assertTrue(successor.calls.isEmpty())
        assertTrue(bound.state.value.errorMessage != null)
        observations.value = SettingsDeviceObservation(connection.deviceFlow.value, second)
        scheduler.runCurrent()
        bound.select("Alpha", first)
        scheduler.runCurrent()
        assertTrue(successor.calls.isEmpty(), "A retained S1 row callback cannot adopt the refreshed S2 observation")
        bound.select("Alpha")
        scheduler.runCurrent()
        assertEquals(listOf("setDefaultFloodScopeVerified(Alpha)"), successor.calls)
        assertNull(bound.state.value.errorMessage)
        bound.stop()
    }

    @Test
    fun `long and mixed aliases have one radio identity while preserving every full name`() {
        val prefix = "é".repeat(15)
        val aliases = listOf(prefix + "two", prefix, prefix + "one")
        val state = DefaultFloodScopeState(device = device(knownRegions = aliases, defaultFloodScopeName = prefix))
        assertEquals(mapOf(prefix to listOf(prefix, prefix + "one", prefix + "two")), state.regionGroups)
        assertEquals(prefix, state.currentScope)
        assertEquals(aliases.size, state.regionGroups.values.sumOf { it.size })
    }

    @Test
    fun `restored raw defaults select their normalized identity at multibyte boundaries`() {
        for ((raw, normalized) in listOf(
            "A".repeat(30) + "suffix" to "A".repeat(30),
            "é".repeat(15) + "suffix" to "é".repeat(15),
            "A".repeat(29) + "é-tail" to "A".repeat(29),
        )) {
            val restored = DefaultFloodScopeState(device = device(
                knownRegions = listOf(raw, normalized), defaultFloodScopeName = raw,
            ))
            assertEquals(normalized, restored.currentScope)
            assertEquals(setOf(normalized), restored.regionGroups.keys)
            assertEquals(raw, restored.device!!.defaultFloodScopeName, "Rendering must not rewrite restored storage")
        }
    }

    @Test
    fun `teardown during service acquisition is routed through the scope action instead of escaping the click`() {
        val bound = DefaultFloodScopeStateHolder(
            scheduler.environment(), connection, { throw SettingsNotConnectedException() }, discovery, store,
        )
        bound.start(); scheduler.runCurrent()
        bound.select("Alpha")
        scheduler.runCurrent()
        assertFalse(bound.state.value.isApplying)
        assertTrue(bound.state.value.errorMessage != null)
        assertTrue(service.calls.isEmpty())
        bound.stop()
    }

    @Test
    fun `discovery adds new regions and reports each empty outcome`() {
        started()
        discovery.outcome = RegionDiscoveryOutcome.Completed(listOf("North", "South"), allRepeatersTableFull = false)
        holder.discover(); scheduler.runCurrent()
        assertEquals(listOf("add(North)", "add(South)"), store.calls)
        assertNull(state.discoveryMessageId)
        assertEquals(listOf(listOf("Zone 10", "zone 2", "Alpha") to false), discovery.calls.map { it.first to it.second })
        val cases = mapOf(
            RegionDiscoveryOutcome.NoRepeatersResponded to R.string.l10n_app_chats_chats_channelinfo_region_norepeatersresponded,
            RegionDiscoveryOutcome.ErrorLoadingRepeaters to R.string.l10n_app_chats_chats_channelinfo_region_errloadingrepeaters,
            RegionDiscoveryOutcome.Completed(emptyList(), true) to R.string.l10n_app_chats_chats_channelinfo_region_errradiocontactsfull,
            RegionDiscoveryOutcome.Completed(emptyList(), false) to R.string.l10n_app_chats_chats_channelinfo_region_nonewregions,
        )
        for ((outcome, id) in cases) {
            discovery.outcome = outcome
            holder.discover(); scheduler.runCurrent()
            assertEquals(id, state.discoveryMessageId)
        }
        discovery.outcome = RegionDiscoveryOutcome.SendFailed
        holder.discover(); scheduler.runCurrent()
        assertNull(state.discoveryMessageId)
        assertFalse(state.isDiscovering)
    }

    @Test
    fun `leaving the section cancels a running discovery`() {
        started()
        discovery.gate = { kotlinx.coroutines.awaitCancellation() }
        holder.discover(); scheduler.runCurrent()
        assertTrue(state.isDiscovering)
        holder.stop(); scheduler.runCurrent()
        assertFalse(state.isDiscovering)
        assertTrue(store.calls.isEmpty())
    }
}

class RegenerateIdentityStateHolderTest {
    private val scheduler = TestScheduler()
    private val service = FakeSettingsPort()
    private var identity = GeneratedIdentity(Bytes(byteArrayOf(0x0A, 0xFF.toByte(), 0x10)), Bytes(ByteArray(64) { it.toByte() }))
    private var requestedPrefix: String? = "unset"
    private var generateError: Throwable? = null
    private val keys = object : IdentityKeyPort {
        override fun isValidExpandedKey(key: Bytes) = true
        override suspend fun generateIdentity(hexPrefix: String?): GeneratedIdentity {
            requestedPrefix = hexPrefix
            generateError?.let { throw it }
            return identity
        }
    }
    private val holder = RegenerateIdentityStateHolder(scheduler.environment(), keys) { service }
    private val state get() = holder.state.value

    @Test
    fun `the prefix keeps ASCII hex only, uppercased, at most four digits, matching the oracle`() {
        val table = mapOf("ab12" to "AB12", "AB 12" to "AB12", "ﬀ" to "FF", "ß" to "", "ǆ0" to "0", "ａｂ" to "", "12345" to "1234",
            "0xff" to "0FF", "g1h2" to "12", "ıa" to "A", "ﬁ" to "F", "ẞ" to "", "ǰ" to "")
        for ((typed, expected) in table) { holder.sanitizePrefix(typed); assertEquals(expected, state.hexPrefix, "[$typed]") }
    }

    @Test
    fun `reserved prefixes are rejected before generating`() {
        for (prefix in listOf("00", "000", "FF", "ff", "ﬀ")) {
            holder.sanitizePrefix(prefix)
            holder.generateKey()
            scheduler.runCurrent()
            assertEquals(UiText.Resource(AppSettingsStrings.regenerateIdentityPrefixErrorReserved), state.prefixError, prefix)
            assertNull(state.generatedKey)
        }
        holder.sanitizePrefix("0")
        holder.generateKey(); scheduler.runCurrent()
        assertNull(state.prefixError)
        assertEquals("0", requestedPrefix)
    }

    @Test
    fun `generating formats the key for display and for TalkBack`() {
        holder.generateKey(); scheduler.runCurrent()
        assertNull(requestedPrefix)
        assertEquals("0A FF 10", state.generatedKey?.publicKeyHex)
        assertEquals("0A, FF, 10", state.generatedKey?.accessibilityLabel)
        assertTrue(state.generatedKey!!.privateKeyHex.startsWith("00 01 02"))
        assertFalse(state.isGenerating)
    }

    @Test
    fun `a generation failure shows the error and cancelling is quiet`() {
        generateError = IllegalStateException("entropy")
        holder.generateKey(); scheduler.runCurrent()
        assertTrue(state.errorMessage != null)
        generateError = null
        holder.dismissError()
        holder.generateKey()
        holder.cancelGeneration()
        scheduler.runCurrent()
        assertNull(state.errorMessage)
        assertFalse(state.isGenerating)
    }

    @Test
    fun `replacing imports the key then refreshes device info and bumps the success trigger`() {
        holder.generateKey(); scheduler.runCurrent()
        holder.requestReplace()
        assertTrue(state.showingReplaceAlert)
        assertTrue(scheduler.run { holder.replaceIdentity() })
        assertEquals(listOf("importPrivateKey(64)", "refreshDeviceInfo"), service.calls)
        assertEquals(1, state.successTrigger)
        assertFalse(state.isImporting)
    }

    @Test
    fun `import failures map to the feature-disabled and device-rejected copy`() {
        holder.generateKey(); scheduler.runCurrent()
        service.failNext("importPrivateKey", SettingsServiceException(SettingsServiceError.SessionError(MeshCoreException.FeatureDisabled())))
        assertFalse(scheduler.run { holder.replaceIdentity() })
        assertEquals(UiText.Resource(AppSettingsStrings.regenerateIdentityErrorFeatureDisabled), state.errorMessage)
        service.failNext("importPrivateKey", SettingsServiceException(SettingsServiceError.SessionError(MeshCoreException.DeviceError(2u))))
        assertFalse(scheduler.run { holder.replaceIdentity() })
        assertEquals(UiText.Resource(AppSettingsStrings.regenerateIdentityErrorDeviceRejected), state.errorMessage)
        service.failNext("importPrivateKey", IllegalStateException("other"))
        assertFalse(scheduler.run { holder.replaceIdentity() })
        assertEquals(UiText.Verbatim("other"), state.errorMessage)
        assertEquals(0, state.successTrigger)
    }

    @Test
    fun `replace without a generated key or a service is a no-op and cancellation propagates`() {
        assertFalse(scheduler.run { holder.replaceIdentity() })
        holder.generateKey(); scheduler.runCurrent()
        val orphan = RegenerateIdentityStateHolder(scheduler.environment(), keys) { null }
        assertFalse(scheduler.run { orphan.replaceIdentity() })
        service.failNext("importPrivateKey", CancellationException("c"))
        var thrown: Throwable? = null
        scheduler.scope.launch { try { holder.replaceIdentity() } catch (e: CancellationException) { thrown = e } }
        scheduler.runCurrent()
        assertTrue(thrown is CancellationException)
    }
}
