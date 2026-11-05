// AndroidOnly: WP-204 Framework lock/specification and JCE authentication assertions, not device Keystore execution.
package com.meshcoreone.android.core.datastore.enrollment

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.datastore.AndroidStorageAccess
import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.datastore.StorageProblem
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32, 33, 37])
class AndroidWitnessAuthenticationTest {
    @Test
    fun nativeSpecAndExplicitLockChecksNeverRequirePinEnrollmentOnLegacySdk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val users = Shadows.shadowOf(context.getSystemService(UserManager::class.java))
        val keyguard = Shadows.shadowOf(context.getSystemService(KeyguardManager::class.java))
        users.setUserUnlocked(true)
        keyguard.setIsDeviceLocked(false)
        keyguard.setIsDeviceSecure(false)
        val keys = TestKeys()
        val authentication = AndroidWitnessAuthentication(AndroidStorageAccess(context, secrets = true), keys)
        val witness = UUID.randomUUID()
        authentication.createNew(witness)
        val spec = keys.specifications.single()
        assertEquals(256, spec.keySize)
        assertFalse(spec.isUserAuthenticationRequired)
        assertEquals(Build.VERSION.SDK_INT >= 35, spec.isUnlockedDeviceRequired)
        assertEquals(listOf("SHA-256"), spec.digests.toList())
        val bytes = "independent witness fixture".toByteArray()
        val authenticated = authentication.authenticate(witness, bytes)
        assertEquals(32, authenticated.size)
        assertContentEquals(authenticated, authentication.authenticate(witness, bytes))
        assertNotEquals(authenticated.toList(), authentication.authenticate(witness, byteArrayOf(9)).toList())
    }

    @Test
    fun missingAndOccupiedKeysCannotBeSilentlyGeneratedOrReplaced() {
        val keys = TestKeys()
        val authentication = AndroidWitnessAuthentication({ }, keys)
        val witness = UUID.randomUUID()
        assertEquals(StorageProblem.FormerKeyMissing, assertFailsWith<StorageFailure> {
            authentication.authenticate(witness, byteArrayOf())
        }.problem)
        assertTrue(keys.specifications.isEmpty())
        authentication.createNew(witness)
        val old = authentication.authenticate(witness, byteArrayOf(7))
        assertEquals(StorageProblem.SecretAlreadyExists, assertFailsWith<StorageFailure> {
            authentication.createNew(witness)
        }.problem)
        assertContentEquals(old, authentication.authenticate(witness, byteArrayOf(7)))
        assertEquals(1, keys.specifications.size)
        keys.keys.clear()
        assertEquals(StorageProblem.FormerKeyMissing, assertFailsWith<StorageFailure> {
            authentication.authenticate(witness, byteArrayOf())
        }.problem)
        assertEquals(1, keys.specifications.size)
    }

    @Test
    fun lockedBeforeFirstUnlockOrDeviceLockedNeverCreatesOrReadsKeys() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val users = Shadows.shadowOf(context.getSystemService(UserManager::class.java))
        val keyguard = Shadows.shadowOf(context.getSystemService(KeyguardManager::class.java))
        val keys = TestKeys()
        val authentication = AndroidWitnessAuthentication(AndroidStorageAccess(context, secrets = true), keys)
        val witness = UUID.randomUUID()
        users.setUserUnlocked(false)
        keyguard.setIsDeviceLocked(false)
        assertEquals(StorageProblem.LockedBeforeFirstUnlock, assertFailsWith<StorageFailure> {
            authentication.createNew(witness)
        }.problem)
        users.setUserUnlocked(true)
        keyguard.setIsDeviceLocked(true)
        assertEquals(StorageProblem.DeviceLocked, assertFailsWith<StorageFailure> {
            authentication.createNew(witness)
        }.problem)
        assertEquals(StorageProblem.DeviceLocked, assertFailsWith<StorageFailure> {
            authentication.authenticate(witness, byteArrayOf())
        }.problem)
        assertTrue(keys.specifications.isEmpty())
    }

    private class TestKeys : WitnessKeys {
        val keys = mutableMapOf<String, SecretKey>()
        val specifications = mutableListOf<KeyGenParameterSpec>()
        override fun find(alias: String) = keys[alias]
        override fun generate(specification: KeyGenParameterSpec) {
            specifications.add(specification)
            keys[specification.keystoreAlias] = KeyGenerator.getInstance("HmacSHA256").apply { init(256) }.generateKey()
        }
    }
}
