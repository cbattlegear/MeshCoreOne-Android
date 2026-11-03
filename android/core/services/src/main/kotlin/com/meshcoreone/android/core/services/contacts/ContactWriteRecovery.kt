// AndroidOnly: WP-209 Distinguishes radio acceptance from incomplete local contact accounting.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.WeakHashMap

/**
 * The radio accepted this write; a full contact sync is required, not a claimed rollback.
 * A null cause means atomic persistence retained a newer local frame instead of this one.
 */
class ContactWriteCompletionFailed(
    val radioId: RadioId,
    val publicKey: Bytes,
    cause: Exception? = null,
) : Exception("Contact accepted by radio; local completion requires reconciliation", cause)

/**
 * Preferences survive graph replacement; epochs prevent an older snapshot from clearing a newer
 * uncertainty. The weak key is the process-owned preference role, shared by successive graphs.
 */
internal object ContactWriteRecovery {
    private val epochs = WeakHashMap<ContactPreferenceFlags, MutableMap<RadioId, Any>>()

    @Synchronized
    fun snapshot(preferences: ContactPreferenceFlags, radioId: RadioId): Any? =
        if (preferences.bool(key(radioId))) epochs.getOrPut(preferences) { mutableMapOf() }.getOrPut(radioId) { Any() }
        else null

    @Synchronized
    fun require(preferences: ContactPreferenceFlags, radioId: RadioId) {
        preferences.set(key(radioId), true)
        epochs.getOrPut(preferences) { mutableMapOf() }[radioId] = Any()
    }

    @Synchronized
    fun reconciled(preferences: ContactPreferenceFlags, radioId: RadioId, epoch: Any) {
        if (epochs[preferences]?.get(radioId) === epoch) {
            preferences.set(key(radioId), false)
            epochs[preferences]?.remove(radioId)
        }
    }

    private fun key(radioId: RadioId): String = "contactWriteReconciliation.${radioId.value}"
}
