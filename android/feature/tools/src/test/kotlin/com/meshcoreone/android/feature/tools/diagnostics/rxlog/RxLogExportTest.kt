// AndroidOnly: WP-316 Android share-sheet CSV export contract.
package com.meshcoreone.android.feature.tools.diagnostics.rxlog

import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.junit.Test

class RxLogExportTest {
    @Test
    fun `export is deterministic and escapes text without dropping packet bytes`() {
        val entry = rxEntry(RouteType.DIRECT, PayloadType.TEXT_MESSAGE).copy(
            decodedText = "hello, \"mesh\"\nnext",
            decryptStatus = DecryptStatus.SUCCESS,
            rawPayload = Bytes.of(0x00, 0xAF, 0xFF),
        )

        val csv = RxLogExport.csv(listOf(entry))

        assertEquals(csv, RxLogExport.csv(listOf(entry)))
        assertContains(csv, "timestamp,route,type,rssi,snr,decrypt_status")
        assertContains(csv, "\"hello, \"\"mesh\"\"\nnext\"")
        assertContains(csv, "\"00AFFF\"")
        assertContains(csv, "\"SUCCESS\"")
    }
}
