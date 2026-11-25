// PortedFrom: MeshCore/Sources/MeshCore/Session/ContactManager.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.EventList
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.MeshContact
import java.text.Collator
import java.time.Instant
import java.util.Locale

internal class ContactManager {
    private val lock = Any()
    private val contacts = linkedMapOf<String, MeshContact>()
    private val pending = linkedMapOf<String, MeshContact>()
    private var lastModified: Instant? = null
    private var completeBaseline = false
    private var invalidationVersion = 0L
    private var dirty = true
    private var autoUpdate = false

    val cachedContacts: List<MeshContact> get() = synchronized(lock) { EventList(contacts.values) }
    val cachedPendingContacts: List<MeshContact> get() = synchronized(lock) { EventList(pending.values) }
    val needsRefresh: Boolean get() = synchronized(lock) { dirty }
    val contactsLastModified: Instant? get() = synchronized(lock) { if (completeBaseline) lastModified else null }
    val invalidationGeneration: Long get() = synchronized(lock) { invalidationVersion }
    val isEmpty: Boolean get() = synchronized(lock) { contacts.isEmpty() }
    val isAutoUpdateEnabled: Boolean get() = synchronized(lock) { autoUpdate }

    fun getByName(name: String, exactMatch: Boolean = false): MeshContact? = synchronized(lock) {
        if (exactMatch) contacts.values.firstOrNull {
            it.advertisedName.lowercase(Locale.ROOT) == name.lowercase(Locale.ROOT)
        } else {
            val collator = Collator.getInstance().apply {
                strength = Collator.PRIMARY
                decomposition = Collator.CANONICAL_DECOMPOSITION
            }
            contacts.values.firstOrNull { contact ->
                val text = contact.advertisedName
                val boundaries = mutableListOf(0)
                var offset = 0
                while (offset < text.length) {
                    offset += Character.charCount(text.codePointAt(offset))
                    boundaries += offset
                }
                boundaries.indices.any { start ->
                    (start until boundaries.size).any { end ->
                        collator.compare(text.substring(boundaries[start], boundaries[end]), name) == 0
                    }
                }
            }
        }
    }

    fun getByKeyPrefix(prefix: String): MeshContact? = synchronized(lock) {
        contacts.values.firstOrNull { it.publicKey.hexString.startsWith(prefix.lowercase(Locale.ROOT)) }
    }
    fun getByKeyPrefix(prefix: Bytes): MeshContact? = synchronized(lock) {
        contacts.values.firstOrNull { it.publicKey.prefix(prefix.size) == prefix }
    }
    fun getByPublicKey(key: Bytes): MeshContact? = synchronized(lock) { contacts[key.hexString] }
    fun store(contact: MeshContact) = synchronized(lock) { contacts[contact.id] = contact; Unit }
    fun updateCache(newContacts: List<MeshContact>, lastModified: Instant) = synchronized(lock) {
        newContacts.forEach { contacts[it.id] = it }
        this.lastModified = lastModified
        completeBaseline = true
        dirty = false
    }
    fun markClean(lastModified: Instant) = synchronized(lock) { this.lastModified = lastModified; completeBaseline = true; dirty = false }
    fun markDirty() = synchronized(lock) { dirty = true; invalidationVersion += 1 }
    fun hasBaseline(since: Instant): Boolean = synchronized(lock) { completeBaseline && lastModified == since }
    fun invalidateBaseline() = synchronized(lock) {
        lastModified = null
        completeBaseline = false
        markDirty()
    }
    fun commitFetch(lastModified: Instant, complete: Boolean, startedAtInvalidation: Long): Boolean = synchronized(lock) {
        if (!complete || invalidationVersion != startedAtInvalidation) {
            invalidateBaseline()
            false
        } else {
            markClean(lastModified)
            true
        }
    }
    fun addPending(contact: MeshContact) = synchronized(lock) { pending[contact.id] = contact; Unit }
    fun popPending(publicKey: String): MeshContact? = synchronized(lock) { pending.remove(publicKey) }
    fun flushPending() = synchronized(lock) { pending.clear() }
    fun remove(contactId: String) = synchronized(lock) {
        contacts.remove(contactId)
        pending.remove(contactId)
        markDirty()
    }
    fun clear() = synchronized(lock) { contacts.clear(); pending.clear(); invalidateBaseline() }
    fun setAutoUpdate(enabled: Boolean) = synchronized(lock) { autoUpdate = enabled }

    fun trackChanges(event: MeshEvent) = synchronized(lock) {
        when (event) {
            is MeshEvent.Contact -> contacts[event.contact.id] = event.contact
            is MeshEvent.NewContact -> { pending[event.contact.id] = event.contact; markDirty() }
            is MeshEvent.Advertisement, is MeshEvent.PathUpdate, MeshEvent.ContactsFull -> markDirty()
            is MeshEvent.ContactDeleted -> {
                contacts.remove(event.publicKey.hexString)
                pending.remove(event.publicKey.hexString)
                markDirty()
            }
            else -> Unit
        }
    }
}
