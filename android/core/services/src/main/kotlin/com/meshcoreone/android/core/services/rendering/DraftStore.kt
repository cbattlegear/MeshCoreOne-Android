// PortedFrom: MC1Services/Sources/MC1Services/Services/DraftStore.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.RadioId

/**
 * The slice of `UserDefaults` [DraftStore] uses: one `[String: String]` dictionary under one key.
 * Implementations persist synchronously, like `UserDefaults.set`. [stringDictionary] returns null when
 * the key is absent or does not hold a string-to-string dictionary (the Swift `as? [String: String]`
 * cast failing).
 */
interface DraftDefaults {
    fun stringDictionary(key: String): Map<String, String>?
    fun setStringDictionary(value: Map<String, String>, key: String)
}

/**
 * Store for per-conversation composer drafts, keyed by [ChatConversationID.draftStorageKey] under a
 * single [STORAGE_KEY] dictionary so drafts survive leaving a chat and app restarts.
 *
 * Drafts are not pruned by design; entries go away when a draft is sent, when the composer is emptied,
 * and when a channel slot is vacated so a reused slot can't surface the prior channel's draft. Drafts
 * are device-local and intentionally excluded from backups. State is confined behind a lock (Swift
 * confines it to the main actor); [DraftDefaults] writes happen under the lock so they stay ordered.
 */
class DraftStore(private val defaults: DraftDefaults) {
    private val lock = Any()
    private var cache: Map<String, String> = defaults.stringDictionary(STORAGE_KEY).orEmpty()
    private val revisions = mutableMapOf<String, Long>()

    /** The stored draft for a conversation, or null if none. */
    fun draft(id: ChatConversationID): String? = synchronized(lock) { cache[id.draftStorageKey] }

    fun revision(id: ChatConversationID): Long = synchronized(lock) { revisions[id.draftStorageKey] ?: 0L }

    fun restoreIfUnchanged(id: ChatConversationID, revision: Long, text: String): Boolean = synchronized(lock) {
        if (revision(id) != revision || cache[id.draftStorageKey] != null) false
        else {
            setDraft(text, id)
            true
        }
    }

    /**
     * Stores [text] verbatim, or removes the entry when [text] is empty or whitespace/newline-only
     * (Foundation `whitespacesAndNewlines`, used only for the emptiness test).
     */
    fun setDraft(text: String, id: ChatConversationID) {
        if (SwiftText.isBlankAfterTrimming(text)) {
            clearDraft(id)
            return
        }
        synchronized(lock) {
            advanceRevisionLocked(id.draftStorageKey)
            cache = cache + (id.draftStorageKey to text)
            persistLocked()
        }
    }

    /** Removes the draft for a conversation, if present; persists only when something was removed. */
    fun clearDraft(id: ChatConversationID) {
        synchronized(lock) {
            val key = id.draftStorageKey
            advanceRevisionLocked(key)
            if (key !in cache) return
            cache = cache - key
            persistLocked()
        }
    }

    /** Removes the drafts for the given channel slots on [radioId], persisting once for the batch. */
    fun clearChannelDrafts(radioId: RadioId, indices: Set<UByte>) {
        clearChannelDrafts(mapOf(radioId to indices))
    }

    /** Removes the drafts for channel slots grouped by radio, persisting once for the batch. */
    fun clearChannelDrafts(slotsByRadio: Map<RadioId, Set<UByte>>) {
        val keys = slotsByRadio.flatMap { (radioId, indices) ->
            indices.map { ChatConversationID.channel(radioId, it).draftStorageKey }
        }
        synchronized(lock) {
            keys.forEach(::advanceRevisionLocked)
            val remaining = cache - keys.toSet()
            if (remaining.size == cache.size) return
            cache = remaining
            persistLocked()
        }
    }

    /**
     * Pure restore decision: the saved draft only when the composer is empty, so a reconnect-driven
     * re-restore never clobbers text the user is typing.
     */
    fun draftToApply(currentText: String, id: ChatConversationID): String? = if (currentText.isEmpty()) draft(id) else null

    private fun advanceRevisionLocked(key: String) {
        revisions[key] = Math.addExact(revisions[key] ?: 0L, 1L)
    }

    private fun persistLocked() {
        defaults.setStringDictionary(cache, STORAGE_KEY)
    }

    companion object {
        /** Top-level key holding the `[draftStorageKey: text]` map. */
        const val STORAGE_KEY: String = "chat.drafts.v1"
    }
}
