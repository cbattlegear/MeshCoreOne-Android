// PortedFrom: MC1Services/Sources/MC1Services/Services/BackupUserDefaults.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.datastore.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

data class BackupUserDefaults(
    val hasCompletedOnboarding: Boolean? = null,
    val liveActivityEnabled: Boolean? = null,
    val mapStyleSelection: String? = null,
    val selectedThemeID: String? = null,
    val appColorSchemePreference: String? = null,
    val mapShowLabels: Boolean? = null,
    val mapClusteringEnabled: Boolean? = null,
    val mapNorthLocked: Boolean? = null,
    val showDiscoveredNodesOnMap: Boolean? = null,
    val mapFilterMainMap: String? = null,
    val mapFilterTracePath: String? = null,
    val mapFilterNeighborSNR: String? = null,
    val mapColorSchemePreference: String? = null,
    val replyWithQuote: Boolean? = null,
    val showInlineImages: Boolean? = null,
    val autoPlayGIFs: Boolean? = null,
    val showIncomingPath: Boolean? = null,
    val showIncomingHopCount: Boolean? = null,
    val showIncomingRegion: Boolean? = null,
    val showIncomingHeardCount: Boolean? = null,
    val showIncomingSendTime: Boolean? = null,
    val autoDeleteStaleNodesDays: Long? = null,
    val discoverySortOrder: String? = null,
    val nodesSortOrder: String? = null,
    val tracePathViewMode: String? = null,
    val linkPreviewsEnabled: Boolean? = null,
    val linkPreviewsAutoResolveDM: Boolean? = null,
    val linkPreviewsAutoResolveChannels: Boolean? = null,
    val showMapPreviewThumbnails: Boolean? = null,
    val frequentEmojis: SnapshotList<String>? = null,
    val recentEmojis: SnapshotList<String>? = null,
    val hasSeenRepeaterDragHint: Boolean? = null,
    val translationTargetLanguage: String? = null,
    val useDefaultTranslationApp: Boolean? = null,
    val translationOffersEnabled: Boolean? = null,
    val regionSelection: RegionSelection? = null,
    val notifyContactMessages: Boolean? = null,
    val notifyChannelMessages: Boolean? = null,
    val notifyRoomMessages: Boolean? = null,
    val notifyNewContacts: Boolean? = null,
    val notifyNewContactsContact: Boolean? = null,
    val notifyNewContactsRepeater: Boolean? = null,
    val notifyNewContactsRoom: Boolean? = null,
    val notifyReactions: Boolean? = null,
    val notificationSoundEnabled: Boolean? = null,
    val notificationBadgeEnabled: Boolean? = null,
    val notifyLowBattery: Boolean? = null,
    val remoteFloodFallback: Boolean? = null,
) {
    internal fun encode(): JsonObject = wireObject {
        for (mapping in boolMappings) flag(mapping.key.rawValue, mapping.get(this@BackupUserDefaults))
        for (mapping in stringMappings) text(mapping.key.rawValue, mapping.get(this@BackupUserDefaults))
        integer("autoDeleteStaleNodesDays", autoDeleteStaleNodesDays)
        strings("frequentEmojis", frequentEmojis); strings("recentEmojis", recentEmojis)
        if (regionSelection != null) put("regionSelection", encodeRegion(regionSelection))
    }

    internal fun nativeSnapshot(): BackupPreferenceSnapshot {
        val values = linkedMapOf<String, PreferenceValue>()
        for (mapping in boolMappings) mapping.get(this)?.let { values[mapping.key.rawValue] = mapping.key.encode(it) }
        for (mapping in stringMappings) mapping.get(this)?.let { values[mapping.key.rawValue] = mapping.key.encode(it) }
        autoDeleteStaleNodesDays?.let { values[AppStorageKey.autoDeleteStaleNodesDays.rawValue] = AppStorageKey.autoDeleteStaleNodesDays.encode(it) }
        frequentEmojis?.let {
            values[AppStorageKey.frequentEmojis.rawValue] = PreferenceValue.BinaryValue(Bytes.utf8(JsonArray(it.map(::JsonPrimitive)).toString()))
        }
        recentEmojis?.let { values[AppStorageKey.recentReactionEmojis.rawValue] = PreferenceValue.StringListValue(it) }
        regionSelection?.let {
            values[BackupPreferenceKeys.regionSelection.rawValue] = PreferenceValue.BinaryValue(Bytes.utf8(encodeRegion(it).toString()))
        }
        return BackupPreferenceSnapshot(values)
    }

    suspend fun restore(to: BackupPreferenceStore): SnapshotList<String> = to.restoreMissing(nativeSnapshot())

    companion object {
        internal data class BoolMapping(val key: PreferenceKey.BooleanKey, val get: (BackupUserDefaults) -> Boolean?)
        internal data class StringMapping(val key: PreferenceKey.StringKey, val get: (BackupUserDefaults) -> String?)
        internal val boolMappings = listOf(
            BoolMapping(AppStorageKey.hasCompletedOnboarding) { it.hasCompletedOnboarding },
            BoolMapping(AppStorageKey.liveActivityEnabled) { it.liveActivityEnabled },
            BoolMapping(AppStorageKey.mapShowLabels) { it.mapShowLabels },
            BoolMapping(AppStorageKey.mapClusteringEnabled) { it.mapClusteringEnabled },
            BoolMapping(AppStorageKey.mapNorthLocked) { it.mapNorthLocked },
            BoolMapping(AppStorageKey.showDiscoveredNodesOnMap) { it.showDiscoveredNodesOnMap },
            BoolMapping(AppStorageKey.replyWithQuote) { it.replyWithQuote },
            BoolMapping(AppStorageKey.showInlineImages) { it.showInlineImages },
            BoolMapping(AppStorageKey.autoPlayGIFs) { it.autoPlayGIFs },
            BoolMapping(AppStorageKey.showIncomingPath) { it.showIncomingPath },
            BoolMapping(AppStorageKey.showIncomingHopCount) { it.showIncomingHopCount },
            BoolMapping(AppStorageKey.showIncomingRegion) { it.showIncomingRegion },
            BoolMapping(AppStorageKey.showIncomingHeardCount) { it.showIncomingHeardCount },
            BoolMapping(AppStorageKey.showIncomingSendTime) { it.showIncomingSendTime },
            BoolMapping(AppStorageKey.linkPreviewsEnabled) { it.linkPreviewsEnabled },
            BoolMapping(AppStorageKey.linkPreviewsAutoResolveDM) { it.linkPreviewsAutoResolveDM },
            BoolMapping(AppStorageKey.linkPreviewsAutoResolveChannels) { it.linkPreviewsAutoResolveChannels },
            BoolMapping(AppStorageKey.showMapPreviewThumbnails) { it.showMapPreviewThumbnails },
            BoolMapping(AppStorageKey.hasSeenRepeaterDragHint) { it.hasSeenRepeaterDragHint },
            BoolMapping(AppStorageKey.useDefaultTranslationApp) { it.useDefaultTranslationApp },
            BoolMapping(AppStorageKey.translationOffersEnabled) { it.translationOffersEnabled },
            BoolMapping(AppStorageKey.remoteFloodFallback) { it.remoteFloodFallback },
            BoolMapping(AppStorageKey.notifyContactMessages) { it.notifyContactMessages },
            BoolMapping(AppStorageKey.notifyChannelMessages) { it.notifyChannelMessages },
            BoolMapping(AppStorageKey.notifyRoomMessages) { it.notifyRoomMessages },
            BoolMapping(AppStorageKey.notifyNewContacts) { it.notifyNewContacts },
            BoolMapping(AppStorageKey.notifyNewContactsContact) { it.notifyNewContactsContact },
            BoolMapping(AppStorageKey.notifyNewContactsRepeater) { it.notifyNewContactsRepeater },
            BoolMapping(AppStorageKey.notifyNewContactsRoom) { it.notifyNewContactsRoom },
            BoolMapping(AppStorageKey.notifyReactions) { it.notifyReactions },
            BoolMapping(AppStorageKey.notificationSoundEnabled) { it.notificationSoundEnabled },
            BoolMapping(AppStorageKey.notificationBadgeEnabled) { it.notificationBadgeEnabled },
            BoolMapping(AppStorageKey.notifyLowBattery) { it.notifyLowBattery },
        )
        internal val stringMappings = listOf(
            StringMapping(AppStorageKey.mapStyleSelection) { it.mapStyleSelection },
            StringMapping(AppStorageKey.mapColorSchemePreference) { it.mapColorSchemePreference },
            StringMapping(AppStorageKey.mapFilterMainMap) { it.mapFilterMainMap },
            StringMapping(AppStorageKey.mapFilterTracePath) { it.mapFilterTracePath },
            StringMapping(AppStorageKey.mapFilterNeighborSNR) { it.mapFilterNeighborSNR },
            StringMapping(AppStorageKey.discoverySortOrder) { it.discoverySortOrder },
            StringMapping(AppStorageKey.nodesSortOrder) { it.nodesSortOrder },
            StringMapping(AppStorageKey.tracePathViewMode) { it.tracePathViewMode },
            StringMapping(AppStorageKey.translationTargetLanguage) { it.translationTargetLanguage },
            StringMapping(AppearanceStorageKey.selectedThemeID) { it.selectedThemeID },
            StringMapping(AppearanceStorageKey.appColorSchemePreference) { it.appColorSchemePreference },
        )
        internal val specialCasedPropertyNames = setOf("autoDeleteStaleNodesDays", "frequentEmojis", "recentEmojis", "regionSelection")

        internal fun decode(value: JsonElement): BackupUserDefaults = value.row("userDefaults").run {
            BackupUserDefaults(
                hasCompletedOnboarding = optionalBoolean("hasCompletedOnboarding"), liveActivityEnabled = optionalBoolean("liveActivityEnabled"),
                mapStyleSelection = optionalString("mapStyleSelection"), selectedThemeID = optionalString("selectedThemeID"),
                appColorSchemePreference = optionalString("appColorSchemePreference"), mapShowLabels = optionalBoolean("mapShowLabels"),
                mapClusteringEnabled = optionalBoolean("mapClusteringEnabled"), mapNorthLocked = optionalBoolean("mapNorthLocked"),
                showDiscoveredNodesOnMap = optionalBoolean("showDiscoveredNodesOnMap"), mapFilterMainMap = optionalString("mapFilterMainMap"),
                mapFilterTracePath = optionalString("mapFilterTracePath"), mapFilterNeighborSNR = optionalString("mapFilterNeighborSNR"),
                mapColorSchemePreference = optionalString("mapColorSchemePreference"), replyWithQuote = optionalBoolean("replyWithQuote"),
                showInlineImages = optionalBoolean("showInlineImages"), autoPlayGIFs = optionalBoolean("autoPlayGIFs"),
                showIncomingPath = optionalBoolean("showIncomingPath"), showIncomingHopCount = optionalBoolean("showIncomingHopCount"),
                showIncomingRegion = optionalBoolean("showIncomingRegion"), showIncomingHeardCount = optionalBoolean("showIncomingHeardCount"),
                showIncomingSendTime = optionalBoolean("showIncomingSendTime"), autoDeleteStaleNodesDays = optionalLong("autoDeleteStaleNodesDays"),
                discoverySortOrder = optionalString("discoverySortOrder"), nodesSortOrder = optionalString("nodesSortOrder"),
                tracePathViewMode = optionalString("tracePathViewMode"), linkPreviewsEnabled = optionalBoolean("linkPreviewsEnabled"),
                linkPreviewsAutoResolveDM = optionalBoolean("linkPreviewsAutoResolveDM"),
                linkPreviewsAutoResolveChannels = optionalBoolean("linkPreviewsAutoResolveChannels"),
                showMapPreviewThumbnails = optionalBoolean("showMapPreviewThumbnails"), frequentEmojis = optionalStrings("frequentEmojis"),
                recentEmojis = optionalStrings("recentEmojis"), hasSeenRepeaterDragHint = optionalBoolean("hasSeenRepeaterDragHint"),
                translationTargetLanguage = optionalString("translationTargetLanguage"),
                useDefaultTranslationApp = optionalBoolean("useDefaultTranslationApp"), translationOffersEnabled = optionalBoolean("translationOffersEnabled"),
                regionSelection = optional("regionSelection")?.let(::decodeRegion), notifyContactMessages = optionalBoolean("notifyContactMessages"),
                notifyChannelMessages = optionalBoolean("notifyChannelMessages"), notifyRoomMessages = optionalBoolean("notifyRoomMessages"),
                notifyNewContacts = optionalBoolean("notifyNewContacts"), notifyNewContactsContact = optionalBoolean("notifyNewContactsContact"),
                notifyNewContactsRepeater = optionalBoolean("notifyNewContactsRepeater"), notifyNewContactsRoom = optionalBoolean("notifyNewContactsRoom"),
                notifyReactions = optionalBoolean("notifyReactions"), notificationSoundEnabled = optionalBoolean("notificationSoundEnabled"),
                notificationBadgeEnabled = optionalBoolean("notificationBadgeEnabled"), notifyLowBattery = optionalBoolean("notifyLowBattery"),
                remoteFloodFallback = optionalBoolean("remoteFloodFallback"),
            )
        }

        suspend fun snapshot(from: BackupPreferenceStore): BackupUserDefaults {
            val values = from.snapshotForBackup().presentValues
            val objectValue = wireObject {
                for (mapping in boolMappings) values[mapping.key.rawValue]?.let { put(mapping.key.rawValue, mapping.key.decode(it)) }
                for (mapping in stringMappings) values[mapping.key.rawValue]?.let { put(mapping.key.rawValue, mapping.key.decode(it)) }
                values[AppStorageKey.autoDeleteStaleNodesDays.rawValue]?.let {
                    put("autoDeleteStaleNodesDays", AppStorageKey.autoDeleteStaleNodesDays.decode(it))
                }
                values[AppStorageKey.frequentEmojis.rawValue]?.let {
                    put("frequentEmojis", storedJson(AppStorageKey.frequentEmojis.decode(it), "frequentEmojis"))
                }
                values[AppStorageKey.recentReactionEmojis.rawValue]?.let {
                    strings("recentEmojis", AppStorageKey.recentReactionEmojis.decode(it))
                }
                values[BackupPreferenceKeys.regionSelection.rawValue]?.let {
                    put("regionSelection", storedJson(BackupPreferenceKeys.regionSelection.decode(it), "regionSelection"))
                }
            }
            return try { decode(objectValue) } catch (cause: BackupValueException) {
                throw StorageFailure(StorageProblem.CorruptPreferences, StorageOperation.EXPORT, cause)
            }
        }

        private fun storedJson(value: Bytes, field: String): JsonElement = try {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            backupJson.parseToJsonElement(decoder.decode(ByteBuffer.wrap(value.toByteArray())).toString())
        } catch (cause: SerializationException) {
            throw StorageFailure(StorageProblem.CorruptPreferences, StorageOperation.EXPORT, cause)
        } catch (cause: CharacterCodingException) {
            throw StorageFailure(StorageProblem.CorruptPreferences, StorageOperation.EXPORT, cause)
        }
    }
}

internal fun decodeRegion(value: JsonElement): RegionSelection = value.row("regionSelection").run {
    val source = string("source")
    RegionSelection(string("countryCode"),
        RegionSelection.Source.entries.firstOrNull { it.rawValue == source }
            ?: invalidValue("regionSelection.source", BackupValueProblem.ENUM),
        optionalString("administrativeAreaCode"), optionalString("countyKey"))
}
internal fun encodeRegion(value: RegionSelection): JsonObject = wireObject {
    put("countryCode", value.countryCode); text("administrativeAreaCode", value.administrativeAreaCode)
    text("countyKey", value.countyKey); put("source", value.source.rawValue)
}
