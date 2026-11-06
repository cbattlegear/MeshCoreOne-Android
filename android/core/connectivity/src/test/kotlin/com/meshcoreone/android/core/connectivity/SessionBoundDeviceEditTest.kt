// AndroidOnly: WP-305 Reject retired preset updates at replacement before any device persistence.
package com.meshcoreone.android.core.connectivity

import com.meshcoreone.android.core.connectivity.device.ConnectedDeviceAccess
import com.meshcoreone.android.core.connectivity.device.ConnectedDeviceEditor
import com.meshcoreone.android.core.connectivity.device.DeviceEditPersistenceConflict
import com.meshcoreone.android.core.connectivity.support.PRESET_MATCHER
import com.meshcoreone.android.core.connectivity.support.PRESET_ROWS
import com.meshcoreone.android.core.connectivity.support.runtimeScenario
import com.meshcoreone.android.core.connectivity.support.selfInfo
import com.meshcoreone.android.core.connectivity.support.testDevice
import com.meshcoreone.android.core.contracts.domain.ConnectedDeviceEdit
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import java.util.UUID
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionBoundDeviceEditTest {
    private enum class EventEdit { DEVICE, AUTO_ADD, REPEAT, SCOPE, HASH }

    private suspend fun apply(editor: ConnectedDeviceEditor, event: EventEdit, token: SessionToken): Boolean =
        when (event) {
            EventEdit.DEVICE -> editor.updateDevice(selfInfo(PRESET_ROWS.first()), null, token)
            EventEdit.AUTO_ADD -> editor.updateAutoAddConfig(AutoAddConfig(7u, 3u), token)
            EventEdit.REPEAT -> editor.updateClientRepeat(true, token)
            EventEdit.SCOPE -> editor.updateDefaultFloodScopeName("US915", token)
            EventEdit.HASH -> editor.updatePathHashMode(2u, token)
        }

    private class Access : ConnectedDeviceAccess {
        var device: DeviceDTO? = testDevice()
        var token: SessionToken? = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), device!!.radioId)
        var beforeReplace: () -> Unit = {}
        override val connectedDevice get() = device
        override val connectedDeviceToken get() = token
        override fun currentDevice(expectedToken: SessionToken): DeviceDTO? {
            beforeReplace()
            return device.takeIf { token == expectedToken }
        }
        override fun replaceConnectedDevice(device: DeviceDTO): ConnectedDeviceEdit? =
            error("Session-bound update must not call the unbound replacement")
        override fun replaceConnectedDevice(device: DeviceDTO, expectedToken: SessionToken): ConnectedDeviceEdit? {
            beforeReplace()
            val current = this.device ?: return null
            if (token != expectedToken || current.id != device.id || current.radioId != device.radioId) return null
            this.device = device
            return ConnectedDeviceEdit(expectedToken, 1, current.knownRegions)
        }
    }

    @Test fun currentSessionUpdateIsPersisted() = runtimeScenario {
        val access = Access()
        devices.saveDevice(access.device!!)
        val editor = ConnectedDeviceEditor(access, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant })
        val preset = PRESET_ROWS.first()
        assertTrue(editor.updateDevice(selfInfo(preset), preset.id, access.token!!))
        assertEquals(preset.id, access.device!!.appliedRadioPresetID)
        assertEquals(access.device, devices.rows[access.device!!.id])
    }

    @Test fun everySessionEventApiRejectsItsOldTokenAndAcceptsCurrentOrigin() = runtimeScenario {
        for (event in EventEdit.entries) {
            val access = Access()
            val before = access.device!!
            devices.saveDevice(before)
            val old = access.token!!
            access.token = old.copy(generation = Generation(2))
            val editor = ConnectedDeviceEditor(access, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant })
            devices.beforeConditionalSave = { error("A retired event must not reach a conditional save") }
            assertFalse(apply(editor, event, old), event.name)
            assertEquals(before, access.device)
            assertEquals(before, devices.rows[before.id])
            devices.beforeConditionalSave = {}
            assertTrue(apply(editor, event, access.token!!), event.name)
            assertEquals(access.device, devices.rows[before.id])
            assertTrue(access.device != before, "${event.name} must actually apply its current-origin fields")
        }
    }

    @Test fun everySessionEventApiPropagatesSaveFailureWithoutLivePublication() = runtimeScenario {
        for (event in EventEdit.entries) {
            val access = Access()
            val before = access.device!!
            devices.saveDevice(before)
            val failure = IOException("${event.name} disk full")
            devices.conditionalSaveFailure = failure
            val editor = ConnectedDeviceEditor(access, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant })
            val caught = assertFailsWith<IOException> { apply(editor, event, access.token!!) }
            assertEquals(failure, caught)
            assertEquals(before, access.device)
            assertEquals(before, devices.rows[before.id])
            devices.conditionalSaveFailure = null
        }
    }

    @Test fun everySessionEventApiCancellationReleasesItsQueuedWriteWithoutPublishing() = runtimeScenario {
        for (event in EventEdit.entries) {
            val access = Access()
            val before = access.device!!
            devices.saveDevice(before)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            devices.beforeConditionalSave = { entered.complete(Unit); release.await() }
            val editor = ConnectedDeviceEditor(access, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant })
            val write = scenario.scope.async { apply(editor, event, access.token!!) }
            try {
                entered.await()
                assertFalse(write.isCompleted)
                write.cancelAndJoin()
                assertTrue(write.isCancelled)
                assertEquals(before, access.device)
                assertEquals(before, devices.rows[before.id])
                devices.beforeConditionalSave = {}
                assertTrue(apply(editor, event, access.token!!), "Cancelled ${event.name} must release the editor")
            } finally {
                release.complete(Unit)
                write.cancelAndJoin()
                devices.beforeConditionalSave = {}
            }
        }
    }

    @Test fun disconnectBetweenReadAndReplacementDoesNotPersist() = runtimeScenario {
        val access = Access()
        val originalDevice = access.device!!
        devices.saveDevice(originalDevice)
        val original = access.token!!
        access.beforeReplace = { access.token = null; access.device = null }
        val editor = ConnectedDeviceEditor(access, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant })
        val preset = PRESET_ROWS.first()
        assertFalse(editor.updateDevice(selfInfo(preset), preset.id, original))
        assertNull(access.device)
        assertEquals(listOf(originalDevice), devices.rows.values.toList())
    }

    @Test fun sameRadioNewGenerationBetweenReadAndReplacementDoesNotPersist() = runtimeScenario {
        val access = Access()
        val original = access.token!!
        val originalDevice = access.device
        devices.saveDevice(originalDevice!!)
        access.beforeReplace = { access.token = original.copy(generation = Generation(2)) }
        val editor = ConnectedDeviceEditor(access, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant })
        val preset = PRESET_ROWS.first()
        assertFalse(editor.updateDevice(selfInfo(preset), preset.id, original))
        assertEquals(originalDevice, access.device)
        assertEquals(listOf(originalDevice), devices.rows.values.toList())
    }

    @Test fun replacementRadioIsNeverEditedOrPersisted() = runtimeScenario {
        val access = Access()
        val original = access.token!!
        val originalDevice = access.device!!
        devices.saveDevice(originalDevice)
        val replacement = testDevice(radioId = RadioId(UUID.randomUUID()), nodeName = "Replacement")
        access.beforeReplace = {
            access.token = original.copy(generation = Generation(2), radioId = replacement.radioId)
            access.device = replacement
        }
        val editor = ConnectedDeviceEditor(access, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant })
        val preset = PRESET_ROWS.first()
        assertFalse(editor.updateDevice(selfInfo(preset), preset.id, original))
        assertEquals(replacement, access.device)
        assertEquals(listOf(originalDevice), devices.rows.values.toList())
    }

    @Test fun acceptedEditWaitsForPersistenceAndPropagatesFailure() = runtimeScenario {
        val access = Access()
        devices.saveDevice(access.device!!)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        devices.beforeConditionalSave = { entered.complete(Unit); release.await() }
        devices.conditionalSaveFailure = IOException("disk full")
        val editor = ConnectedDeviceEditor(access, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant })
        val preset = PRESET_ROWS.first()
        val edit = scenario.scope.async {
            runCatching { editor.updateDevice(selfInfo(preset), preset.id, access.token!!) }
        }
        entered.await()
        assertFalse(edit.isCompleted)
        release.complete(Unit)
        assertEquals("disk full", edit.await().exceptionOrNull()?.message)
        assertNull(devices.rows[access.device!!.id]!!.appliedRadioPresetID)
    }

    @Test fun newerActivationCannotBeOverwrittenByAnAcceptedOlderEdit() = runtimeScenario {
        val access = Access()
        val originalDevice = access.device!!.copy(isActive = true)
        access.device = originalDevice
        devices.saveDevice(originalDevice)
        val replacement = testDevice(radioId = RadioId(UUID.randomUUID()), nodeName = "Replacement")
        devices.beforeConditionalSave = {
            devices.saveDevice(replacement)
            devices.setActiveDevice(replacement.id)
        }
        val editor = ConnectedDeviceEditor(access, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant })
        val preset = PRESET_ROWS.first()
        assertFailsWith<DeviceEditPersistenceConflict> { editor.updateDevice(selfInfo(preset), preset.id, access.token!!) }
        assertFalse(devices.rows[originalDevice.id]!!.isActive)
        assertEquals(replacement.id, devices.fetchActiveDevice()!!.id)
        assertNull(devices.rows[originalDevice.id]!!.appliedRadioPresetID)
    }

    @Test fun sameRadioSuccessorEvenWithIdenticalRestoredFieldsInvalidatesOldSave() = runtimeScenario {
        val access = Access()
        val originalDevice = access.device!!
        devices.saveDevice(originalDevice)
        devices.beforeConditionalSave = {
            devices.saveDevice(originalDevice.copy(nodeName = "Successor"))
            devices.saveDevice(originalDevice)
        }
        val editor = ConnectedDeviceEditor(access, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant })
        val preset = PRESET_ROWS.first()
        assertFailsWith<DeviceEditPersistenceConflict> { editor.updateDevice(selfInfo(preset), preset.id, access.token!!) }
        assertEquals(originalDevice, devices.rows[originalDevice.id])
    }

    @Test fun retiredPathHashEditCannotMutateOrPersistReplacement() = runtimeScenario {
        val access = Access()
        val originalDevice = access.device!!
        devices.saveDevice(originalDevice)
        val original = access.token!!
        access.beforeReplace = { access.token = original.copy(generation = Generation(2)) }
        val editor = ConnectedDeviceEditor(access, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant })
        assertFalse(editor.updatePathHashMode(2u, original))
        assertEquals(originalDevice, access.device)
        assertEquals(originalDevice, devices.rows[originalDevice.id])
    }
}
