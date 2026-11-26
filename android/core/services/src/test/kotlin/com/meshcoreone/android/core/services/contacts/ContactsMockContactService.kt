// PortedFrom: MC1Services/Tests/MC1ServicesTests/Mocks/MockContactService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ContactServiceProtocol
import com.meshcoreone.android.core.contracts.domain.ContactSyncResult
import com.meshcoreone.android.core.model.RadioId
import java.time.Instant

/**
 * Mock implementation of [ContactServiceProtocol] for testing.
 *
 * Configure the mock by setting the stub before calling methods; track calls by examining the
 * recorded invocations. State is lock-confined, standing in for the Swift actor.
 */
internal class ContactsMockContactService : ContactServiceProtocol {
    data class SyncContactsInvocation(val radioId: RadioId, val since: Instant?)

    private val lock = Any()
    private var stubbedResult: Result<ContactSyncResult> = Result.success(
        ContactSyncResult(contactsReceived = 0, lastSyncTimestamp = 0u, isIncremental = false),
    )
    private val invocations = mutableListOf<SyncContactsInvocation>()

    /** Result to return from [syncContacts]. */
    val stubbedSyncContactsResult: Result<ContactSyncResult> get() = synchronized(lock) { stubbedResult }

    val syncContactsInvocations: List<SyncContactsInvocation> get() = synchronized(lock) { invocations.toList() }

    override suspend fun syncContacts(radioId: RadioId, since: Instant?): ContactSyncResult {
        val result = synchronized(lock) {
            invocations += SyncContactsInvocation(radioId, since)
            stubbedResult
        }
        return result.getOrThrow()
    }

    /** Resets all recorded invocations. */
    fun reset() = synchronized(lock) { invocations.clear() }

    /** Sets the stubbed result for [syncContacts]. */
    fun setStubbedSyncContactsResult(result: Result<ContactSyncResult>) = synchronized(lock) { stubbedResult = result }
}
