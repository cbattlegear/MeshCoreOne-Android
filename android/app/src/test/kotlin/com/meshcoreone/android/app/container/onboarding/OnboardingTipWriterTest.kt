// AndroidOnly: WP-305 Real legacy onboarding writer invalidates durable attempt identities.
package com.meshcoreone.android.app.container.onboarding

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.datastore.PreferenceKey
import com.meshcoreone.android.core.ui.SharedTip
import com.meshcoreone.android.core.ui.ShowOnceTips
import com.meshcoreone.android.core.ui.TipHostState
import com.meshcoreone.android.feature.onboarding.OnboardingState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class OnboardingTipWriterTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(directory: File): Context {
        val application = ApplicationProvider.getApplicationContext<Context>()
        return object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
    }

    @Test fun realWriterResetAndUnattributedCompletionCannotResurrectAnEarlierReceipt() = runBlocking {
        withTimeout(15_000) {
            val context = context(temporary.newFolder())
            val storage = MeshCoreStorage.get(context)
            val owner = SupervisorJob()
            try {
                val tips = ShowOnceTips(storage.preferences)
                tips.completeOnboarding("attempt-a")
                assertTrue(tips.isOnboardingCompletionFinalized("attempt-a"))
                val flags = WriteBehindOnboardingFlags.load(
                    storage.preferences, CoroutineScope(owner + Dispatchers.Default),
                )
                val state = OnboardingState(flags)
                assertTrue(state.hasCompletedOnboarding)
                state.resetOnboarding()
                storage.preferences.observe(AppStorageKey.hasCompletedOnboarding).first { !it }
                assertFalse(tips.isOnboardingCompletionFinalized("attempt-a"))
                state.completeOnboarding()
                storage.preferences.observe(AppStorageKey.hasCompletedOnboarding).first { it }
                assertTrue(state.hasCompletedOnboarding)
                assertFalse(tips.isOnboardingCompletionFinalized("attempt-a"))
                assertTrue(tips.claim(SharedTip.DEVICE_MENU) {
                    TipHostState(AppTab.CHATS, true, true, false)
                })
            } finally {
                owner.cancelAndJoin()
                storage.close()
            }
            val reopened = MeshCoreStorage.get(context)
            try {
                val tips = ShowOnceTips(reopened.preferences)
                assertTrue(reopened.preferences.get(AppStorageKey.hasCompletedOnboarding))
                assertFalse(tips.isOnboardingCompletionFinalized("attempt-a"))
                assertTrue(tips.hasDisplayed(SharedTip.DEVICE_MENU))
                tips.stageOnboardingCompletion("attempt-b")
                tips.completeOnboarding("attempt-b")
                assertTrue(tips.isOnboardingCompletionFinalized("attempt-b"))
                assertFalse(tips.isOnboardingCompletionFinalized("attempt-a"))
            } finally {
                reopened.close()
            }
        }
    }

    @Test fun realWriterResetClearsStagedIdentityWithoutConsumingThePendingTip() = runBlocking {
        withTimeout(15_000) {
            val storage = MeshCoreStorage.get(context(temporary.newFolder()))
            val owner = SupervisorJob()
            try {
                val tips = ShowOnceTips(storage.preferences)
                tips.completeOnboarding("attempt-a")
                tips.stageOnboardingCompletion("attempt-b")
                val state = OnboardingState(WriteBehindOnboardingFlags.load(
                    storage.preferences, CoroutineScope(owner + Dispatchers.Default),
                ))
                state.resetOnboarding()
                storage.preferences.observe(AppStorageKey.hasCompletedOnboarding).first { !it }
                assertEquals("", storage.preferences.get(
                    PreferenceKey.StringKey("onboarding.completion.pending", ""),
                ))
                assertFalse(tips.hasDisplayed(SharedTip.DEVICE_MENU))
                assertTrue(tips.claim(SharedTip.DEVICE_MENU) {
                    TipHostState(AppTab.NODES, true, true, false)
                })
            } finally {
                owner.cancelAndJoin()
                storage.close()
            }
        }
    }
}
