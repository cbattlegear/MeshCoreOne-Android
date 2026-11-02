// AndroidOnly: WP-204 Real encrypted DataStore persistence, namespace integrity and failure atomicity.
package com.meshcoreone.android.core.datastore

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.FileStorage
import androidx.datastore.core.Serializer
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.ProviderException
import javax.crypto.KeyGenerator
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SecretBoundaryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun newNamespaceHasNoEntryAndDoesNotGenerateAKeyOnRead() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            assertNull(h.owner.secrets.retrievePassword(radioId(), nodePublicKey()))
            assertFalse(h.owner.secrets.hasPassword(radioId(), nodePublicKey()))
            assertTrue(h.platform.specifications.isEmpty())
            assertFalse(File(h.directory, MeshCoreStorage.SECRET_FILENAME).exists())
        }
    }

    @Test
    fun passwordReopensAndNeverAppearsInPreferenceOrSecretFiles() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            val password = "WP204_FIXTURE_ONLY_\u4E2D\u6587_\uD83D\uDD10"
            h.owner.secrets.storePassword(password, radioId(), nodePublicKey())
            assertTrue(h.owner.secrets.hasPassword(radioId(), nodePublicKey()))
            assertTrue(h.owner.preferences.snapshot().storedValues.isEmpty())
            val needle = password.toByteArray(Charsets.UTF_8)
            for (file in h.directory.walkTopDown().filter { it.isFile }) {
                assertFalse(file.readBytes().containsSequence(needle), file.name)
            }
            h.reopen()
            assertEquals(password, h.owner.secrets.retrievePassword(radioId(), nodePublicKey()))
            assertEquals(1, h.platform.specifications.size)
        }
    }

    @Test
    fun sourceServiceAccountAndRadioPartitionsAreExact() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            val publicKey = Bytes(ByteArray(32) { -1 })
            val identity = SecretIdentity.NodePassword(radioId(), publicKey)
            assertEquals(
                "com.pocketmesh.nodepasswords/00000000-0000-0000-0000-000000000001/" +
                    "//////////////////////////////////////////8=",
                identity.storageKey,
            )
            assertEquals(identity.storageKey, identityFromStorageKey(identity.storageKey).storageKey)
            h.owner.secrets.storePassword("A", radioId(1), publicKey)
            h.owner.secrets.storePassword("B", radioId(2), publicKey)
            h.owner.secrets.storePassword("C", radioId(1), nodePublicKey(2))
            assertEquals("A", h.owner.secrets.retrievePassword(radioId(1), publicKey))
            assertEquals("B", h.owner.secrets.retrievePassword(radioId(2), publicKey))
            assertEquals("C", h.owner.secrets.retrievePassword(radioId(1), nodePublicKey(2)))
            h.owner.secrets.rotateEncryptionKey()
            assertEquals("A", h.owner.secrets.retrievePassword(radioId(1), publicKey))
            assertEquals("B", h.owner.secrets.retrievePassword(radioId(2), publicKey))
        }
    }

    @Test
    fun concurrentNewEntriesCreateExactlyOneMasterKeyAndLoseNoPasswords() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            coroutineScope {
                List(24) { index ->
                    async(Dispatchers.Default) {
                        h.owner.secrets.storePassword("fixture-$index", radioId(), nodePublicKey(index))
                    }
                }.forEach { it.await() }
            }
            assertEquals(1, h.platform.specifications.size)
            for (index in 0 until 24) {
                assertEquals("fixture-$index", h.owner.secrets.retrievePassword(radioId(), nodePublicKey(index)))
            }
        }
    }

    @Test
    fun replacingAValidPasswordIsAtomicAndUsesNewCiphertext() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.secrets.storePassword("old", radioId(), nodePublicKey())
            val file = File(h.directory, MeshCoreStorage.SECRET_FILENAME)
            val before = file.readBytes()
            h.owner.secrets.storePassword("new", radioId(), nodePublicKey())
            assertFalse(before.contentEquals(file.readBytes()))
            h.reopen()
            assertEquals("new", h.owner.secrets.retrievePassword(radioId(), nodePublicKey()))
            assertEquals(1, h.platform.specifications.size)
        }
    }

    @Test
    fun missingEntryDoesNotMeanFormerKeyMissing() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.secrets.storePassword("kept", radioId(), nodePublicKey())
            assertNull(h.owner.secrets.retrievePassword(radioId(), nodePublicKey(2)))
            h.platform.keys.clear()
            expectStorageFailure(StorageProblem.FormerKeyMissing) {
                h.owner.secrets.retrievePassword(radioId(), nodePublicKey())
            }
            expectStorageFailure(StorageProblem.FormerKeyMissing) {
                h.owner.secrets.hasPassword(radioId(), nodePublicKey(2))
            }
            assertEquals(1, h.platform.specifications.size)
        }
    }

    @Test
    fun keyLossBlocksReadsWritesDeletionAndRotationWithoutReplacingCiphertext() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.secrets.storePassword("kept", radioId(), nodePublicKey())
            val file = File(h.directory, MeshCoreStorage.SECRET_FILENAME)
            val before = file.readBytes()
            h.platform.keys.clear()
            expectStorageFailure(StorageProblem.FormerKeyMissing) { h.owner.secrets.retrievePassword(radioId(), nodePublicKey()) }
            expectStorageFailure(StorageProblem.FormerKeyMissing) { h.owner.secrets.storePassword("replacement", radioId(), nodePublicKey()) }
            expectStorageFailure(StorageProblem.FormerKeyMissing) { h.owner.secrets.storePassword("unrelated", radioId(2), nodePublicKey()) }
            expectStorageFailure(StorageProblem.FormerKeyMissing) { h.owner.secrets.deletePassword(radioId(), nodePublicKey()) }
            expectStorageFailure(StorageProblem.FormerKeyMissing) { h.owner.secrets.rotateEncryptionKey() }
            assertContentEquals(before, file.readBytes())
            assertEquals(1, h.platform.specifications.size)
            assertTrue(h.platform.deleted.isEmpty())
        }
    }

    @Test
    fun deletingLastEntryRetainsFormerlyUsedKeyMetadata() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.secrets.storePassword("", radioId(), nodePublicKey())
            assertEquals("", h.owner.secrets.retrievePassword(radioId(), nodePublicKey()))
            h.owner.secrets.deletePassword(radioId(), nodePublicKey())
            h.owner.secrets.deletePassword(radioId(), nodePublicKey())
            assertNull(h.owner.secrets.retrievePassword(radioId(), nodePublicKey()))
            h.reopen()
            h.platform.keys.clear()
            expectStorageFailure(StorageProblem.FormerKeyMissing) {
                h.owner.secrets.storePassword("new", radioId(), nodePublicKey())
            }
            assertEquals(1, h.platform.specifications.size)
        }
    }

    @Test
    fun lockedBeforeUnlockAndLockedScreenAreDistinctFromMissingPassword() = runBlocking<Unit> {
        var problem: StorageProblem? = null
        val access = StorageAccess { operation -> problem?.let { throw StorageFailure(it, operation) } }
        val h = StorageHarness(temporary.newFolder(), secretAccess = access)
        try {
            h.owner.secrets.storePassword("kept", radioId(), nodePublicKey())
            for (state in listOf(StorageProblem.LockedBeforeFirstUnlock, StorageProblem.DeviceLocked, StorageProblem.UserStorageInaccessible)) {
                problem = state
                expectStorageFailure(state) { h.owner.secrets.retrievePassword(radioId(), nodePublicKey()) }
                expectStorageFailure(state) { h.owner.secrets.storePassword("new", radioId(), nodePublicKey()) }
                assertEquals(1, h.platform.specifications.size)
                assertTrue(h.owner.preferences.snapshot().storedValues.isEmpty())
            }
            problem = null
            assertEquals("kept", h.owner.secrets.retrievePassword(radioId(), nodePublicKey()))
        } finally {
            h.close()
        }
    }

    @Test
    fun permanentlyInvalidatedKeyIsReportedWithoutRecreation() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.secrets.storePassword("kept", radioId(), nodePublicKey())
            val before = File(h.directory, MeshCoreStorage.SECRET_FILENAME).readBytes()
            h.platform.findFailure = KeyPermanentlyInvalidatedException()
            expectStorageFailure(StorageProblem.KeyPermanentlyInvalidated) {
                h.owner.secrets.retrievePassword(radioId(), nodePublicKey())
            }
            expectStorageFailure(StorageProblem.KeyPermanentlyInvalidated) {
                h.owner.secrets.storePassword("new", radioId(), nodePublicKey())
            }
            assertContentEquals(before, File(h.directory, MeshCoreStorage.SECRET_FILENAME).readBytes())
            assertEquals(1, h.platform.specifications.size)
        }
    }

    @Test
    fun wrongExistingKeyIsAuthenticationFailureNotMissingOrRegenerated() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.secrets.storePassword("kept", radioId(), nodePublicKey())
            val alias = h.platform.keys.keys.single()
            h.platform.keys[alias] = KeyGenerator.getInstance("AES").run { init(256); generateKey() }
            expectStorageFailure(StorageProblem.AuthenticationFailed) { h.owner.secrets.retrievePassword(radioId(), nodePublicKey()) }
            expectStorageFailure(StorageProblem.AuthenticationFailed) { h.owner.secrets.storePassword("new", radioId(), nodePublicKey()) }
            assertEquals(1, h.platform.specifications.size)
        }
    }

    @Test
    fun corruptCiphertextIsAuthenticatedAndCannotBeOverwrittenAsRecovery() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.secrets.storePassword("kept", radioId(), nodePublicKey())
            rewriteArchive(h) { archive ->
                val key = archive.entries.keys.single()
                val bytes = checkNotNull(archive.entries[key]).toByteArray()
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                SecretArchive(archive.alias, archive.generation, archive.entries + (key to Bytes(bytes)))
            }
            val before = File(h.directory, MeshCoreStorage.SECRET_FILENAME).readBytes()
            expectStorageFailure(StorageProblem.AuthenticationFailed) { h.owner.secrets.retrievePassword(radioId(), nodePublicKey()) }
            expectStorageFailure(StorageProblem.AuthenticationFailed) { h.owner.secrets.hasPassword(radioId(), nodePublicKey()) }
            expectStorageFailure(StorageProblem.AuthenticationFailed) { h.owner.secrets.storePassword("new", radioId(), nodePublicKey()) }
            assertContentEquals(before, File(h.directory, MeshCoreStorage.SECRET_FILENAME).readBytes())
            assertEquals(1, h.platform.specifications.size)
        }
    }

    @Test
    fun unsupportedEnvelopeVersionDoesNotFallBackOrDeleteData() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.secrets.storePassword("kept", radioId(), nodePublicKey())
            rewriteArchive(h) { archive ->
                val key = archive.entries.keys.single()
                val bytes = checkNotNull(archive.entries[key]).toByteArray()
                bytes[4] = 2
                SecretArchive(archive.alias, archive.generation, archive.entries + (key to Bytes(bytes)))
            }
            expectStorageFailure(StorageProblem.UnsupportedVersion(2)) { h.owner.secrets.retrievePassword(radioId(), nodePublicKey()) }
            assertEquals(1, h.platform.specifications.size)
        }
    }

    @Test
    fun unsupportedArchiveVersionAndMalformedArchiveAreDistinctTypedFailures() = runBlocking<Unit> {
        for ((bytes, problem) in listOf(
            byteArrayOf(0x4D, 0x43, 0x44, 0x53, 2) to StorageProblem.UnsupportedVersion(2),
            byteArrayOf(1, 2, 3) to StorageProblem.CorruptSecretState,
        )) {
            val directory = temporary.newFolder()
            val file = File(directory, MeshCoreStorage.SECRET_FILENAME)
            file.writeBytes(bytes)
            withStorage(directory) { h ->
                expectStorageFailure(problem) { h.owner.secrets.retrievePassword(radioId(), nodePublicKey()) }
                expectStorageFailure(problem) { h.owner.secrets.storePassword("new", radioId(), nodePublicKey()) }
                assertContentEquals(bytes, file.readBytes())
                assertTrue(h.platform.specifications.isEmpty())
            }
        }
    }

    @Test
    fun oversizedArchiveRemainsUntouchedAndNeverGeneratesKey() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val file = File(directory, MeshCoreStorage.SECRET_FILENAME)
        file.writeBytes(ByteArray(SecretArchiveSerializer.MAXIMUM_BYTES + 1))
        withStorage(directory) { h ->
            expectStorageFailure(StorageProblem.StateTooLarge(SecretArchiveSerializer.MAXIMUM_BYTES)) {
                h.owner.secrets.retrievePassword(radioId(), nodePublicKey())
            }
            assertEquals(SecretArchiveSerializer.MAXIMUM_BYTES + 1L, file.length())
            assertTrue(h.platform.specifications.isEmpty())
        }
    }

    @Test
    fun permissionProviderAndIoFailuresAreExplicitAndDoNotCreateEmptyStore() = runBlocking<Unit> {
        val failures = listOf(
            SecurityException("Test-only permission failure") to StorageProblem.PermissionDenied,
            ProviderException("Test-only provider failure") to StorageProblem.ProviderFailure,
            IOException("Test-only IO failure") to StorageProblem.IoFailure,
        )
        for ((failure, problem) in failures) {
            withStorage(temporary.newFolder()) { h ->
                h.platform.findFailure = failure
                expectStorageFailure(problem) { h.owner.secrets.storePassword("value", radioId(), nodePublicKey()) }
                assertFalse(File(h.directory, MeshCoreStorage.SECRET_FILENAME).exists())
                assertTrue(h.platform.specifications.isEmpty())
                assertEquals(problem, h.reporter.failures.single().problem)
            }
        }
    }

    @Test
    fun failedKeyCreationDoesNotPersistAClaimedInitializedNamespace() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.platform.generateFailure = ProviderException("Test-only creation failure")
            expectStorageFailure(StorageProblem.ProviderFailure) { h.owner.secrets.storePassword("value", radioId(), nodePublicKey()) }
            assertFalse(File(h.directory, MeshCoreStorage.SECRET_FILENAME).exists())
            assertTrue(h.platform.keys.isEmpty())
            h.platform.generateFailure = null
            h.owner.secrets.storePassword("value", radioId(), nodePublicKey())
            assertEquals(1, h.platform.specifications.size)
        }
    }

    @Test
    fun keyRotationReencryptsAllEntriesBeforeRemovingOldKey() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.secrets.storePassword("A", radioId(1), nodePublicKey())
            h.owner.secrets.storePassword("B", radioId(2), nodePublicKey())
            val oldAlias = h.platform.keys.keys.single()
            val before = File(h.directory, MeshCoreStorage.SECRET_FILENAME).readBytes()
            val result = h.owner.secrets.rotateEncryptionKey()
            assertTrue(result.oldKeyRemoved)
            assertEquals(listOf(oldAlias), h.platform.deleted)
            assertEquals(1, h.platform.keys.size)
            assertEquals(2, h.platform.specifications.size)
            assertFalse(before.contentEquals(File(h.directory, MeshCoreStorage.SECRET_FILENAME).readBytes()))
            h.reopen()
            assertEquals("A", h.owner.secrets.retrievePassword(radioId(1), nodePublicKey()))
            assertEquals("B", h.owner.secrets.retrievePassword(radioId(2), nodePublicKey()))
        }
    }

    @Test
    fun postCommitOldKeyCleanupFailureReportsCommittedRotation() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.secrets.storePassword("kept", radioId(), nodePublicKey())
            h.platform.deleteFailure = ProviderException("Test-only obsolete-key cleanup failure")
            val result = h.owner.secrets.rotateEncryptionKey()
            assertFalse(result.oldKeyRemoved)
            assertEquals(StorageProblem.ProviderFailure, assertNotNull(result.oldKeyCleanupIssue).problem)
            assertEquals(2, h.platform.keys.size)
            h.reopen()
            assertEquals("kept", h.owner.secrets.retrievePassword(radioId(), nodePublicKey()))
        }
    }

    @Test
    fun passwordDeletionIsIdempotentAndRadioScoped() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.secrets.storePassword("A", radioId(1), nodePublicKey())
            h.owner.secrets.storePassword("B", radioId(2), nodePublicKey())
            h.owner.secrets.deletePassword(radioId(1), nodePublicKey())
            h.owner.secrets.deletePassword(radioId(1), nodePublicKey())
            assertNull(h.owner.secrets.retrievePassword(radioId(1), nodePublicKey()))
            assertEquals("B", h.owner.secrets.retrievePassword(radioId(2), nodePublicKey()))
            assertEquals(1, h.platform.specifications.size)
            assertTrue(h.platform.deleted.isEmpty())
        }
    }

    @Test
    fun expandedIdentityImportExportChecksPublicMatchingAndExcludesSeedFormat() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            val generated = KeyGenerationService(SeedSource { rfc8032Seed }).generateIdentity(null)
            assertFailsWith<KeyGenerationFailure.InvalidKey> {
                h.owner.secrets.saveIdentity(radioId(), rfc8032Seed, rfc8032PublicKey)
            }
            expectStorageFailure(StorageProblem.InvalidSecretValue) {
                h.owner.secrets.saveIdentity(radioId(), generated.expandedPrivateKey, nodePublicKey())
            }
            h.owner.secrets.saveIdentity(radioId(), generated.expandedPrivateKey, rfc8032PublicKey)
            expectStorageFailure(StorageProblem.SecretAlreadyExists) {
                h.owner.secrets.saveIdentity(radioId(), generated.expandedPrivateKey, rfc8032PublicKey)
            }
            h.reopen()
            val export = h.owner.secrets.exportIdentityForUser(radioId())
            export.use {
                assertContentEquals(generated.expandedPrivateKey.toByteArray(), it.copyExpandedPrivateKey())
                assertEquals(rfc8032PublicKey, it.publicKey())
                assertTrue(it.toString().contains("REDACTED"))
                assertFalse(it.toString().contains(generated.expandedPrivateKey.hexUppercase()))
            }
            assertFailsWith<IllegalStateException> { export.copyExpandedPrivateKey() }
            assertTrue(h.owner.backupPreferences.snapshotForBackup().presentValues.isEmpty())
        }
    }

    @Test
    fun identityReplacementRequiresExplicitRequestAndNeverGeneratesAutomatically() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            val first = KeyGenerationService(SeedSource { rfc8032Seed }).generateIdentity(null)
            val second = KeyGenerationService().generateIdentity(null)
            h.owner.secrets.saveIdentity(radioId(), first.expandedPrivateKey, first.publicKey)
            expectStorageFailure(StorageProblem.SecretAlreadyExists) {
                h.owner.secrets.saveIdentity(radioId(), second.expandedPrivateKey, second.publicKey)
            }
            h.owner.secrets.saveIdentity(radioId(), second.expandedPrivateKey, second.publicKey, replaceExisting = true)
            h.owner.secrets.exportIdentityForUser(radioId()).use { assertEquals(second.publicKey, it.publicKey()) }
            h.owner.secrets.deleteIdentity(radioId())
            expectStorageFailure(StorageProblem.SecretNotFound) { h.owner.secrets.exportIdentityForUser(radioId()) }
            assertEquals(1, h.platform.specifications.size)
        }
    }

    @Test
    fun writeCancellationBeforeCommitAndReadCancellationPropagateWithoutLoggingSuccess() = runBlocking<Unit> {
        var cancel: Boolean = false
        val access = StorageAccess { if (cancel) throw CancellationException("Test-only operation cancellation") }
        val h = StorageHarness(temporary.newFolder(), secretAccess = access)
        try {
            h.owner.secrets.storePassword("kept", radioId(), nodePublicKey())
            val before = File(h.directory, MeshCoreStorage.SECRET_FILENAME).readBytes()
            cancel = true
            assertFailsWith<CancellationException> { h.owner.secrets.storePassword("new", radioId(), nodePublicKey()) }
            assertFailsWith<CancellationException> { h.owner.secrets.retrievePassword(radioId(), nodePublicKey()) }
            assertFailsWith<CancellationException> { h.owner.secrets.rotateEncryptionKey() }
            assertFailsWith<CancellationException> { h.owner.secrets.deletePassword(radioId(), nodePublicKey()) }
            assertContentEquals(before, File(h.directory, MeshCoreStorage.SECRET_FILENAME).readBytes())
            assertTrue(h.reporter.failures.isEmpty())
            assertTrue(h.platform.deleted.isEmpty())
            cancel = false
            assertEquals("kept", h.owner.secrets.retrievePassword(radioId(), nodePublicKey()))
        } finally {
            h.close()
        }
    }

    @Test
    @Config(sdk = [31, 37])
    fun realDataStoreWriteFailureKeepsOldCiphertextAndKey() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val lifetime = SupervisorJob()
        val platform = TestKeystoreAccess()
        val reporter = RecordingReporter()
        val cryptography = AndroidKeystoreCryptography(platform, StorageAccess {})
        val serializer = SecretArchiveSerializer()
        var fail = false
        val failing = object : Serializer<SecretArchive> {
            override val defaultValue get() = serializer.defaultValue
            override suspend fun readFrom(input: InputStream) = serializer.readFrom(input)
            override suspend fun writeTo(t: SecretArchive, output: OutputStream) {
                if (fail) throw IOException("Test-only encrypted-file write failure")
                serializer.writeTo(t, output)
            }
        }
        val data = DataStoreFactory.create(
            storage = SerializedFileStorage(
                FileStorage(failing) { File(directory, MeshCoreStorage.SECRET_FILENAME) },
            ),
            scope = CoroutineScope(lifetime + Dispatchers.IO),
        )
        val store = SecretStore(data, cryptography, StorageAccess {}, reporter, "test.io")
        try {
            store.storePassword("kept", radioId(), nodePublicKey())
            val before = File(directory, MeshCoreStorage.SECRET_FILENAME).readBytes()
            fail = true
            expectStorageFailure(StorageProblem.IoFailure) { store.storePassword("new", radioId(), nodePublicKey()) }
            expectStorageFailure(StorageProblem.IoFailure) { store.rotateEncryptionKey() }
            assertContentEquals(before, File(directory, MeshCoreStorage.SECRET_FILENAME).readBytes())
            assertEquals("kept", store.retrievePassword(radioId(), nodePublicKey()))
            assertTrue(platform.keys.containsKey("test.io.0"))
            assertTrue(platform.deleted.isEmpty())
            fail = false
            assertTrue(store.rotateEncryptionKey().oldKeyRemoved)
            assertEquals("kept", store.retrievePassword(radioId(), nodePublicKey()))
            assertEquals(2, platform.specifications.size)
        } finally {
            lifetime.cancelAndJoin()
        }
    }

    @Test
    fun deletingAnEntryFromUntrustedArchiveCannotBecomeAMissingPasswordSuccess() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.secrets.storePassword("kept", radioId(), nodePublicKey())
            val file = File(h.directory, MeshCoreStorage.SECRET_FILENAME)
            h.owner.close()
            val serializer = SecretArchiveSerializer()
            val original = file.inputStream().use { serializer.readFrom(it) }
            val forged = SecretArchive(original.alias, original.generation, emptyMap(), original.authentication)
            file.outputStream().use { serializer.writeTo(forged, it) }
            h.reopen()
            expectStorageFailure(StorageProblem.AuthenticationFailed) {
                h.owner.secrets.retrievePassword(radioId(), nodePublicKey())
            }
            assertEquals(1, h.platform.specifications.size)
        }
    }

    @Test
    fun reassigningCiphertextToAnotherIdentityCannotBypassArchiveAuthentication() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.secrets.storePassword("kept", radioId(), nodePublicKey())
            val file = File(h.directory, MeshCoreStorage.SECRET_FILENAME)
            h.owner.close()
            val serializer = SecretArchiveSerializer()
            val original = file.inputStream().use { serializer.readFrom(it) }
            val other = SecretIdentity.NodePassword(radioId(2), nodePublicKey()).storageKey
            val forged = SecretArchive(
                original.alias, original.generation, mapOf(other to original.entries.values.single()), original.authentication,
            )
            file.outputStream().use { serializer.writeTo(forged, it) }
            h.reopen()
            expectStorageFailure(StorageProblem.AuthenticationFailed) {
                h.owner.secrets.retrievePassword(radioId(2), nodePublicKey())
            }
        }
    }

    @Test
    fun anExistingUnauthenticatedEmptyArchiveNeverAuthorizesKeyGeneration() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val file = File(directory, MeshCoreStorage.SECRET_FILENAME)
        file.outputStream().use { output ->
            java.io.DataOutputStream(output).apply {
                writeInt(0x4D434453)
                writeByte(1)
                writeLong(0)
                writeShort(0)
                writeInt(0)
            }
        }

        val before = file.readBytes()
        withStorage(directory) { h ->
            expectStorageFailure(StorageProblem.CorruptSecretState) {
                h.owner.secrets.storePassword("replacement", radioId(), nodePublicKey())
            }
            assertTrue(h.platform.specifications.isEmpty())
            assertContentEquals(before, file.readBytes())
        }
    }

    @Test
    fun concurrentReadsAndRotationsNeverReportOrdinaryRetirementAsLostKey() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.secrets.storePassword("kept", radioId(), nodePublicKey())
            coroutineScope {
                val reading = async(Dispatchers.Default) {
                    repeat(32) { assertEquals("kept", h.owner.secrets.retrievePassword(radioId(), nodePublicKey())) }
                }
                val rotating = async(Dispatchers.Default) {
                    repeat(4) { assertTrue(h.owner.secrets.rotateEncryptionKey().oldKeyRemoved) }
                }
                reading.await()
                rotating.await()
            }
            assertEquals(5, h.platform.specifications.size)
            assertEquals(1, h.platform.keys.size)
            assertTrue(h.reporter.failures.isEmpty())
        }
    }

    private suspend fun rewriteArchive(h: StorageHarness, transform: (SecretArchive) -> SecretArchive) {
        val file = File(h.directory, MeshCoreStorage.SECRET_FILENAME)
        h.owner.close()
        val serializer = SecretArchiveSerializer()
        val original = file.inputStream().use { serializer.readFrom(it) }
        file.outputStream().use { serializer.writeTo(transform(original).authenticated(h.cryptography), it) }
        h.reopen()
    }
}

private fun ByteArray.containsSequence(value: ByteArray): Boolean =
    value.isNotEmpty() && size >= value.size && (0..size - value.size).any { start ->
        value.indices.all { this[start + it] == value[it] }
    }
