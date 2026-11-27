// AndroidOnly: WP-101 Prizm Mobile Observer persistence: switches, privacy zones, claimed node types and the encrypted session.
package com.meshcoreone.android.app.prizm

import com.meshcoreone.android.core.observer.claim.NodeType
import com.meshcoreone.android.core.observer.prizm.PrizmSession
import com.meshcoreone.android.feature.settings.prizm.PrizmZone

/** A browser sign-in in flight; survives a process kill while the browser is open. Never contains tokens. */
data class PendingAuthorization(val url: String, val codeVerifier: String, val state: String)

/**
 * Everything the observer remembers. Switches and zones are plain; the session (access and refresh tokens) and the pending
 * authorization (PKCE verifier) are only ever written through a [SecretBox].
 */
interface PrizmObserverStore {
    fun uploadEnabled(): Boolean
    fun setUploadEnabled(enabled: Boolean)
    fun shareLocation(): Boolean
    fun setShareLocation(enabled: Boolean)
    fun zones(): List<PrizmZone>
    fun saveZones(zones: List<PrizmZone>)

    /** Node type chosen when the node was claimed, by lowercase hex public key. Null: not claimed from this phone. */
    fun nodeType(nodeKeyHex: String): NodeType?
    fun setNodeType(nodeKeyHex: String, type: NodeType)
    fun session(): PrizmSession?
    fun saveSession(session: PrizmSession?)
    fun pending(): PendingAuthorization?
    fun savePending(pending: PendingAuthorization?)
    fun clearAccount()
}

/** Encrypts small secrets for storage. The production implementation is Android Keystore AES-GCM. */
interface SecretBox {
    fun seal(plaintext: String): String

    /** Returns null when the value cannot be opened (key lost, corrupt); the caller treats that as signed out. */
    fun open(sealed: String): String?
}

/** Plain-text codecs, kept free of Android types so they can be tested on the JVM. */
internal object PrizmStoreCodecs {
    private val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
    private val unb64 = java.util.Base64.getUrlDecoder()

    private fun enc(value: String) = b64.encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun dec(value: String) = String(unb64.decode(value), Charsets.UTF_8)

    /** One zone per line: base64url(id);latitude;longitude;radius. Neither base64url nor a Double contains ';'. */
    fun encodeZones(zones: List<PrizmZone>): String =
        zones.joinToString("\n") { "${enc(it.id)};${it.latitude};${it.longitude};${it.radiusMeters}" }

    /** Unreadable or out-of-range lines are dropped, never thrown. */
    fun decodeZones(text: String): List<PrizmZone> = text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
        runCatching {
            val f = line.split(';')
            if (f.size != 4) return@runCatching null
            PrizmZone(dec(f[0]), f[1].toDouble(), f[2].toDouble(), f[3].toDouble())
        }.getOrNull()?.takeIf {
            it.latitude.isFinite() && it.longitude.isFinite() && it.radiusMeters.isFinite() &&
                it.radiusMeters > 0.0 && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        }
    }

    fun encodeSession(session: PrizmSession): String = listOf(
        "v1", session.accessToken, session.refreshToken.orEmpty(), session.expiresAt.toEpochMilli().toString(),
        session.user.discordUserId, session.user.displayName,
    ).joinToString(".") { enc(it) }

    fun decodeSession(text: String): PrizmSession? = runCatching {
        val f = text.split(".").map { dec(it) }
        if (f.size != 6 || f[0] != "v1") return null
        PrizmSession(
            f[1], f[2].ifEmpty { null }, java.time.Instant.ofEpochMilli(f[3].toLong()),
            com.meshcoreone.android.core.observer.prizm.PrizmUser(f[4], f[5]),
        )
    }.getOrNull()

    fun encodePending(pending: PendingAuthorization): String = listOf("v1", pending.url, pending.codeVerifier, pending.state).joinToString(".") { enc(it) }

    fun decodePending(text: String): PendingAuthorization? = runCatching {
        val f = text.split(".").map { dec(it) }
        if (f.size != 4 || f[0] != "v1") return null
        PendingAuthorization(f[1], f[2], f[3])
    }.getOrNull()
}
