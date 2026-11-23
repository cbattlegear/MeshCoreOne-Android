// PortedFrom: MC1Services/Sources/MC1Services/Services/AppStorageKey.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/SceneStorageKey.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Models/DevicePreferenceStore.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.datastore

import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID

sealed interface PreferenceValue {
    data class BooleanValue(val value: Boolean) : PreferenceValue
    data class StringValue(val value: String) : PreferenceValue
    data class IntegerValue(val value: Long) : PreferenceValue
    data class DecimalValue(val value: Double) : PreferenceValue
    data class BinaryValue(val value: Bytes) : PreferenceValue
    data class StringListValue(val value: SnapshotList<String>) : PreferenceValue
}

sealed class PreferenceKey<T : Any>(val rawValue: String, val defaultValue: T?) {
    abstract fun decode(value: PreferenceValue): T
    abstract fun encode(value: T): PreferenceValue

    protected fun wrongType(): Nothing =
        throw StorageFailure(StorageProblem.PreferenceTypeMismatch(rawValue), StorageOperation.READ)

    class BooleanKey(rawValue: String, defaultValue: Boolean?) : PreferenceKey<Boolean>(rawValue, defaultValue) {
        override fun decode(value: PreferenceValue): Boolean =
            (value as? PreferenceValue.BooleanValue)?.value ?: wrongType()
        override fun encode(value: Boolean): PreferenceValue = PreferenceValue.BooleanValue(value)
    }

    class StringKey(rawValue: String, defaultValue: String?) : PreferenceKey<String>(rawValue, defaultValue) {
        override fun decode(value: PreferenceValue): String =
            (value as? PreferenceValue.StringValue)?.value ?: wrongType()
        override fun encode(value: String): PreferenceValue = PreferenceValue.StringValue(value)
    }

    class IntegerKey(rawValue: String, defaultValue: Long) : PreferenceKey<Long>(rawValue, defaultValue) {
        override fun decode(value: PreferenceValue): Long =
            (value as? PreferenceValue.IntegerValue)?.value ?: wrongType()
        override fun encode(value: Long): PreferenceValue = PreferenceValue.IntegerValue(value)
    }

    class DecimalKey(rawValue: String, defaultValue: Double) : PreferenceKey<Double>(rawValue, defaultValue) {
        override fun decode(value: PreferenceValue): Double =
            (value as? PreferenceValue.DecimalValue)?.value ?: wrongType()
        override fun encode(value: Double): PreferenceValue = PreferenceValue.DecimalValue(value)
    }

    class BinaryKey(rawValue: String, defaultValue: Bytes?) : PreferenceKey<Bytes>(rawValue, defaultValue) {
        override fun decode(value: PreferenceValue): Bytes =
            (value as? PreferenceValue.BinaryValue)?.value ?: wrongType()
        override fun encode(value: Bytes): PreferenceValue = PreferenceValue.BinaryValue(value)
    }

    class StringListKey(rawValue: String, defaultValue: SnapshotList<String>) :
        PreferenceKey<SnapshotList<String>>(rawValue, defaultValue) {
        override fun decode(value: PreferenceValue): SnapshotList<String> =
            (value as? PreferenceValue.StringListValue)?.value ?: wrongType()
        override fun encode(value: SnapshotList<String>): PreferenceValue = PreferenceValue.StringListValue(value)
    }
}

