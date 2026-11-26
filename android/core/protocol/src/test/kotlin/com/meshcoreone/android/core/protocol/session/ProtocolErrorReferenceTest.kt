// PortedFrom: MC1Services/Sources/MC1Services/Errors/ProtocolError.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: GPL app descriptions are retained only in this unpackaged test reference, not MIT production.
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.command.PinnedCommandSource
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.ErrorCode
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

class ProtocolErrorReferenceTest {
    @TestFactory
    fun originalErrorCodeFamily() = listOf(
        ErrorCode.UNSUPPORTED_COMMAND to "Command not supported by device firmware.",
        ErrorCode.NOT_FOUND to "Item not found on device.",
        ErrorCode.TABLE_FULL to "Device storage is full.",
        ErrorCode.BAD_STATE to "Device is in an invalid state for this operation.",
        ErrorCode.FILE_IO_ERROR to "Device file system error.",
        ErrorCode.ILLEGAL_ARGUMENT to "Invalid parameter sent to device.",
    ).mapIndexed { index, (code, prose) ->
        nativeCase("GPL ProtocolError raw value ${index + 1} reuses canonical MIT error with test-only prose") {
            val source = PinnedCommandSource.read("MC1Services/Sources/MC1Services/Errors/ProtocolError.swift")
            assertTrue(source.contains("\"$prose\""))
            assertEquals((index + 1).toUByte(), code.rawValue)
            val failure = MeshCoreException.DeviceError(code.rawValue)
            assertEquals(code, failure.deviceErrorCode)
            assertNotEquals(prose, failure.message)
        }
    } + nativeCase("unmodeled device error retains its raw byte without fabricated app localization") {
        val error = MeshCoreException.DeviceError(255u)
        assertEquals(255u.toUByte(), error.code)
        assertNull(error.deviceErrorCode)
    }
}
