// PortedFrom: MC1Services/Tests/MC1ServicesTests/BackupIntegrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/BackupUserDefaultsCoverageTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.datastore.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.lang.reflect.Modifier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BackupPreferenceTest : BackupRoomTest() {
    @OriginalCase("BackupIntegrationTests::regionSelection round-trips through encode/decode()")
    @Test fun completeRegionWireRoundTrip() {
        val expected = BackupUserDefaults(regionSelection = RegionSelection("US", RegionSelection.Source.LOCATION, "US-CA", "los angeles"))
        assertEquals(expected.regionSelection, BackupUserDefaults.decode(expected.encode()).regionSelection)
    }

    @OriginalCase("BackupIntegrationTests::Legacy envelope without regionSelection decodes as nil()")
    @Test fun legacyRegionIsOmittedNotDefaulted() {
        val actual = legacy()
        assertNull(actual.regionSelection); assertEquals(true, actual.hasCompletedOnboarding)
    }

    @OriginalCase("BackupIntegrationTests::restore writes regionSelection only when local key is missing()")
    @Test fun existingRegionPresenceWinsEvenAgainstDifferentCountry() = runBlocking {
        val local = RegionSelection("DE", RegionSelection.Source.MANUAL)
        storage.preferences.set(BackupPreferenceKeys.regionSelection, Bytes.utf8(encodeRegion(local).toString()))
        val added = BackupUserDefaults(regionSelection = RegionSelection("US", RegionSelection.Source.LOCATION)).restore(storage.backupPreferences)
        assertFalse(added.contains(BackupPreferenceKeys.regionSelection.rawValue))
        assertEquals(local, BackupUserDefaults.snapshot(storage.backupPreferences).regionSelection)
    }

    @OriginalCase("BackupIntegrationTests::restore writes regionSelection when local is missing (fresh install)()")
    @Test fun missingRegionIsWrittenOnceWithFullSourceShape() = runBlocking {
        val expected = RegionSelection("US", RegionSelection.Source.LOCATION)
        val added = BackupUserDefaults(regionSelection = expected).restore(storage.backupPreferences)
        assertTrue(added.contains(BackupPreferenceKeys.regionSelection.rawValue))
        assertEquals(expected, BackupUserDefaults.snapshot(storage.backupPreferences).regionSelection)
    }

    @OriginalCase("BackupIntegrationTests::translationTargetLanguage round-trips through encode/decode()")
    @Test fun languageSubtagWireRoundTrip() { assertEquals("da", BackupUserDefaults.decode(BackupUserDefaults(translationTargetLanguage = "da").encode()).translationTargetLanguage) }

    @OriginalCase("BackupIntegrationTests::Legacy envelope without translationTargetLanguage decodes as nil()")
    @Test fun legacyLanguageHasNoSyntheticDefault() { assertNull(legacy().translationTargetLanguage) }

    @OriginalCase("BackupIntegrationTests::useDefaultTranslationApp round-trips through encode/decode()")
    @Test fun translationOverlayWireRoundTrip() { assertEquals(true, BackupUserDefaults.decode(BackupUserDefaults(useDefaultTranslationApp = true).encode()).useDefaultTranslationApp) }

    @OriginalCase("BackupIntegrationTests::Legacy envelope without useDefaultTranslationApp decodes as nil()")
    @Test fun legacyOverlayHasNoSyntheticFalse() { assertNull(legacy().useDefaultTranslationApp) }

    @OriginalCase("BackupIntegrationTests::overlay backup keeps the language subtag alongside useDefaultTranslationApp()")
    @Test fun overlayAndLanguageStayIndependentThroughDataStore() = runBlocking {
        val expected = BackupUserDefaults(translationTargetLanguage = "da", useDefaultTranslationApp = true)
        val actual = BackupUserDefaults.decode(expected.encode())
        assertEquals("da", actual.translationTargetLanguage); assertEquals(true, actual.useDefaultTranslationApp)
        val keys = actual.restore(storage.backupPreferences)
        assertTrue(keys.contains("translationTargetLanguage")); assertTrue(keys.contains("useDefaultTranslationApp"))
        assertEquals("da", storage.preferences.get(AppStorageKey.translationTargetLanguage))
        assertTrue(storage.preferences.get(AppStorageKey.useDefaultTranslationApp))
    }

    @OriginalCase("BackupIntegrationTests::selectedThemeID round-trips through encode/decode()")
    @Test fun unlockedThemeWireValueIsOpaque() { assertEquals("ember", BackupUserDefaults.decode(BackupUserDefaults(selectedThemeID = "ember").encode()).selectedThemeID) }

    @OriginalCase("BackupIntegrationTests::Legacy envelope without selectedThemeID decodes as nil()")
    @Test fun omittedThemeRemainsNil() {
        val actual = legacy()
        assertNull(actual.selectedThemeID); assertEquals(true, actual.hasCompletedOnboarding)
    }

    @OriginalCase("BackupIntegrationTests::restore writes selectedThemeID only when local key is missing()")
    @Test fun existingThemeIsNeverReplaced() = runBlocking {
        storage.preferences.set(AppearanceStorageKey.selectedThemeID, "marine")
        assertFalse(BackupUserDefaults(selectedThemeID = "ember").restore(storage.backupPreferences).contains("selectedThemeID"))
        assertEquals("marine", storage.preferences.get(AppearanceStorageKey.selectedThemeID))
    }

    @OriginalCase("BackupIntegrationTests::restore writes selectedThemeID when local is missing (fresh install)()")
    @Test fun absentThemeIsRestoredExactly() = runBlocking {
        assertTrue(BackupUserDefaults(selectedThemeID = "ember").restore(storage.backupPreferences).contains("selectedThemeID"))
        assertEquals("ember", storage.preferences.get(AppearanceStorageKey.selectedThemeID))
    }

    @OriginalCase("BackupIntegrationTests::Legacy envelope without appColorSchemePreference decodes as nil()")
    @Test fun omittedAppSchemeRemainsNil() { assertNull(legacy().appColorSchemePreference) }

    @OriginalCase("BackupIntegrationTests::restore writes appColorSchemePreference only when local key is missing()")
    @Test fun existingAppSchemeIsNeverReplaced() = runBlocking {
        storage.preferences.set(AppearanceStorageKey.appColorSchemePreference, "light")
        assertFalse(BackupUserDefaults(appColorSchemePreference = "dark").restore(storage.backupPreferences).contains("appColorSchemePreference"))
        assertEquals("light", storage.preferences.get(AppearanceStorageKey.appColorSchemePreference))
    }

    @OriginalCase("BackupIntegrationTests::restore writes appColorSchemePreference when local is missing (fresh install)()")
    @Test fun missingAppSchemeIsRestored() = runBlocking {
        assertTrue(BackupUserDefaults(appColorSchemePreference = "dark").restore(storage.backupPreferences).contains("appColorSchemePreference"))
        assertEquals("dark", storage.preferences.get(AppearanceStorageKey.appColorSchemePreference))
    }

    @OriginalCase("BackupIntegrationTests::snapshot reads selectedThemeID and appColorSchemePreference from UserDefaults()")
    @Test fun snapshotRetainsOnlyPresentAppearanceValues() = runBlocking {
        storage.preferences.set(AppearanceStorageKey.selectedThemeID, "marine")
        storage.preferences.set(AppearanceStorageKey.appColorSchemePreference, "dark")
        val snapshot = BackupUserDefaults.snapshot(storage.backupPreferences)
        assertEquals("marine", snapshot.selectedThemeID); assertEquals("dark", snapshot.appColorSchemePreference)
    }

    @OriginalCase("BackupIntegrationTests::snapshot leaves appearance keys nil when unset()")
    @Test fun snapshotDoesNotSynthesizeAppearanceDefaults() = runBlocking {
        val snapshot = BackupUserDefaults.snapshot(storage.backupPreferences)
        assertNull(snapshot.selectedThemeID); assertNull(snapshot.appColorSchemePreference)
        assertFalse(snapshot.encode().containsKey("selectedThemeID")); assertFalse(snapshot.encode().containsKey("appColorSchemePreference"))
    }

    @OriginalCase("BackupIntegrationTests::Import reports userDefaultsRestored=false when no new keys were written()")
    @Test fun restoreOutcomeReflectsActualPreferenceWrites() = runBlocking {
        storage.preferences.set(AppStorageKey.hasCompletedOnboarding, true)
        val result = service.importBackup(envelope(devices = listOf(device()), preferences = BackupUserDefaults(hasCompletedOnboarding = true)), store)
        assertFalse(result.userDefaultsRestored)
        assertFalse(service.importBackup(envelope(preferences = BackupUserDefaults(hasCompletedOnboarding = true)), store).hasRestoredChanges)
    }

    @OriginalCase("BackupUserDefaultsCoverageTests::Every Bool? property has a boolMappings row()")
    @Test fun everyNullableBooleanHasExactlyOneNativeMapping() {
        val fields = instanceFields().filter { it.type == java.lang.Boolean::class.java }.map { it.name }.toSet()
        val keys = BackupUserDefaults.boolMappings.map { it.key.rawValue }
        assertEquals(33, fields.size)
        assertEquals(fields, keys.toSet())
        assertEquals(keys.size, keys.distinct().size)
        val populated = BackupUserDefaults.decode(wireObject { for (name in fields) put(name, name.length % 2 == 0) })
        for (mapping in BackupUserDefaults.boolMappings) assertEquals(mapping.key.rawValue.length % 2 == 0, mapping.get(populated))
    }

    @OriginalCase("BackupUserDefaultsCoverageTests::Every String? property has a stringMappings row()")
    @Test fun everyNullableStringHasExactlyOneNativeMapping() {
        val fields = instanceFields().filter { it.type == String::class.java }.map { it.name }.toSet()
        val keys = BackupUserDefaults.stringMappings.map { it.key.rawValue }
        assertEquals(11, fields.size)
        assertEquals(fields, keys.toSet())
        assertEquals(keys.size, keys.distinct().size)
        val populated = BackupUserDefaults.decode(wireObject { for (name in fields) put(name, "value-$name") })
        for (mapping in BackupUserDefaults.stringMappings) assertEquals("value-${mapping.key.rawValue}", mapping.get(populated))
    }

    @OriginalCase("BackupUserDefaultsCoverageTests::Every Optional property is covered by a mapping or special-cased()")
    @Test fun allStoredOptionalFieldsAndSpecialKeysAreAccountedFor() {
        val fields = instanceFields().map { it.name }.toSet()
        val covered = BackupUserDefaults.boolMappings.map { it.key.rawValue }.toSet() +
            BackupUserDefaults.stringMappings.map { it.key.rawValue }.toSet() + BackupUserDefaults.specialCasedPropertyNames
        assertEquals(48, fields.size); assertEquals(fields, covered)
        assertTrue(fields.containsAll(BackupUserDefaults.specialCasedPropertyNames))
        val value = BackupUserDefaults()
        for (field in instanceFields()) { field.isAccessible = true; assertNull(field.get(value)) }
        assertEquals(emptyMap<String, JsonElement>(), value.encode())
    }

    @Test fun remoteFloodFallbackIsOptionalAndRestoresExplicitFalseOnlyWhenMissing() = runBlocking {
        assertTrue(storage.preferences.get(AppStorageKey.remoteFloodFallback))
        assertNull(legacy().remoteFloodFallback)
        assertFalse(BackupUserDefaults.snapshot(storage.backupPreferences).encode().containsKey("remoteFloodFallback"))
        val fromWire = BackupUserDefaults.decode(backupJson.parseToJsonElement("""{"remoteFloodFallback":false}"""))
        assertEquals(listOf("remoteFloodFallback"), fromWire.restore(storage.backupPreferences))
        assertFalse(storage.preferences.get(AppStorageKey.remoteFloodFallback))
        assertEquals(JsonPrimitive(false), BackupUserDefaults.snapshot(storage.backupPreferences).encode()["remoteFloodFallback"])

        assertTrue(BackupUserDefaults(remoteFloodFallback = true).restore(storage.backupPreferences).isEmpty())
        assertFalse(storage.preferences.get(AppStorageKey.remoteFloodFallback))
        storage.preferences.set(AppStorageKey.remoteFloodFallback, true)
        assertEquals(JsonPrimitive(true), BackupUserDefaults.snapshot(storage.backupPreferences).encode()["remoteFloodFallback"])
    }

    @Test fun specialArraysPreserveOrderingPresenceAndExactStorageKeys() = runBlocking {
        val expected = BackupUserDefaults(autoDeleteStaleNodesDays = -30, frequentEmojis = SnapshotList.of("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDC4D"),
            recentEmojis = SnapshotList.of("\uD83D\uDE02", "\uD83D\uDE2E"))
        val keys = expected.restore(storage.backupPreferences)
        assertEquals(listOf("autoDeleteStaleNodesDays", "frequentEmojis", "recentReactionEmojis"), keys)
        assertEquals(expected, BackupUserDefaults.snapshot(storage.backupPreferences))
        assertEquals(expected.recentEmojis, storage.preferences.get(AppStorageKey.recentReactionEmojis))
        assertTrue(storage.preferences.get(AppStorageKey.frequentEmojis).toByteArray().toString(Charsets.UTF_8).startsWith("["))
    }

    private fun legacy(): BackupUserDefaults = BackupUserDefaults.decode(backupJson.parseToJsonElement(
        "{\"hasCompletedOnboarding\":true,\"mapStyleSelection\":\"topo\"}"))
    private fun instanceFields() = BackupUserDefaults::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
}
