// AndroidOnly: WP-209 Native cancellation, event-order, boundary, real-session and cleanup-chain cases for the contact port.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.ContactAvatarUpdateResult
import com.meshcoreone.android.core.contracts.domain.ContactFavoriteUpdateResult
import com.meshcoreone.android.core.contracts.domain.ContactLocalUpdate
import com.meshcoreone.android.core.contracts.domain.ContactLocalUpdateResult
import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.model.applicationBytesFromHex
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.session.ContactSessionOps
import com.meshcoreone.android.core.protocol.session.ContactWriteDispatch
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.yield
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ContactServiceNativeTests {
    private val radioId = RadioId(UUID.randomUUID())

    private fun meshContact(keyByte: Int, name: String, lastModified: Long = 0) = MeshContact(
        id = contactsKey(keyByte).uppercaseHexString(), publicKey = contactsKey(keyByte), type = ContactType.CHAT,
        flags = ContactFlags(0u), outPathLength = 0u, outPath = Bytes.EMPTY, advertisedName = name,
        lastAdvertisement = Instant.EPOCH, latitude = 0.0, longitude = 0.0, lastModified = Instant.ofEpochSecond(lastModified),
    )

    private fun stored(keyByte: Int, name: String, flags: UByte = 0u, isFavorite: Boolean = false) = ContactDTO(
        radioId = radioId, publicKey = contactsKey(keyByte), name = name, flags = flags, isFavorite = isFavorite,
        lastHeardTimestamp = null,
    )

    private fun cliMarker(publicKey: Bytes) = "remoteCLIWriteReconciliation.${radioId.value}.${publicKey.hexString}"

    private suspend fun applyLocalEdit(service: ContactService, key: EntityKey, update: ContactLocalUpdate) {
        when (update) {
            is ContactLocalUpdate.Preferences ->
                service.updateContactPreferences(key, update.nickname, update.isBlocked, update.isFavorite)
            is ContactLocalUpdate.OCVSettings -> service.updateContactOCVSettings(key, update.preset, update.customArray)
            is ContactLocalUpdate.TelemetryPermissions -> service.setTelemetryPermissions(key, update.granted)
        }
    }

    @TestFactory
    fun radioCliReviewRegressions(): List<DynamicTest> = listOf(
        contactsNative("a rejected local block is not reported as a successful block with CLI failure") {
            val backing = ContactsFakeStore()
            val contact = stored(0x71, "Chat", 0xb5u).copy(typeRawValue = 1u).also(backing::seed)
            val failure = IllegalStateException("local transaction rolled back")
            val store = object : ContactPersisting by backing {
                override suspend fun updateContactLocalFields(
                    key: EntityKey, expectedPublicKey: Bytes, update: ContactLocalUpdate,
                ): ContactLocalUpdateResult = throw failure
            }
            val radio = ContactsFakeSession()
            val service = ContactService(radio, backing, store, null, null, ContactsFakePreferences(),
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            assertSame(failure, runCatching {
                service.updateContactPreferences(EntityKey(radioId, contact.id), isBlocked = true)
            }.exceptionOrNull())
            assertFalse(assertNotNull(backing.fetchContact(EntityKey(radioId, contact.id))).isBlocked)
            assertFalse(service.needsRemoteCLIWriteReconciliation(radioId))
            assertTrue(radio.addContactInvocations.isEmpty())
        },
        contactsNative("a delayed path snapshot cannot restore a revoked CLI permission") {
            val backing = ContactsFakeStore()
            val contact = stored(0x71, "Chat", 0xb5u).copy(typeRawValue = 1u).also(backing::seed)
            val captured = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var hold = true
            val store = object : ContactPersisting by backing {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    val snapshot = backing.fetchContact(radioId, publicKey)
                    if (hold) {
                        hold = false
                        captured.complete(Unit)
                        release.await()
                    }
                    return snapshot
                }
            }
            val radio = ContactsFakeSession().apply {
                setStubbedContacts(listOf(meshContact(0x71, "Chat").copy(flags = ContactFlags(0xb5u))))
            }
            val service = ContactService(radio, backing, store, null, null, ContactsFakePreferences(),
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            val path = async { runCatching { service.setPath(radioId, contact.publicKey, Bytes.of(1), 1u) } }
            captured.await()
            service.updateContactPreferences(EntityKey(radioId, contact.id), isBlocked = true)
            release.complete(Unit)
            assertFailsWith<ContactServiceError.SessionError> { path.await().getOrThrow() }
            assertEquals(1, radio.addContactInvocations.size)
            assertEquals(0xa5u.toUByte(), backing.fetchContact(EntityKey(radioId, contact.id))?.flags)
            assertFalse(service.needsRemoteCLIWriteReconciliation(radioId))
        },
        contactsNative("block cleanup cancellation and failure retain a definitely unsent retry obligation") {
            for (failure in listOf(CancellationException("closed sheet"), IllegalStateException("cleanup failed"))) {
                val backing = ContactsFakeStore()
                val contact = stored(0x71, "Chat", 0xb5u).copy(typeRawValue = 1u).also(backing::seed)
                val preferences = ContactsFakePreferences()
                val radio = ContactsFakeSession()
                val cleanup = object : ContactCleanupHandling {
                    override suspend fun handleCleanup(contact: EntityKey, reason: ContactCleanupReason, publicKey: Bytes) {
                        throw failure
                    }
                }
                val service = ContactService(radio, backing, backing, null, cleanup, preferences,
                    ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
                val result = runCatching { service.updateContactPreferences(EntityKey(radioId, contact.id), isBlocked = true) }
                if (failure is CancellationException) assertSame(failure, result.exceptionOrNull())
                else assertTrue(result.exceptionOrNull() is ContactRemoteCLIRevocationFailed)
                assertTrue(assertNotNull(backing.fetchContact(EntityKey(radioId, contact.id))).isBlocked)
                assertEquals(setOf(contact.publicKey), service.remoteCLIRecoveryScope(radioId).failedRevocations)
                assertFalse(service.remoteCLIRecoveryScope(radioId).uncertain)
                assertTrue(radio.addContactInvocations.isEmpty())
            }
        },
        contactsNative("connected and offline late commit cancellation verify the block before recording recovery") {
            for (offline in listOf(false, true)) {
                for (commit in listOf(false, true)) {
                    val backing = ContactsFakeStore()
                    val contact = stored(0x71, "Chat", 0xb5u).copy(typeRawValue = 1u).also(backing::seed)
                    val key = EntityKey(radioId, contact.id)
                    val preferences = ContactsFakePreferences()
                    val cancelled = CancellationException("late caller cancellation")
                    val store = object : ContactPersisting by backing {
                        override suspend fun updateContactLocalFields(
                            key: EntityKey, expectedPublicKey: Bytes, update: ContactLocalUpdate,
                        ): ContactLocalUpdateResult {
                            if (commit) backing.updateContactLocalFields(key, expectedPublicKey, update)
                            throw cancelled
                        }
                    }
                    val radio = ContactsFakeSession()
                    val service = ContactService(radio, backing, store, null, null, preferences,
                        ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
                    assertSame(cancelled, runCatching {
                        if (offline) {
                            OfflineContactBlockRecovery.block(preferences, radioId, contact.publicKey,
                                verifyCommittedBlock = {
                                    backing.fetchContact(key)?.takeIf { it.isBlocked }?.let { it.flags.toInt() and 0x10 != 0 }
                                },
                            ) {
                                store.updateContactLocalFields(key, contact.publicKey, ContactLocalUpdate.Preferences(null, true, null))
                                true
                            }
                        } else service.updateContactPreferences(key, isBlocked = true)
                    }.exceptionOrNull())
                    assertEquals(commit, assertNotNull(backing.fetchContact(key)).isBlocked)
                    assertEquals(if (commit) setOf(contact.publicKey) else emptySet(),
                        service.remoteCLIRecoveryScope(radioId).failedRevocations)
                    assertFalse(service.remoteCLIRecoveryScope(radioId).uncertain)
                    assertTrue(radio.addContactInvocations.isEmpty())
                }
            }
        },
        contactsNative("absent or already revoked radio contacts clear the cached grant before the warning") {
            for (sync in listOf(false, true)) {
                for (absent in listOf(false, true)) {
                    val backing = ContactsFakeStore(listOf(contactsDevice(radioId)))
                    val contact = stored(0x71, "Chat", 0xb5u).copy(typeRawValue = 1u, isBlocked = sync).also(backing::seed)
                    val key = EntityKey(radioId, contact.id)
                    val preferences = ContactsFakePreferences()
                    RemoteCLIRecoveryPreferences.setRevocationFailed(preferences, radioId, contact.publicKey, true)
                    val radio = ContactsFakeSession().apply {
                        if (!absent) directContactStub = meshContact(0x71, "Chat").copy(flags = ContactFlags(0xa5u))
                    }
                    val coordinator = ContactsFakeSyncCoordinator()
                    val service = ContactService(radio, backing, backing, coordinator, null, preferences,
                        ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
                    if (sync) service.syncContacts(radioId, Instant.ofEpochSecond(1))
                    else service.updateContactPreferences(key, isBlocked = true)
                    assertEquals(0xa5u.toUByte(), backing.fetchContact(key)?.flags)
                    assertFalse(service.needsRemoteCLIWriteReconciliation(radioId))
                    assertTrue("contactsChanged" in coordinator.notifications)
                    service.setContactFavorite(key, true)
                    assertEquals(0, radio.changeContactFlagsInvocations.last().flags.rawValue.toInt() and 0x10)
                }
            }
        },
        contactsNative("rejected revocation then confirmed removal leaves no orphan warning after reconnect") {
            val backing = ContactsFakeStore(listOf(contactsDevice(radioId)))
            val contact = stored(0x71, "Chat", 0xb5u).copy(typeRawValue = 1u).also(backing::seed)
            val preferences = ContactsFakePreferences()
            val radio = ContactsFakeSession().apply {
                setStubbedContacts(listOf(meshContact(0x71, "Chat").copy(flags = ContactFlags(0xb5u))))
                addContactFailure = MeshCoreException.DeviceError(6u)
            }
            val service = ContactService(radio, backing, backing, null, null, preferences,
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            assertFailsWith<ContactRemoteCLIRevocationFailed> {
                service.updateContactPreferences(EntityKey(radioId, contact.id), isBlocked = true)
            }
            service.removeContact(radioId, contact.publicKey)
            assertNull(backing.fetchContact(EntityKey(radioId, contact.id)))
            radio.setStubbedContacts(emptyList())
            val restarted = ContactService(radio, backing, backing, null, null, preferences,
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            restarted.syncContacts(radioId)
            assertFalse(restarted.needsRemoteCLIWriteReconciliation(radioId))
        },
        contactsNative("reconnect revokes the exact blocked duplicate rather than the first public key match") {
            val backing = ContactsFakeStore(listOf(contactsDevice(radioId)))
            stored(0x71, "Unblocked duplicate", 0xb5u).copy(typeRawValue = 1u).also(backing::seed)
            val blocked = stored(0x71, "Blocked duplicate", 0xb5u).copy(typeRawValue = 1u, isBlocked = true).also(backing::seed)
            val radio = ContactsFakeSession().apply {
                setStubbedContacts(listOf(meshContact(0x71, "Chat").copy(flags = ContactFlags(0xb5u))))
            }
            val service = ContactService(radio, backing, backing, null, null, ContactsFakePreferences(),
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            service.syncContacts(radioId, Instant.ofEpochSecond(1))
            assertEquals(1, radio.addContactInvocations.size)
            assertEquals(0xa5u.toUByte(), backing.fetchContact(EntityKey(radioId, blocked.id))?.flags)
        },
        contactsNative("a picker selection cannot grant a replacement public key") {
            val backing = ContactsFakeStore()
            val selected = stored(0x71, "Selected", 0x85u).copy(typeRawValue = 1u)
            val replacement = selected.copy(publicKey = contactsKey(0x72)).also(backing::seed)
            val radio = ContactsFakeSession().apply { setStubbedContacts(listOf(meshContact(0x72, "Replacement"))) }
            val service = ContactService(radio, backing, backing, null, null, ContactsFakePreferences(),
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            assertFailsWith<ContactServiceError.ContactNotFound> {
                service.setRemoteCLIAllowed(EntityKey(radioId, selected.id), true, selected.publicKey)
            }
            assertTrue(radio.addContactInvocations.isEmpty())
            assertEquals(0x85u.toUByte(), backing.fetchContact(EntityKey(radioId, replacement.id))?.flags)
        },
    )

    @TestFactory
    fun radioCliPermissionCases(): List<DynamicTest> = listOf(
        contactsNative("radio CLI grant and revoke preserve radio and local flag bits") {
            val backing = ContactsFakeStore()
            val contact = stored(0x71, "Chat", 0x85u).copy(typeRawValue = 1u).also(backing::seed)
            val session = ContactsFakeSession().apply {
                setStubbedContacts(listOf(meshContact(0x71, "Chat").copy(flags = ContactFlags(0xa5u))))
            }
            val service = ContactService(session, backing, backing, null, null, ContactsFakePreferences(),
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            val key = EntityKey(radioId, contact.id)
            service.setRemoteCLIAllowed(key, true)
            assertEquals(0xb5u.toUByte(), session.addContactInvocations.last().flags.rawValue)
            assertEquals(0x95u.toUByte(), backing.fetchContact(key)?.flags)
            service.setRemoteCLIAllowed(key, false)
            assertEquals(0xa5u.toUByte(), session.addContactInvocations.last().flags.rawValue)
            assertEquals(0x85u.toUByte(), backing.fetchContact(key)?.flags)
        },
        contactsNative("failed radio grant does not alter local permission") {
            val backing = ContactsFakeStore()
            val contact = stored(0x72, "Chat", 0x85u).copy(typeRawValue = 1u).also(backing::seed)
            val session = ContactsFakeSession().apply {
                setStubbedContacts(listOf(meshContact(0x72, "Chat").copy(flags = ContactFlags(0x85u))))
                addContactFailure = MeshCoreException.DeviceError(6u)
            }
            val service = ContactService(session, backing, backing, null, null, ContactsFakePreferences(),
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            assertFailsWith<ContactServiceError.SessionError> { service.setRemoteCLIAllowed(EntityKey(radioId, contact.id), true) }
            assertEquals(0x85u.toUByte(), backing.fetchContact(EntityKey(radioId, contact.id))?.flags)
        },
        contactsNative("a failed permission write-ahead marker prevents radio dispatch") {
            val backing = ContactsFakeStore()
            val contact = stored(0x72, "Chat", 0x85u).copy(typeRawValue = 1u).also(backing::seed)
            val radio = ContactsFakeSession().apply { setStubbedContacts(listOf(meshContact(0x72, "Chat"))) }
            val preferences = object : ContactPreferenceFlags {
                override fun bool(key: String) = false
                override fun set(key: String, value: Boolean) {
                    if (key.startsWith("remoteCLIWriteReconciliation.") && value) {
                        throw IllegalStateException("marker not persisted")
                    }
                }
            }
            val service = ContactService(radio, backing, backing, null, null, preferences,
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            assertFailsWith<IllegalStateException> {
                service.setRemoteCLIAllowed(EntityKey(radioId, contact.id), true)
            }
            assertTrue(radio.addContactInvocations.isEmpty())
            assertEquals(0x85u.toUByte(), backing.fetchContact(EntityKey(radioId, contact.id))?.flags)
        },
        contactsNative("blocking revokes the radio permission and excludes the blocked contact from future grants") {
            val backing = ContactsFakeStore()
            val contact = stored(0x73, "Chat", 0xb5u).copy(typeRawValue = 1u).also(backing::seed)
            val session = ContactsFakeSession().apply {
                setStubbedContacts(listOf(meshContact(0x73, "Chat").copy(flags = ContactFlags(0xb5u))))
            }
            val service = ContactService(session, backing, backing, null, null, ContactsFakePreferences(),
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            val key = EntityKey(radioId, contact.id)
            service.updateContactPreferences(key, isBlocked = true)
            assertEquals(0xa5u.toUByte(), session.addContactInvocations.last().flags.rawValue)
            assertTrue(assertNotNull(backing.fetchContact(key)).isBlocked)
            assertEquals(0xa5u.toUByte(), backing.fetchContact(key)?.flags)
            assertFailsWith<ContactServiceError.SessionError> { service.setRemoteCLIAllowed(key, true) }
        },
        contactsNative("blocking rejects a replaced public key before changing local or radio state") {
            val backing = ContactsFakeStore()
            val contact = stored(0x73, "Chat", 0xb5u).copy(typeRawValue = 1u).also(backing::seed)
            val radio = ContactsFakeSession().apply { setStubbedContacts(listOf(meshContact(0x73, "Chat"))) }
            val service = ContactService(radio, backing, backing, null, null, ContactsFakePreferences(),
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            assertFailsWith<ContactServiceError.ContactNotFound> {
                service.updateContactPreferences(
                    EntityKey(radioId, contact.id), isBlocked = true, expectedPublicKey = contactsKey(0x74),
                )
            }
            assertFalse(assertNotNull(backing.fetchContact(EntityKey(radioId, contact.id))).isBlocked)
            assertTrue(radio.addContactInvocations.isEmpty())
        },
        contactsNative("blocking remains local when radio CLI revocation fails") {
            for (failure in listOf(MeshCoreException.Timeout(), MeshCoreException.DeviceError(6u))) {
                val backing = ContactsFakeStore()
                val contact = stored(0x73, "Chat", 0xb5u).copy(typeRawValue = 1u).also(backing::seed)
                val peer = stored(0x72, "Peer", 0x80u).copy(typeRawValue = 1u).also(backing::seed)
                val radio = ContactsFakeSession().apply {
                    setStubbedContacts(listOf(meshContact(0x73, "Chat").copy(flags = ContactFlags(0xb5u))))
                    addContactFailure = failure
                }
                val preferences = ContactsFakePreferences()
                val service = ContactService(radio, backing, backing, null, null, preferences,
                    ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
                val key = EntityKey(radioId, contact.id)
                assertFailsWith<ContactRemoteCLIRevocationFailed> {
                    service.updateContactPreferences(key, isBlocked = true)
                }
                assertTrue(assertNotNull(backing.fetchContact(key)).isBlocked)
                assertEquals(0xb5u.toUByte(), backing.fetchContact(key)?.flags)
                assertEquals(failure is MeshCoreException.Timeout, preferences.bool(cliMarker(contact.publicKey)))
                assertTrue(service.needsRemoteCLIWriteReconciliation(radioId))
                service.setContactFavorite(EntityKey(radioId, peer.id), true)
                assertEquals(0x81u.toUByte(), backing.fetchContact(EntityKey(radioId, peer.id))?.flags)
                val attempts = radio.addContactInvocations.size
                radio.addContactFailure = null
                val restartedPreferences = object : ContactPreferenceFlags by preferences {}
                val restarted = ContactService(radio, backing, backing, null, null, restartedPreferences,
                    ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
                assertTrue(restarted.needsRemoteCLIWriteReconciliation(radioId))
                if (failure is MeshCoreException.Timeout) {
                    assertFailsWith<ContactRemoteCLIRevocationFailed> {
                        restarted.updateContactPreferences(key, isBlocked = true)
                    }
                    assertEquals(attempts, radio.addContactInvocations.size)
                    assertEquals(0xb5u.toUByte(), backing.fetchContact(key)?.flags)
                } else {
                    restarted.updateContactPreferences(key, isBlocked = true)
                    assertEquals(attempts + 1, radio.addContactInvocations.size)
                    assertEquals(0xa5u.toUByte(), backing.fetchContact(key)?.flags)
                    assertFalse(restarted.needsRemoteCLIWriteReconciliation(radioId))
                }
            }
        },
        contactsNative("sync retries rejected blocked contact revocation after recreation") {
            val backing = ContactsFakeStore(listOf(contactsDevice(radioId)))
            val blocked = stored(0x74, "Blocked", 0xb5u).copy(typeRawValue = 1u, isBlocked = true).also(backing::seed)
            val radio = ContactsFakeSession().apply {
                setStubbedContacts(listOf(meshContact(0x74, "Blocked").copy(flags = ContactFlags(0xb5u))))
                addContactFailure = MeshCoreException.DeviceError(6u)
            }
            val preferences = ContactsFakePreferences()
            val original = ContactService(radio, backing, backing, null, null, preferences,
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            original.syncContacts(radioId)
            assertFalse(preferences.bool(cliMarker(blocked.publicKey)))
            assertTrue(original.needsRemoteCLIWriteReconciliation(radioId))
            assertEquals(0xb5u.toUByte(), backing.fetchContact(EntityKey(radioId, blocked.id))?.flags)
            radio.addContactFailure = null
            val restartedPreferences = object : ContactPreferenceFlags by preferences {}
            val restarted = ContactService(radio, backing, backing, null, null, restartedPreferences,
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            assertTrue(restarted.needsRemoteCLIWriteReconciliation(radioId))
            restarted.syncContacts(radioId)
            assertEquals(2, radio.addContactInvocations.size)
            assertEquals(0xa5u.toUByte(), backing.fetchContact(EntityKey(radioId, blocked.id))?.flags)
            assertFalse(restarted.needsRemoteCLIWriteReconciliation(radioId))
        },
        contactsNative("reconnect sync waits for the offline block and pending warning") {
            val backing = ContactsFakeStore(listOf(contactsDevice(radioId)))
            val contact = stored(0x74, "Chat", 0xb5u).copy(typeRawValue = 1u).also(backing::seed)
            val radio = ContactsFakeSession().apply {
                setStubbedContacts(listOf(meshContact(0x74, "Chat").copy(flags = ContactFlags(0xb5u))))
            }
            val preferences = ContactsFakePreferences()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val offline = async(start = CoroutineStart.UNDISPATCHED) {
                OfflineContactBlockRecovery.block(
                    preferences, radioId, contact.publicKey,
                    verifyCommittedBlock = {
                        backing.fetchContact(EntityKey(radioId, contact.id))?.takeIf { it.isBlocked }
                            ?.let { it.flags.toInt() and 0x10 != 0 }
                    },
                ) {
                    val result = backing.updateContactLocalFields(
                        EntityKey(radioId, contact.id), contact.publicKey,
                        ContactLocalUpdate.Preferences(null, true, null),
                    )
                    assertTrue(result is ContactLocalUpdateResult.Updated)
                    entered.complete(Unit)
                    release.await()
                    true
                }
            }
            entered.await()
            val service = ContactService(radio, backing, backing, null, null, preferences,
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            val sync = async(start = CoroutineStart.UNDISPATCHED) { service.syncContacts(radioId) }
            assertFalse(sync.isCompleted)
            assertTrue(radio.addContactInvocations.isEmpty())
            release.complete(Unit)
            assertTrue(offline.await())
            sync.await()
            assertEquals(0xa5u.toUByte(), backing.fetchContact(EntityKey(radioId, contact.id))?.flags)
            assertEquals(1, radio.addContactInvocations.size)
            assertFalse(service.needsRemoteCLIWriteReconciliation(radioId))
        },
        contactsNative("an obsolete owner cannot grant radio CLI permissions") {
            val backing = ContactsFakeStore()
            val contact = stored(0x75, "Chat", 0x85u).copy(typeRawValue = 1u).also(backing::seed)
            val session = ContactsFakeSession().apply { setStubbedContacts(listOf(meshContact(0x75, "Chat"))) }
            val service = ContactService(session, backing, backing, null, null, ContactsFakePreferences(),
                ownerRadioId = radioId, isCurrent = { false }, supportsRadioCLI = { true })
            assertFailsWith<ContactServiceError.NotConnected> {
                service.setRemoteCLIAllowed(EntityKey(radioId, contact.id), true)
            }
            assertTrue(session.addContactInvocations.isEmpty())
            assertEquals(0x85u.toUByte(), backing.fetchContact(EntityKey(radioId, contact.id))?.flags)
        },
        contactsNative("radio observation rejects incomplete and duplicate contact responses") {
            val backing = ContactsFakeStore()
            val contact = meshContact(0x75, "Chat").copy(flags = ContactFlags(0x10u))
            val session = ContactsFakeSession().apply { setStubbedContacts(listOf(contact)) }
            val service = ContactService(session, backing, backing, null, null, ContactsFakePreferences(),
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            assertEquals(mapOf(contact.publicKey to true), service.observeRemoteCLIPermissions(radioId))
            session.setStubbedReportedTotal(2)
            assertFailsWith<ContactServiceError.SyncInterrupted> { service.observeRemoteCLIPermissions(radioId) }
            session.setStubbedContacts(listOf(contact, contact))
            assertFailsWith<ContactServiceError.SyncInterrupted> { service.observeRemoteCLIPermissions(radioId) }
            session.setStubbedReportsNoTotal(true)
            assertFailsWith<ContactServiceError.SyncInterrupted> { service.observeRemoteCLIPermissions(radioId) }
        },
        contactsNative("radio observation rejects an owner replaced during the fetch") {
            val backing = ContactsFakeStore()
            var current = true
            val session = ContactsFakeSession().apply {
                setStubbedContacts(listOf(meshContact(0x75, "Chat")))
                holdNextGetContacts()
            }
            val service = ContactService(session, backing, backing, null, null, ContactsFakePreferences(),
                ownerRadioId = radioId, isCurrent = { current }, supportsRadioCLI = { true })
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                assertFailsWith<ContactServiceError.NotConnected> { service.observeRemoteCLIPermissions(radioId) }
            }
            session.waitForGetContactsStart()
            current = false
            session.releaseGetContacts()
            pending.await()
            assertTrue(backing.fetchContacts(radioId).isEmpty())
        },
        contactsNative("incremental sync revokes a blocked contact omitted from the returned frames") {
            val backing = ContactsFakeStore(listOf(contactsDevice(radioId)))
            val contact = stored(0x74, "Blocked", 0x95u).copy(typeRawValue = 1u, isBlocked = true).also(backing::seed)
            val session = ContactsFakeSession().apply {
                directContactStub = meshContact(0x74, "Blocked").copy(flags = ContactFlags(0xb5u))
            }
            val service = ContactService(session, backing, backing, null, null, ContactsFakePreferences(),
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            service.syncContacts(radioId, since = Instant.ofEpochSecond(1))
            assertEquals(0xa5u.toUByte(), session.addContactInvocations.single().flags.rawValue)
            assertTrue(assertNotNull(backing.fetchContact(EntityKey(radioId, contact.id))).isBlocked)
            assertEquals(0xa5u.toUByte(), backing.fetchContact(EntityKey(radioId, contact.id))?.flags)
        },
        contactsNative("blocked contact revocation is tracked from durable admission through local completion") {
            val backing = ContactsFakeStore(listOf(contactsDevice(radioId)))
            val contact = stored(0x74, "Blocked", 0x95u).copy(typeRawValue = 1u, isBlocked = true).also(backing::seed)
            val preferences = ContactsFakePreferences()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val radio = ContactsFakeSession().apply {
                setStubbedContacts(listOf(meshContact(0x74, "Blocked").copy(flags = ContactFlags(0xb5u))))
            }
            val held = object : ContactSessionOps by radio {
                override suspend fun setRemoteCLIAllowed(
                    publicKey: Bytes, granted: Boolean, dispatch: ContactWriteDispatch,
                    shouldDispatch: suspend () -> Boolean, onCommitted: suspend (MeshContact) -> Unit,
                ): MeshContact? = radio.setRemoteCLIAllowed(publicKey, granted, dispatch, shouldDispatch) {
                    entered.complete(Unit)
                    release.await()
                    onCommitted(it)
                }
            }
            val service = ContactService(held, backing, backing, null, null, preferences,
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            val sync = async(start = CoroutineStart.UNDISPATCHED) { service.syncContacts(radioId) }
            entered.await()
            assertTrue(preferences.bool(cliMarker(contact.publicKey)))
            assertTrue(service.needsRemoteCLIWriteReconciliation(radioId))
            release.complete(Unit)
            sync.await()
            assertFalse(service.needsRemoteCLIWriteReconciliation(radioId))
            assertFalse(preferences.bool(cliMarker(contact.publicKey)))
            assertFalse(service.needsContactWriteReconciliation(radioId))
            assertEquals(0xa5u.toUByte(), backing.fetchContact(EntityKey(radioId, contact.id))?.flags)
        },
        contactsNative("uncertain blocked revocation leaves ordinary sync available and preserves CLI state") {
            val backing = ContactsFakeStore(listOf(contactsDevice(radioId)))
            val blocked = stored(0x74, "Blocked", 0x85u).copy(typeRawValue = 1u, isBlocked = true).also(backing::seed)
            val peer = stored(0x75, "Old", 0x21u).copy(typeRawValue = 1u).also(backing::seed)
            val preferences = ContactsFakePreferences()
            val radio = ContactsFakeSession().apply {
                setStubbedContacts(listOf(
                    meshContact(0x74, "Blocked").copy(flags = ContactFlags(0x95u)),
                    meshContact(0x75, "Updated").copy(flags = ContactFlags(0x31u)),
                ))
                addContactFailure = MeshCoreException.Timeout()
            }
            val service = ContactService(radio, backing, backing, null, null, preferences,
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            assertEquals(2L, service.syncContacts(radioId).contactsReceived)
            assertTrue(service.needsRemoteCLIWriteReconciliation(radioId))
            assertFalse(service.needsContactWriteReconciliation(radioId))
            assertEquals(0x85u.toUByte(), backing.fetchContact(EntityKey(radioId, blocked.id))?.flags)
            assertEquals(0x31u.toUByte(), backing.fetchContact(EntityKey(radioId, peer.id))?.flags)
            assertEquals("Updated", backing.fetchContact(EntityKey(radioId, peer.id))?.name)
            assertFailsWith<ContactWriteAdmissionBlocked> {
                service.addOrUpdateContact(radioId, blocked.toContactFrame())
            }
            assertEquals(2L, service.syncContacts(radioId).contactsReceived)
            assertTrue(preferences.bool(cliMarker(blocked.publicKey)))
        },
        contactsNative("uncertainty after recreation protects only its contact") {
            val backing = ContactsFakeStore(listOf(contactsDevice(radioId)))
            val affected = stored(0x76, "Affected", 0x85u).copy(typeRawValue = 1u).also(backing::seed)
            val peer = stored(0x77, "Peer", 0xa0u).copy(typeRawValue = 1u).also(backing::seed)
            val radio = ContactsFakeSession().apply {
                setStubbedContacts(listOf(
                    meshContact(0x76, "Affected").copy(flags = ContactFlags(0x85u)),
                    meshContact(0x77, "Peer").copy(flags = ContactFlags(0xa0u)),
                ))
                addContactFailure = MeshCoreException.Timeout()
            }
            val preferences = ContactsFakePreferences()
            val original = ContactService(radio, backing, backing, null, null, preferences,
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            assertFailsWith<ContactServiceError.SessionError> {
                original.setRemoteCLIAllowed(EntityKey(radioId, affected.id), true)
            }
            assertTrue(preferences.bool(cliMarker(affected.publicKey)))
            val restartedPreferences = object : ContactPreferenceFlags by preferences {}
            val restarted = ContactService(radio, backing, backing, null, null, restartedPreferences,
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            val affectedKey = EntityKey(radioId, affected.id)
            val peerKey = EntityKey(radioId, peer.id)
            assertTrue(restarted.needsRemoteCLIWriteReconciliation(radioId))
            assertFailsWith<ContactWriteAdmissionBlocked> { restarted.setContactFavorite(affectedKey, true) }
            assertFailsWith<ContactWriteAdmissionBlocked> { restarted.setTelemetryPermissions(affectedKey, true) }
            assertFailsWith<ContactWriteAdmissionBlocked> { restarted.addOrUpdateContact(radioId, affected.toContactFrame()) }
            assertFailsWith<ContactWriteAdmissionBlocked> { restarted.removeContact(radioId, affected.publicKey) }
            radio.addContactFailure = null
            restarted.setContactFavorite(peerKey, true)
            restarted.setTelemetryPermissions(peerKey, true)
            restarted.setRemoteCLIAllowed(peerKey, true)
            val updated = assertNotNull(backing.fetchContact(peerKey))
            restarted.addOrUpdateContact(radioId, updated.toContactFrame())
            restarted.removeContact(radioId, peer.publicKey)
            assertTrue(radio.removeContactPublicKeys.contains(peer.publicKey))
            assertTrue(preferences.bool(cliMarker(affected.publicKey)))
            assertTrue(restarted.needsRemoteCLIWriteReconciliation(radioId))
        },
        contactsNative("legacy radio-wide uncertainty conservatively protects every contact") {
            val backing = ContactsFakeStore()
            val peer = stored(0x77, "Peer", 0xa0u).copy(typeRawValue = 1u).also(backing::seed)
            val preferences = ContactsFakePreferences().apply {
                set("remoteCLIWriteReconciliation.${radioId.value}", true)
            }
            val service = ContactService(ContactsFakeSession(), backing, backing, null, null, preferences,
                ownerRadioId = radioId, isCurrent = { true }, supportsRadioCLI = { true })
            assertFailsWith<ContactWriteAdmissionBlocked> {
                service.setContactFavorite(EntityKey(radioId, peer.id), true)
            }
            assertTrue(service.needsRemoteCLIWriteReconciliation(radioId))
        },
    )

    @TestFactory
    fun atomicAvatarCases(): List<DynamicTest> = listOf(
        contactsNative("explicit avatar identity never re-queries and substitutes a replacement public key") {
            val backing = ContactsFakeStore()
            val original = stored(0x90, "Original").also(backing::seed)
            val key = EntityKey(radioId, original.id)
            var fetches = 0
            val contacts = object : ContactPersisting by backing {
                override suspend fun fetchContact(key: EntityKey): ContactDTO? {
                    fetches++
                    return backing.fetchContact(key)
                }
            }
            val coordinator = ContactsFakeSyncCoordinator()
            val service = ContactService(
                ContactsFakeSession(), backing, contacts, coordinator, null, ContactsFakePreferences(),
            )
            val replacement = original.copy(publicKey = contactsKey(0xA0), name = "Replacement")
            backing.seed(replacement)
            for (image in listOf(Bytes.of(1), null)) {
                assertFailsWith<ContactServiceError.ContactNotFound> {
                    service.updateContactAvatar(key, original.publicKey, image)
                }
                assertEquals(0, fetches)
                assertEquals(replacement, backing.fetchContact(key))
            }
            assertTrue(coordinator.notifications.isEmpty())
            service.updateContactAvatar(key, replacement.publicKey, Bytes.of(2))
            assertEquals(replacement.withAvatar(Bytes.of(2)), backing.fetchContact(key))
            assertEquals(0, fetches)
            assertEquals(listOf("contactsChanged"), coordinator.notifications)
        },
        contactsNative("avatar set and clear pass the fetched identity to the atomic operation and notify only on success") {
            for (result in ContactAvatarUpdateResult.entries) {
                for (image in listOf(Bytes.of(1, 2), null)) {
                    val backing = ContactsFakeStore()
                    val original = stored(0x91, "Avatar")
                    backing.seed(original)
                    val key = EntityKey(radioId, original.id)
                    var writes = 0
                    val contacts = object : ContactPersisting by backing {
                        override suspend fun saveContact(dto: ContactDTO): Unit =
                            error("Avatar writes must never save a stale full DTO")
                        override suspend fun updateContactAvatar(
                            key: EntityKey, expectedPublicKey: Bytes, imageData: Bytes?,
                        ): ContactAvatarUpdateResult {
                            assertEquals(EntityKey(radioId, original.id), key)
                            assertEquals(original.publicKey, expectedPublicKey)
                            assertEquals(image, imageData)
                            writes++
                            return result
                        }
                    }
                    val coordinator = ContactsFakeSyncCoordinator()
                    val service = ContactService(
                        ContactsFakeSession(), backing, contacts, coordinator, null, ContactsFakePreferences(),
                    )
                    if (result == ContactAvatarUpdateResult.UPDATED) {
                        service.updateContactAvatar(key, image)
                        assertEquals(listOf("contactsChanged"), coordinator.notifications)
                    } else {
                        assertFailsWith<ContactServiceError.ContactNotFound> { service.updateContactAvatar(key, image) }
                        assertTrue(coordinator.notifications.isEmpty())
                    }
                    assertEquals(1, writes)
                }
            }
        },
        contactsNative("avatar missing at initial fetch never writes or notifies") {
            val backing = ContactsFakeStore()
            val coordinator = ContactsFakeSyncCoordinator()
            val contacts = object : ContactPersisting by backing {
                override suspend fun updateContactAvatar(
                    key: EntityKey, expectedPublicKey: Bytes, imageData: Bytes?,
                ): ContactAvatarUpdateResult = error("Missing contact must not reach avatar write")
            }
            val service = ContactService(
                ContactsFakeSession(), backing, contacts, coordinator, null, ContactsFakePreferences(),
            )
            assertFailsWith<ContactServiceError.ContactNotFound> {
                service.updateContactAvatar(EntityKey(radioId, UUID.randomUUID()), Bytes.of(1))
            }
            assertTrue(coordinator.notifications.isEmpty())
        },
        contactsNative("avatar storage failure and cancellation propagate unchanged without success notification") {
            val failures = listOf(
                PersistenceStoreException(PersistenceStoreError.SaveFailed("disk full")),
                CancellationException("avatar cancelled"),
            )
            for (failure in failures) {
                val backing = ContactsFakeStore()
                val original = stored(0x92, "Avatar").also(backing::seed)
                val coordinator = ContactsFakeSyncCoordinator()
                val contacts = object : ContactPersisting by backing {
                    override suspend fun updateContactAvatar(
                        key: EntityKey, expectedPublicKey: Bytes, imageData: Bytes?,
                    ): ContactAvatarUpdateResult = throw failure
                }
                val service = ContactService(
                    ContactsFakeSession(), backing, contacts, coordinator, null, ContactsFakePreferences(),
                )
                val caught = assertFailsWith<Exception> {
                    service.updateContactAvatar(EntityKey(radioId, original.id), Bytes.of(1))
                }
                assertSame(failure, caught)
                assertTrue(coordinator.notifications.isEmpty())
                assertEquals(original, backing.fetchContact(EntityKey(radioId, original.id)))
            }
        },
    )

    @TestFactory
    fun syncAndEventCases(): List<DynamicTest> = listOf(
        contactsNative("syncContacts emits syncProgress 0 of n then received of n to a subscriber registered before the call") {
            val session = ContactsFakeSession().apply {
                setStubbedContacts(listOf(meshContact(0xAA, "A", 10), meshContact(0xBB, "B", 30), meshContact(0xCC, "C", 20)))
            }
            val service = contactsService(session = session, store = ContactsFakeStore(listOf(contactsDevice(radioId))))
            val subscription = service.events()

            val result = service.syncContacts(radioId, since = Instant.ofEpochSecond(5))
            service.finishEvents()

            assertEquals(
                listOf(ContactServiceEvent.SyncProgress(0, 3), ContactServiceEvent.SyncProgress(3, 3)),
                subscription.events.toList(),
            )
            assertEquals(30u, result.lastSyncTimestamp)
            assertEquals(listOf<Instant?>(Instant.ofEpochSecond(5)), session.getContactsInvocations)
        },
        contactsNative("an empty device reply yields lastSyncTimestamp 0 and a full sync with a complete empty snapshot prunes everything but the V-contact") {
            val store = ContactsFakeStore(listOf(contactsDevice(radioId)))
            val vKey = assertNotNull(VContactIdentity.publicKey(contactsKey(0x01)))
            store.seed(stored(0xDD, "Gone"))
            store.seed(ContactDTO(radioId = radioId, publicKey = vKey, name = "v", lastHeardTimestamp = null))
            val cleanup = ContactsRecordingCleanup()

            val result = contactsService(store = store, cleanupCoordinator = cleanup).syncContacts(radioId)

            assertEquals(0u, result.lastSyncTimestamp)
            assertEquals(0L, result.contactsReceived)
            assertEquals(listOf("v"), store.fetchContacts(radioId).map { it.name })
            assertEquals(listOf(ContactCleanupReason.DELETED), cleanup.invocations.map { it.reason })
            assertEquals(contactsKey(0xDD), cleanup.invocations.single().publicKey)
        },
        contactsNative("full sync prune skips a truncated snapshot, a missing contactsStart total and an unknown self key") {
            for (variant in listOf("truncated", "noTotal", "noDevice", "deviceFails")) {
                val devices = if (variant == "noDevice") emptyList() else listOf(contactsDevice(radioId))
                val store = ContactsFakeStore(devices)
                if (variant == "deviceFails") store.fetchDeviceFailure = IllegalStateException("store offline")
                store.seed(stored(0xDD, "Keep"))
                val session = ContactsFakeSession().apply {
                    setStubbedContacts(listOf(meshContact(0xAA, "Alice")))
                    if (variant == "truncated") setStubbedReportedTotal(2)
                    if (variant == "noTotal") setStubbedReportsNoTotal(true)
                }
                contactsService(session = session, store = store).syncContacts(radioId)
                assertNotNull(store.fetchContact(radioId, contactsKey(0xDD)), variant)
            }
        },
        contactsNative("syncContacts maps a session failure to sessionError and passes a store failure through unchanged") {
            val timeout = MeshCoreException.Timeout()
            val session = ContactsFakeSession().apply { getContactsFailure = timeout }
            val mapped = assertFailsWith<ContactServiceError.SessionError> { contactsService(session = session).syncContacts(radioId) }
            assertSame(timeout, mapped.error)
            assertEquals("Mesh operation timed out", mapped.errorDescription)

            val storeFailure = IllegalStateException("disk full")
            val store = object : com.meshcoreone.android.core.contracts.domain.ContactPersisting by ContactsFakeStore() {
                override suspend fun batchSaveContacts(
                    radioId: RadioId,
                    frames: com.meshcoreone.android.core.model.SnapshotList<com.meshcoreone.android.core.model.ContactFrame>,
                ): Long = throw storeFailure
            }
            val service = ContactService(ContactsFakeSession(), ContactsFakeStore(), store, null, null, ContactsFakePreferences())
            assertSame(storeFailure, assertFailsWith<IllegalStateException> { service.syncContacts(radioId) })
        },
        contactsNative("finishEvents completes live collectors and later subscriptions start finished") {
            val service = contactsService()
            val live = service.events()
            val collector = async(start = CoroutineStart.UNDISPATCHED) { live.events.toList() }
            service.finishEvents()
            assertEquals(emptyList(), collector.await())
            assertEquals(emptyList(), service.events().events.toList())
        },
    )

    @TestFactory
    fun cancellationCases(): List<DynamicTest> = listOf(
        contactsNative("syncContactsForRefresh cancelled while waiting for the advert claim propagates cancellation and never fetches") {
            val coordinator = ContactsFakeSyncCoordinator()
            val session = ContactsFakeSession()
            val service = contactsService(session = session, syncCoordinator = coordinator)
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val holder = object : com.meshcoreone.android.core.contracts.domain.ContactServiceProtocol {
                override suspend fun syncContacts(radioId: RadioId, since: Instant?) =
                    gate.await().let { com.meshcoreone.android.core.contracts.domain.ContactSyncResult(0, 0u, true) }
            }
            val advert = async { coordinator.performAdvertContactSync(radioId, holder) }
            yield()

            val refresh = async { service.syncContactsForRefresh(radioId) }
            delay(50.milliseconds)
            refresh.cancelAndJoin()

            assertTrue(refresh.isCancelled)
            assertFalse(coordinator.manualContactSyncActive)
            assertTrue(session.getContactsInvocations.isEmpty())
            gate.complete(Unit)
            assertEquals(ContactsAdvertOutcome.SYNCED, advert.await())
        },
        contactsNative("syncContactsForRefresh cancelled mid-fetch still clears the manual claim") {
            val coordinator = ContactsFakeSyncCoordinator()
            val session = ContactsFakeSession().apply { holdNextGetContacts() }
            val service = contactsService(session = session, syncCoordinator = coordinator)

            val refresh = async { service.syncContactsForRefresh(radioId) }
            session.waitForGetContactsStart()
            assertTrue(coordinator.manualContactSyncActive)
            refresh.cancelAndJoin()

            assertTrue(refresh.isCancelled)
            assertFalse(coordinator.manualContactSyncActive)
            assertEquals(listOf("manual:true", "manual:false"), coordinator.notifications)
        },
        contactsNative("syncContactsForRefresh clears the claim after a failed sync and rethrows the mapped error") {
            val coordinator = ContactsFakeSyncCoordinator()
            val session = ContactsFakeSession().apply { getContactsFailure = MeshCoreException.NotConnected() }
            val service = contactsService(session = session, syncCoordinator = coordinator)
            assertFailsWith<ContactServiceError.SessionError> { service.syncContactsForRefresh(radioId) }
            assertEquals(listOf("manual:true", "manual:false"), coordinator.notifications)
        },
        contactsNative("migrateAppFavoritesToDevice propagates cancellation rather than counting it as a failed contact") {
            val store = ContactsFakeStore()
            store.seed(stored(0xA1, "Fav", isFavorite = true))
            val session = ContactsFakeSession().apply {
                changeContactFlagsFailures = mapOf(contactsKey(0xA1) to CancellationException("cancelled"))
            }
            val preferences = ContactsFakePreferences()
            assertFailsWith<CancellationException> {
                contactsService(session = session, store = store, preferences = preferences).migrateAppFavoritesToDevice(radioId)
            }
            assertFalse(preferences.bool(ContactService.FAVORITES_MIGRATION_KEY))
        },
    )

    @TestFactory
    fun removalAndErrorMappingCases(): List<DynamicTest> = listOf(
        contactsNative("removeContact ignores the ZephCore V-contact: no device remove, no local delete, no nodeDeleted") {
            val store = ContactsFakeStore(listOf(contactsDevice(radioId)))
            val vKey = assertNotNull(VContactIdentity.publicKey(contactsKey(0x01)))
            store.seed(ContactDTO(radioId = radioId, publicKey = vKey, name = "v", lastHeardTimestamp = null))
            val session = ContactsFakeSession()
            val service = contactsService(session = session, store = store)
            val events = service.events()

            service.removeContact(radioId, vKey)
            service.removeLocalContact(EntityKey(radioId, store.contacts.keys.single()), vKey)
            service.finishEvents()

            assertTrue(session.removeContactPublicKeys.isEmpty())
            assertEquals(1, store.contacts.size)
            assertEquals(emptyList(), events.events.toList())
        },
        contactsNative("removeContact deletes, cleans up, emits nodeDeleted and notifies; device NOT_FOUND maps to contactNotFound") {
            val store = ContactsFakeStore(listOf(contactsDevice(radioId)))
            val contact = stored(0x22, "Bob").also(store::seed)
            val cleanup = ContactsRecordingCleanup()
            val coordinator = ContactsFakeSyncCoordinator()
            val session = ContactsFakeSession()
            val service = contactsService(session = session, store = store, syncCoordinator = coordinator, cleanupCoordinator = cleanup)
            val events = service.events()

            service.removeContact(radioId, contact.publicKey)
            assertEquals(listOf(EntityKey(radioId, contact.id)), cleanup.invocations.map { it.contact })
            assertEquals(listOf("contactsChanged"), coordinator.notifications)

            session.removeContactFailure = MeshCoreException.DeviceError(2u)
            assertFailsWith<ContactServiceError.ContactNotFound> { service.removeContact(radioId, contact.publicKey) }
            session.removeContactFailure = MeshCoreException.DeviceError(3u)
            assertFailsWith<ContactServiceError.SessionError> { service.removeContact(radioId, contact.publicKey) }
            service.finishEvents()
            assertEquals(listOf<ContactServiceEvent>(ContactServiceEvent.NodeDeleted), events.events.toList())
        },
        contactsNative("removeLocalContact propagates lookup failure without deletion, cleanup or success events; a verified retry succeeds") {
            val store = ContactsFakeStore(listOf(contactsDevice(radioId)))
            val contact = stored(0x33, "Carol").also(store::seed)
            val lookupFailure = IllegalStateException("lookup failed")
            store.fetchContactByKeyFailure = lookupFailure
            val cleanup = ContactsRecordingCleanup()
            val coordinator = ContactsFakeSyncCoordinator()
            val service = contactsService(store = store, syncCoordinator = coordinator, cleanupCoordinator = cleanup)
            val events = service.events()

            assertSame(lookupFailure, assertFailsWith<IllegalStateException> {
                service.removeLocalContact(EntityKey(radioId, contact.id), contact.publicKey)
            })
            service.finishEvents()
            assertEquals(contact, store.fetchContact(radioId, contact.publicKey))
            assertTrue(store.deletedContactIDs.isEmpty())
            assertTrue(cleanup.invocations.isEmpty())
            assertTrue(coordinator.notifications.isEmpty())
            assertEquals(emptyList(), events.events.toList())

            store.fetchContactByKeyFailure = null
            val retry = contactsService(store = store, syncCoordinator = coordinator, cleanupCoordinator = cleanup)
            val retryEvents = retry.events()
            retry.removeLocalContact(EntityKey(radioId, contact.id), contact.publicKey)
            retry.finishEvents()
            assertEquals(listOf(contact.id), store.deletedContactIDs)
            assertEquals(listOf(ContactCleanupReason.DELETED), cleanup.invocations.map { it.reason })
            assertEquals(listOf<ContactServiceEvent>(ContactServiceEvent.NodeDeleted), retryEvents.events.toList())
            assertEquals(listOf("contactsChanged"), coordinator.notifications)
        },
        contactsNative("device error codes map to the Swift ContactServiceError cases per operation") {
            val store = ContactsFakeStore()
            val contact = stored(0x44, "Dan").also(store::seed)
            val session = ContactsFakeSession()
            val service = contactsService(session = session, store = store)
            val frame = contact.toContactFrame()

            session.addContactFailure = MeshCoreException.DeviceError(3u)
            assertFailsWith<ContactServiceError.ContactTableFull> { service.addOrUpdateContact(radioId, frame) }
            session.addContactFailure = MeshCoreException.DeviceError(2u)
            assertFailsWith<ContactServiceError.SessionError> { service.addOrUpdateContact(radioId, frame) }
            session.shareContactFailure = MeshCoreException.DeviceError(3u)
            assertFailsWith<ContactServiceError.ShareContactUnavailable> { service.shareContact(contact.publicKey) }
            session.shareContactFailure = MeshCoreException.DeviceError(2u)
            assertFailsWith<ContactServiceError.ContactNotFound> { service.shareContact(contact.publicKey) }
            session.shareContactFailure = MeshCoreException.DeviceError(4u)
            assertFailsWith<ContactServiceError.SessionError> { service.shareContact(contact.publicKey) }
            session.resetPathFailure = MeshCoreException.DeviceError(2u)
            assertFailsWith<ContactServiceError.ContactNotFound> { service.resetPath(radioId, contact.publicKey) }
            session.sendPathDiscoveryFailure = MeshCoreException.DeviceError(2u)
            assertFailsWith<ContactServiceError.ContactNotFound> { service.sendPathDiscovery(radioId, contact.publicKey) }
            session.sendPathDiscoveryFailure = MeshCoreException.DeviceError(3u)
            assertFailsWith<ContactServiceError.SessionError> { service.sendPathDiscovery(radioId, contact.publicKey) }
            session.importContactFailure = MeshCoreException.DeviceError(2u)
            assertFailsWith<ContactServiceError.SessionError> { service.importContact(Bytes.of(1)) }
            session.exportContactFailure = MeshCoreException.Timeout()
            @Suppress("DEPRECATION")
            assertFailsWith<ContactServiceError.SessionError> { service.exportContact(null) }
            assertEquals(emptyList(), store.deletedContactIDs)
            assertEquals(contact, store.contacts[contact.id])
        },
        contactsNative("setPath stamps lastModified and preserves the unknown type byte when pushing to the device") {
            val store = ContactsFakeStore()
            val contact = stored(0x55, "Eve").copy(typeRawValue = 0x7Fu, lastHeardTimestamp = 9u).also(store::seed)
            val session = ContactsFakeSession()
            val clock = Clock.fixed(Instant.ofEpochSecond(1_800_000_000, 900_000_000), ZoneOffset.UTC)
            val service = contactsService(session = session, store = store, clock = clock)

            service.setPath(radioId, contact.publicKey, Bytes.of(0x0A, 0x0B), 2u)

            val pushed = session.addContactInvocations.single()
            assertEquals(1_800_000_000L, pushed.lastModified.epochSecond)
            assertEquals(0x7Fu.toUByte(), pushed.typeRawValue)
            assertEquals(Bytes.of(0x0A, 0x0B), pushed.outPath)
            val saved = assertNotNull(store.fetchContact(radioId, contact.publicKey))
            assertEquals(1_800_000_000u, saved.lastModified)
            assertEquals(2u.toUByte(), saved.outPathLength)
            assertFailsWith<ContactServiceError.ContactNotFound> {
                service.setPath(radioId, contactsKey(0x99), Bytes.EMPTY, 0u)
            }
        },
    )

    @TestFactory
    fun flagAndPreferenceCases(): List<DynamicTest> = listOf(
        contactsNative("local field updates forward captured identity and map rejected outcomes without full saves or cleanup") {
            val updates = listOf(
                ContactLocalUpdate.Preferences("Nickname", true, null),
                ContactLocalUpdate.OCVSettings("custom", null), ContactLocalUpdate.TelemetryPermissions(true),
            )
            for (update in updates) {
                for (result in listOf(
                    ContactLocalUpdateResult.Updated(), ContactLocalUpdateResult.ContactNotFound,
                    ContactLocalUpdateResult.IdentityMismatch,
                )) {
                    val backing = ContactsFakeStore()
                    val original = stored(0x97, "Local").also(backing::seed)
                    val key = EntityKey(radioId, original.id)
                    val session = ContactsFakeSession()
                    val cleanup = ContactsRecordingCleanup()
                    val sync = ContactsFakeSyncCoordinator()
                    val expectedUpdate = update
                    var writes = 0
                    val contacts = object : ContactPersisting by backing {
                        override suspend fun saveContact(dto: ContactDTO): Unit = error("Must not save stale DTO")
                        override suspend fun updateContactLocalFields(
                            key: EntityKey, expectedPublicKey: Bytes, update: ContactLocalUpdate,
                        ): ContactLocalUpdateResult {
                            assertEquals(EntityKey(radioId, original.id), key)
                            assertEquals(original.publicKey, expectedPublicKey)
                            assertEquals(expectedUpdate, update)
                            assertEquals(if (update is ContactLocalUpdate.TelemetryPermissions) 1 else 0,
                                session.changeContactFlagsInvocations.size)
                            writes++
                            return result
                        }
                    }
                    val service = ContactService(session, backing, contacts, sync, cleanup, ContactsFakePreferences())
                    if (result is ContactLocalUpdateResult.Updated) {
                        applyLocalEdit(service, key, update)
                    } else {
                        assertFailsWith<ContactServiceError.ContactNotFound> { applyLocalEdit(service, key, update) }
                    }
                    assertEquals(1, writes)
                    assertTrue(cleanup.invocations.isEmpty())
                    assertEquals(if (result is ContactLocalUpdateResult.Updated && update is ContactLocalUpdate.Preferences)
                        listOf("contactsChanged") else emptyList(), sync.notifications)
                    assertEquals(original, backing.fetchContact(key))
                }
            }
        },
        contactsNative("local field persistence errors and cancellation propagate without cleanup or notification") {
            val updates = listOf(
                ContactLocalUpdate.Preferences("", true, false), ContactLocalUpdate.OCVSettings("custom", "3.2"),
                ContactLocalUpdate.TelemetryPermissions(false),
            )
            for (update in updates) {
                for (failure in listOf(
                    PersistenceStoreException(PersistenceStoreError.SaveFailed("disk full")),
                    CancellationException("local cancelled"),
                )) {
                    val backing = ContactsFakeStore()
                    val original = stored(0x98, "Local").also(backing::seed)
                    val contacts = object : ContactPersisting by backing {
                        override suspend fun updateContactLocalFields(
                            key: EntityKey, expectedPublicKey: Bytes, update: ContactLocalUpdate,
                        ): ContactLocalUpdateResult = throw failure
                    }
                    val cleanup = ContactsRecordingCleanup()
                    val sync = ContactsFakeSyncCoordinator()
                    val service = ContactService(ContactsFakeSession(), backing, contacts, sync, cleanup, ContactsFakePreferences())
                    assertSame(failure, assertFailsWith<Exception> {
                        applyLocalEdit(service, EntityKey(radioId, original.id), update)
                    })
                    assertEquals(original, backing.fetchContact(EntityKey(radioId, original.id)))
                    assertTrue(cleanup.invocations.isEmpty())
                    assertTrue(sync.notifications.isEmpty())
                }
            }
        },
        contactsNative("favorite acknowledgement uses only the atomic identity-aware preference write") {
            for (result in ContactFavoriteUpdateResult.entries) {
                val backing = ContactsFakeStore()
                val original = stored(0x95, "Favorite").also(backing::seed)
                val key = EntityKey(radioId, original.id)
                var writes = 0
                val session = ContactsFakeSession()
                val contacts = object : ContactPersisting by backing {
                    override suspend fun saveContact(dto: ContactDTO): Unit =
                        error("Favorite acknowledgement must not save a stale DTO")
                    override suspend fun updateContactFavorite(
                        key: EntityKey, expectedPublicKey: Bytes, isFavorite: Boolean,
                    ): ContactFavoriteUpdateResult {
                        assertEquals(listOf(0x01u.toUByte()), session.changeContactFlagsInvocations.map { it.flags.rawValue })
                        assertEquals(EntityKey(radioId, original.id), key)
                        assertEquals(original.publicKey, expectedPublicKey)
                        assertTrue(isFavorite)
                        writes++
                        return result
                    }
                }
                val service = ContactService(session, backing, contacts, null, null, ContactsFakePreferences())
                if (result == ContactFavoriteUpdateResult.UPDATED) {
                    service.setContactFavorite(key, true)
                } else {
                    assertFailsWith<ContactServiceError.ContactNotFound> { service.setContactFavorite(key, true) }
                }
                assertEquals(1, writes)
            }
        },
        contactsNative("favorite persistence failure and cancellation propagate unchanged after acknowledgement") {
            for (failure in listOf(
                PersistenceStoreException(PersistenceStoreError.SaveFailed("disk full")),
                CancellationException("favorite cancelled"),
            )) {
                val backing = ContactsFakeStore()
                val original = stored(0x96, "Favorite").also(backing::seed)
                val contacts = object : ContactPersisting by backing {
                    override suspend fun updateContactFavorite(
                        key: EntityKey, expectedPublicKey: Bytes, isFavorite: Boolean,
                    ): ContactFavoriteUpdateResult = throw failure
                }
                val service = ContactService(ContactsFakeSession(), backing, contacts, null, null, ContactsFakePreferences())
                assertSame(failure, assertFailsWith<Exception> {
                    service.setContactFavorite(EntityKey(radioId, original.id), true)
                })
                assertEquals(original, backing.fetchContact(EntityKey(radioId, original.id)))
            }
        },
        contactsNative("setTelemetryPermissions sets and clears bits 1-3 preserving the favourite bit") {
            val store = ContactsFakeStore()
            val contact = stored(0x66, "Fay", flags = 0x01u).also(store::seed)
            val session = ContactsFakeSession()
            val service = contactsService(session = session, store = store)
            val key = EntityKey(radioId, contact.id)

            service.setTelemetryPermissions(key, granted = true)
            assertEquals(0x0Fu.toUByte(), store.contacts[contact.id]?.flags)
            assertTrue(ContactService.hasTelemetryPermissions(0x0Fu))
            service.setTelemetryPermissions(key, granted = false)
            assertEquals(0x01u.toUByte(), store.contacts[contact.id]?.flags)
            assertFalse(ContactService.hasTelemetryPermissions(0x01u))
            assertTrue(ContactService.hasTelemetryPermissions(0x04u))
            // The device record carries the pre-change flags with the new flags as the argument.
            assertEquals(
                listOf(0x01u to 0x0Fu, 0x0Fu to 0x01u).map { (old, new) -> old.toUByte() to new.toUByte() },
                session.changeContactFlagsInvocations.map { it.contact.flags.rawValue to it.flags.rawValue },
            )
        },
        contactsNative("setContactFavorite persists nothing when the device rejects the flag change") {
            val store = ContactsFakeStore()
            val contact = stored(0x77, "Gus").also(store::seed)
            val session = ContactsFakeSession().apply {
                changeContactFlagsFailures = mapOf(contact.publicKey to MeshCoreException.Timeout())
            }
            assertFailsWith<ContactServiceError.SessionError> {
                contactsService(session = session, store = store).setContactFavorite(EntityKey(radioId, contact.id), true)
            }
            assertEquals(contact, store.contacts[contact.id])
        },
        contactsNative("telemetry rejection persists nothing and preserves the existing session error convention") {
            val backing = ContactsFakeStore()
            val original = stored(0x99, "Telemetry").also(backing::seed)
            val contacts = object : ContactPersisting by backing {
                override suspend fun updateContactLocalFields(
                    key: EntityKey, expectedPublicKey: Bytes, update: ContactLocalUpdate,
                ): ContactLocalUpdateResult = error("Rejected telemetry must not persist")
            }
            val session = ContactsFakeSession().apply {
                changeContactFlagsFailures = mapOf(original.publicKey to MeshCoreException.Timeout())
            }
            val service = ContactService(session, backing, contacts, null, null, ContactsFakePreferences())
            assertFailsWith<ContactServiceError.SessionError> {
                service.setTelemetryPermissions(EntityKey(radioId, original.id), true)
            }
            assertEquals(original, backing.fetchContact(EntityKey(radioId, original.id)))
        },
        contactsNative("migrateAppFavoritesToDevice marks complete only when every favourite reaches the device") {
            val store = ContactsFakeStore()
            store.seed(stored(0xA1, "One", isFavorite = true))
            store.seed(stored(0xA2, "Two", isFavorite = true))
            store.seed(stored(0xA3, "AlreadyOnDevice", flags = 0x01u, isFavorite = true))
            store.seed(stored(0xA4, "NotFavourite"))
            val session = ContactsFakeSession().apply {
                changeContactFlagsFailures = mapOf(contactsKey(0xA2) to MeshCoreException.Timeout())
            }
            val preferences = ContactsFakePreferences()
            val service = contactsService(session = session, store = store, preferences = preferences)

            assertEquals(1L, service.migrateAppFavoritesToDevice(radioId))
            assertFalse(preferences.bool(ContactService.FAVORITES_MIGRATION_KEY))

            session.changeContactFlagsFailures = emptyMap()
            assertEquals(1L, service.migrateAppFavoritesToDevice(radioId))
            assertTrue(preferences.bool(ContactService.FAVORITES_MIGRATION_KEY))
            assertEquals(0L, service.migrateAppFavoritesToDevice(radioId))
            assertEquals(3, session.changeContactFlagsInvocations.size)
        },
        contactsNative("updateContactPreferences trims Foundation whitespacesAndNewlines, not Kotlin isWhitespace") {
            val store = ContactsFakeStore()
            val contact = stored(0x88, "Hal").also(store::seed)
            val service = contactsService(store = store)
            val key = EntityKey(radioId, contact.id)

            service.updateContactPreferences(key, nickname = " \tRico \u0085")
            assertEquals("Rico", store.contacts[contact.id]?.nickname)
            service.updateContactPreferences(key, nickname = "\u001FRico\u001F")
            assertEquals("\u001FRico\u001F", store.contacts[contact.id]?.nickname)
        },
        contactsNative("field-only avatar and OCV updates preserve an unrelated nil lastHeardTimestamp") {
            val store = ContactsFakeStore()
            val contact = stored(0x89, "Ida").also(store::seed)
            val service = contactsService(store = store)
            val key = EntityKey(radioId, contact.id)

            service.updateContactAvatar(key, Bytes.of(1))
            assertNull(store.contacts[contact.id]?.lastHeardTimestamp)
            service.updateContactOCVSettings(key, "liIon", null)
            assertNull(store.contacts[contact.id]?.lastHeardTimestamp)
            assertEquals("liIon", store.contacts[contact.id]?.ocvPreset)
            assertFailsWith<ContactServiceError.ContactNotFound> {
                service.updateContactOCVSettings(EntityKey(radioId, UUID.randomUUID()), "x", null)
            }
        },
    )

    @TestFactory
    fun uriAndShareCases(): List<DynamicTest> = listOf(
        contactsNative("exportContactURI percent-encodes query-significant characters like URLComponents plus MeshCoreURIQuery") {
            val key = assertNotNull(applicationBytesFromHex(SHARE_HEX))
            fun uri(name: String, type: ContactType = ContactType.CHAT) = ContactService.exportContactURI(name, key, type)
            assertEquals("meshcore://contact/add?name=Node&public_key=$SHARE_HEX&type=1", uri("Node"))
            assertEquals("meshcore://contact/add?name=a%20%26%20b&public_key=$SHARE_HEX&type=3", uri("a & b", ContactType.ROOM))
            assertTrue(uri("C++ dev").contains("name=C%2B%2B%20dev&"))
            assertTrue(uri("100% sure?").contains("name=100%25%20sure?&"))
            assertTrue(uri("a#b=c").contains("name=a%23b%3Dc&"))
            assertTrue(uri("北京").contains("name=%E5%8C%97%E4%BA%AC&"))
            assertTrue(uri("12:30/@'*,;!\$()~._-").contains("name=12:30/@'*,;!\$()~._-&"))
            assertTrue(uri("Alice&public_key=BC&type=3").startsWith("meshcore://contact/add?name=Alice%26public_key%3DBC%26type%3D3&"))
        },
        contactsNative("parseShare follows ICU first-match semantics when the first token has a non-ASCII digit type") {
            val text = "<$SHARE_HEX:٣:first> <$SHARE_HEX:2:second>"
            assertNull(ContactShareUtilities.parseShare(text))
            assertEquals(listOf("second"), ContactShareUtilities.extractShares(text).map { it.name })
            assertEquals(ContactType.ROOM, ContactShareUtilities.parseShare("<${SHARE_HEX.lowercase()}:003:x>")?.contactType)
        },
        contactsNative("share tokens use grapheme semantics for the terminator and separators") {
            val key = assertNotNull(applicationBytesFromHex(SHARE_HEX))
            // A '>' fused with a combining mark is a different Character in Swift and is kept.
            assertEquals("<$SHARE_HEX:1:a>́b>", ContactShareUtilities.formatShare(key, ContactType.CHAT, "a>́b>"))
            // A ':' fused with a combining mark does not split the type from the name.
            assertNull(ContactShareUtilities.parseShare("<$SHARE_HEX:1:́name>"))
            assertEquals("́", ContactResult("x", key, ContactType.CHAT).id.let { "́" })
            assertEquals(SHARE_HEX, ContactResult("x", key, ContactType.CHAT).id)
        },
    )

    @TestFactory
    fun cleanupCoordinatorCases(): List<DynamicTest> = listOf(
        contactsNative("cleanup coordinator: block deletes channel messages, refreshes the cache, notifies, clears notifications and badge") {
            val fixture = CleanupFixture()
            val contact = stored(0x10, "Blocked").also(fixture.store::seed)

            fixture.coordinator.handleCleanup(EntityKey(radioId, contact.id), ContactCleanupReason.BLOCKED, contact.publicKey)

            assertEquals(listOf("Blocked" to radioId), fixture.store.deletedChannelMessageSenders)
            assertEquals(listOf("refreshBlocked:${radioId.value}", "conversationsChanged"), fixture.sync.notifications)
            assertEquals(listOf("remove:${contact.id}", "badge"), fixture.calls)
        },
        contactsNative("cleanup coordinator: unblock refreshes without deleting messages or notifications") {
            val fixture = CleanupFixture()
            val contact = stored(0x11, "Unblocked").also(fixture.store::seed)

            fixture.coordinator.handleCleanup(EntityKey(radioId, contact.id), ContactCleanupReason.UNBLOCKED, contact.publicKey)

            assertTrue(fixture.store.deletedChannelMessageSenders.isEmpty())
            assertEquals(listOf("refreshBlocked:${radioId.value}", "conversationsChanged"), fixture.sync.notifications)
            assertEquals(listOf("badge"), fixture.calls)
        },
        contactsNative("cleanup coordinator: delete removes the matching remote node session for the coordinator radio") {
            val fixture = CleanupFixture()
            val key = contactsKey(0x12)
            val session = RemoteNodeSessionDTO(radioId = radioId, publicKey = key, name = "Rpt", role = RemoteNodeRole.REPEATER)
            fixture.store.seed(session)
            fixture.store.seed(RemoteNodeSessionDTO(radioId = radioId, publicKey = contactsKey(0x13), name = "Other", role = RemoteNodeRole.REPEATER))
            val contactId = UUID.randomUUID()

            fixture.coordinator.handleCleanup(EntityKey(radioId, contactId), ContactCleanupReason.DELETED, key)

            assertEquals(listOf("remove:$contactId", "badge", "session:${session.id}"), fixture.calls)
            assertEquals(listOf("conversationsChanged"), fixture.sync.notifications)
        },
        contactsNative("cleanup coordinator: failing optional steps do not stop the chain") {
            val fixture = CleanupFixture(failSessionRemoval = true)
            fixture.store.fetchContactByKeyFailure = IllegalStateException("lookup")
            fixture.coordinator.handleCleanup(EntityKey(radioId, UUID.randomUUID()), ContactCleanupReason.BLOCKED, contactsKey(1))
            assertTrue(fixture.sync.notifications.isEmpty())
            assertEquals(2, fixture.calls.size)
            assertEquals("badge", fixture.calls.last())

            fixture.store.seed(RemoteNodeSessionDTO(radioId = radioId, publicKey = contactsKey(2), name = "R", role = RemoteNodeRole.REPEATER))
            fixture.coordinator.handleCleanup(EntityKey(radioId, UUID.randomUUID()), ContactCleanupReason.DELETED, contactsKey(2))
            assertEquals(listOf("conversationsChanged"), fixture.sync.notifications)

            fixture.store.fetchSessionsFailure = IllegalStateException("sessions")
            fixture.coordinator.handleCleanup(EntityKey(radioId, UUID.randomUUID()), ContactCleanupReason.DELETED, contactsKey(2))
            assertEquals(listOf("conversationsChanged", "conversationsChanged"), fixture.sync.notifications)
        },
    )

    private inner class CleanupFixture(failSessionRemoval: Boolean = false) {
        val store = ContactsFakeStore()
        val sync = ContactsFakeSyncCoordinator()
        private val lock = Any()
        private val recorded = mutableListOf<String>()
        val calls: List<String> get() = synchronized(lock) { recorded.toList() }
        val coordinator = ContactCleanupCoordinator(
            contacts = store,
            rooms = store,
            syncCoordinator = sync,
            notificationService = object : ContactCleanupNotifications {
                override suspend fun removeDeliveredNotifications(contactId: UUID) = synchronized(lock) { recorded += "remove:$contactId" }
                override suspend fun updateBadgeCount() = synchronized(lock) { recorded += "badge" }
            },
            remoteNodeService = object : ContactCleanupRemoteSessions {
                override suspend fun removeSession(id: UUID, publicKey: Bytes) {
                    synchronized(lock) { recorded += "session:$id" }
                    if (failSessionRemoval) throw IllegalStateException("keychain")
                }
            },
            radioId = radioId,
        )
    }

    private companion object {
        const val SHARE_HEX = "A1432C142E1615EAB6414856F58C90CD61E7C5901650142E5EFE4D2F1332654D"
    }
}
