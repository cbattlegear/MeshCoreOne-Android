// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.mqtt

import com.meshcoreone.android.core.observer.prizm.HttpRequest
import com.meshcoreone.android.core.observer.prizm.HttpResponse
import com.meshcoreone.android.core.observer.prizm.HttpTransport
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrokerDirectoryTest {
    private var base = "https://mqtt.nodakmesh.org"
    private var reply: () -> HttpResponse = { HttpResponse(200, """{"enabled":true,"host":"mqtt.nodakmesh.org","port":443,"transport":"websockets","tls":true,"audience":"mqtt.nodakmesh.org","regions":[]}""") }
    private val asked = mutableListOf<HttpRequest>()
    private val directory = BrokerDirectory({ base }, HttpTransport { asked += it; reply() })

    @Test fun fallsBackToTheBaseUrlHostBeforeAnyFetch() {
        assertEquals(BrokerConfig("mqtt.nodakmesh.org", 443, true, "mqtt.nodakmesh.org"), directory.current())
        base = "https://beta.example.org:8443"
        assertEquals(BrokerConfig("beta.example.org", 443, true, "beta.example.org"), directory.current())
    }

    @Test fun readsHostPortAudienceFromObserverJoin() = runBlocking {
        reply = { HttpResponse(200, """{"enabled":true,"host":"broker.example.org","port":8884,"tls":true,"audience":"aud.example.org"}""") }
        val config = directory.refresh()
        assertEquals(BrokerConfig("broker.example.org", 8884, true, "aud.example.org"), config)
        assertEquals("https://mqtt.nodakmesh.org/api/config/observer-join", asked.single().url)
        assertEquals("wss://broker.example.org:8884/", config.endpoint().url)
        assertEquals("aud.example.org", directory.audience())
    }

    @Test fun aMissingAudienceDefaultsToTheHost() = runBlocking {
        reply = { HttpResponse(200, """{"enabled":true,"host":"h.example.org","port":443,"tls":true}""") }
        assertEquals("h.example.org", directory.refresh().audience)
    }

    @Test fun disabledOrUnreadableAnswersKeepTheLastGoodOrDefaults() = runBlocking {
        reply = { HttpResponse(200, """{"enabled":false}""") }
        assertFalse(directory.refresh().enabled)
        reply = { HttpResponse(200, """{"enabled":true,"host":"good.example.org","port":443,"tls":true}""") }
        assertEquals("good.example.org", directory.refresh().host)
        for (bad in listOf(HttpResponse(500, "x"), HttpResponse(200, "<html>"))) {
            reply = { bad }; assertEquals("good.example.org", directory.refresh().host)
        }
        reply = { throw IOException("offline") }
        assertEquals("good.example.org", directory.refresh().host)
    }

    @Test fun neverDowngradesToPlainWebSocketAndRejectsOddHostsAndPorts() = runBlocking {
        reply = { HttpResponse(200, """{"enabled":true,"host":"h.example.org","port":80,"tls":false}""") }
        assertTrue(directory.refresh().useTls); assertEquals("mqtt.nodakmesh.org", directory.current().host, "tls:false answer ignored")
        reply = { HttpResponse(200, """{"enabled":true,"host":"evil host/x","port":99999,"tls":true}""") }
        val config = directory.refresh()
        assertEquals("mqtt.nodakmesh.org", config.host); assertEquals(443, config.port)
    }

    @Test fun aChangedBaseUrlDropsTheCachedAnswer() = runBlocking {
        reply = { HttpResponse(200, """{"enabled":true,"host":"prod-broker.example.org","port":443,"tls":true}""") }
        directory.refresh()
        base = "https://beta.example.org"
        assertEquals("beta.example.org", directory.current().host)
    }
}
