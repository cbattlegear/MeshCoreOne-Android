// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.prizm

/** Paths from the Prizm server contract "as built", joined onto the base URL by [HttpPrizmClient]. */
object PrizmEndpoints {
    const val DEFAULT_BASE_URL = "https://mqtt.nodakmesh.org"
    /** Custom Tab: `?code_challenge=<S256>&code_challenge_method=S256&state=<8-128 chars>`. */
    const val AUTHORIZE = "/api/prizm/auth/app/login"
    /** POST `{"code","codeVerifier"}` -> `{"token":"pza_...","expiresAt":<unix s>,"user":{"name"}}`; tokens last 180 days, no refresh. */
    const val TOKEN = "/api/prizm/app/token"
    const val LOGOUT = "/api/prizm/app/logout"
    const val ME = "/api/prizm/me"
    /** POST `{"pk"}` -> `{"nonce","message","expiresAt"}`. */
    const val CLAIM_START = "/api/prizm/app/claim/start"
    /** POST `{"pk","nonce","signature","nodeType","device","takeOver"}`; 409 `owned`, 410 nonce expired. */
    const val CLAIM_FINISH = "/api/prizm/app/claim/finish"
    /** PUT `{"pk","nodeType","device"}`: update an already claimed node without a signature. */
    const val NODE_UPDATE = "/api/prizm/app/node"
    /** DELETE `<NODE_REMOVE>/<pk>`. */
    const val NODE_REMOVE = "/api/prizm/app/node"
    const val DATA_DELETE = "/api/prizm/app/data/delete"
    const val OBSERVER_JOIN = "/api/config/observer-join"
}
