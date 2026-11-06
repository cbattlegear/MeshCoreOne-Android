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
import com.meshcoreone.android.core.contracts.domain.ConnectedDeviceWriteView
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionBoundDeviceEditTest {
    private class Access : ConnectedDeviceAccess {
        var device: DeviceDTO? = testDevice()
        var token: SessionToken? = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), device!!.radioId)
        var beforeReplace: () -> Unit = {}
        var beforeTransform: () -> Unit = {}
        override val connectedDevice get() = device
        override val connectedDeviceToken get() = token
        override var ocvPublicationRevision = 0L
            private set
        override fun captureDeviceWriteView(expectedToken: SessionToken?): ConnectedDeviceWriteView? =
            (if (expectedToken == null) device else currentDevice(expectedToken))
                ?.let { ConnectedDeviceWriteView(it, ocvPublicationRevision) }
        override fun publishOCVSettings(device: DeviceDTO, expectedToken: SessionToken): ConnectedDeviceEdit? {
            val live = currentDevice(expectedToken) ?: return null
            if (live.id != device.id || live.radioId != device.radioId) return null
            val revision = ocvPublicationRevision
            val edit = replaceConnectedDevice(
                live.copy(ocvPreset = device.ocvPreset, customOCVArrayString = device.customOCVArrayString), expectedToken,
            ) ?: return null
            if (ocvPublicationRevision == revision) ocvPublicationRevision++
            return edit
        }
        override fun transformConnectedDevice(
            expectedToken: SessionToken?,
            transform: (DeviceDTO) -> DeviceDTO,
        ): ConnectedDeviceEdit? {
            val hook = beforeTransform
            beforeTransform = {}
            hook()
            return super.transformConnectedDevice(expectedToken, transform)
        }
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
            if (current.ocvPreset != device.ocvPreset || current.customOCVArrayString != device.customOCVArrayString) {
                ocvPublicationRevision++
            }
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

    @Test fun publicationRevisionDistinguishesEqualOcvAndAbaAtTheActualEditorMerge() = runtimeScenario {
        for (aba in listOf(false, true)) {
            for (maskRevision in listOf(false, true)) {
                val access = Access()
                val original = access.device!!.copy(ocvPreset = "A", customOCVArrayString = "curve-A")
                access.device = original
                val readalong = original.copy(ocvPreset = "B", customOCVArrayString = "curve-B")
                devices.saveDevice(readalong)
                val editorAccess = object : ConnectedDeviceAccess by access {
                    // Negative control recreates value-only publication without changing the editor.
                    override val ocvPublicationRevision: Long
                        get() = if (maskRevision) 0L else access.ocvPublicationRevision
                }
                val editor = ConnectedDeviceEditor(
                    editorAccess, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant },
                )
                val token = access.token!!
                var publications = 0
                access.beforeTransform = {
                    if (aba) {
                        editor.updateOCVSettings(readalong, token)
                        publications++
                    }
                    editor.updateOCVSettings(original, token)
                    publications++
                    assertEquals(original, access.device)
                    assertEquals(publications.toLong(), access.ocvPublicationRevision)
                }
                assertTrue(editor.updatePathHashMode(2u, token))
                assertEquals(if (aba) 2 else 1, publications)
                assertEquals(publications.toLong() + if (maskRevision) 1L else 0L, access.ocvPublicationRevision)
                val expectedLive = (if (maskRevision) readalong else original).copy(pathHashMode = 2u)
                assertEquals(expectedLive, access.device, "aba=$aba maskRevision=$maskRevision")
                // This is a live-publication merge control, not a second durable OCV transaction.
                assertEquals(readalong.copy(pathHashMode = 2u), devices.rows[original.id])
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
