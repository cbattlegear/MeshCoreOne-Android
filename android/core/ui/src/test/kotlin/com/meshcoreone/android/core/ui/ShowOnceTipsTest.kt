// PortedFrom: MC1/Tips/DeviceMenuTip.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Tips/LiveActivityTip.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: actual DataStore concurrency, persistence, cancellation and process-lifetime assertions.
package com.meshcoreone.android.core.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.BackupPreferenceStore
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.datastore.PreferenceKey
import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.datastore.StorageIssueReporter
import com.meshcoreone.android.core.datastore.StorageProblem
import com.meshcoreone.android.core.datastore.StorageOperation
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ShowOnceTipsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val connected = TipHostState(AppTab.CHATS, true, true, false)
    private val status = TipHostState(AppTab.SETTINGS, false, true, true)

    private class Harness(val directory: File) {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val reported = ConcurrentLinkedQueue<StorageFailure>()
        val storage = MeshCoreStorage.get(context, StorageIssueReporter(reported::add))
        val tips = ShowOnceTips(storage.preferences)
        suspend fun close() = storage.close()
    }

    @Test fun unattributedSameValueWritesInvalidateIdentityWithoutDonatingOrConsumingTips() = runBlocking {
        val h = Harness(temporary.newFolder())
        try {
            h.tips.persistOnboardingFlag(true)
            assertTrue(h.storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
            assertFalse(h.storage.preferences.snapshot().contains(SharedTip.DEVICE_MENU.eventKey))
            assertFalse(h.tips.claim(SharedTip.DEVICE_MENU) { connected })
            h.tips.completeOnboarding("attempt-a")
            h.tips.stageOnboardingCompletion("attempt-b")
            h.tips.persistOnboardingFlag(true)
            assertTrue(h.storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
            assertFalse(h.tips.isOnboardingCompletionFinalized("attempt-a"))
            assertEquals("", h.storage.preferences.get(PreferenceKey.StringKey("onboarding.completion.pending", "")))
            assertFalse(h.tips.hasDisplayed(SharedTip.DEVICE_MENU))
            assertTrue(h.tips.claim(SharedTip.DEVICE_MENU) { connected })
            h.tips.completeOnboarding("attempt-c")
            assertTrue(h.tips.isOnboardingCompletionFinalized("attempt-c"))
            h.tips.persistOnboardingFlag(false)
            assertFalse(h.storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
            assertFalse(h.tips.isOnboardingCompletionFinalized("attempt-c"))
            assertTrue(h.tips.hasDisplayed(SharedTip.DEVICE_MENU))
        } finally { h.close() }
    }

    @Test fun failedUnattributedWritePreservesGateIdentityAndDonationTogether() = runBlocking {
        val directory = temporary.newFolder()
        val h = Harness(directory)
        val storageDirectory = File(directory, "meshcoreone-datastore")
        val temporaryWrite = File(storageDirectory, "app.preferences_pb.tmp")
        try {
            h.tips.completeOnboarding("attempt-a")
            h.tips.stageOnboardingCompletion("attempt-b")
            val previous = File(storageDirectory, "app.preferences_pb").readBytes()
            assertTrue(temporaryWrite.mkdir())
            val failure = assertFailsWith<StorageFailure> { h.tips.persistOnboardingFlag(false) }
            assertEquals(StorageProblem.IoFailure, failure.problem)
            assertEquals(StorageOperation.WRITE, failure.operation)
            assertTrue(h.reported.contains(failure))
            assertContentEquals(previous, File(storageDirectory, "app.preferences_pb").readBytes())
            assertTrue(h.storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
            assertTrue(h.tips.isOnboardingCompletionFinalized("attempt-a"))
            assertEquals("attempt-b", h.storage.preferences.get(
                PreferenceKey.StringKey("onboarding.completion.pending", ""),
            ))
            assertTrue(h.storage.preferences.get(SharedTip.DEVICE_MENU.eventKey))
            assertFalse(h.tips.hasDisplayed(SharedTip.DEVICE_MENU))
        } finally {
            h.close()
            if (temporaryWrite.exists()) {
                assertTrue(temporaryWrite.isDirectory)
                assertTrue(temporaryWrite.delete())
            }
        }
        val reopened = Harness(directory)
        try {
            assertTrue(reopened.tips.isOnboardingCompletionFinalized("attempt-a"))
            reopened.tips.persistOnboardingFlag(false)
            assertFalse(reopened.tips.isOnboardingCompletionFinalized("attempt-a"))
            assertFalse(reopened.storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
            assertTrue(reopened.tips.claim(SharedTip.DEVICE_MENU) { connected })
        } finally { reopened.close() }
    }

    @Test fun missingDonationsAndDisconnectedOrInvisibleHostsDoNotConsumeOrCreateAShownDefault() = runBlocking {
        val h = Harness(temporary.newFolder())
        try {
            assertFalse(h.tips.claim(SharedTip.DEVICE_MENU) { connected })
            assertFalse(h.storage.preferences.snapshot().contains(SharedTip.DEVICE_MENU.displayedKey))
            h.tips.donateCompletedOnboarding()
            assertFalse(h.tips.claim(SharedTip.DEVICE_MENU) { connected.copy(connectedDevicePresent = false) })
            assertFalse(h.tips.claim(SharedTip.DEVICE_MENU) { connected.copy(deviceMenuVisible = false) })
            assertFalse(h.storage.preferences.snapshot().contains(SharedTip.DEVICE_MENU.displayedKey))
            assertTrue(h.tips.claim(SharedTip.DEVICE_MENU) { connected })
        } finally { h.close() }
    }

    @Test fun completionPersistsItsTipUntilAValidTabHasAnEligibleHost() = runBlocking {
        val directory = temporary.newFolder()
        val h = Harness(directory)
        try {
            h.tips.completeOnboarding()
            assertTrue(h.storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
            for (tab in listOf(AppTab.SETTINGS, AppTab.TOOLS)) {
                assertFalse(h.tips.claim(SharedTip.DEVICE_MENU) { connected.copy(selectedTab = tab) })
            }
            assertFalse(h.tips.hasDisplayed(SharedTip.DEVICE_MENU))
            assertTrue(h.tips.claim(SharedTip.DEVICE_MENU) { connected })
        } finally { h.close() }

        val reopened = Harness(directory)
        try {
            assertTrue(reopened.storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
            assertTrue(reopened.tips.hasDisplayed(SharedTip.DEVICE_MENU))
            assertFalse(reopened.tips.claim(SharedTip.DEVICE_MENU) { connected })
        } finally { reopened.close() }
    }

    @Test fun completionAndTipDonationRemainUncommittedOnWriteFailureAndCanRetry() = runBlocking {
        val directory = temporary.newFolder()
        val h = Harness(directory)
        val blockingTemp = File(File(directory, "meshcoreone-datastore"), "app.preferences_pb.tmp")
        blockingTemp.parentFile.mkdirs()
        assertTrue(blockingTemp.mkdir())
        try {
            assertFailsWith<StorageFailure> { h.tips.completeOnboarding() }
            assertFalse(h.storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
            assertFalse(h.tips.claim(SharedTip.DEVICE_MENU) { connected })
            assertFalse(h.tips.hasDisplayed(SharedTip.DEVICE_MENU))
            assertTrue(h.reported.isNotEmpty())
        } finally {
            blockingTemp.deleteRecursively()
            h.close()
        }

        val reopened = Harness(directory)
        try {
            assertFalse(reopened.storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
            reopened.tips.completeOnboarding()
            assertTrue(reopened.storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
            assertTrue(reopened.tips.claim(SharedTip.DEVICE_MENU) { connected })
        } finally { reopened.close() }
    }

    @Test fun persistedDonationCanBeClaimedOnEachOriginalEligibleTab() = runBlocking {
        for (tab in listOf(AppTab.CHATS, AppTab.NODES, AppTab.MAP)) {
            val h = Harness(temporary.newFolder())
            try {
                h.tips.completeOnboarding()
                assertTrue(h.tips.claim(SharedTip.DEVICE_MENU) { connected.copy(selectedTab = tab) })
                assertTrue(h.tips.hasDisplayed(SharedTip.DEVICE_MENU))
            } finally { h.close() }
        }
    }

    @Test fun stagedAttemptSurvivesReopenWithoutCompletionUntilItsExactIdIsFinalized() = runBlocking {
        val directory = temporary.newFolder()
        val attempt = "b9adb122-424d-41d9-917d-f67a88dac492"
        val other = "0ea2c8a9-4ad0-46c5-b295-56f935eb634b"
        val pending = PreferenceKey.StringKey("onboarding.completion.pending", "")
        val staged = Harness(directory)
        try {
            staged.tips.stageOnboardingCompletion(attempt)
            assertEquals(attempt, staged.storage.preferences.get(pending))
            assertFalse(staged.storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
            assertFalse(staged.tips.isOnboardingCompletionFinalized(attempt))
            assertFalse(staged.tips.claim(SharedTip.DEVICE_MENU) { connected })
        } finally { staged.close() }

        val finalizing = Harness(directory)
        try {
            assertEquals(attempt, finalizing.storage.preferences.get(pending))
            assertFalse(finalizing.tips.isOnboardingCompletionFinalized(attempt))
            finalizing.tips.completeOnboarding(attempt)
            val committed = finalizing.storage.preferences.snapshot()
            assertTrue(committed[AppStorageKey.hasCompletedOnboarding])
            assertTrue(committed[SharedTip.DEVICE_MENU.eventKey])
            assertFalse(committed.contains(pending))
            assertTrue(finalizing.tips.isOnboardingCompletionFinalized(attempt))
            assertFalse(finalizing.tips.isOnboardingCompletionFinalized(other))
        } finally { finalizing.close() }

        val reopened = Harness(directory)
        try {
            assertTrue(reopened.tips.isOnboardingCompletionFinalized(attempt))
            assertFalse(reopened.tips.isOnboardingCompletionFinalized(other))
            assertFalse(reopened.storage.preferences.snapshot().contains(pending))
            assertTrue(reopened.tips.claim(SharedTip.DEVICE_MENU) { connected })
            assertFalse(reopened.tips.claim(SharedTip.DEVICE_MENU) { connected })
        } finally { reopened.close() }
    }

    @Test fun failedFinalizationPreservesStagedIdAndRetryCommitsThatSameIdAfterReopen() = runBlocking {
        val directory = temporary.newFolder()
        val attempt = "8f549593-e946-4ffb-b438-884711d87482"
        val pending = PreferenceKey.StringKey("onboarding.completion.pending", "")
        val h = Harness(directory)
        val blockingTemp = File(File(directory, "meshcoreone-datastore"), "app.preferences_pb.tmp")
        try {
            h.tips.stageOnboardingCompletion(attempt)
            assertTrue(blockingTemp.mkdir())
            assertFailsWith<StorageFailure> { h.tips.completeOnboarding(attempt) }
            assertEquals(attempt, h.storage.preferences.get(pending))
            assertFalse(h.storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
            assertFalse(h.tips.isOnboardingCompletionFinalized(attempt))
            assertFalse(h.tips.claim(SharedTip.DEVICE_MENU) { connected })
            assertTrue(h.reported.isNotEmpty())
        } finally {
            if (blockingTemp.isDirectory) blockingTemp.deleteRecursively()
            h.close()
        }

        val reopened = Harness(directory)
        try {
            assertEquals(attempt, reopened.storage.preferences.get(pending))
            assertFalse(reopened.tips.isOnboardingCompletionFinalized(attempt))
            reopened.tips.completeOnboarding(attempt)
            assertTrue(reopened.tips.isOnboardingCompletionFinalized(attempt))
            assertFalse(reopened.storage.preferences.snapshot().contains(pending))
            assertTrue(reopened.tips.claim(SharedTip.DEVICE_MENU) { connected })
        } finally { reopened.close() }
    }

    @Test fun unattributedCompletionCannotReviveAnEarlierFinalizedAttemptAfterResetAndReopen() = runBlocking {
        val directory = temporary.newFolder()
        val original = "2fc968e1-b9bf-4f34-889e-d87cf3da2a65"
        val h = Harness(directory)
        try {
            h.tips.completeOnboarding(original)
            assertTrue(h.tips.isOnboardingCompletionFinalized(original))
            h.storage.preferences.set(AppStorageKey.hasCompletedOnboarding, false)
            assertFalse(h.tips.isOnboardingCompletionFinalized(original))
        } finally { h.close() }

        val replacement = Harness(directory)
        try {
            assertFalse(replacement.tips.isOnboardingCompletionFinalized(original))
            replacement.tips.completeOnboarding()
            val committed = replacement.storage.preferences.snapshot()
            assertTrue(committed[AppStorageKey.hasCompletedOnboarding])
            assertTrue(committed[SharedTip.DEVICE_MENU.eventKey])
            assertFalse(committed.contains(PreferenceKey.StringKey("onboarding.completion.finalized", "")))
            assertFalse(replacement.tips.isOnboardingCompletionFinalized(original))
        } finally { replacement.close() }

        val reopened = Harness(directory)
        try {
            assertTrue(reopened.storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
            assertFalse(reopened.tips.isOnboardingCompletionFinalized(original))
            assertTrue(reopened.tips.claim(SharedTip.DEVICE_MENU) { connected })
        } finally { reopened.close() }
    }

    @Test fun failedUnattributedCompletionPreservesTheExactPriorDecisionAndStagedReplacement() = runBlocking {
        val directory = temporary.newFolder()
        val original = "77c06be0-3131-4e8b-b1fc-489c06976102"
        val replacement = "d17d098c-6bbc-4bbf-a797-92b477dc5ae7"
        val pending = PreferenceKey.StringKey("onboarding.completion.pending", "")
        val h = Harness(directory)
        val blockingTemp = File(File(directory, "meshcoreone-datastore"), "app.preferences_pb.tmp")
        try {
            h.tips.completeOnboarding(original)
            h.storage.preferences.set(AppStorageKey.hasCompletedOnboarding, false)
            h.tips.stageOnboardingCompletion(replacement)
            val before = h.storage.preferences.snapshot().storedValues
            assertTrue(blockingTemp.mkdir())
            assertFailsWith<StorageFailure> { h.tips.completeOnboarding() }
            assertEquals(before, h.storage.preferences.snapshot().storedValues)
            assertFalse(h.tips.isOnboardingCompletionFinalized(original))
            assertEquals(replacement, h.storage.preferences.get(pending))
            assertTrue(h.reported.isNotEmpty())
        } finally {
            if (blockingTemp.isDirectory) blockingTemp.deleteRecursively()
            h.close()
        }

        val retry = Harness(directory)
        try {
            assertFalse(retry.storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
            assertEquals(replacement, retry.storage.preferences.get(pending))
            retry.tips.completeOnboarding(replacement)
            assertTrue(retry.tips.isOnboardingCompletionFinalized(replacement))
            assertFalse(retry.tips.isOnboardingCompletionFinalized(original))
            assertFalse(retry.storage.preferences.snapshot().contains(pending))
        } finally { retry.close() }

        val reopened = Harness(directory)
        try {
            assertTrue(reopened.tips.isOnboardingCompletionFinalized(replacement))
            assertFalse(reopened.tips.isOnboardingCompletionFinalized(original))
        } finally { reopened.close() }
    }

    @Test fun backupCompletionFlagDoesNotExportOrRestoreAnExactAttemptIdentity() = runBlocking {
        val source = Harness(temporary.newFolder())
        val restoredDirectory = temporary.newFolder()
        val restored = Harness(restoredDirectory)
        val original = "7b2cfd50-2d13-49b6-bf86-83ea143736aa"
        try {
            source.tips.completeOnboarding(original)
            val backup = BackupPreferenceStore(source.storage.preferences).snapshotForBackup()
            assertTrue(AppStorageKey.hasCompletedOnboarding.rawValue in backup.presentValues)
            assertFalse("onboarding.completion.finalized" in backup.presentValues)
            assertFalse("onboarding.completion.pending" in backup.presentValues)
            BackupPreferenceStore(restored.storage.preferences).restoreMissing(backup)
            assertTrue(restored.storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
            assertFalse(restored.tips.isOnboardingCompletionFinalized(original))
        } finally {
            restored.close()
            source.close()
        }

        val reopened = Harness(restoredDirectory)
        try {
            assertTrue(reopened.storage.preferences.get(AppStorageKey.hasCompletedOnboarding))
            assertFalse(reopened.tips.isOnboardingCompletionFinalized(original))
        } finally { reopened.close() }
    }

    @Test fun thirtyTwoIndependentConsumersHaveExactlyOneRealAtomicDurableClaim() = runBlocking {
        val h = Harness(temporary.newFolder())
        try {
            h.tips.donateCompletedOnboarding()
            val claims = coroutineScope {
                List(32) { async(Dispatchers.Default) {
                    ShowOnceTips(h.storage.preferences).claim(SharedTip.DEVICE_MENU) { connected }
                } }.awaitAll()
            }
            assertEquals(1, claims.count { it })
            assertTrue(h.storage.preferences.snapshot().contains(SharedTip.DEVICE_MENU.displayedKey))
            assertTrue(h.tips.hasDisplayed(SharedTip.DEVICE_MENU))
        } finally { h.close() }
    }

    @Test fun claimsPersistAcrossRealOwnerCloseReopenAndAnotherRadioWithoutClosingProcessPreferencesFromUi() = runBlocking {
        val directory = temporary.newFolder()
        val first = Harness(directory)
        first.tips.donateCompletedOnboarding()
        assertTrue(first.tips.claim(SharedTip.DEVICE_MENU) { connected })
        first.close()
        val reopened = Harness(directory)
        try {
            assertTrue(reopened.tips.hasDisplayed(SharedTip.DEVICE_MENU))
            assertFalse(reopened.tips.claim(SharedTip.DEVICE_MENU) { connected })
            reopened.storage.preferences.set(PreferenceKey.StringKey("wp304.persistence.probe", null), "survived")
            assertEquals("survived", reopened.storage.preferences.get(PreferenceKey.StringKey("wp304.persistence.probe", null)))
        } finally { reopened.close() }
    }

    @Test fun liveStatusRequiresItsActualPresentedHostAndDoesNotInferPromotedLiveUpdateEligibility() = runBlocking {
        val h = Harness(temporary.newFolder())
        try {
            assertFalse(h.tips.claim(SharedTip.LIVE_STATUS) { status })
            h.tips.donateRadioConnected()
            assertFalse(h.tips.claim(SharedTip.LIVE_STATUS) { status.copy(liveStatusPresented = false) })
            assertTrue(h.tips.claim(SharedTip.LIVE_STATUS) { status })
            assertFalse(h.tips.claim(SharedTip.LIVE_STATUS) { status })
        } finally { h.close() }
    }

    @Test fun anActiveTaskOrAnotherOverlayDoesNotConsumeTheTip() = runBlocking {
        val h = Harness(temporary.newFolder())
        try {
            h.tips.donateCompletedOnboarding()
            assertFalse(h.tips.claim(SharedTip.DEVICE_MENU) { connected.copy(taskInProgress = true) })
            assertFalse(h.tips.claim(SharedTip.DEVICE_MENU) { connected.copy(overlayQuiescent = false) })
            assertFalse(h.tips.hasDisplayed(SharedTip.DEVICE_MENU))
        } finally { h.close() }
    }

    @Test fun cancellationPropagatesAndDoesNotTurnAFailedClaimIntoAShownDefault() = runBlocking {
        val h = Harness(temporary.newFolder())
        try {
            h.tips.donateCompletedOnboarding()
            assertFailsWith<CancellationException> {
                h.tips.claim(SharedTip.DEVICE_MENU) { throw CancellationException("cancelled synthetic host") }
            }
            assertFalse(h.storage.preferences.snapshot().contains(SharedTip.DEVICE_MENU.displayedKey))
            assertTrue(h.tips.claim(SharedTip.DEVICE_MENU) { connected })
        } finally { h.close() }
    }

    @Test fun wrongPersistedFlagTypeIsReportedAndNeverRecreatedAsFalse() = runBlocking {
        val h = Harness(temporary.newFolder())
        try {
            h.tips.donateCompletedOnboarding()
            h.storage.preferences.set(PreferenceKey.IntegerKey(SharedTip.DEVICE_MENU.displayedKey.rawValue, 0), 1)
            val failure = assertFailsWith<StorageFailure> { h.tips.claim(SharedTip.DEVICE_MENU) { connected } }
            assertIs<StorageProblem.PreferenceTypeMismatch>(failure.problem)
            assertTrue(h.reported.isNotEmpty())
            assertEquals(1L, h.storage.preferences.get(PreferenceKey.IntegerKey(SharedTip.DEVICE_MENU.displayedKey.rawValue, 0)))
        } finally { h.close() }
    }

    @Test fun closedOwnerHasATypedFailureRatherThanAnInMemoryTipStore() = runBlocking {
        val h = Harness(temporary.newFolder())
        h.close()
        val failure = assertFailsWith<StorageFailure> { h.tips.hasDisplayed(SharedTip.DEVICE_MENU) }
        assertEquals(StorageProblem.OwnerClosed, failure.problem)
        assertTrue(h.reported.isNotEmpty())
    }

    @Test fun realLockedWriteIsTypedAndDoesNotConsumeTheDurableClaim() = runBlocking {
        val h = Harness(temporary.newFolder())
        val users = Shadows.shadowOf(h.context.getSystemService(UserManager::class.java))
        try {
            h.tips.donateCompletedOnboarding()
            users.setUserUnlocked(false)
            val failure = assertFailsWith<StorageFailure> { h.tips.claim(SharedTip.DEVICE_MENU) { connected } }
            assertEquals(StorageOperation.WRITE, failure.operation)
            assertEquals(StorageProblem.LockedBeforeFirstUnlock, failure.problem)
            users.setUserUnlocked(true)
            assertFalse(h.tips.hasDisplayed(SharedTip.DEVICE_MENU))
            assertTrue(h.tips.claim(SharedTip.DEVICE_MENU) { connected })
        } finally { users.setUserUnlocked(true); h.close() }
    }

    @Test fun realFilesystemWriteFailureRetainsPreviousBytesAndReopenDoesNotTreatTheTipAsDisplayed() = runBlocking {
        val directory = temporary.newFolder()
        val h = Harness(directory)
        val storageDirectory = File(directory, "meshcoreone-datastore")
        val temporaryWrite = File(storageDirectory, "app.preferences_pb.tmp")
        h.tips.donateCompletedOnboarding()
        val previous = File(storageDirectory, "app.preferences_pb").readBytes()
        assertTrue(h.storage.preferences.get(SharedTip.DEVICE_MENU.eventKey))
        assertTrue(temporaryWrite.mkdir())
        try {
            val failure = assertFailsWith<StorageFailure> { h.tips.claim(SharedTip.DEVICE_MENU) { connected } }
            assertEquals(StorageProblem.IoFailure, failure.problem)
            assertEquals(StorageOperation.WRITE, failure.operation)
            assertFalse(h.tips.hasDisplayed(SharedTip.DEVICE_MENU))
            assertContentEquals(previous, File(storageDirectory, "app.preferences_pb").readBytes())
            assertTrue(h.reported.contains(failure))
        } finally {
            h.close()
            if (temporaryWrite.exists()) {
                assertTrue(temporaryWrite.isDirectory)
                assertTrue(temporaryWrite.delete())
            }
        }
        val reopened = Harness(directory)
        try {
            assertFalse(reopened.tips.hasDisplayed(SharedTip.DEVICE_MENU))
            assertTrue(reopened.tips.claim(SharedTip.DEVICE_MENU) { connected })
        } finally { reopened.close() }
    }

    @Test fun cancellingTheActualConsumerDuringASerializedWritePreventsClaimCommit() = runBlocking {
        val h = Harness(temporary.newFolder())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            h.tips.donateCompletedOnboarding()
            val consumer = async(Dispatchers.IO) {
                h.tips.claim(SharedTip.DEVICE_MENU) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    connected
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            consumer.cancel()
            release.countDown()
            assertFailsWith<CancellationException> { consumer.await() }
            assertFalse(h.storage.preferences.snapshot().contains(SharedTip.DEVICE_MENU.displayedKey))
            assertTrue(h.tips.claim(SharedTip.DEVICE_MENU) { connected })
        } finally { release.countDown(); h.close() }
    }

    @Test fun actualOwnerCloseDuringAnInFlightWriteDoesNotReturnSuccessOrPersistAShownFlag() = runBlocking {
        val directory = temporary.newFolder()
        val h = Harness(directory)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.tips.donateCompletedOnboarding()
        val consumer = async(Dispatchers.IO) {
            h.tips.claim(SharedTip.DEVICE_MENU) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                connected
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closing = async(start = CoroutineStart.UNDISPATCHED) { h.close() }
            val closed = assertFailsWith<StorageFailure> { h.storage.preferences.snapshot() }
            assertEquals(StorageProblem.OwnerClosed, closed.problem)
            release.countDown()
            closing.await()
            assertFailsWith<CancellationException> { consumer.await() }
        } finally { release.countDown(); consumer.cancelAndJoin(); h.close() }
        val reopened = Harness(directory)
        try {
            assertFalse(reopened.tips.hasDisplayed(SharedTip.DEVICE_MENU))
            assertTrue(reopened.tips.claim(SharedTip.DEVICE_MENU) { connected })
        } finally { reopened.close() }
    }
}
