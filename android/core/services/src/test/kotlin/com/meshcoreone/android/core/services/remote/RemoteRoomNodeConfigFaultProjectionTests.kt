// AndroidOnly: WP-210 Executed producer assertions for the neutral remote/room/binary/node-config fault projection.
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.errors.BinaryProtocolFault
import com.meshcoreone.android.core.contracts.domain.errors.NodeConfigCoordinateField
import com.meshcoreone.android.core.contracts.domain.errors.NodeConfigRadioField
import com.meshcoreone.android.core.contracts.domain.errors.NodeConfigServiceFault
import com.meshcoreone.android.core.contracts.domain.errors.RemoteNodeFault
import com.meshcoreone.android.core.contracts.domain.errors.RoomServerFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFaultCarrier
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every actual producer case is constructed, projected through [SourceServiceFaultCarrier] and checked for
 * the exact family/case, identical payload values and the identical session cause instance, while its own
 * message, cause, raw fields and retryability stay as they were before the carrier existed.
 */
class RemoteRoomNodeConfigFaultProjectionTests {
    /** Projects through the neutral carrier, not the producer's covariant override, and checks stability. */
    private fun project(producer: Throwable): SourceServiceFault {
        val carrier = assertIs<SourceServiceFaultCarrier>(producer)
        val fault = carrier.sourceServiceFault
        assertEquals(fault, carrier.sourceServiceFault, "projection must be stable across reads")
        return fault
    }

    private fun assertPlain(producer: Throwable, expected: SourceServiceFault, message: String) {
        assertSame(expected, project(producer))
        assertEquals(message, producer.message)
        assertNull(producer.cause)
    }

    private fun assertSession(producer: Throwable, cause: MeshCoreException, message: String): SourceServiceFault {
        val fault = project(producer)
        assertEquals(message, producer.message)
        assertSame(cause, producer.cause)
        return fault
    }

    private fun remotePlain(producer: RemoteNodeError, expected: RemoteNodeFault, message: String, retryable: Boolean) {
        assertPlain(producer, expected, message)
        assertEquals(message, producer.errorDescription)
        assertEquals(retryable, producer.isRetryable, "producer retryability unchanged")
        assertEquals(retryable, expected.isRetryable, "payload carries the source retryability")
        assertEquals("RemoteNodeError.${producer.javaClass.simpleName.replaceFirstChar(Char::lowercaseChar)}", producer.toString())
    }

    // MARK: - RemoteNode (17)

