// AndroidOnly: WP-211 Real-session companion CLI read framing and error regressions.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRadioCLITest {
    private fun raw(code: Int, payload: Bytes) = Bytes.of(code) + payload

    @Test
    fun framedValuesArePreservedAndOnlyExactUnknownRepliesReturnNull() = runTest {
        val f = settingsFixture()
        try {
            val value = request { f.settings.getRadioCLIValue("wifi.ssid") }
            reply(f, 2, raw(0x42, Bytes.utf8("00|get wifi.ssid")), raw(0x1d, Bytes.utf8("00|> network \r\n")))
            assertEquals("network \r\n", value.await())

            val missing = request { f.settings.getRadioCLIValue("wifi.enabled") }
            reply(f, 3, raw(0x42, Bytes.utf8("01|get wifi.enabled")), raw(0x1d, Bytes.utf8("01|??: wifi.enabled")))
            assertNull(missing.await())

            val unsupported = request { f.settings.getRadioCLIValue("wifi.enabled") }
            reply(f, 4, raw(0x42, Bytes.utf8("02|get wifi.enabled")), raw(0x1d, Bytes.utf8("02|Unknown command")))
            assertNull(unsupported.await())
        } finally {
            f.close()
        }
    }

    @Test
    fun rejectedAndUnexpectedRepliesRemainDistinctTypedFailures() = runTest {
        val f = settingsFixture()
        try {
            val rejected = request { f.settings.getRadioCLIValue("wifi.ssid") }
            reply(f, 2, raw(0x42, Bytes.utf8("00|get wifi.ssid")), raw(0x1d, Bytes.utf8("00|Error: unsupported\r\n")))
            assertEquals(
                SettingsServiceError.RadioCLIRejected("Error: unsupported"),
                assertFailsWith<SettingsServiceException> { rejected.await() }.error,
            )
            val invalid = request { f.settings.getRadioCLIValue("wifi.ssid") }
            reply(f, 3, raw(0x42, Bytes.utf8("01|get wifi.ssid")), raw(0x1d, Bytes.utf8("01|??: wifi.enabled")))
            assertEquals(SettingsServiceError.InvalidResponse, assertFailsWith<SettingsServiceException> { invalid.await() }.error)
        } finally {
            f.close()
        }
    }
}
