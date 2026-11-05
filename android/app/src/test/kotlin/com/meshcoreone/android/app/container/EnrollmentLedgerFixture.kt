// AndroidOnly: WP-303 Test-only real verifier/witness SQLite with JCE HMAC and host fsync, not Keystore proof.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.datastore.StorageIssueReporter
import com.meshcoreone.android.core.datastore.StorageOperation
import com.meshcoreone.android.core.datastore.StorageProblem
import com.meshcoreone.android.core.datastore.enrollment.AcknowledgmentAuthentication
import com.meshcoreone.android.core.datastore.enrollment.ForegroundWitnessAcknowledgmentVerifier
import com.meshcoreone.android.core.datastore.enrollment.NotificationEnrollmentWitness
import com.meshcoreone.android.core.datastore.enrollment.WitnessAuthentication
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import android.database.sqlite.SQLiteDatabase

/** Reopens actual product ledgers; no injected acknowledgment result or witness mutation. */
internal class EnrollmentLedgerFixture(val root: File) {
    val acknowledgmentFile = File(root, "ack.db")
    val witnessFile = File(root, "witness.db")
    private val acknowledgmentAuthentication = HmacAcknowledgment()
    private val witnessAuthentication = HmacWitness()
    val failures = java.util.concurrent.CopyOnWriteArrayList<StorageFailure>()
    @Volatile var gate: Gate? = null
    @Volatile var verifier: ForegroundWitnessAcknowledgmentVerifier? = null
    @Volatile var witness: NotificationEnrollmentWitness? = null

    fun backend(journal: NotificationEnrollmentCoordinator.Journal) = AndroidEnrollmentBackend(
        journal,
        { copy ->
            ForegroundWitnessAcknowledgmentVerifier(
                acknowledgmentFile, AndroidEnrollmentBackend.warning(copy), acknowledgmentAuthentication,
                StorageIssueReporter { failures += it }, UUID.randomUUID(), null, ::synchronize,
                { point -> gate?.takeIf { it.point == point.name }?.await() },
            ).also { verifier = it }
        },
        { verifier ->
            NotificationEnrollmentWitness(
                witnessFile, witnessAuthentication, verifier, StorageIssueReporter { failures += it },
                synchronize = ::synchronize,
            ).also { witness = it }
        },
    )

    fun statuses(): List<String> {
        if (!acknowledgmentFile.exists()) return emptyList()
        return SQLiteDatabase.openDatabase(
            acknowledgmentFile.path, null, SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("SELECT payload FROM acknowledgment_history ORDER BY sequence", null).use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        DataInputStream(ByteArrayInputStream(rows.getBlob(0))).use { input ->
                            check(input.readUTF() == "MeshCoreOne.notification-ack.history.v1")
                            input.readLong()
                            repeat(2) {
                                val length = input.readInt()
                                check(length > 0 && length <= 1_048_576)
                                input.readFully(ByteArray(length))
                            }
                            repeat(4) { input.readLong() }
                            add(input.readUTF())
                            input.readFully(ByteArray(32))
                            check(input.available() == 0)
                        }
                    }
                }
            }
        }
    }

    fun close() { gate?.release?.countDown() }

    class Gate(val point: String) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun await() {
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "Admission barrier was not released" }
        }
    }

    private fun synchronize(file: File) {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { it.force(true) }
        FileChannel.open(file.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private class HmacAcknowledgment : AcknowledgmentAuthentication {
        private var key: SecretKey? = null
        override fun requireAccessible() = Unit
        override fun hasKey() = key != null
        override fun createKey() { check(key == null); key = newKey() }
        override fun authenticate(bytes: ByteArray) = authenticate(requireKey(key), bytes)
    }

    private class HmacWitness : WitnessAuthentication {
        private val keys = mutableMapOf<UUID, SecretKey>()
        override fun requireAccessible() = Unit
        override fun createNew(witnessId: UUID) { check(witnessId !in keys); keys[witnessId] = newKey() }
        override fun authenticate(witnessId: UUID, bytes: ByteArray) = authenticate(requireKey(keys[witnessId]), bytes)
    }

    companion object {
        private fun newKey() = KeyGenerator.getInstance("HmacSHA256").apply { init(256) }.generateKey()
        private fun requireKey(key: SecretKey?) = key
            ?: throw StorageFailure(StorageProblem.FormerKeyMissing, StorageOperation.READ)
        private fun authenticate(key: SecretKey, bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal(bytes)
        }
    }
}