    @TestFactory
    fun remoteNodeProjection(): List<DynamicTest> = listOf(
        remoteNative("NotConnected", RemoteNodeFault.NotConnected, "Not connected to mesh device", true) { RemoteNodeError.NotConnected() },
        remoteCoreNative("RemoteNodeError.LoginFailed projects to RemoteNodeFault.LoginFailed carrying the identical raw reason") {
            val producer = RemoteNodeError.invalidPublicKeyLength(31)
            val reason = producer.reason
            val fault = assertIs<RemoteNodeFault.LoginFailed>(project(producer))
            assertEquals(RemoteNodeFault.LoginFailed(reason), fault)
            assertSame(reason, fault.reason)
            assertEquals("Invalid public key length: expected 32 bytes, got 31", reason)
            assertEquals("Login failed: Invalid public key length: expected 32 bytes, got 31", producer.message)
            assertEquals("RemoteNodeError.loginFailed(\"$reason\")", producer.toString())
            assertNull(producer.cause)
            assertFalse(producer.isRetryable)
            assertFalse(fault.isRetryable)
        },
        remoteCoreNative("RemoteNodeError.SendFailed projects to RemoteNodeFault.SendFailed carrying the identical raw reason") {
            val reason = ""
            val producer = RemoteNodeError.SendFailed(reason)
            val fault = assertIs<RemoteNodeFault.SendFailed>(project(producer))
            assertEquals(RemoteNodeFault.SendFailed(reason), fault)
            assertSame(reason, fault.reason)
            assertSame(reason, producer.reason)
            assertEquals("Failed to send: ", producer.message)
            assertEquals("RemoteNodeError.sendFailed(\"\")", producer.toString())
            assertNull(producer.cause)
            assertFalse(producer.isRetryable)
            assertFalse(fault.isRetryable)
        },
        remoteNative("InvalidResponse", RemoteNodeFault.InvalidResponse, "Invalid response from remote node", false) { RemoteNodeError.InvalidResponse() },
        remoteNative("PermissionDenied", RemoteNodeFault.PermissionDenied, "Permission denied", false) { RemoteNodeError.PermissionDenied() },
        remoteNative("Timeout", RemoteNodeFault.Timeout, "Request timed out", true) { RemoteNodeError.Timeout() },
        remoteNative("SessionNotFound", RemoteNodeFault.SessionNotFound, "Remote node session not found", false) { RemoteNodeError.SessionNotFound() },
        remoteNative("PasswordNotFound", RemoteNodeFault.PasswordNotFound, "Password not found in keychain", false) { RemoteNodeError.PasswordNotFound() },
        remoteNative("FloodRouted", RemoteNodeFault.FloodRouted, "Keep-alive requires direct routing path", true) { RemoteNodeError.FloodRouted() },
        remoteNative("PathDiscoveryFailed", RemoteNodeFault.PathDiscoveryFailed, "Failed to establish direct path", false) { RemoteNodeError.PathDiscoveryFailed() },
        remoteNative("ContactNotFound", RemoteNodeFault.ContactNotFound, "Contact not found in database", false) { RemoteNodeError.ContactNotFound() },
        remoteNative("RadioContactsFull", RemoteNodeFault.RadioContactsFull, "Radio contact list is full", false) { RemoteNodeError.RadioContactsFull() },
        remoteNative("Cancelled", RemoteNodeFault.Cancelled, "Login cancelled", false) { RemoteNodeError.Cancelled() },
        remoteNative("InvalidSensorSetting", RemoteNodeFault.InvalidSensorSetting, "Invalid sensor setting", false) { RemoteNodeError.InvalidSensorSetting() },
        remoteNative("SensorSettingRejected", RemoteNodeFault.SensorSettingRejected, "Sensor rejected the setting", false) { RemoteNodeError.SensorSettingRejected() },
        remoteNative("SensorOutcomeUnknown", RemoteNodeFault.SensorOutcomeUnknown, "Sensor setting outcome unknown; reload before retrying", false) { RemoteNodeError.SensorOutcomeUnknown() },
        remoteCoreNative("RemoteNodeError.SessionError projects to RemoteNodeFault.SessionError carrying the identical MeshCoreException and its own cause chain") {
            val transportFailure = IllegalStateException("link dropped")
            val cause = MeshCoreException.ConnectionLost(transportFailure)
            val producer = RemoteNodeError.SessionError(cause)
            val fault = assertIs<RemoteNodeFault.SessionError>(assertSession(producer, cause, "Connection was lost"))
            assertSame(cause, fault.error)
            assertSame(cause, producer.error)
            assertSame(transportFailure, fault.error.cause)
            assertEquals("RemoteNodeError.sessionError(ConnectionLost)", producer.toString())
            assertFalse(producer.isRetryable)
            assertFalse(fault.isRetryable)
        },
    )

    private fun remoteNative(
        case: String,
        expected: RemoteNodeFault,
        message: String,
        retryable: Boolean,
        producer: () -> RemoteNodeError,
    ): DynamicTest = remoteCoreNative(
        "RemoteNodeError.$case projects to RemoteNodeFault.$case with message, cause and retryability ($retryable) unchanged",
    ) { remotePlain(producer(), expected, message, retryable) }

    // MARK: - RoomServer (6)

