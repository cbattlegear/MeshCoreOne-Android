// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.prizm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrizmBaseUrlTest {
    @Test fun acceptsHttpsAndNormalises() {
        assertEquals("https://beta.nodakmesh.org", PrizmBaseUrl.parse(" https://Beta.NodakMesh.org/ "))
        assertEquals("https://beta.example.org:8443/prizm", PrizmBaseUrl.parse("https://beta.example.org:8443/prizm/"))
    }

    @Test fun plainHttpOnlyForDeveloperAndPrivateNetworks() {
        assertEquals("http://10.0.2.2:8080", PrizmBaseUrl.parse("http://10.0.2.2:8080"))
        assertEquals("http://192.168.1.20", PrizmBaseUrl.parse("http://192.168.1.20/"))
        assertEquals("http://localhost:3000", PrizmBaseUrl.parse("http://localhost:3000"))
        assertEquals("http://172.20.0.5", PrizmBaseUrl.parse("http://172.20.0.5"))
        for (bad in listOf("http://mqtt.nodakmesh.org", "http://172.32.0.1", "http://8.8.8.8", "http://10.0.0")) assertNull(PrizmBaseUrl.parse(bad), bad)
    }

    @Test fun rejectsEverythingElse() {
        for (bad in listOf("", "  ", "mqtt.nodakmesh.org", "ftp://x.org", "https://", "https://u:p@x.org", "https://x.org?a=1", "https://x.org#f", "javascript:alert(1)", "https://exa mple.org")) {
            assertNull(PrizmBaseUrl.parse(bad), bad)
        }
    }
}