object AppStorageKey {
    val hasCompletedOnboarding = PreferenceKey.BooleanKey("hasCompletedOnboarding", false)
    val liveActivityEnabled = PreferenceKey.BooleanKey("liveActivityEnabled", true)
    val showIncomingPath = PreferenceKey.BooleanKey("showIncomingPath", false)
    val showIncomingHopCount = PreferenceKey.BooleanKey("showIncomingHopCount", false)
    val showIncomingRegion = PreferenceKey.BooleanKey("showIncomingRegion", false)
    val showIncomingHeardCount = PreferenceKey.BooleanKey("showIncomingHeardCount", false)
    val showIncomingSendTime = PreferenceKey.BooleanKey("showIncomingSendTime", false)
    val linkPreviewsEnabled = PreferenceKey.BooleanKey("linkPreviewsEnabled", false)
    val linkPreviewsAutoResolveDM = PreferenceKey.BooleanKey("linkPreviewsAutoResolveDM", true)
    val linkPreviewsAutoResolveChannels = PreferenceKey.BooleanKey("linkPreviewsAutoResolveChannels", true)
    val showInlineImages = PreferenceKey.BooleanKey("showInlineImages", null)
    val autoPlayGIFs = PreferenceKey.BooleanKey("autoPlayGIFs", true)
    val replyWithQuote = PreferenceKey.BooleanKey("replyWithQuote", false)
    val remoteFloodFallback = PreferenceKey.BooleanKey("remoteFloodFallback", true)
    val showMapPreviewThumbnails = PreferenceKey.BooleanKey("showMapPreviewThumbnails", true)
    val nodesSortOrder = PreferenceKey.StringKey("nodesSortOrder", "lastHeard")
    val discoverySortOrder = PreferenceKey.StringKey("discoverySortOrder", "lastHeard")
    val tracePathViewMode = PreferenceKey.StringKey("tracePathViewMode", "list")
    val mapStyleSelection = PreferenceKey.StringKey("mapStyleSelection", "standard")
    val mapShowLabels = PreferenceKey.BooleanKey("mapShowLabels", true)
    val mapClusteringEnabled = PreferenceKey.BooleanKey("mapClusteringEnabled", true)
    val mapNorthLocked = PreferenceKey.BooleanKey("mapNorthLocked", false)
    val showDiscoveredNodesOnMap = PreferenceKey.BooleanKey("showDiscoveredNodesOnMap", false)
    val mapFilterMainMap = PreferenceKey.StringKey("mapFilterMainMap", "")
    val mapFilterTracePath = PreferenceKey.StringKey("mapFilterTracePath", "")
    val mapFilterNeighborSNR = PreferenceKey.StringKey("mapFilterNeighborSNR", "")
    val mapColorSchemePreference = PreferenceKey.StringKey("mapColorSchemePreference", "system")
    val hasSeenRepeaterDragHint = PreferenceKey.BooleanKey("hasSeenRepeaterDragHint", false)
    val translationTargetLanguage = PreferenceKey.StringKey("translationTargetLanguage", "app")
    val useDefaultTranslationApp = PreferenceKey.BooleanKey("useDefaultTranslationApp", false)
    val translationOffersEnabled = PreferenceKey.BooleanKey("translationOffersEnabled", true)
    val autoDeleteStaleNodesDays = PreferenceKey.IntegerKey("autoDeleteStaleNodesDays", 0)
    val lastStaleCleanupDate = PreferenceKey.DecimalKey("lastStaleCleanupDate", 0.0)
    val frequentEmojis = PreferenceKey.BinaryKey("frequentEmojis", Bytes.EMPTY)
    val recentReactionEmojis = PreferenceKey.StringListKey("recentReactionEmojis", SnapshotList.empty())
    val isDemoModeUnlocked = PreferenceKey.BooleanKey("isDemoModeUnlocked", false)
    val isDemoModeEnabled = PreferenceKey.BooleanKey("isDemoModeEnabled", false)
    val lastShownWhatsNewVersion = PreferenceKey.StringKey("lastShownWhatsNewVersion", null)
    val notifyContactMessages = PreferenceKey.BooleanKey("notifyContactMessages", true)
    val notifyChannelMessages = PreferenceKey.BooleanKey("notifyChannelMessages", true)
    val notifyRoomMessages = PreferenceKey.BooleanKey("notifyRoomMessages", true)
    val notifyNewContacts = PreferenceKey.BooleanKey("notifyNewContacts", true)
    val notifyNewContactsContact = PreferenceKey.BooleanKey("notifyNewContactsContact", true)
    val notifyNewContactsRepeater = PreferenceKey.BooleanKey("notifyNewContactsRepeater", true)
    val notifyNewContactsRoom = PreferenceKey.BooleanKey("notifyNewContactsRoom", true)
    val notifyReactions = PreferenceKey.BooleanKey("notifyReactions", true)
    val notificationSoundEnabled = PreferenceKey.BooleanKey("notificationSoundEnabled", true)
    val notificationBadgeEnabled = PreferenceKey.BooleanKey("notificationBadgeEnabled", true)
    val notifyLowBattery = PreferenceKey.BooleanKey("notifyLowBattery", true)

    val all: SnapshotList<PreferenceKey<*>> = listOf(
        hasCompletedOnboarding, liveActivityEnabled, showIncomingPath, showIncomingHopCount,
        showIncomingRegion, showIncomingHeardCount, showIncomingSendTime, linkPreviewsEnabled,
        linkPreviewsAutoResolveDM, linkPreviewsAutoResolveChannels, showInlineImages, autoPlayGIFs,
        replyWithQuote, showMapPreviewThumbnails, nodesSortOrder, discoverySortOrder, tracePathViewMode,
        mapStyleSelection, mapShowLabels, mapClusteringEnabled, mapNorthLocked, showDiscoveredNodesOnMap,
        mapFilterMainMap, mapFilterTracePath, mapFilterNeighborSNR, mapColorSchemePreference,
        hasSeenRepeaterDragHint, translationTargetLanguage, useDefaultTranslationApp, translationOffersEnabled,
        autoDeleteStaleNodesDays, lastStaleCleanupDate, frequentEmojis, recentReactionEmojis,
        isDemoModeUnlocked, isDemoModeEnabled, lastShownWhatsNewVersion, notifyContactMessages,
        notifyChannelMessages, notifyRoomMessages, notifyNewContacts, notifyNewContactsContact,
        notifyNewContactsRepeater, notifyNewContactsRoom, notifyReactions, notificationSoundEnabled,
        notificationBadgeEnabled, notifyLowBattery, remoteFloodFallback,
    ).snapshot()
}

object AppearanceStorageKey {
    val selectedThemeID = PreferenceKey.StringKey(PersistenceKeys.SELECTED_THEME_ID, "default")
    val appColorSchemePreference = PreferenceKey.StringKey(PersistenceKeys.APP_COLOR_SCHEME_PREFERENCE, "system")
}

enum class SceneStorageKey(val rawValue: String) {
    MAP_CAMERA_REGION("mapCameraRegion");

    fun scoped(sceneId: UUID): PreferenceKey.StringKey =
        PreferenceKey.StringKey("scene.${sceneId.canonicalString()}.$rawValue", "")
}

enum class GPSSource(val rawValue: String) { PHONE("phone"), DEVICE("device") }

internal fun autoUpdateLocationKey(deviceId: UUID) =
    PreferenceKey.BooleanKey("device.${deviceId.canonicalString()}.autoUpdateLocation", false)

internal fun gpsSourceKey(deviceId: UUID) =
    PreferenceKey.StringKey("device.${deviceId.canonicalString()}.gpsSource", GPSSource.PHONE.rawValue)