    @TestFactory
    fun roomServerProjection(): List<DynamicTest> = listOf(
        roomNative("NotConnected", RoomServerFault.NotConnected, "Not connected to device.") { RoomServerError.NotConnected() },
        roomNative("SessionNotFound", RoomServerFault.SessionNotFound, "Room session not found.") { RoomServerError.SessionNotFound() },
        remoteCoreNative("RoomServerError.SendFailed projects to RoomServerFault.SendFailed carrying the identical raw reason") {
            val reason = "Device returned error code 6"
            val producer = RoomServerError.SendFailed(reason)
            val fault = assertIs<RoomServerFault.SendFailed>(project(producer))
            assertEquals(RoomServerFault.SendFailed(reason), fault)
            assertSame(reason, fault.reason)
            assertSame(reason, producer.reason)
            assertEquals("Send failed: Device returned error code 6", producer.message)
            assertEquals("RoomServerError.sendFailed(\"$reason\")", producer.toString())
            assertNull(producer.cause)
        },
        roomNative("PermissionDenied", RoomServerFault.PermissionDenied, "Permission denied.") { RoomServerError.PermissionDenied() },
        roomNative("InvalidResponse", RoomServerFault.InvalidResponse, "Invalid response from device.") { RoomServerError.InvalidResponse() },
        remoteCoreNative("RoomServerError.SessionError projects to RoomServerFault.SessionError carrying the identical MeshCoreException") {
            val cause = MeshCoreException.DeviceError(3u)
            val producer = RoomServerError.SessionError(cause)
            val fault = assertIs<RoomServerFault.SessionError>(assertSession(producer, cause, "Device returned error code 3"))
            assertSame(cause, fault.error)
            assertSame(cause, producer.error)
            assertEquals("RoomServerError.sessionError(DeviceError)", producer.toString())
        },
    )

    private fun roomNative(case: String, expected: RoomServerFault, message: String, producer: () -> RoomServerError): DynamicTest =
        remoteCoreNative("RoomServerError.$case projects to RoomServerFault.$case with message and cause unchanged") {
            val error = producer()
            assertPlain(error, expected, message)
            assertEquals(message, error.errorDescription)
            assertEquals("RoomServerError.${case.replaceFirstChar(Char::lowercaseChar)}", error.toString())
        }

    // MARK: - BinaryProtocol (5)

    @TestFactory
    fun binaryProtocolProjection(): List<DynamicTest> = listOf(
        binaryNative("NotConnected", BinaryProtocolFault.NotConnected, "Not connected to device.") { BinaryProtocolError.NotConnected() },
        binaryNative("SendFailed", BinaryProtocolFault.SendFailed, "Failed to send request.") { BinaryProtocolError.SendFailed() },
        binaryNative("Timeout", BinaryProtocolFault.Timeout, "Request timed out.") { BinaryProtocolError.Timeout() },
        binaryNative("InvalidResponse", BinaryProtocolFault.InvalidResponse, "Invalid response from device.") { BinaryProtocolError.InvalidResponse() },
        remoteCoreNative("BinaryProtocolError.SessionError projects to BinaryProtocolFault.SessionError carrying the identical MeshCoreException") {
            val cause = MeshCoreException.Timeout()
            val producer = BinaryProtocolError.SessionError(cause)
            val fault = assertIs<BinaryProtocolFault.SessionError>(assertSession(producer, cause, "Mesh operation timed out"))
            assertSame(cause, fault.error)
            assertSame(cause, producer.error)
            assertEquals("BinaryProtocolError.sessionError(Timeout)", producer.toString())
        },
    )

    private fun binaryNative(
        case: String,
        expected: BinaryProtocolFault,
        message: String,
        producer: () -> BinaryProtocolError,
    ): DynamicTest = remoteCoreNative("BinaryProtocolError.$case projects to BinaryProtocolFault.$case with message and cause unchanged") {
        val error = producer()
        assertPlain(error, expected, message)
        assertEquals(message, error.errorDescription)
        assertEquals("BinaryProtocolError.${case.replaceFirstChar(Char::lowercaseChar)}", error.toString())
    }

    // MARK: - NodeConfig (9)

