// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.prizm

import java.io.IOException

class HttpRequest(val method: String, val url: String, val headers: Map<String, String> = emptyMap(), val body: String? = null) {
    /** Never prints header values (they carry the bearer token) or the body. */
    override fun toString(): String = "HttpRequest($method $url)"
}

class HttpResponse(val status: Int, val body: String) {
    override fun toString(): String = "HttpResponse($status, ${body.length} chars)"
}

/** The seam to an HTTP stack (OkHttp in the app). Throws [IOException] when the server cannot be reached or the call times out. */
fun interface HttpTransport {
    @Throws(IOException::class)
    suspend fun execute(request: HttpRequest): HttpResponse
}
