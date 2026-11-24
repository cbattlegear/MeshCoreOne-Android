// PortedFrom: MC1Services/Sources/MC1Services/Services/AppStorageKey.swift@db14559b39d32322b06477c6ae676112f583db50
// Raw presence/write-if-missing seam; WP-203 owns Codable arrays, region JSON and full backup restore.
package com.meshcoreone.android.core.datastore

import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotMap
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.snapshotMap
import com.meshcoreone.android.core.protocol.bytes.Bytes

object BackupPreferenceKeys {
    val regionSelection = PreferenceKey.BinaryKey("userPrefs.region", null)

    val all: SnapshotList<PreferenceKey<*>> = listOf(
        AppStorageKey.hasCompletedOnboarding, AppStorageKey.liveActivityEnabled,
        AppStorageKey.mapShowLabels, AppStorageKey.mapClusteringEnabled, AppStorageKey.mapNorthLocked,
        AppStorageKey.showDiscoveredNodesOnMap, AppStorageKey.replyWithQuote, AppStorageKey.showInlineImages,
        AppStorageKey.autoPlayGIFs, AppStorageKey.showIncomingPath, AppStorageKey.showIncomingHopCount,
        AppStorageKey.showIncomingRegion, AppStorageKey.showIncomingHeardCount, AppStorageKey.showIncomingSendTime,
        AppStorageKey.linkPreviewsEnabled, AppStorageKey.linkPreviewsAutoResolveDM,
        AppStorageKey.linkPreviewsAutoResolveChannels, AppStorageKey.showMapPreviewThumbnails,
        AppStorageKey.hasSeenRepeaterDragHint, AppStorageKey.useDefaultTranslationApp,
        AppStorageKey.translationOffersEnabled, AppStorageKey.remoteFloodFallback,
        AppStorageKey.notifyContactMessages, AppStorageKey.notifyChannelMessages, AppStorageKey.notifyRoomMessages,
        AppStorageKey.notifyNewContacts, AppStorageKey.notifyNewContactsContact,
        AppStorageKey.notifyNewContactsRepeater, AppStorageKey.notifyNewContactsRoom,
        AppStorageKey.notifyReactions, AppStorageKey.notificationSoundEnabled,
        AppStorageKey.notificationBadgeEnabled, AppStorageKey.notifyLowBattery,
        AppStorageKey.mapStyleSelection, AppStorageKey.mapColorSchemePreference, AppStorageKey.mapFilterMainMap,
        AppStorageKey.mapFilterTracePath, AppStorageKey.mapFilterNeighborSNR, AppStorageKey.discoverySortOrder,
        AppStorageKey.nodesSortOrder, AppStorageKey.tracePathViewMode, AppStorageKey.translationTargetLanguage,
        AppearanceStorageKey.selectedThemeID,
        AppearanceStorageKey.appColorSchemePreference, AppStorageKey.autoDeleteStaleNodesDays,
        AppStorageKey.frequentEmojis, AppStorageKey.recentReactionEmojis, regionSelection,
    ).snapshot()
}

class BackupPreferenceSnapshot(values: Map<String, PreferenceValue>) {
    val presentValues: SnapshotMap<String, PreferenceValue> = values.snapshotMap()

    init {
        for ((key, value) in presentValues) {
            val definition = BackupPreferenceKeys.all.firstOrNull { it.rawValue == key }
                ?: throw StorageFailure(StorageProblem.InvalidPreference(key), StorageOperation.WRITE)
            definition.decode(value)
        }
    }
}

class BackupPreferenceStore(private val store: PreferenceStore) {
    suspend fun snapshotForBackup(): BackupPreferenceSnapshot {
        val current = store.snapshot()
        return BackupPreferenceSnapshot(
            BackupPreferenceKeys.all.mapNotNull { key ->
                current.storedValues[key.rawValue]?.let { key.rawValue to it }
            }.toMap(),
        )
    }

    suspend fun restoreMissing(snapshot: BackupPreferenceSnapshot): SnapshotList<String> {
        val inserted = mutableListOf<String>()
        store.update {
            for (key in BackupPreferenceKeys.all) {
                val value = snapshot.presentValues[key.rawValue] ?: continue
                if (!this.snapshot.contains(key)) {
                    putRaw(key.rawValue, value)
                    inserted += key.rawValue
                }
            }
        }
        return inserted.snapshot()
    }
}