    /** Projects a node-config producer and checks its message, absent cause and data-class surface. */
    private fun projectConfig(producer: NodeConfigServiceError, message: String, rendered: String): NodeConfigServiceFault {
        val fault = assertIs<NodeConfigServiceFault>(project(producer))
        assertEquals(message, producer.message)
        assertEquals(rendered, producer.toString())
        assertNull(producer.cause)
        return fault
    }

    @TestFactory
    fun nodeConfigProjection(): List<DynamicTest> = listOf(
        remoteCoreNative("NodeConfigServiceError.InvalidChannelSecret projects to NodeConfigServiceFault.InvalidChannelSecret carrying index and hexLength") {
            val producer = NodeConfigServiceError.InvalidChannelSecret(index = 5, hexLength = 31)
            val fault = projectConfig(producer, "Channel 5 has invalid secret (31 hex chars, expected 32)", "InvalidChannelSecret(index=5, hexLength=31)")
            assertEquals(NodeConfigServiceFault.InvalidChannelSecret(index = 5, hexLength = 31), fault)
        },
        remoteCoreNative("NodeConfigServiceError.InvalidContactPublicKey projects to NodeConfigServiceFault.InvalidContactPublicKey carrying the identical name") {
            val name = "Base \"Camp\""
            val producer = NodeConfigServiceError.InvalidContactPublicKey(name)
            val fault = assertIs<NodeConfigServiceFault.InvalidContactPublicKey>(
                projectConfig(producer, "Contact \"Base \"Camp\"\" has an invalid public key", "InvalidContactPublicKey(name=$name)"),
            )
            assertSame(name, fault.name)
        },
        remoteCoreNative("NodeConfigServiceError.InvalidPathHashMode projects to NodeConfigServiceFault.InvalidPathHashMode carrying the identical name and UByte mode") {
            val name = "Relay"
            val mode: UByte = 255u
            val producer = NodeConfigServiceError.InvalidPathHashMode(name, mode)
            val fault = assertIs<NodeConfigServiceFault.InvalidPathHashMode>(
                projectConfig(producer, "Contact \"Relay\" has unsupported path hash mode 255 (expected 0, 1, or 2)", "InvalidPathHashMode(name=Relay, mode=255)"),
            )
            assertEquals(NodeConfigServiceFault.InvalidPathHashMode(name, mode), fault)
            assertSame(name, fault.name)
            assertEquals(mode, fault.mode)
        },
        remoteCoreNative("NodeConfigServiceError.InvalidPrivateKey projects to NodeConfigServiceFault.InvalidPrivateKey carrying hexLength") {
            val producer = NodeConfigServiceError.InvalidPrivateKey(hexLength = 127)
            val fault = projectConfig(producer, "Invalid private key (127 hex chars, expected 128)", "InvalidPrivateKey(hexLength=127)")
            assertEquals(NodeConfigServiceFault.InvalidPrivateKey(hexLength = 127), fault)
        },
        remoteCoreNative("NodeConfigServiceError.InvalidRadioSettings projects every RadioField one-for-one onto NodeConfigRadioField") {
            val expected = mapOf(
                RadioField.FREQUENCY to NodeConfigRadioField.FREQUENCY,
                RadioField.BANDWIDTH to NodeConfigRadioField.BANDWIDTH,
                RadioField.SPREADING_FACTOR to NodeConfigRadioField.SPREADING_FACTOR,
                RadioField.CODING_RATE to NodeConfigRadioField.CODING_RATE,
                RadioField.TX_POWER to NodeConfigRadioField.TX_POWER,
            )
            assertEquals(RadioField.entries.toSet(), expected.keys)
            assertEquals(NodeConfigRadioField.entries.toSet(), expected.values.toSet())
            expected.forEach { (field, faultField) ->
                val producer = NodeConfigServiceError.InvalidRadioSettings(field)
                val fault = projectConfig(producer, "Radio parameter is outside the supported range", "InvalidRadioSettings(field=$field)")
                assertEquals(NodeConfigServiceFault.InvalidRadioSettings(faultField), fault)
                assertSame(field, producer.field)
            }
        },
        remoteCoreNative("NodeConfigServiceError.NoAvailableChannelSlot projects to NodeConfigServiceFault.NoAvailableChannelSlot carrying the identical name") {
            val name = "#mesh"
            val producer = NodeConfigServiceError.NoAvailableChannelSlot(name)
            val fault = assertIs<NodeConfigServiceFault.NoAvailableChannelSlot>(
                projectConfig(producer, "No empty channel slot available for \"#mesh\"", "NoAvailableChannelSlot(name=#mesh)"),
            )
            assertSame(name, fault.name)
        },
        remoteCoreNative("NodeConfigServiceError.InvalidCoordinate projects every CoordinateField one-for-one, carrying the identical contact name") {
            val latitudeName = "North Ridge"
            val longitudeName = ""
            val expected = listOf(
                CoordinateField.PositionLatitude to NodeConfigCoordinateField.PositionLatitude,
                CoordinateField.PositionLongitude to NodeConfigCoordinateField.PositionLongitude,
                CoordinateField.ContactLatitude(latitudeName) to NodeConfigCoordinateField.ContactLatitude(latitudeName),
                CoordinateField.ContactLongitude(longitudeName) to NodeConfigCoordinateField.ContactLongitude(longitudeName),
            )
            assertEquals(permitted(CoordinateField::class.java), expected.map { it.first.javaClass }.toSet())
            assertEquals(permitted(NodeConfigCoordinateField::class.java), expected.map { it.second.javaClass }.toSet())
            expected.forEach { (field, faultField) ->
                val producer = NodeConfigServiceError.InvalidCoordinate(field)
                val fault = assertIs<NodeConfigServiceFault.InvalidCoordinate>(
                    projectConfig(producer, "Coordinate is invalid or out of range", "InvalidCoordinate(field=$field)"),
                )
                assertEquals(NodeConfigServiceFault.InvalidCoordinate(faultField), fault)
                assertEquals(field.javaClass.simpleName, fault.field.javaClass.simpleName, "case-for-case name mapping")
            }
            val latitude = assertIs<NodeConfigCoordinateField.ContactLatitude>(
                assertIs<NodeConfigServiceFault.InvalidCoordinate>(
                    project(NodeConfigServiceError.InvalidCoordinate(CoordinateField.ContactLatitude(latitudeName))),
                ).field,
            )
            assertSame(latitudeName, latitude.name)
            val longitude = assertIs<NodeConfigCoordinateField.ContactLongitude>(
                assertIs<NodeConfigServiceFault.InvalidCoordinate>(
                    project(NodeConfigServiceError.InvalidCoordinate(CoordinateField.ContactLongitude(longitudeName))),
                ).field,
            )
            assertSame(longitudeName, longitude.name)
        },
        remoteCoreNative("NodeConfigServiceError.InvalidOutPath projects to NodeConfigServiceFault.InvalidOutPath carrying the identical name") {
            val name = "Gateway"
            val producer = NodeConfigServiceError.InvalidOutPath(name)
            val fault = assertIs<NodeConfigServiceFault.InvalidOutPath>(
                projectConfig(producer, "Contact \"Gateway\" has an invalid routing path", "InvalidOutPath(name=Gateway)"),
            )
            assertSame(name, fault.name)
        },
        remoteCoreNative("NodeConfigServiceError.ContactCapacityExceeded projects to NodeConfigServiceFault.ContactCapacityExceeded carrying needed and available") {
            val producer = NodeConfigServiceError.ContactCapacityExceeded(needed = 12, available = 0)
            val fault = projectConfig(
                producer,
                "Import needs 12 free contact slot(s) but only 0 remain on the device",
                "ContactCapacityExceeded(needed=12, available=0)",
            )
            assertEquals(NodeConfigServiceFault.ContactCapacityExceeded(needed = 12, available = 0), fault)
        },
    )

