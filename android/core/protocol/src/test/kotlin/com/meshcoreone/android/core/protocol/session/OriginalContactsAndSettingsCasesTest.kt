// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/AutoContactRefreshTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/OtherParamsSerializationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OriginalContactsAndSettingsCasesTest {
    @TestFactory
    fun sourceCases() = listOf(
        original("AutoContactRefreshTests", "auto-refresh coalesces bursty contact invalidations") {
            val f = fixture(); start(f)
            f.session.setAutoUpdateContacts(true)
            repeat(3) { f.transport.receive(raw(0x80, filled(0x22, 32))) }; runCurrent()
            assertEquals(2, f.transport.sent.size)
            assertEquals(hex("04"), f.transport.sent.last())
            f.transport.receive(contactsStart(0)); f.transport.receive(contactsEnd(1)); runCurrent()
            assertTrue(f.transport.sent.size in 2..3)
            if (f.transport.sent.size == 3) {
                assertEquals(hex("0401000000"), f.transport.sent.last())
                f.transport.receive(contactsStart(0)); f.transport.receive(contactsEnd(2)); runCurrent()
            }
            assertTrue(f.transport.sent.size <= 3)
            assertFalse(f.session.isContactsDirty)
            f.session.stop()
        },
        original("OtherParamsSerializationTests", "concurrent granular setters do not revert each other") {
            val f = fixture(SessionConfiguration(clientIdentifier = "MCore")); start(f)
            var manual = false
            var acks = 0
            val written = mutableListOf<com.meshcoreone.android.core.protocol.bytes.Bytes>()
            f.transport.onSend = { frame ->
                when (frame[0].toInt()) {
                    0x26 -> {
                        assertEquals(5, frame.size)
                        manual = frame[1] != 0.toUByte()
                        acks = frame[4].toInt()
                        written += frame
                        f.transport.ok()
                    }
                    0x01 -> f.transport.receive(selfPacket(manual = manual, multiAcks = acks))
                    else -> fail("Unexpected device operation in the atomic other-params exchange")
                }
            }
            val first = backgroundScope.async { f.session.setManualAddContacts(true) }
            val second = backgroundScope.async { f.session.setMultiAcks(7u) }
            runCurrent()
            first.await(); second.await()
            assertEquals(listOf(hex("2601000000"), hex("2601000007")), written)
            assertTrue(manual)
            assertEquals(7, acks)
            assertEquals(true, f.session.currentSelfInfo?.manualAddContacts)
            assertEquals(7u.toUByte(), f.session.currentSelfInfo?.multiAcks)
            assertEquals(listOf(0x01, 0x26, 0x01, 0x26, 0x01), f.transport.sent.map { it[0].toInt() })
            f.session.stop()
        },
    )
}
