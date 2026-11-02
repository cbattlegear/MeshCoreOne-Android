// AndroidOnly: WP-204 Real file-backed DataStore atomicity, observation, scope and corruption assertions.
package com.meshcoreone.android.core.datastore

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.FileStorage
import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.Preferences
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PreferenceBoundaryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun cleanFirstUseDoesNotPersistDefaults() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            val snapshot = h.owner.preferences.snapshot()
            assertTrue(snapshot.storedValues.isEmpty())
            assertEquals(false, snapshot[AppStorageKey.hasCompletedOnboarding])
            assertEquals(true, snapshot[AppStorageKey.liveActivityEnabled])
            assertEquals("app", snapshot[AppStorageKey.translationTargetLanguage])
            assertEquals("system", snapshot[AppStorageKey.mapColorSchemePreference])
            assertEquals(0L, snapshot[AppStorageKey.autoDeleteStaleNodesDays])
            assertEquals(0.0, snapshot[AppStorageKey.lastStaleCleanupDate])
            assertEquals("lastHeard", snapshot[AppStorageKey.nodesSortOrder])
            assertEquals("standard", snapshot[AppStorageKey.mapStyleSelection])
            assertEquals("list", snapshot[AppStorageKey.tracePathViewMode])
            assertEquals(48, AppStorageKey.all.size)
            assertEquals(48, AppStorageKey.all.map { it.rawValue }.toSet().size)
            assertFalse(File(h.directory, MeshCoreStorage.PREFERENCE_FILENAME).exists())
        }
    }

    @Test
    fun sourceBooleanDefaultsAreCompleteAndPinned() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            val s = h.owner.preferences.snapshot()
            val falseKeys = listOf(
                AppStorageKey.hasCompletedOnboarding, AppStorageKey.showIncomingPath, AppStorageKey.showIncomingHopCount,
                AppStorageKey.showIncomingRegion, AppStorageKey.showIncomingHeardCount, AppStorageKey.showIncomingSendTime,
                AppStorageKey.linkPreviewsEnabled, AppStorageKey.replyWithQuote,
                AppStorageKey.mapNorthLocked, AppStorageKey.showDiscoveredNodesOnMap, AppStorageKey.hasSeenRepeaterDragHint,
                AppStorageKey.useDefaultTranslationApp, AppStorageKey.isDemoModeUnlocked, AppStorageKey.isDemoModeEnabled,
            )
            for (key in AppStorageKey.all.filterIsInstance<PreferenceKey.BooleanKey>()) {
                if (key.defaultValue == null) assertNull(s.stored(key))
                else assertEquals(key !in falseKeys, s[key], key.rawValue)
                assertFalse(s.contains(key), key.rawValue)
            }
        }
    }

    @Test
    fun coldStartRetainsEveryPrimitiveAndOrderedArray() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            val id = UUID.randomUUID()
            h.owner.preferences.update {
                this[AppStorageKey.hasCompletedOnboarding] = true
                this[AppStorageKey.translationTargetLanguage] = "zh"
                this[AppStorageKey.autoDeleteStaleNodesDays] = Long.MAX_VALUE
                this[AppStorageKey.lastStaleCleanupDate] = 123.125
                this[AppStorageKey.frequentEmojis] = Bytes.utf8("[\"\\uD83D\\uDC4D\"]")
                this[AppStorageKey.recentReactionEmojis] = listOf("second", "first", "second").snapshot()
            }
            h.owner.devicePreferences.setGPSSource(GPSSource.DEVICE, id)
            val before = h.owner.preferences.snapshot()
            h.reopen()
            assertEquals(before, h.owner.preferences.snapshot())
            assertEquals(GPSSource.DEVICE, h.owner.devicePreferences.gpsSource(id))
            assertEquals(listOf("second", "first", "second"), h.owner.preferences.get(AppStorageKey.recentReactionEmojis))
        }
    }

    @Test
    fun concurrentReadModifyWriteDoesNotLoseUpdates() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            coroutineScope {
                List(100) {
                    async(Dispatchers.Default) {
                        h.owner.preferences.update {
                            this[AppStorageKey.autoDeleteStaleNodesDays] = snapshot[AppStorageKey.autoDeleteStaleNodesDays] + 1
                        }
                    }
                }.forEach { it.await() }
            }
            assertEquals(100L, h.owner.preferences.get(AppStorageKey.autoDeleteStaleNodesDays))
            h.reopen()
            assertEquals(100L, h.owner.preferences.get(AppStorageKey.autoDeleteStaleNodesDays))
        }
    }

    @Test
    fun awaitedFlowUpdatesAreOrderedAndSharedAcrossConsumers() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            val a = Channel<Long>(Channel.UNLIMITED)
            val b = Channel<Long>(Channel.UNLIMITED)
            val first = launch { h.owner.preferences.observe(AppStorageKey.autoDeleteStaleNodesDays).collect(a::send) }
            val second = launch { h.owner.preferences.observe(AppStorageKey.autoDeleteStaleNodesDays).collect(b::send) }
            try {
                assertEquals(0L, a.receive())
                assertEquals(0L, b.receive())
                for (value in 1L..3L) {
                    h.owner.preferences.set(AppStorageKey.autoDeleteStaleNodesDays, value)
                    assertEquals(value, a.receive())
                    assertEquals(value, b.receive())
                }
            } finally {
                first.cancelAndJoin()
                second.cancelAndJoin()
                a.close()
                b.close()
            }
        }
    }

    @Test
    @Config(sdk = [31, 37])
    fun vendorDirtyReadCanSuppressTheCommittedValueForAnExistingObserver() = runTest(timeout = 10.seconds) {
        observeDuringFileReplacement(serialized = false)
    }

    @Test
    @Config(sdk = [31, 37])
    fun serializedFileReadObservesTheCommitWhenSubscriptionStartsDuringReplacement() = runTest(timeout = 10.seconds) {
        observeDuringFileReplacement(serialized = true)
    }

    private suspend fun TestScope.observeDuringFileReplacement(serialized: Boolean) {
        val file = File(temporary.newFolder(), MeshCoreStorage.PREFERENCE_FILENAME)
        val lifetime = SupervisorJob()
        val writing = CompletableDeferred<Unit>()
        val replace = CompletableDeferred<Unit>()
        val delegate = BoundedPreferencesSerializer()
        var pauseWrite = false
        val serializer = object : Serializer<Preferences> {
            override val defaultValue = delegate.defaultValue
            override suspend fun readFrom(input: InputStream) = delegate.readFrom(input)
            override suspend fun writeTo(t: Preferences, output: OutputStream) {
                if (pauseWrite) {
                    pauseWrite = false
                    writing.complete(Unit)
                    replace.await()
                }
                delegate.writeTo(t, output)
            }
        }
        val raw = FileStorage(serializer) { file }
        val data = DataStoreFactory.create(
            storage = if (serialized) SerializedFileStorage(raw) else raw,
            scope = CoroutineScope(coroutineContext + lifetime),
        )
        val reporter = RecordingReporter()
        val preferences = PreferenceStore(data, StorageAccess {}, reporter)
        val values = Channel<Double>(Channel.UNLIMITED)
        var observer: kotlinx.coroutines.Job? = null
        try {
            assertEquals(0.0, preferences.get(AppStorageKey.lastStaleCleanupDate))
            preferences.set(AppStorageKey.lastStaleCleanupDate, 0.0)
            val before = file.readBytes()
            pauseWrite = true
            val committed = 1793603518.0
            val writer = async { preferences.set(AppStorageKey.lastStaleCleanupDate, committed) }
            writing.await()
            assertContentEquals(before, file.readBytes(), "Writer has advanced its version but not replaced the file")
            observer = launch { preferences.observe(AppStorageKey.lastStaleCleanupDate).collect(values::send) }
            testScheduler.runCurrent()
            if (serialized) {
                assertTrue(values.tryReceive().isFailure, "A dirty file must not become the initial observation")
                val cancelledReader = launch { preferences.snapshot() }
                testScheduler.runCurrent()
                assertFalse(cancelledReader.isCompleted)
                cancelledReader.cancelAndJoin()
                assertTrue(cancelledReader.isCancelled)
                assertFalse(writer.isCompleted, "Cancelling a waiting reader must not cancel the writer")
            } else {
                assertEquals(0.0, values.tryReceive().getOrThrow(), "Negative control must reach the dirty read")
            }
            replace.complete(Unit)
            writer.await()
            testScheduler.runCurrent()
            assertEquals(committed, preferences.get(AppStorageKey.lastStaleCleanupDate))
            val persisted = file.inputStream().use { delegate.readFrom(it) }
            assertEquals(committed, PreferenceSnapshot.from(persisted)[AppStorageKey.lastStaleCleanupDate])
            if (serialized) {
                assertEquals(committed, values.tryReceive().getOrThrow())
            } else {
                assertTrue(values.tryReceive().isFailure, "Vendor collector suppressed the equal-version commit")
            }
            preferences.set(AppStorageKey.lastStaleCleanupDate, committed + 1)
            testScheduler.runCurrent()
            assertEquals(committed + 1, values.tryReceive().getOrThrow(), "A later version must still be observed")
            assertTrue(reporter.failures.isEmpty())
        } finally {
            replace.complete(Unit)
            observer?.cancelAndJoin()
            lifetime.cancelAndJoin()
            values.close()
        }
    }

    @Test
    fun transactionExceptionDoesNotCommitPartialValues() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.preferences.set(AppStorageKey.replyWithQuote, true)
            assertFailsWith<IllegalStateException> {
                h.owner.preferences.update {
                    this[AppStorageKey.replyWithQuote] = false
                    this[AppStorageKey.liveActivityEnabled] = false
                    throw IllegalStateException("Test-only aborted transform")
                }
            }
            val s = h.owner.preferences.snapshot()
            assertTrue(s[AppStorageKey.replyWithQuote])
            assertFalse(s.contains(AppStorageKey.liveActivityEnabled))
        }
    }

    @Test
    fun transformCancellationPropagatesWithoutCommitting() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.preferences.set(AppStorageKey.replyWithQuote, true)
            assertFailsWith<CancellationException> {
                h.owner.preferences.update {
                    this[AppStorageKey.replyWithQuote] = false
                    throw CancellationException("Test-only cancellation before commit")
                }
            }
            assertTrue(h.owner.preferences.get(AppStorageKey.replyWithQuote))
            assertTrue(h.reporter.failures.isEmpty())
        }
    }

    @Test
    fun missingAndUnknownGpsRemainDistinctAndSourceKeysUseCanonicalUuid() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            val id = UUID.fromString("aabbccdd-1234-5678-9abc-aabbccddeeff")
            assertEquals("device.AABBCCDD-1234-5678-9ABC-AABBCCDDEEFF.gpsSource", gpsSourceKey(id).rawValue)
            assertFalse(h.owner.devicePreferences.hasSetGPSSource(id))
            h.owner.preferences.set(gpsSourceKey(id), "futureGPS")
            assertTrue(h.owner.devicePreferences.hasSetGPSSource(id))
            assertEquals(GPSSource.PHONE, h.owner.devicePreferences.gpsSource(id))
            assertEquals("futureGPS", h.owner.preferences.get(gpsSourceKey(id)))
            h.reopen()
            assertTrue(h.owner.devicePreferences.hasSetGPSSource(id))
            assertEquals("futureGPS", h.owner.preferences.get(gpsSourceKey(id)))
        }
    }

    @Test
    fun unknownRawSelectionsSurviveStorageAndExport() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.preferences.update {
                this[AppStorageKey.nodesSortOrder] = "futureSort"
                this[AppStorageKey.mapStyleSelection] = "futureStyle"
                this[AppStorageKey.translationTargetLanguage] = "overlay"
            }
            h.reopen()
            val backup = h.owner.backupPreferences.snapshotForBackup()
            assertEquals(PreferenceValue.StringValue("futureSort"), backup.presentValues["nodesSortOrder"])
            assertEquals(PreferenceValue.StringValue("futureStyle"), backup.presentValues["mapStyleSelection"])
            assertEquals(PreferenceValue.StringValue("overlay"), backup.presentValues["translationTargetLanguage"])
        }
    }

    @Test
    fun corruptProtobufNeverCreatesAnEmptyReplacement() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val file = File(directory, MeshCoreStorage.PREFERENCE_FILENAME)
        val corrupt = byteArrayOf(-1, -1, -1, -1)
        file.writeBytes(corrupt)
        withStorage(directory) { h ->
            expectStorageFailure(StorageProblem.CorruptPreferences) { h.owner.preferences.snapshot() }
            assertContentEquals(corrupt, file.readBytes())
            assertEquals(StorageProblem.CorruptPreferences, h.reporter.failures.single().problem)
            val state = h.owner.preferences.states.first { it is StoreState.Failed }
            assertIs<StoreState.Failed>(state)
            assertContentEquals(corrupt, file.readBytes())
        }
    }

    @Test
    fun oversizedStateFailsBeforeUnboundedParsingAndIsNotReplaced() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val file = File(directory, MeshCoreStorage.PREFERENCE_FILENAME)
        file.writeBytes(ByteArray(BoundedPreferencesSerializer.MAXIMUM_BYTES + 1) { 1 })
        withStorage(directory) { h ->
            expectStorageFailure(StorageProblem.StateTooLarge(BoundedPreferencesSerializer.MAXIMUM_BYTES)) {
                h.owner.preferences.snapshot()
            }
            assertEquals(BoundedPreferencesSerializer.MAXIMUM_BYTES + 1L, file.length())
        }
    }

    @Test
    fun oversizedWritePreservesThePreviouslyCommittedFile() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.preferences.set(AppStorageKey.replyWithQuote, true)
            val file = File(h.directory, MeshCoreStorage.PREFERENCE_FILENAME)
            val before = file.readBytes()
            expectStorageFailure(StorageProblem.StateTooLarge(BoundedPreferencesSerializer.MAXIMUM_BYTES)) {
                h.owner.preferences.set(AppStorageKey.frequentEmojis, Bytes(ByteArray(BoundedPreferencesSerializer.MAXIMUM_BYTES + 1)))
            }
            assertContentEquals(before, file.readBytes())
            assertTrue(h.owner.preferences.get(AppStorageKey.replyWithQuote))
            assertFalse(h.owner.preferences.snapshot().contains(AppStorageKey.frequentEmojis))
        }
    }

    @Test
    fun ioFailureIsReportedAndPreservesDurableState() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val lifetime = SupervisorJob()
        var failWrites = false
        val delegate = BoundedPreferencesSerializer()
        val serializer = object : Serializer<Preferences> {
            override val defaultValue = delegate.defaultValue
            override suspend fun readFrom(input: InputStream) = delegate.readFrom(input)
            override suspend fun writeTo(t: Preferences, output: OutputStream) {
                if (failWrites) throw IOException("Test-only write fault")
                delegate.writeTo(t, output)
            }
        }
        val data = DataStoreFactory.create(
            storage = SerializedFileStorage(FileStorage(serializer) { File(directory, MeshCoreStorage.PREFERENCE_FILENAME) }),
            scope = CoroutineScope(lifetime + Dispatchers.IO),
        )
        val reporter = RecordingReporter()
        val preferences = PreferenceStore(data, StorageAccess {}, reporter)
        try {
            preferences.set(AppStorageKey.replyWithQuote, true)
            val before = File(directory, MeshCoreStorage.PREFERENCE_FILENAME).readBytes()
            failWrites = true
            expectStorageFailure(StorageProblem.IoFailure) { preferences.set(AppStorageKey.replyWithQuote, false) }
            assertContentEquals(before, File(directory, MeshCoreStorage.PREFERENCE_FILENAME).readBytes())
            assertTrue(preferences.get(AppStorageKey.replyWithQuote))
            assertEquals(StorageProblem.IoFailure, reporter.failures.single().problem)
        } finally {
            lifetime.cancelAndJoin()
        }
        withStorage(directory) { h -> assertTrue(h.owner.preferences.get(AppStorageKey.replyWithQuote)) }
    }

    @Test
    fun duplicateOwnerIsRejectedAndReopenWaitsForClose() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            expectStorageFailure(StorageProblem.DuplicateOwner) {
                MeshCoreStorage.createOwned(
                    h.directory, h.cryptography, h.reporter, StorageAccess {}, StorageAccess {},
                    Dispatchers.IO, "duplicate-owner-must-not-create-a-key",
                )
            }
            h.owner.preferences.set(AppStorageKey.replyWithQuote, true)
            val previous = h.owner
            h.reopen()
            expectStorageFailure(StorageProblem.OwnerClosed) { previous.preferences.snapshot() }
            assertTrue(h.owner.preferences.get(AppStorageKey.replyWithQuote))
        }
    }

    @Test
    fun preunlockPermissionAndUserStorageErrorsAreNotDefaultPreferences() = runBlocking<Unit> {
        for (problem in listOf(
            StorageProblem.LockedBeforeFirstUnlock, StorageProblem.UserStorageInaccessible, StorageProblem.PermissionDenied,
        )) {
            val platform = TestKeystoreAccess()
            val reporter = RecordingReporter()
            val access = StorageAccess { throw StorageFailure(problem, it) }
            val h = StorageHarness(temporary.newFolder(), platform, reporter, access)
            try {
                expectStorageFailure(problem) { h.owner.preferences.get(AppStorageKey.liveActivityEnabled) }
                assertEquals(problem, reporter.failures.single().problem)
                assertFalse(File(h.directory, MeshCoreStorage.PREFERENCE_FILENAME).exists())
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun appDeviceAndSceneResetScopesDoNotEraseEachOtherOrSecrets() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            val device = UUID.randomUUID()
            val scene = UUID.randomUUID()
            h.owner.preferences.set(AppStorageKey.replyWithQuote, true)
            h.owner.devicePreferences.setGPSSource(GPSSource.DEVICE, device)
            h.owner.scenePreferences.set(scene, SceneStorageKey.MAP_CAMERA_REGION, "1,2,3,4")
            h.owner.secrets.storePassword("test-only-password", radioId(), nodePublicKey())
            h.owner.preferences.resetAppPreferences()
            assertFalse(h.owner.preferences.get(AppStorageKey.replyWithQuote))
            assertEquals(GPSSource.DEVICE, h.owner.devicePreferences.gpsSource(device))
            assertEquals("1,2,3,4", h.owner.scenePreferences.get(scene, SceneStorageKey.MAP_CAMERA_REGION))
            h.owner.devicePreferences.reset(device)
            assertFalse(h.owner.devicePreferences.hasSetGPSSource(device))
            assertEquals("1,2,3,4", h.owner.scenePreferences.get(scene, SceneStorageKey.MAP_CAMERA_REGION))
            h.owner.scenePreferences.reset(scene)
            assertEquals("", h.owner.scenePreferences.get(scene, SceneStorageKey.MAP_CAMERA_REGION))
            assertEquals("test-only-password", h.owner.secrets.retrievePassword(radioId(), nodePublicKey()))
        }
    }

    @Test
    fun scenesRestorePerSceneAndNeverEnterManualAppPreferencesBackup() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            assertEquals("mapCameraRegion", SceneStorageKey.MAP_CAMERA_REGION.rawValue)
            assertEquals("", h.owner.scenePreferences.get(a, SceneStorageKey.MAP_CAMERA_REGION))
            h.owner.scenePreferences.set(a, SceneStorageKey.MAP_CAMERA_REGION, "51.5,-0.125,0.1,0.2")
            assertEquals("", h.owner.scenePreferences.get(b, SceneStorageKey.MAP_CAMERA_REGION))
            h.reopen()
            assertEquals("51.5,-0.125,0.1,0.2", h.owner.scenePreferences.get(a, SceneStorageKey.MAP_CAMERA_REGION))
            assertTrue(h.owner.backupPreferences.snapshotForBackup().presentValues.isEmpty())
        }
    }

    @Test
    fun backupMissingVersusExplicitDefaultsAndWriteIfMissingAreAtomic() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            assertTrue(h.owner.backupPreferences.snapshotForBackup().presentValues.isEmpty())
            h.owner.preferences.set(AppStorageKey.replyWithQuote, false)
            h.owner.preferences.set(AppStorageKey.lastShownWhatsNewVersion, "1.2.3")
            val snapshot = BackupPreferenceSnapshot(mapOf(
                "replyWithQuote" to PreferenceValue.BooleanValue(true),
                "mapStyleSelection" to PreferenceValue.StringValue("topo"),
                "showInlineImages" to PreferenceValue.BooleanValue(true),
                "recentReactionEmojis" to PreferenceValue.StringListValue(listOf("b", "a").snapshot()),
            ))
            assertEquals(
                listOf("showInlineImages", "mapStyleSelection", "recentReactionEmojis"),
                h.owner.backupPreferences.restoreMissing(snapshot),
            )
            assertFalse(h.owner.preferences.get(AppStorageKey.replyWithQuote))
            val exported = h.owner.backupPreferences.snapshotForBackup().presentValues
            assertEquals(PreferenceValue.BooleanValue(false), exported["replyWithQuote"])
            assertFalse(exported.containsKey("lastShownWhatsNewVersion"))
            assertTrue(h.owner.backupPreferences.restoreMissing(snapshot).isEmpty())
        }
    }

    @Test
    fun allTenThemesAreUnlockedAndUnknownCorrectionIsExplicit() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            val appearance = h.owner.appearancePreferences
            assertEquals(10, AppearancePreferenceStore.UNLOCKED_THEME_IDS.size)
            assertEquals(AppearanceSelection("default", "system"), appearance.resolveStoredSelection().selection)
            assertTrue(h.owner.preferences.snapshot().storedValues.isEmpty())
            for (id in AppearancePreferenceStore.UNLOCKED_THEME_IDS) {
                appearance.setTheme(id)
                assertEquals(id, appearance.resolveStoredSelection().selection.themeID)
            }
            h.owner.preferences.update {
                this[AppearanceStorageKey.selectedThemeID] = "future-theme"
                this[AppearanceStorageKey.appColorSchemePreference] = "future-scheme"
            }
            assertEquals("future-theme", h.owner.preferences.get(AppearanceStorageKey.selectedThemeID))
            val result = appearance.resolveStoredSelection()
            assertEquals(AppearanceSelection("default", "system"), result.selection)
            assertEquals(listOf("selectedThemeID", "appColorSchemePreference"), result.correctedKeys)
            assertEquals("default", h.owner.preferences.get(AppearanceStorageKey.selectedThemeID))
            assertEquals("system", h.owner.preferences.get(AppearanceStorageKey.appColorSchemePreference))
        }
    }

    @Test
    fun discoveryFirstActivationPersistsChildrenButPreservesExistingChoices() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            val n = h.owner.notificationPreferences()
            assertTrue(n.preferences.value.soundEnabled)
            n.setDiscoveryEnabled(false)
            assertFalse(h.owner.preferences.snapshot().contains(AppStorageKey.notifyNewContactsContact))
            n.setDiscoveryEnabled(true)
            assertTrue(h.owner.preferences.snapshot().contains(AppStorageKey.notifyNewContactsContact))
            assertTrue(h.owner.preferences.get(AppStorageKey.notifyNewContactsContact))
            h.owner.preferences.set(AppStorageKey.notifyNewContactsContact, false)
            n.setDiscoveryEnabled(false)
            n.setDiscoveryEnabled(true)
            assertFalse(h.owner.preferences.get(AppStorageKey.notifyNewContactsContact))
            assertSame(n, h.owner.notificationPreferences())
            assertTrue(h.owner.preferences.get(AppStorageKey.notifyNewContactsRepeater))
        }
    }

    @Test
    fun notificationAdapterStartsFromActualStorageAndUpdatesWholeSnapshotAtomically() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.preferences.set(AppStorageKey.notificationSoundEnabled, false)
            val n = h.owner.notificationPreferences()
            assertFalse(n.preferences.value.soundEnabled)
            val changed = n.preferences.value.copy(contactMessagesEnabled = false, badgeEnabled = false)
            n.update(changed)
            val observed = n.states.first { it is StoreState.Ready && it.value == changed }
            assertEquals(changed, assertIs<StoreState.Ready<*>>(observed).value)
            assertEquals(changed, n.preferences.value)
        }
    }

    @Test
    fun recentEmojiOrderDefaultsDuplicateRemovalAndSixLimitPersist() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            val recent = RecentEmojiPreferences(h.owner.preferences)
            assertEquals(RecentEmojiPreferences.DEFAULT_EMOJIS, recent.recentEmojis())
            assertFalse(h.owner.preferences.snapshot().contains(AppStorageKey.recentReactionEmojis))
            for (emoji in listOf("a", "b", "c", "d", "e", "f", "g", "c")) recent.recordUsage(emoji)
            assertEquals(listOf("c", "g", "f", "e", "d", "b"), recent.recentEmojis())
            h.reopen()
            assertEquals(listOf("c", "g", "f", "e", "d", "b"), RecentEmojiPreferences(h.owner.preferences).recentEmojis())
        }
    }

    @Test
    fun malformedKnownPrimitiveTypeIsReportedWithoutRepairOrDefault() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val file = File(directory, MeshCoreStorage.PREFERENCE_FILENAME)
        val malformed = androidx.datastore.preferences.core.mutablePreferencesOf(
            androidx.datastore.preferences.core.stringPreferencesKey("liveActivityEnabled") to "not-a-boolean",
        )
        file.outputStream().use { androidx.datastore.preferences.core.PreferencesFileSerializer.writeTo(malformed, it) }
        val before = file.readBytes()
        withStorage(directory) { h ->
            expectStorageFailure(StorageProblem.PreferenceTypeMismatch("liveActivityEnabled")) {
                h.owner.preferences.snapshot()
            }
            assertContentEquals(before, file.readBytes())
            assertEquals(StorageProblem.PreferenceTypeMismatch("liveActivityEnabled"), h.reporter.failures.single().problem)
        }
    }

    @Test
    fun malformedOrderedArrayIsNotConvertedIntoAnEmptyRecentList() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val file = File(directory, MeshCoreStorage.PREFERENCE_FILENAME)
        val malformed = androidx.datastore.preferences.core.mutablePreferencesOf(
            androidx.datastore.preferences.core.byteArrayPreferencesKey("recentReactionEmojis") to byteArrayOf(1, 2),
        )
        file.outputStream().use { androidx.datastore.preferences.core.PreferencesFileSerializer.writeTo(malformed, it) }
        val before = file.readBytes()
        withStorage(directory) { h ->
            expectStorageFailure(StorageProblem.CorruptPreferences) {
                RecentEmojiPreferences(h.owner.preferences).recentEmojis()
            }
            assertContentEquals(before, file.readBytes())
        }
    }

    @Test
    fun nonFiniteDateWriteCannotCommitAndRawReferenceEpochSecondsArePreserved() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.preferences.set(AppStorageKey.lastStaleCleanupDate, 123.125)
            for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                expectStorageFailure(StorageProblem.InvalidPreference("lastStaleCleanupDate")) {
                    h.owner.preferences.set(AppStorageKey.lastStaleCleanupDate, value)
                }
            }
            h.reopen()
            assertEquals(123.125, h.owner.preferences.get(AppStorageKey.lastStaleCleanupDate))
            assertFalse(h.owner.backupPreferences.snapshotForBackup().presentValues.containsKey("lastStaleCleanupDate"))
        }
    }

    @Test
    fun backupInputRejectsDeviceLocalAndMistypedKeysRatherThanDroppingThem() {
        assertFailsWith<StorageFailure> {
            BackupPreferenceSnapshot(mapOf("lastShownWhatsNewVersion" to PreferenceValue.StringValue("1.2.3")))
        }
        assertFailsWith<StorageFailure> {
            BackupPreferenceSnapshot(mapOf("mapCameraRegion" to PreferenceValue.StringValue("1,2,3,4")))
        }
        val wrongType = assertFailsWith<StorageFailure> {
            BackupPreferenceSnapshot(mapOf("replyWithQuote" to PreferenceValue.StringValue("false")))
        }
        assertEquals(StorageProblem.PreferenceTypeMismatch("replyWithQuote"), wrongType.problem)
    }

    @Test
    fun cancellationDuringCloseReleasesOwnerOnlyAfterDatastoreCleanup() = runBlocking<Unit> {
        withStorage(temporary.newFolder()) { h ->
            h.owner.preferences.set(AppStorageKey.replyWithQuote, true)
            val closing = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                checkNotNull(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]).cancel()
                h.owner.close()
            }
            closing.join()
            assertTrue(closing.isCancelled)
            h.reopen()
            assertTrue(h.owner.preferences.get(AppStorageKey.replyWithQuote))
        }
    }
}