    // MARK: - Exhaustiveness

    /**
     * One instance of every producer case. kotlin-reflect is not on this module's classpath, so the
     * sealed hierarchies are read through the JVM `PermittedSubclasses` attribute instead.
     */
    private val remoteProducers: List<RemoteNodeError> = listOf(
        RemoteNodeError.NotConnected(), RemoteNodeError.LoginFailed("login"), RemoteNodeError.SendFailed("send"),
        RemoteNodeError.InvalidResponse(), RemoteNodeError.PermissionDenied(), RemoteNodeError.Timeout(),
        RemoteNodeError.SessionNotFound(), RemoteNodeError.PasswordNotFound(), RemoteNodeError.FloodRouted(),
        RemoteNodeError.PathDiscoveryFailed(), RemoteNodeError.ContactNotFound(), RemoteNodeError.RadioContactsFull(),
        RemoteNodeError.Cancelled(), RemoteNodeError.SessionError(MeshCoreException.NotConnected()),
        RemoteNodeError.InvalidSensorSetting(), RemoteNodeError.SensorSettingRejected(), RemoteNodeError.SensorOutcomeUnknown(),
    )

    private val roomProducers: List<RoomServerError> = listOf(
        RoomServerError.NotConnected(), RoomServerError.SessionNotFound(), RoomServerError.SendFailed("send"),
        RoomServerError.PermissionDenied(), RoomServerError.InvalidResponse(),
        RoomServerError.SessionError(MeshCoreException.DeviceError(2u)),
    )

