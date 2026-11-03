// PortedFrom: MC1Tests/AppState/OnboardingStateTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.onboarding

import com.meshcoreone.android.core.contracts.AppTab
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test

class OnboardingStateTests : SourceCaseProof() {
    private val key = "hasCompletedOnboarding"
    private fun store() = InMemoryOnboardingFlagStore()

    @OriginalCase("OnboardingStateTests::completeOnboarding sets flag to true()")
    @Test fun completeSetsFlag() = prove {
        val onboarding = OnboardingState(store())
        onboarding.hasCompletedOnboarding = false
        runBlocking { onboarding.completeOnboarding() }
        assertTrue(onboarding.hasCompletedOnboarding)
    }

    @OriginalCase("OnboardingStateTests::completeOnboarding persists to UserDefaults()", "adapted")
    @Test fun completePersists() = prove {
        val flags = store()
        val onboarding = OnboardingState(flags)
        onboarding.hasCompletedOnboarding = false
        runBlocking { onboarding.completeOnboarding() }
        assertTrue(flags.getBoolean(key))
    }

    @OriginalCase("OnboardingStateTests::resetOnboarding clears flag()")
    @Test fun resetClearsFlag() = prove {
        val onboarding = OnboardingState(store())
        onboarding.hasCompletedOnboarding = true
        runBlocking { onboarding.resetOnboarding() }
        assertFalse(onboarding.hasCompletedOnboarding)
    }

    @OriginalCase("OnboardingStateTests::resetOnboarding clears onboarding path()")
    @Test fun resetClearsPath() = prove {
        val onboarding = OnboardingState(store())
        onboarding.replacePath(listOf(OnboardingStep.WELCOME, OnboardingStep.PERMISSIONS))
        runBlocking { onboarding.resetOnboarding() }
        assertTrue(onboarding.onboardingPath.isEmpty())
    }

    @OriginalCase("OnboardingStateTests::resetOnboarding persists false to UserDefaults()", "adapted")
    @Test fun resetPersistsFalse() = prove {
        val flags = store()
        val onboarding = OnboardingState(flags)
        onboarding.hasCompletedOnboarding = true
        runBlocking { onboarding.resetOnboarding() }
        assertFalse(flags.getBoolean(key))
    }

    @OriginalCase("OnboardingStateTests::onboardingPath starts empty()", "adapted")
    @Test fun pathStartsEmpty() = prove {
        assertTrue(OnboardingState(store()).onboardingPath.isEmpty())
    }

    @OriginalCase("OnboardingStateTests::onboardingPath can be appended to()", "adapted")
    @Test fun pathAppend() = prove {
        val onboarding = OnboardingState(store())
        onboarding.append(OnboardingStep.WELCOME)
        onboarding.append(OnboardingStep.PERMISSIONS)
        assertEquals(listOf(OnboardingStep.WELCOME, OnboardingStep.PERMISSIONS), onboarding.onboardingPath)
    }

    private fun tipOn(tab: AppTab): DeviceMenuTipCoordinator {
        val coordinator = DeviceMenuTipCoordinator({ tab }, {})
        return coordinator
    }

    private fun donateOn(tab: AppTab, pendingBefore: Boolean): Boolean = runBlocking {
        val coordinator = tipOn(tab)
        coordinator.markPending(pendingBefore)
        coordinator.donateIfOnValidTab()
        coordinator.pendingDonation.value
    }

    @OriginalCase("OnboardingStateTests::donateDeviceMenuTipIfOnValidTab on Chats tab clears pending()", "adapted")
    @Test fun donateChats() = prove { assertFalse(donateOn(AppTab.CHATS, true)) }

    @OriginalCase("OnboardingStateTests::donateDeviceMenuTipIfOnValidTab on Contacts tab clears pending()", "adapted")
    @Test fun donateContacts() = prove { assertFalse(donateOn(AppTab.NODES, true)) }

    @OriginalCase("OnboardingStateTests::donateDeviceMenuTipIfOnValidTab on Map tab clears pending()", "adapted")
    @Test fun donateMap() = prove { assertFalse(donateOn(AppTab.MAP, true)) }

    @OriginalCase("OnboardingStateTests::donateDeviceMenuTipIfOnValidTab on Settings tab sets pending()", "adapted")
    @Test fun donateSettings() = prove { assertTrue(donateOn(AppTab.SETTINGS, false)) }

    @OriginalCase("OnboardingStateTests::donateDeviceMenuTipIfOnValidTab on Tools tab sets pending()", "adapted")
    @Test fun donateTools() = prove { assertTrue(donateOn(AppTab.TOOLS, false)) }

