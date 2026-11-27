// AndroidOnly: WP-101 Prizm Mobile Observer persistence on app-private SharedPreferences (tokens sealed by the Keystore).
package com.meshcoreone.android.app.prizm

import android.content.Context
import android.content.SharedPreferences
import com.meshcoreone.android.core.observer.claim.NodeType
import com.meshcoreone.android.core.observer.prizm.PrizmSession
import com.meshcoreone.android.feature.settings.prizm.PrizmZone

/**
 * The app has no generic key-value port outside the backup-contract `PreferenceStore`, whose keys are enumerated by the backup
 * format, so the observer keeps its own small file. It is excluded from device transfer by the app's data-extraction rules
 * (backup is off), and the two secrets in it are sealed by [SecretBox].
 */
class SharedPreferencesPrizmObserverStore(
    private val prefs: SharedPreferences,
    private val secrets: SecretBox,
) : PrizmObserverStore {
    constructor(context: Context, secrets: SecretBox) :
        this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE), secrets)

    override fun uploadEnabled() = prefs.getBoolean(UPLOAD, false)
    override fun setUploadEnabled(enabled: Boolean) = prefs.edit().putBoolean(UPLOAD, enabled).apply()
    override fun shareLocation() = prefs.getBoolean(SHARE, false)
    override fun setShareLocation(enabled: Boolean) = prefs.edit().putBoolean(SHARE, enabled).apply()
    override fun zones() = PrizmStoreCodecs.decodeZones(prefs.getString(ZONES, "").orEmpty())
    override fun saveZones(zones: List<PrizmZone>) = prefs.edit().putString(ZONES, PrizmStoreCodecs.encodeZones(zones)).apply()

    override fun nodeType(nodeKeyHex: String): NodeType? =
        prefs.getString(NODE_TYPE_PREFIX + nodeKeyHex, null)?.let { wire -> NodeType.entries.firstOrNull { it.wireName == wire } }

    override fun setNodeType(nodeKeyHex: String, type: NodeType) =
        prefs.edit().putString(NODE_TYPE_PREFIX + nodeKeyHex, type.wireName).apply()

    override fun clearNodeType(nodeKeyHex: String) = prefs.edit().remove(NODE_TYPE_PREFIX + nodeKeyHex).apply()
    override fun serverBaseUrl(): String? = prefs.getString(SERVER_URL, null)?.takeIf { it.isNotBlank() }
    override fun setServerBaseUrl(url: String?) =
        (if (url.isNullOrBlank()) prefs.edit().remove(SERVER_URL) else prefs.edit().putString(SERVER_URL, url)).apply()
    override fun useStandIn() = prefs.getBoolean(STAND_IN, false)
    override fun setUseStandIn(enabled: Boolean) = prefs.edit().putBoolean(STAND_IN, enabled).apply()

    override fun session(): PrizmSession? =
        prefs.getString(SESSION, null)?.let(secrets::open)?.let(PrizmStoreCodecs::decodeSession)

    override fun saveSession(session: PrizmSession?) {
        val editor = prefs.edit()
        if (session == null) editor.remove(SESSION) else editor.putString(SESSION, secrets.seal(PrizmStoreCodecs.encodeSession(session)))
        editor.apply()
    }

    override fun pending(): PendingAuthorization? =
        prefs.getString(PENDING, null)?.let(secrets::open)?.let(PrizmStoreCodecs::decodePending)

    override fun savePending(pending: PendingAuthorization?) {
        val editor = prefs.edit()
        if (pending == null) editor.remove(PENDING) else editor.putString(PENDING, secrets.seal(PrizmStoreCodecs.encodePending(pending)))
        editor.apply()
    }

    /** Sign out: tokens and the pending authorization go; switches, zones and node types stay (they are the user's choices). */
    override fun clearAccount() { prefs.edit().remove(SESSION).remove(PENDING).apply() }

    private companion object {
        const val FILE = "prizm_observer"
        const val UPLOAD = "upload_enabled"
        const val SHARE = "share_location"
        const val ZONES = "zones"
        const val SESSION = "session_sealed"
        const val PENDING = "pending_sealed"
        const val NODE_TYPE_PREFIX = "node_type."
        const val SERVER_URL = "server_base_url"
        const val STAND_IN = "use_stand_in"
    }
}