    private val binaryProducers: List<BinaryProtocolError> = listOf(
        BinaryProtocolError.NotConnected(), BinaryProtocolError.SendFailed(), BinaryProtocolError.Timeout(),
        BinaryProtocolError.InvalidResponse(), BinaryProtocolError.SessionError(MeshCoreException.FeatureDisabled()),
    )

    private val nodeConfigProducers: List<NodeConfigServiceError> = listOf(
        NodeConfigServiceError.InvalidChannelSecret(1, 2), NodeConfigServiceError.InvalidContactPublicKey("a"),
        NodeConfigServiceError.InvalidPathHashMode("b", 3u), NodeConfigServiceError.InvalidPrivateKey(4),
        NodeConfigServiceError.InvalidRadioSettings(RadioField.TX_POWER), NodeConfigServiceError.NoAvailableChannelSlot("c"),
        NodeConfigServiceError.InvalidCoordinate(CoordinateField.PositionLatitude), NodeConfigServiceError.InvalidOutPath("d"),
        NodeConfigServiceError.ContactCapacityExceeded(5, 6),
    )

    private fun permitted(type: Class<*>): Set<Class<*>> = type.permittedSubclasses.orEmpty().toSet()

    private fun assertBijection(
        producers: List<Throwable>,
        producerType: Class<*>,
        faultType: Class<*>,
        sourceCaseCount: Int,
    ) {
        val producerClasses = producers.map { it.javaClass }
        val faultClasses = producers.map { project(it).javaClass }
        assertEquals(sourceCaseCount, permitted(producerType).size, "producer cases")
        assertEquals(sourceCaseCount, permitted(faultType).size, "payload cases")
        assertEquals(permitted(producerType), producerClasses.toSet(), "every producer case is exercised once")
        assertEquals(sourceCaseCount, producerClasses.size)
        assertEquals(permitted(faultType), faultClasses.toSet(), "every payload case is produced")
        assertEquals(sourceCaseCount, faultClasses.toSet().size, "no two producer cases share a payload case")
        producers.zip(faultClasses).forEach { (producer, faultClass) ->
            assertEquals(producer.javaClass.simpleName, faultClass.simpleName, "case-for-case name mapping")
        }
    }