    @OriginalCase("OnboardingStateTests::hasCompletedOnboarding syncs to UserDefaults on set()", "adapted")
    @Test fun flagSyncs() = prove {
        val flags = store()
        val onboarding = OnboardingState(flags)
        onboarding.hasCompletedOnboarding = true
        assertTrue(flags.getBoolean(key))
        onboarding.hasCompletedOnboarding = false
        assertFalse(flags.getBoolean(key))
    }

    private val granted = PermissionStatus.GRANTED
    private val undetermined = PermissionStatus.NOT_DETERMINED

    @OriginalCase("OnboardingStateTests.SuggestedStartingPathTests::Returns empty when onboarding is already complete()")
    @Test fun resumeEmptyWhenComplete() = prove {
        val onboarding = OnboardingState(store())
        onboarding.hasCompletedOnboarding = true
        val path = onboarding.suggestedStartingPath(OnboardingResumeFacts(1, true), undetermined, undetermined, false)
        assertTrue(path.isEmpty())
    }

    @OriginalCase("OnboardingStateTests.SuggestedStartingPathTests::Returns empty when no paired device()")
    @Test fun resumeEmptyWhenNoPairedDevice() = prove {
        val onboarding = OnboardingState(store())
        val path = onboarding.suggestedStartingPath(OnboardingResumeFacts(0, false), undetermined, undetermined, false)
        assertTrue(path.isEmpty())
    }

    @OriginalCase("OnboardingStateTests.SuggestedStartingPathTests::Resumes when a device was connected even with no system pairing registry (macOS)()", "adapted")
    @Test fun resumeWithLastConnectedOnly() = prove {
        val onboarding = OnboardingState(store())
        val path = onboarding.suggestedStartingPath(OnboardingResumeFacts(0, true), undetermined, undetermined, false)
        assertEquals(listOf(OnboardingStep.PERMISSIONS), path)
    }

    // Behaviors beyond the 16 source ids: the remaining branches of suggestedStartingPath.
    @Test fun resumePastPermissionsGoesToRegionOrPreset() {
        val onboarding = OnboardingState(store())
        val facts = OnboardingResumeFacts(1, false)
        assertEquals(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR, OnboardingStep.REGION),
            onboarding.suggestedStartingPath(facts, granted, PermissionStatus.DENIED, false))
        assertEquals(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR, OnboardingStep.PRESET),
            onboarding.suggestedStartingPath(facts, granted, granted, true))
        assertEquals(listOf(OnboardingStep.PERMISSIONS), onboarding.suggestedStartingPath(facts, granted, undetermined, true))
    }

    @Test fun flagIsReadFromStoreAtConstruction() {
        assertTrue(OnboardingState(InMemoryOnboardingFlagStore(mapOf(key to true))).hasCompletedOnboarding)
    }

    @Test fun popRemovesLastStepAndReportsEmpty() {
        val onboarding = OnboardingState(store())
        assertFalse(onboarding.pop())
        onboarding.append(OnboardingStep.PERMISSIONS); onboarding.append(OnboardingStep.PAIR)
        assertTrue(onboarding.pop())
        assertEquals(listOf(OnboardingStep.PERMISSIONS), onboarding.onboardingPath)
    }

    @Test fun completionWaitsForPersistenceAndPropagatesWriteFailure() = runBlocking {
        var stored = false
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val flags = object : OnboardingFlagStore {
            override fun getBoolean(key: String) = stored
            override fun setBoolean(key: String, value: Boolean) { stored = value }
            override suspend fun persistBoolean(key: String, value: Boolean) {
                started.complete(Unit)
                release.await()
                stored = value
            }
        }
        val onboarding = OnboardingState(flags)
        val completing = async { onboarding.completeOnboarding() }
        started.await()
        assertFalse(onboarding.hasCompletedOnboarding)
        release.complete(Unit)
        completing.await()
        assertTrue(onboarding.hasCompletedOnboarding)

        val failing = object : OnboardingFlagStore {
            override fun getBoolean(key: String) = false
            override fun setBoolean(key: String, value: Boolean) = Unit
            override suspend fun persistBoolean(key: String, value: Boolean): Unit = error("write failed")
        }
        val failedOnboarding = OnboardingState(failing)
        assertFailsWith<IllegalStateException> { failedOnboarding.completeOnboarding() }
        assertFalse(failedOnboarding.hasCompletedOnboarding)
    }

    @Test fun failedTipDonationRetainsPendingStateForRetry() = runBlocking {
        var attempts = 0
        val coordinator = DeviceMenuTipCoordinator({ AppTab.CHATS }) {
            attempts += 1
            if (attempts == 1) throw IllegalStateException("preference write failed")
        }
        coordinator.markPending(true)
        assertFailsWith<IllegalStateException> { coordinator.donateIfOnValidTab() }
        assertTrue(coordinator.pendingDonation.value)
        coordinator.donateIfOnValidTab()
        assertFalse(coordinator.pendingDonation.value)
        assertEquals(2, attempts)
    }
}
