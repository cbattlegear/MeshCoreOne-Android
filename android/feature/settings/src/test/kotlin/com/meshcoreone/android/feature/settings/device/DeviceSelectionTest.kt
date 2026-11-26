// PortedFrom: MC1Tests/Views/Settings/DeviceSelectionSheetTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.settings.device.support.OriginalCase
import com.meshcoreone.android.feature.settings.device.support.XmlStrings
import com.meshcoreone.android.feature.settings.device.support.device
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeviceSelectionTest {
    private fun wifi() = ConnectionMethod.WiFi("10.0.0.2", 5000.toUShort(), "Home")
    private fun ble(id: UUID = UUID.randomUUID()) = ConnectionMethod.Bluetooth(id, "Radio")

    @Test @OriginalCase("DeviceSelectionFilterTests::WiFi-capable device is shown regardless of ASK registration()")
    fun `wifi device is shown regardless of registration`() {
        assertTrue(DeviceSelectionFilter.isConnectable(device(methods = listOf(wifi())), emptySet()))
    }

    @Test @OriginalCase("DeviceSelectionFilterTests::BLE device registered with AccessorySetupKit is shown()")
    fun `ble device registered with the system is shown`() {
        val id = UUID.randomUUID()
        assertTrue(DeviceSelectionFilter.isConnectable(device(id = id, methods = listOf(ble(id))), setOf(id)))
    }

    @Test @OriginalCase("DeviceSelectionFilterTests::BLE device missing from ASK is hidden (imported shadow)()")
    fun `ble device missing from the registry is hidden`() {
        assertFalse(DeviceSelectionFilter.isConnectable(device(methods = listOf(ble())), setOf(UUID.randomUUID())))
    }

    @Test @OriginalCase("DeviceSelectionFilterTests::Device with empty connection methods and no ASK entry is hidden()")
    fun `method-less device without registry entry is hidden`() {
        assertFalse(DeviceSelectionFilter.isConnectable(device(), emptySet()))
    }

    @Test @OriginalCase("DeviceSelectionFilterTests::Device with empty connection methods is shown when ASK knows its id()")
    fun `method-less device is shown when the registry knows its id`() {
        val id = UUID.randomUUID()
        assertTrue(DeviceSelectionFilter.isConnectable(device(id = id), setOf(id)))
    }

    @Test @OriginalCase("DeviceSelectionFilterTests::WiFi+BLE device is shown when its BLE id is missing from ASK()")
    fun `wifi plus ble device is shown when the ble id is unregistered`() {
        assertTrue(DeviceSelectionFilter.isConnectable(device(methods = listOf(wifi(), ble())), setOf(UUID.randomUUID())))
    }

    @Test @OriginalCase("DeviceSelectionFilterTests::macOS: BLE device with a stored bluetooth method is shown (no ASK registry)()", "platform-adaptation")
    fun `registry-less ble device with a stored method is shown`() {
        assertTrue(DeviceSelectionFilter.isConnectable(device(methods = listOf(ble())), emptySet(), hasSystemPairingRegistry = false))
    }

    @Test @OriginalCase("DeviceSelectionFilterTests::macOS: ghost/shadow with no bluetooth method is hidden()", "platform-adaptation")
    fun `registry-less ghost without a method is hidden`() {
        assertFalse(DeviceSelectionFilter.isConnectable(device(), emptySet(), hasSystemPairingRegistry = false))
    }

    @Test @OriginalCase("DeviceSelectionFilterTests::macOS: a method-less device is hidden even when its id is in the registry set()", "platform-adaptation")
    fun `registry-less method-less device stays hidden even if its id is in the set`() {
        val id = UUID.randomUUID()
        assertFalse(DeviceSelectionFilter.isConnectable(device(id = id), setOf(id), hasSystemPairingRegistry = false))
    }

    @Test @OriginalCase("DeviceSelectionFilterTests::saved radio and unsaved ASK accessory both appear()")
    fun `saved radio and unsaved accessory both appear`() {
        val savedId = UUID.randomUUID()
        val askId = UUID.randomUUID()
        val result = DeviceSelectionListBuilder.make(
            saved = listOf(device(id = savedId, methods = listOf(ble(savedId)))),
            accessories = listOf(savedId to "Radio", askId to "Stray"),
            needsSetup = listOf(SystemPairedAccessory(askId, "Stray")),
            hasSystemPairingRegistry = true,
        )
        assertEquals(listOf(savedId), result.connectable.map { it.id })
        assertEquals(listOf(askId), result.needsSetup.map { it.id })
        assertEquals(listOf("Stray"), result.needsSetup.map { it.name })
    }

    @Test @OriginalCase("DeviceSelectionFilterTests::macOS registry-less builder never reports needs-setup()", "platform-adaptation")
    fun `registry-less builder never reports needs-setup`() {
        val askId = UUID.randomUUID()
        val result = DeviceSelectionListBuilder.make(
            saved = emptyList(), accessories = listOf(askId to "Stray"),
            needsSetup = listOf(SystemPairedAccessory(askId, "Stray")), hasSystemPairingRegistry = false,
        )
        assertTrue(result.needsSetup.isEmpty())
    }

    @Test @OriginalCase("DeviceSelectionFilterTests::needs-setup row copy is Set Up VoiceOver and is not a Connect row()")
    fun `needs-setup row copy is Set Up and not a connect row`() {
        val strings = XmlStrings()
        val presentation = DeviceSelectionListBuilder.needsSetupPresentation(
            name = "Radio B",
            previouslyPairedLabel = strings.string(AppSettingsStrings.deviceSelectionPreviouslyPaired),
        ) { name, status ->
            strings.format(R.string.l10n_app_settings_deviceselection_accessibility_devicelabel, name, status)
        }
        assertEquals(UiText.Resource(AppSettingsStrings.deviceSelectionSetup), presentation.trailingTitle)
        assertEquals(UiText.Verbatim("Radio B, Previously Paired"), presentation.accessibilityLabel)
        assertEquals(UiText.Resource(AppSettingsStrings.deviceSelectionAccessibilitySetupHint), presentation.accessibilityHint)
        assertNotEquals(UiText.Resource(AppSettingsStrings.deviceSelectionAccessibilitySelectHint), presentation.accessibilityHint)
        assertNotEquals(UiText.Resource(AppSettingsStrings.deviceSelectionAccessibilityOutOfRangeHint), presentation.accessibilityHint)
    }

    @Test
    fun `needs-setup TalkBack label uses localized Android-neutral copy`() {
        val locales = listOf(null, "de", "es", "fr", "it", "ko", "nl", "pl", "pt", "ru", "uk", "b+zh+Hans")
        for (locale in locales) {
            val strings = XmlStrings(locale)
            val previouslyPaired = strings.string(AppSettingsStrings.deviceSelectionPreviouslyPaired)
            val presentation = DeviceSelectionListBuilder.needsSetupPresentation(
                name = "Radio B",
                previouslyPairedLabel = previouslyPaired,
            ) { name, status ->
                strings.format(R.string.l10n_app_settings_deviceselection_accessibility_devicelabel, name, status)
            }
            val label = (presentation.accessibilityLabel as UiText.Verbatim).value
            assertEquals(
                strings.format(R.string.l10n_app_settings_deviceselection_accessibility_devicelabel, "Radio B", previouslyPaired),
                label,
                "Locale $locale",
            )
            assertFalse(label.contains("iPhone", ignoreCase = true), "Locale $locale")
        }
    }
}