    @TestFactory
    fun exhaustiveness(): List<DynamicTest> = listOf(
        remoteCoreNative("every RemoteNodeError case (17) maps one-for-one onto every RemoteNodeFault case with identical retryability") {
            assertBijection(remoteProducers, RemoteNodeError::class.java, RemoteNodeFault::class.java, 17)
            remoteProducers.forEach { producer ->
                val fault = assertIs<RemoteNodeFault>(project(producer))
                assertEquals(producer.isRetryable, fault.isRetryable, producer.javaClass.simpleName)
            }
            assertEquals(
                setOf(RemoteNodeError.NotConnected::class.java, RemoteNodeError.Timeout::class.java, RemoteNodeError.FloodRouted::class.java),
                remoteProducers.filter { it.isRetryable }.map { it.javaClass }.toSet(),
            )
        },
        remoteCoreNative("every RoomServerError case (6) maps one-for-one onto every RoomServerFault case") {
            assertBijection(roomProducers, RoomServerError::class.java, RoomServerFault::class.java, 6)
        },
        remoteCoreNative("every BinaryProtocolError case (5) maps one-for-one onto every BinaryProtocolFault case") {
            assertBijection(binaryProducers, BinaryProtocolError::class.java, BinaryProtocolFault::class.java, 5)
        },
        remoteCoreNative("every NodeConfigServiceError case (9) maps one-for-one onto every NodeConfigServiceFault case") {
            assertBijection(nodeConfigProducers, NodeConfigServiceError::class.java, NodeConfigServiceFault::class.java, 9)
            assertEquals(RadioField.entries.map { it.name }, NodeConfigRadioField.entries.map { it.name })
            assertEquals(4, permitted(CoordinateField::class.java).size)
            assertEquals(
                permitted(CoordinateField::class.java).map { it.simpleName }.toSet(),
                permitted(NodeConfigCoordinateField::class.java).map { it.simpleName }.toSet(),
            )
        },
        remoteCoreNative("the four families are the SourceServiceFault families this declaration owns") {
            val owned = setOf(
                RemoteNodeFault::class.java, RoomServerFault::class.java,
                BinaryProtocolFault::class.java, NodeConfigServiceFault::class.java,
            )
            assertEquals(owned, permitted(SourceServiceFault::class.java).intersect(owned))
            val expectedFamily = remoteProducers.map { it to RemoteNodeFault::class.java } +
                roomProducers.map { it to RoomServerFault::class.java } +
                binaryProducers.map { it to BinaryProtocolFault::class.java } +
                nodeConfigProducers.map { it to NodeConfigServiceFault::class.java }
            expectedFamily.forEach { (producer, family) ->
                assertEquals(family, owned.single { it.isInstance(project(producer)) }, producer.javaClass.name)
            }
        },
        remoteCoreNative("NodeConfigDecodingException stays separate and does not carry a SourceServiceFault") {
            val decoding = permitted(NodeConfigDecodingException::class.java)
            assertEquals(4, decoding.size)
            decoding.forEach { case ->
                assertFalse(SourceServiceFaultCarrier::class.java.isAssignableFrom(case), case.name)
            }
            val error: Throwable = NodeConfigDecodingException.DataCorrupted("contacts[0]", "bad")
            assertFalse(error is SourceServiceFaultCarrier)
        },
        remoteCoreNative("the carried PR #49 declaration ContactsChannelAdvertisementFaults.kt is byte-exact") {
            val bytes = File(CARRIED_DECLARATION).readBytes()
            assertEquals(CARRIED_DECLARATION_SHA256, digest("SHA-256", bytes))
            val blob = "blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII) + bytes
            assertEquals(CARRIED_DECLARATION_GIT_BLOB, digest("SHA-1", blob))
        },
    )

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /** Relative to the `:core:services` project directory, which Gradle uses as the test working directory. */
        const val CARRIED_DECLARATION =
            "../contracts/src/main/kotlin/com/meshcoreone/android/core/contracts/domain/errors/ContactsChannelAdvertisementFaults.kt"
        const val CARRIED_DECLARATION_SHA256 = "1b7f21f5ff827e88462a2ac6c722a681c8c24dd9d496d130da9b3139d6e5a592"
        const val CARRIED_DECLARATION_GIT_BLOB = "3f1f2077a0846242b1bbec669ce6e0ac913d1bae"
    }
}
