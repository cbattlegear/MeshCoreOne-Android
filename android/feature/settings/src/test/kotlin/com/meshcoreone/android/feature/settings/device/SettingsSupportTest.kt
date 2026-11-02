// AndroidOnly: WP-317 Retry-alert counting and failure routing shared by every settings section.
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.feature.settings.device.ui.showPhysicalConnectionSettings
import com.meshcoreone.android.feature.settings.device.support.TestScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

class SettingsSupportTest {
    private val scheduler = TestScheduler()
    private val alert = RetryAlertController()
    private val router = SettingsFailureRouter(scheduler.environment(), alert)

    @Test
    fun `simulator does not expose bluetooth or wifi connection settings`() {
        assertFalse(showPhysicalConnectionSettings(TransportType.SIMULATOR))
        assertTrue(showPhysicalConnectionSettings(TransportType.BLUETOOTH))
        assertTrue(showPhysicalConnectionSettings(TransportType.WIFI))
        assertTrue(showPhysicalConnectionSettings(null))
    }

    @Test
    fun `retry count rises per show and max retries is the third`() {
        repeat(2) { alert.show(UiText.Verbatim("x"), {}, {}) }
        assertFalse(alert.state.value.isMaxRetriesExceeded)
        alert.show(UiText.Verbatim("x"), {}, {})
        assertTrue(alert.state.value.isMaxRetriesExceeded)
        assertEquals(3, alert.state.value.retryCount)
    }

    @Test
    fun `cancel resets the count and drops the callbacks`() {
        var retried = 0
        alert.show(UiText.Verbatim("x"), { retried++ }, {})
        alert.cancel()
        alert.retry()
        assertEquals(0, retried)
        assertFalse(alert.state.value.isPresented)
        assertEquals(0, alert.state.value.retryCount)
    }

    @Test
    fun `retry runs the callback and dismisses the dialog but keeps the count`() {
        var retried = 0
        alert.show(UiText.Verbatim("x"), { retried++ }, {})
        alert.retry()
        assertEquals(1, retried)
        assertFalse(alert.state.value.isPresented)
        assertEquals(1, alert.state.value.retryCount)
    }

    @Test
    fun `retryable service failures go to the alert and others return their message`() {
        assertNull(router.route(SettingsServiceException(SettingsServiceError.NotConnected)) {})
        assertTrue(alert.state.value.isPresented)
        assertNull(router.route(SettingsServiceException(SettingsServiceError.SessionError(MeshCoreException.Timeout()))) {})
        assertEquals(UiText.Verbatim("Invalid response from device"), router.route(SettingsServiceException(SettingsServiceError.InvalidResponse)) {})
        assertEquals(UiText.Verbatim("Device not connected"), router.route(SettingsNotConnectedException()) {})
    }

    @Test
    fun `cancellation is never routed`() {
        assertFailsWith<CancellationException> { router.route(CancellationException("c")) {} }
    }
}
