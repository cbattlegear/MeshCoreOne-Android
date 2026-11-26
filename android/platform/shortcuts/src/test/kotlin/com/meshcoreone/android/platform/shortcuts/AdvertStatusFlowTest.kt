// PortedFrom: MC1Tests/Intents/SendAdvertIntentTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Intents/StatusQueryIntentTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.platform.shortcuts

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdvertStatusFlowTest {
    private val text = FakeText()

    @Test
    @SourceCases("SendAdvertIntentTests::reach maps to flood flag", "SendAdvertIntentTests::reach raw values are pinned shortcut identifiers")
    fun reachIdentifiersArePinned() {
        assertEquals(listOf("zeroHop", "flood"), AdvertReach.entries.map { it.rawValue })
        assertEquals(listOf(false, true), AdvertReach.entries.map { it.sendsFlood })
        assertNull(AdvertReach.fromRawValue("bogus"))
        assertNull(AdvertReach.fromRawValue(null))
    }

    @Test
    @SourceCases("SendAdvertIntentTests::success dialog is reach specific and honest")
    fun zeroHopRunsWithoutConfirmationAndFloodRequiresIt() = runSuspend {
        val radio = FakeRadio()
        val confirmation = FakeConfirmation()
        val flow = AdvertFlow(radio, confirmation, text)
        assertEquals(AdvertResult.Sent("sent:zeroHop"), flow.execute(AdvertReach.ZERO_HOP))
        assertTrue(confirmation.requests.isEmpty())
        assertEquals(AdvertResult.Sent("sent:flood"), flow.execute(AdvertReach.FLOOD))
        assertEquals(1, confirmation.requests.size)
        assertEquals(listOf(false, true), radio.adverts)
        confirmation.answer = false
        assertEquals(AdvertResult.Declined, flow.execute(AdvertReach.FLOOD))
        assertEquals(2, radio.adverts.size)
    }

    @Test
    @SourceCases("SendAdvertIntentTests::map to intent error routes every case", "SendAdvertIntentTests::general error mapping never leaks raw errors", "SendAdvertIntentTests::send self advert without services throws not connected")
    fun failuresAreTypedAndNotConnectedShortCircuits() = runSuspend {
        val radio = FakeRadio(advertFailure = RuntimeException("raw"))
        val flow = AdvertFlow(radio, FakeConfirmation(), text)
        assertEquals(ShortcutError.ADVERT_FAILED, assertFailsWith<ShortcutException> { flow.execute(AdvertReach.ZERO_HOP) }.error)
        radio.advertFailure = ShortcutException(ShortcutError.NOT_CONNECTED)
        assertEquals(ShortcutError.NOT_CONNECTED, assertFailsWith<ShortcutException> { flow.execute(AdvertReach.ZERO_HOP) }.error)
        radio.connectionState = DeviceConnectionState.CONNECTED
        assertEquals(ShortcutError.NOT_CONNECTED, assertFailsWith<ShortcutException> { flow.execute(AdvertReach.ZERO_HOP) }.error)
    }

    @Test
    fun floodStateChangeDuringConfirmationAborts() = runSuspend {
        val radio = FakeRadio()
        val flow = AdvertFlow(radio, FakeConfirmation { radio.connectionState = DeviceConnectionState.DISCONNECTED }, text)
        assertFailsWith<ShortcutException> { flow.execute(AdvertReach.FLOOD) }
        assertTrue(radio.adverts.isEmpty())
    }

    @Test
    @SourceCases(
        "StatusQueryIntentTests::connected rungs report cached battery (arguments)", "StatusQueryIntentTests::absent battery reports no reading not zero percent",
        "StatusQueryIntentTests::connecting speaks connecting", "StatusQueryIntentTests::disconnected with known radio speaks offline name",
        "StatusQueryIntentTests::never connected reports no radio", "StatusQueryIntentTests::nil app state means not ready",
    )
    fun statusDialogs() {
        fun dialog(state: DeviceConnectionState, node: String?, last: String?, battery: Int?) =
            StatusFlow.dialog(RadioStatusSnapshot(state, node, last, battery), text)
        listOf(DeviceConnectionState.READY, DeviceConnectionState.SYNCING, DeviceConnectionState.CONNECTED).forEach {
            assertEquals("Node:80", dialog(it, "Node", "Old", 80))
        }
        assertEquals("Node:nobattery", dialog(DeviceConnectionState.READY, "Node", null, null))
        assertEquals("Old:80", dialog(DeviceConnectionState.READY, null, "Old", 80))
        assertEquals("Your radio:nobattery", dialog(DeviceConnectionState.READY, null, null, null))
        assertEquals("Old:connecting", dialog(DeviceConnectionState.CONNECTING, null, "Old", null))
        assertEquals("Old:offline", dialog(DeviceConnectionState.DISCONNECTED, null, "Old", 50))
        assertEquals("none", dialog(DeviceConnectionState.DISCONNECTED, null, null, null))
        assertEquals("notready", StatusFlow.dialog(null, text))
    }
}
