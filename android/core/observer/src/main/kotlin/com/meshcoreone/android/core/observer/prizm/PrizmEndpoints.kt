// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.prizm

/** Paths from the Prizm mobile spec. The real HTTP client (not written yet) joins them onto [BASE_URL]. */
object PrizmEndpoints {
    const val BASE_URL = "https://mqtt.nodakmesh.org"
    const val AUTHORIZE = "/oauth/authorize"
    const val TOKEN = "/oauth/token"
    const val MQTT_CREDENTIALS = "/api/mobile/mqtt-credentials"
    const val CLAIM_START = "/api/mobile/claim/start"
    const val CLAIM_FINISH = "/api/mobile/claim/finish"
}
