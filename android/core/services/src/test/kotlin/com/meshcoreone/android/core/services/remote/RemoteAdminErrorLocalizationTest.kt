// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ErrorLocalizationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/** The ErrorLocalizationTests cases whose error types WP-210 owns (`RoomServerError`, `BinaryProtocolError`). */
class RemoteAdminErrorLocalizationTest {
    @TestFactory
    fun serviceErrorSpotChecks(): List<DynamicTest> = listOf(
        remoteCoreOriginal("ErrorLocalizationTests", "RoomServerError.permissionDenied produces readable description") {
            assertEquals("Permission denied.", RoomServerError.PermissionDenied().errorDescription)
        },
        remoteCoreOriginal("ErrorLocalizationTests", "BinaryProtocolError.timeout produces readable description") {
            assertEquals("Request timed out.", BinaryProtocolError.Timeout().errorDescription)
        },
    )
}
