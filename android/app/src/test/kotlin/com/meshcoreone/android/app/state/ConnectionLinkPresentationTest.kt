// AndroidOnly: WP-206 Same-radio link presentation regressions compared with current upstream.
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TransportType
import java.util.UUID
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConnectionLinkPresentationTest {
    private val radio = RadioId(UUID.randomUUID())
    private val device = UUID.randomUUID()
    private val bluetooth = UUID.randomUUID()
    private val dial = ConnectionLinkWiFiDial("radio.test", 5001u)

    @Test
    fun `live wifi uses only its saved endpoint while bluetooth can use a reported address`() {
        val saved = ConnectionMethod.WiFi("  saved.test  ", 5001u)
        assertEquals(dial.copy(host = "saved.test"), ConnectionLinkWiFiDial.capture(TransportType.WIFI, saved, "new.test"))
        assertNull(ConnectionLinkWiFiDial.capture(TransportType.WIFI, null, "new.test"))
        assertEquals(dial.copy(host = "saved.test"), ConnectionLinkWiFiDial.capture(TransportType.BLUETOOTH, saved, "new.test"))
        assertEquals(ConnectionLinkWiFiDial("new.test", 5000u), ConnectionLinkWiFiDial.capture(
            TransportType.BLUETOOTH, ConnectionMethod.WiFi("invalid host", 0u), " new.test ",
        ))
        assertNull(ConnectionLinkWiFiDial.capture(TransportType.BLUETOOTH, null, "invalid host"))
    }

    @Test
    fun `rows require associated wifi and retain the captured checked link during recovery`() {
        val inactive = ConnectionLinkRows.make(
            TransportType.BLUETOOTH, bluetooth, ConnectionLinkRows.WiFiSupport.SUPPORTED, false, dial, null,
        )
        assertEquals(listOf(false, false), inactive.rows.map { it.canSwitch })
        val eligible = ConnectionLinkRows.make(
            TransportType.BLUETOOTH, bluetooth, ConnectionLinkRows.WiFiSupport.SUPPORTED, true, dial, null,
        )
        assertEquals(listOf(false, true), eligible.rows.map { it.canSwitch })
        val held = started().copy(target = null)
        val recovery = ConnectionLinkRows.make(null, null, ConnectionLinkRows.WiFiSupport.UNKNOWN, false, null, held)
        assertEquals(TransportType.BLUETOOTH, recovery.checked)
        assertEquals(listOf(true, true), recovery.rows.map { it.canSwitch })
        assertEquals("radio.test", recovery.rows.last().address)
    }

    @Test
    fun `switch completion is token and radio fenced and failure keeps captured endpoints`() {
        val attempt = started()
        assertNull(ConnectionLinkSwitch.start(
            null, TransportType.BLUETOOTH, TransportType.BLUETOOTH, radio, device, bluetooth, dial, UUID.randomUUID(),
        ))
        assertNull(ConnectionLinkSwitch.start(
            null, TransportType.WIFI, TransportType.BLUETOOTH, radio, device, bluetooth, null, UUID.randomUUID(),
        ))
        assertNull(ConnectionLinkSwitch.start(
            null, TransportType.WIFI, TransportType.SIMULATOR, radio, device, bluetooth, dial, UUID.randomUUID(),
        ))
        assertNull(ConnectionLinkSwitch.start(
            attempt, TransportType.BLUETOOTH, null, radio, device, bluetooth, dial, UUID.randomUUID(),
        ))
        assertEquals(ConnectionLinkSwitch.Completion.Ignored, attempt.complete(UUID.randomUUID(), false, TransportType.WIFI, radio))
        assertEquals(ConnectionLinkSwitch.Completion.Cleared, attempt.complete(attempt.token, false, TransportType.WIFI, radio))
        assertEquals(ConnectionLinkSwitch.Completion.Cleared, attempt.complete(attempt.token, true, null, RadioId(UUID.randomUUID())))
        val held = (attempt.complete(attempt.token, true, null, null) as ConnectionLinkSwitch.Completion.Held).record
        assertNull(ConnectionLinkSwitch.start(
            held, TransportType.BLUETOOTH, null, RadioId(UUID.randomUUID()), device, bluetooth, dial, UUID.randomUUID(),
        ))
        val retry = assertNotNull(ConnectionLinkSwitch.start(
            held, TransportType.BLUETOOTH, null, radio, device, null, ConnectionLinkWiFiDial("different.test", 5000u), UUID.randomUUID(),
        ))
        assertEquals(dial, retry.wifi)
        assertEquals(bluetooth, retry.bluetoothDeviceId)
        assertEquals(TransportType.BLUETOOTH, retry.checked)
    }

    private fun started() = assertNotNull(ConnectionLinkSwitch.start(
        null, TransportType.WIFI, TransportType.BLUETOOTH, radio, device, bluetooth, dial, UUID.randomUUID(),
    ))
}
