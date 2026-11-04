// AndroidOnly: WP-305 Reject retired preset updates at replacement before any device persistence.
package com.meshcoreone.android.core.connectivity

import com.meshcoreone.android.core.connectivity.device.ConnectedDeviceAccess
import com.meshcoreone.android.core.connectivity.device.ConnectedDeviceEditor
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
import java.util.UUID
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionBoundDeviceEditTest {
    private class Access : ConnectedDeviceAccess {
        var device: DeviceDTO? = testDevice()
        var token: SessionToken? = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), device!!.radioId)
        var beforeReplace: () -> Unit = {}
        override val connectedDevice get() = device
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
        val editor = ConnectedDeviceEditor(access, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant })
        val preset = PRESET_ROWS.first()
        val job = editor.updateDevice(selfInfo(preset), preset.id, access.token!!)
        assertNotNull(job).join()
        assertEquals(preset.id, access.device!!.appliedRadioPresetID)
        assertEquals(access.device, devices.rows[access.device!!.id])
    }

    @Test fun disconnectBetweenReadAndReplacementDoesNotPersist() = runtimeScenario {
        val access = Access()
        val original = access.token!!
        access.beforeReplace = { access.token = null; access.device = null }
        val editor = ConnectedDeviceEditor(access, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant })
        val preset = PRESET_ROWS.first()
        assertNull(editor.updateDevice(selfInfo(preset), preset.id, original))
        assertNull(access.device)
        assertTrue(devices.rows.isEmpty())
    }

    @Test fun sameRadioNewGenerationBetweenReadAndReplacementDoesNotPersist() = runtimeScenario {
        val access = Access()
        val original = access.token!!
        val originalDevice = access.device
        access.beforeReplace = { access.token = original.copy(generation = Generation(2)) }
        val editor = ConnectedDeviceEditor(access, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant })
        val preset = PRESET_ROWS.first()
        assertNull(editor.updateDevice(selfInfo(preset), preset.id, original))
        assertEquals(originalDevice, access.device)
        assertTrue(devices.rows.isEmpty())
    }

    @Test fun replacementRadioIsNeverEditedOrPersisted() = runtimeScenario {
        val access = Access()
        val original = access.token!!
        val replacement = testDevice(radioId = RadioId(UUID.randomUUID()), nodeName = "Replacement")
        access.beforeReplace = {
            access.token = original.copy(generation = Generation(2), radioId = replacement.radioId)
            access.device = replacement
        }
        val editor = ConnectedDeviceEditor(access, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant })
        val preset = PRESET_ROWS.first()
        assertNull(editor.updateDevice(selfInfo(preset), preset.id, original))
        assertEquals(replacement, access.device)
        assertTrue(devices.rows.isEmpty())
    }
}
