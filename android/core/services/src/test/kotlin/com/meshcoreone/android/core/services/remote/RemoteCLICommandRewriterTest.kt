// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/RemoteCLICommandRewriterTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import java.time.Instant
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/** Swift suite "RemoteCLICommandRewriter"; parameterized families run every Swift argument. */
class RemoteCLICommandRewriterTest {
    private val now = Instant.ofEpochSecond(1_786_722_487)
    private val expected = "time 1786722487"

    private fun case(name: String, signature: String = "()", body: () -> Unit): DynamicTest =
        DynamicTest.dynamicTest("RemoteCLICommandRewriterTests::$name$signature", body)

    @TestFactory
    fun sourceCases(): List<DynamicTest> = listOf(
        case("clock sync becomes time with the host epoch") {
            assertEquals(expected, RemoteCLICommandRewriter.rewrite("clock sync", now))
        },
        case("clock sync match is case and whitespace insensitive", "(command : String)") {
            for (command in listOf("CLOCK SYNC", "  clock   sync  ", "Clock Sync")) {
                assertEquals(expected, RemoteCLICommandRewriter.rewrite(command, now), "argument: '$command'")
            }
        },
        case("other commands are left unchanged", "(command : String)") {
            for (command in listOf("clock", "time 123", "clock sync extra", "sync_time", "st")) {
                assertEquals(command, RemoteCLICommandRewriter.rewrite(command, now), "argument: '$command'")
            }
        },
        case("pre epoch dates saturate to zero") {
            assertEquals("time 0", RemoteCLICommandRewriter.rewrite("clock sync", Instant.ofEpochSecond(-1000)))
        },
    )
}
