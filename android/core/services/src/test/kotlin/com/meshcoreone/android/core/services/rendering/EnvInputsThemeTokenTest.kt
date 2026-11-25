// PortedFrom: MC1Services/Tests/MC1ServicesTests/EnvInputsThemeTokenTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Adaptation: Swift `Locale(identifier:)` BCP-47 identifiers become `Locale.forLanguageTag`. */
class EnvInputsThemeTokenTest {
    private fun make(themeID: String = EnvInputs.DEFAULT_THEME_ID, preferredLanguageCode: String = EnvInputs.DEFAULT_PREFERRED_LANGUAGE_CODE) =
        EnvInputs(
            autoPlayGIFs = true,
            showIncomingPath = true,
            showIncomingHopCount = true,
            showIncomingRegion = true,
            showIncomingHeardCount = true,
            showIncomingSendTime = true,
            previewsEnabled = true,
            isHighContrast = false,
            isDark = false,
            showMapPreviews = true,
            isOffline = false,
            currentUserName = "Tester",
            themeID = themeID,
            contentSizeCategory = EnvInputs.DEFAULT_CONTENT_SIZE_CATEGORY,
            preferredLanguageCode = preferredLanguageCode,
        )

    @TestFactory
    fun envInputsThemeTokenTests(): List<DynamicTest> = listOf(
        case("changing only themeID makes EnvInputs unequal (drives cache invalidation)") {
            assertNotEquals(make(themeID = "default"), make(themeID = "ember"))
            assertEquals(make(themeID = "default"), make(themeID = "default"))
        },
        case("changing only preferredLanguageCode makes EnvInputs unequal") {
            assertNotEquals(make(preferredLanguageCode = "en"), make(preferredLanguageCode = "uk"))
            assertEquals(make(preferredLanguageCode = "en"), make(preferredLanguageCode = "en"))
        },
        case("EnvInputs.default carries the default theme id") {
            assertEquals("default", EnvInputs.DEFAULT.themeID)
        },
        case("preferredLanguageCode from locale collapses region and script") {
            assertEquals("en", EnvInputs.preferredLanguageCode(Locale.forLanguageTag("en-US")))
            assertEquals("zh", EnvInputs.preferredLanguageCode(Locale.forLanguageTag("zh-Hans")))
            assertEquals("de", EnvInputs.preferredLanguageCode(Locale.forLanguageTag("de-DE")))
        },
    )

    @TestFactory
    fun nativeEnvInputsCases(): List<DynamicTest> = listOf(
        DynamicTest.dynamicTest("WP-213::EnvInputs.DEFAULT restates the Swift AppStorageKey defaults") {
            val expected = EnvInputs(
                autoPlayGIFs = true, showIncomingPath = false, showIncomingHopCount = false, showIncomingRegion = false,
                showIncomingHeardCount = false, showIncomingSendTime = false, previewsEnabled = false, isHighContrast = false,
                isDark = false, showMapPreviews = true, isOffline = false, currentUserName = "", themeID = "default",
                contentSizeCategory = "large", preferredLanguageCode = "en", translationOffersEnabled = true,
            )
            assertEquals(expected, EnvInputs.DEFAULT)
        },
        DynamicTest.dynamicTest("WP-213::preferredLanguageCode matches Swift for scripts, legacy codes and the root locale") {
            assertEquals("zh", EnvInputs.preferredLanguageCode(Locale.forLanguageTag("zh-Hant-TW")))
            assertEquals("sr", EnvInputs.preferredLanguageCode(Locale.forLanguageTag("sr-Latn-RS")))
            assertEquals("he", EnvInputs.preferredLanguageCode(Locale.forLanguageTag("iw")))
            assertEquals("fil", EnvInputs.preferredLanguageCode(Locale.forLanguageTag("fil")))
            assertEquals("yue", EnvInputs.preferredLanguageCode(Locale.forLanguageTag("yue-Hant")))
            assertEquals("en", EnvInputs.preferredLanguageCode(Locale.ROOT))
        },
    )

    private fun case(name: String, body: () -> Unit) = DynamicTest.dynamicTest("EnvInputsThemeTokenTests::$name()", body)
}
