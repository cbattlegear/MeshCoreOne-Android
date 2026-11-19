// AndroidOnly: WP-302 Retain onboarding entry, flow and completion witness across host recreation.
package com.meshcoreone.android.app.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.app.container.onboarding.AppOnboarding
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.feature.onboarding.OnboardingCompletionProducer
import com.meshcoreone.android.feature.onboarding.OnboardingCompletionPublication
import com.meshcoreone.android.feature.onboarding.OnboardingFeatureDependencies
import com.meshcoreone.android.feature.onboarding.OnboardingFlowCoordinator
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class OnboardingEntryRetention(
    private val navigation: NavigationCoordinator,
    private val dependencies: OnboardingFeatureDependencies,
) {
    internal data class Recovery(
        val failed: Boolean = false,
        val restarting: Boolean = false,
        val deliveryRevision: Long = 0,
    )

    internal sealed interface RestartWitness {
        data class Mounted(val value: MountedOnboardingCompletion) : RestartWitness
        data class FirstRun(val value: FirstRunOnboardingCompletion) : RestartWitness
    }

    internal sealed class Entry(
        val owner: OnboardingFeatureDependencies,
        val flow: OnboardingFlowCoordinator,
    ) {
        internal val recovery = MutableStateFlow(Recovery())
        // Guarded by the retention lock; restart waits for an in-flight delivery decision.
        internal var delivering = false
        internal var delivered = false
        internal var restartQueued: RestartWitness? = null
    }

    internal class MountedEntry(
        val identity: NavigationEntryIdentity,
        val capturedRevision: Long,
        owner: OnboardingFeatureDependencies,
        flow: OnboardingFlowCoordinator,
        witness: MountedOnboardingCompletion,
        val fallbackJob: Job?,
    ) : Entry(owner, flow) {
        val witness = AtomicReference(witness)
        val presentationRevision = MutableStateFlow(0L)
    }

    internal class FirstRunEntry(
        owner: OnboardingFeatureDependencies,
        flow: OnboardingFlowCoordinator,
        witness: FirstRunOnboardingCompletion,
    ) : Entry(owner, flow) {
        val witness = AtomicReference(witness)
    }

    private val lock = Any()
    private val mounted = MutableStateFlow<Map<NavigationEntryIdentity, MountedEntry>>(emptyMap())
    val mountedEntries: StateFlow<Map<NavigationEntryIdentity, MountedEntry>> = mounted.asStateFlow()
    private val retiringOwners = Collections.newSetFromMap(IdentityHashMap<OnboardingFeatureDependencies, Boolean>())
    private val failedOwners = Collections.newSetFromMap(IdentityHashMap<OnboardingFeatureDependencies, Boolean>())
    private val retiredFlows =
        IdentityHashMap<OnboardingFeatureDependencies, MutableSet<OnboardingFlowCoordinator>>()
    private val revision = MutableStateFlow(0L)
    val availabilityRevision: StateFlow<Long> = revision.asStateFlow()
    private val unavailable = MutableStateFlow<Set<NavigationEntryIdentity>>(emptySet())
    val unavailableEntries: StateFlow<Set<NavigationEntryIdentity>> = unavailable.asStateFlow()
    private val firstRun = MutableStateFlow<FirstRunEntry?>(null)
    val firstRunEntry: StateFlow<FirstRunEntry?> = firstRun.asStateFlow()
    private val pendingFirstRun = MutableStateFlow(
        (dependencies as? AppOnboarding)?.let { !it.wasCompletedAtLoad || !it.hasCompleted.value } == true,
    )
    val firstRunPending: StateFlow<Boolean> = pendingFirstRun.asStateFlow()
    private val firstRunEpoch = MutableStateFlow(0L)
    val firstRunDecisionEpoch: StateFlow<Long> = firstRunEpoch.asStateFlow()
    private var observer: Job? = null
    private var workScope: CoroutineScope? = null

    internal fun observe(scope: CoroutineScope) {
        synchronized(lock) {
            if (workScope === scope && observer?.isActive == true) return
            observer?.cancel()
            workScope = scope
            revision.value = Math.incrementExact(revision.value)
            val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                navigation.publishedNavigation.collect { retireRemovedEntries(it) }
            }.also { observer = it }
            check(job.start()) { "Onboarding entry observer could not start" }
        }
    }

    internal fun requireFirstRun(observedEpoch: Long) {
        synchronized(lock) {
            if (firstRunEpoch.value == observedEpoch) pendingFirstRun.value = true
        }
    }

    internal fun mount(
        presented: NavigationStateStore.PublishedSnapshot,
        entryId: Long,
    ) {
        val identity = presented.entryIdentity(entryId) ?: return
        retireRemovedEntries(presented)
        val obsolete = synchronized(lock) {
            mounted.value[identity]?.takeUnless {
                it.owner.ownsFlowCoordinator(it.flow) && it.fallbackJob?.isActive != false
            }?.also {
                mounted.value = mounted.value - identity
                beginRetirement(it)
            }
        }
        if (obsolete != null) retire(obsolete)
        val existing = synchronized(lock) { mounted.value[identity] }
        if (existing != null) {
            refreshPresentation(existing, presented)
            return
        }
        synchronized(lock) {
            if (mounted.value.containsKey(identity)) return
            if (failedOwners.isNotEmpty()) {
                unavailable.value = unavailable.value + identity
                return
            }
            if (dependencies !is AppOnboarding) {
                if (mounted.value.values.any { it.owner === dependencies }) {
                    unavailable.value = unavailable.value + identity
                    return
                }
                if (dependencies in retiringOwners) return
            }
            val witness = navigation.captureMountedOnboarding(presented, entryId) ?: return
            val owner = if (dependencies is AppOnboarding) {
                dependencies.createRerunOwner()
            } else {
                dependencies
            }
            val retained = owner.retainedFlowCoordinator()
            if (retained != null && retiredFlows[owner]?.contains(retained) == true) {
                unavailable.value = unavailable.value + identity
                return
            }
            val fallbackScope = if (retained == null) workScope ?: return else null
            val flow = retained ?: OnboardingFlowCoordinator(checkNotNull(fallbackScope), owner).also {
                it.restoreStartingPath()
            }
            unavailable.value = unavailable.value - identity
            mounted.value = mounted.value + (
                identity to MountedEntry(
                    identity, presented.revision, owner, flow, witness, fallbackScope?.coroutineContext?.get(Job),
                )
            )
        }
        retireRemovedEntries(navigation.publishedNavigation.value)
    }

    private fun refreshPresentation(
        entry: MountedEntry,
        presented: NavigationStateStore.PublishedSnapshot,
    ) {
        val previous = entry.witness.get()
        val next = navigation.prepareMountedOnboardingPresentation(previous, presented) ?: return
        val refreshed = entry.flow.refreshCompletionPresentationIfIdle {
            entry.witness.compareAndSet(previous, next)
        }
        if (refreshed) {
            navigation.retireMountedOnboardingPresentation(previous)
            entry.presentationRevision.update { Math.incrementExact(it) }
        }
    }

    internal fun mountFirstRun(presented: NavigationStateStore.PublishedSnapshot, observedEpoch: Long) {
        val obsolete = synchronized(lock) {
            if (firstRunEpoch.value != observedEpoch) return
            firstRun.value?.takeUnless { dependencies.ownsFlowCoordinator(it.flow) }?.also {
                firstRun.value = null
            }
        }
        if (obsolete != null) navigation.retireFirstRunOnboarding(obsolete.witness.get())
        synchronized(lock) {
            if (firstRunEpoch.value != observedEpoch) return
            pendingFirstRun.value = true
            if (firstRun.value != null) return
            val witness = navigation.captureFirstRunOnboarding(presented) ?: return
            val flow = dependencies.retainedFlowCoordinator()
                ?: error("First-run onboarding requires a retained flow coordinator")
            if (!dependencies.ownsFlowCoordinator(flow)) return
            firstRun.value = FirstRunEntry(dependencies, flow, witness)
        }
    }

    internal fun deliver(
        entry: Entry,
        flow: OnboardingFlowCoordinator,
        publication: OnboardingCompletionPublication,
        commit: (() -> Boolean) -> Boolean,
    ): Boolean {
        synchronized(lock) {
            if (flow !== entry.flow || !ownsLocked(entry) || entry.delivering ||
                entry.delivered || entry.recovery.value.restarting
            ) return false
            entry.delivering = true
        }
        val producer = if (publication.attemptId == null) null else when (val value = publication.producer) {
            is OnboardingCompletionProducer.Preset -> MountedOnboardingProducer.Preset(value.originalToken)
            is OnboardingCompletionProducer.NoDevice -> value.sessionAtAttempt?.let {
                MountedOnboardingProducer.NoDeviceAtSession(it)
            } ?: MountedOnboardingProducer.NoDevice
            null -> null
        }
        var attempted = false
        var accepted = false
        var restartAfterDelivery: RestartWitness? = null
        var discardedRestart: RestartWitness? = null
        var restartScope: CoroutineScope? = null
        try {
            accepted = commit {
                attempted = true
                if (producer == null) false else when (entry) {
                    is MountedEntry -> navigation.completeMountedOnboarding(entry.witness.get(), producer)
                    is FirstRunEntry -> navigation.completeFirstRunOnboarding(entry.witness.get(), producer)
                }
            }
        } finally {
            synchronized(lock) {
                entry.delivering = false
                val queued = entry.restartQueued
                if (accepted) {
                    entry.delivered = true
                    if (queued != null) {
                        discardedRestart = queued
                        entry.recovery.update { it.copy(restarting = false) }
                    }
                    if (entry is FirstRunEntry && firstRun.value === entry) {
                        firstRun.value = null
                        firstRunEpoch.value = Math.incrementExact(firstRunEpoch.value)
                        pendingFirstRun.value = false
                    }
                } else if (queued != null && ownsLocked(entry)) {
                    restartAfterDelivery = queued
                    restartScope = workScope
                } else {
                    if (queued != null) {
                        discardedRestart = queued
                        entry.recovery.update { it.copy(restarting = false) }
                    }
                    if (attempted && ownsLocked(entry)) entry.recovery.update { it.copy(failed = true) }
                }
                entry.restartQueued = null
            }
            discardedRestart?.let(::retireRestartWitness)
            restartAfterDelivery?.let { launchRestart(entry, restartScope, it) }
        }
        return accepted
    }

    internal fun retryDelivery(entry: Entry) {
        if (!owns(entry)) return
        entry.recovery.update {
            if (it.restarting) it else it.copy(
                failed = false,
                deliveryRevision = Math.incrementExact(it.deliveryRevision),
            )
        }
    }

    internal fun restart(entry: Entry) {
        synchronized(lock) {
            if (!ownsLocked(entry) || entry.delivered || entry.recovery.value.restarting) return
        }
        val origin = try {
            captureRestartWitness(entry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "Unable to capture an onboarding restart", failure)
            synchronized(lock) {
                if (ownsLocked(entry) && !entry.delivered && !entry.recovery.value.restarting) {
                    entry.recovery.update { it.copy(failed = true) }
                }
            }
            return
        }
        var admitted = false
        var queued = false
        var scope: CoroutineScope? = null
        synchronized(lock) {
            if (ownsLocked(entry) && !entry.delivered && !entry.recovery.value.restarting) {
                admitted = true
                entry.recovery.update { it.copy(restarting = true) }
                if (entry.delivering) {
                    queued = true
                    entry.restartQueued = origin
                } else {
                    scope = workScope
                }
            }
        }
        when {
            !admitted -> retireRestartWitness(origin)
            !queued -> launchRestart(entry, scope, origin)
        }
    }

    private fun captureRestartWitness(entry: Entry): RestartWitness {
        val presented = navigation.publishedNavigation.value
        return when (entry) {
            is MountedEntry -> {
                check(presented.entryIdentity(entry.identity.entryId) == entry.identity) {
                    "Onboarding entry was replaced"
                }
                RestartWitness.Mounted(
                    checkNotNull(navigation.captureMountedOnboarding(presented, entry.identity.entryId)) {
                        "Onboarding entry is no longer presented"
                    },
                )
            }
            is FirstRunEntry -> RestartWitness.FirstRun(
                checkNotNull(navigation.captureFirstRunOnboarding(presented)) {
                    "First-run onboarding is no longer presented"
                },
            )
        }
    }

    private fun retireRestartWitness(witness: RestartWitness) {
        when (witness) {
            is RestartWitness.Mounted -> navigation.retireMountedOnboarding(witness.value)
            is RestartWitness.FirstRun -> navigation.retireFirstRunOnboarding(witness.value)
        }
    }

    private fun launchRestart(entry: Entry, scope: CoroutineScope?, origin: RestartWitness) {
        if (scope == null) {
            retireRestartWitness(origin)
            entry.recovery.update { it.copy(failed = true, restarting = false) }
            logger.warning("Onboarding recovery has no active process owner")
            return
        }
        val entered = AtomicBoolean()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            entered.set(true)
            var installed = false
            try {
                entry.flow.onboarding.resetOnboarding()
                val presented = navigation.publishedNavigation.value
                when {
                    entry is MountedEntry && origin is RestartWitness.Mounted -> {
                        val next = checkNotNull(navigation.prepareMountedOnboardingRestart(origin.value, presented)) {
                            "Onboarding entry is no longer presented"
                        }
                        check(navigation.isOnboardingRestartCurrent(origin.value)) {
                            "Onboarding restart session was replaced"
                        }
                        val previous = if (owns(entry)) swapRestartWitness(entry, entry.witness, next) else null
                        if (previous == null) {
                            check(!owns(entry)) { "Onboarding restart was superseded by an active action" }
                        } else {
                            installed = true
                            navigation.retireMountedOnboardingPresentation(origin.value)
                            if (!owns(entry)) navigation.retireMountedOnboarding(next)
                            navigation.retireMountedOnboarding(previous)
                            if (owns(entry)) entry.presentationRevision.update { Math.incrementExact(it) }
                        }
                    }
                    entry is FirstRunEntry && origin is RestartWitness.FirstRun -> {
                        val next = checkNotNull(navigation.prepareFirstRunOnboardingRestart(origin.value, presented)) {
                            "First-run onboarding is no longer presented"
                        }
                        check(navigation.isOnboardingRestartCurrent(origin.value.authority)) {
                            "Onboarding restart session was replaced"
                        }
                        val previous = if (owns(entry)) swapRestartWitness(entry, entry.witness, next) else null
                        if (previous == null) {
                            check(!owns(entry)) { "Onboarding restart was superseded by an active action" }
                        } else {
                            installed = true
                            navigation.retireMountedOnboardingPresentation(origin.value.authority)
                            if (!owns(entry)) navigation.retireFirstRunOnboarding(next)
                            navigation.retireFirstRunOnboarding(previous)
                        }
                    }
                    else -> error("Onboarding restart witness does not match its entry")
                }
                if (owns(entry)) entry.recovery.update { it.copy(failed = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.log(Level.WARNING, "Unable to restart onboarding completion", failure)
                if (owns(entry)) {
                    entry.recovery.update { it.copy(failed = true) }
                }
            } finally {
                try {
                    if (!installed) retireRestartWitness(origin)
                } finally {
                    entry.recovery.update {
                        it.copy(
                            restarting = false,
                            deliveryRevision = Math.incrementExact(it.deliveryRevision),
                        )
                    }
                }
            }
        }
        job.invokeOnCompletion {
            if (!entered.get()) {
                try {
                    retireRestartWitness(origin)
                } finally {
                    entry.recovery.update { it.copy(failed = true, restarting = false) }
                    logger.warning("Onboarding recovery owner is no longer active")
                }
            }
        }
        job.start()
    }

    private fun <T : Any> swapRestartWitness(
        entry: Entry,
        witness: AtomicReference<T>,
        next: T,
    ): T? {
        var previous: T? = null
        val swapped = entry.flow.refreshCompletionPresentationIfIdle {
            previous = witness.getAndSet(next)
            true
        }
        return if (swapped) checkNotNull(previous) else null
    }

    internal fun cancelMounted(entry: MountedEntry) {
        if (!owns(entry)) return
        val presented = navigation.publishedNavigation.value
        if (presented.entryIdentity(entry.identity.entryId) == entry.identity &&
            presented.value.activeStack.last().id == entry.identity.entryId
        ) {
            navigation.back(presented)
        }
    }

    private fun owns(entry: Entry): Boolean = synchronized(lock) { ownsLocked(entry) }

    private fun ownsLocked(entry: Entry): Boolean = when (entry) {
        is MountedEntry -> mounted.value[entry.identity] === entry
        is FirstRunEntry -> firstRun.value === entry
    }

    private fun retireRemovedEntries(presented: NavigationStateStore.PublishedSnapshot) {
        val live = presented.value.stacks.values.flatten().mapNotNull { presented.entryIdentity(it.id) }.toSet()
        val removed = synchronized(lock) {
            val expired = mounted.value.values.filter {
                it.capturedRevision <= presented.revision && it.identity !in live
            }
            if (expired.isNotEmpty()) {
                mounted.value = mounted.value - expired.map { it.identity }.toSet()
                expired.forEach(::beginRetirement)
            }
            unavailable.value = unavailable.value.intersect(live)
            expired
        }
        removed.forEach(::retire)
    }

    private fun beginRetirement(entry: MountedEntry) {
        if (entry.owner !== dependencies || dependencies is AppOnboarding) return
        check(retiringOwners.add(entry.owner)) { "Onboarding owner is already retiring" }
        retiredFlows.getOrPut(entry.owner) {
            Collections.newSetFromMap(WeakHashMap<OnboardingFlowCoordinator, Boolean>())
        }.add(entry.flow)
    }

    private fun retire(entry: MountedEntry) {
        var finished = false
        try {
            try {
                navigation.retireMountedOnboarding(entry.witness.get())
                entry.owner.onFlowCoordinatorEntryRemoved(entry.flow)
            } finally {
                if (entry.fallbackJob != null) entry.flow.onboarding.retireCompletion()
            }
            finished = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "Unable to retire an onboarding entry", failure)
        } finally {
            synchronized(lock) {
                if (!finished) failedOwners.add(entry.owner)
                if (entry.owner === dependencies && dependencies !is AppOnboarding) {
                    retiringOwners.remove(entry.owner)
                }
                if (!finished || entry.owner === dependencies && dependencies !is AppOnboarding) {
                    revision.value = Math.incrementExact(revision.value)
                }
            }
        }
    }

    private companion object {
        val logger: Logger = Logger.getLogger("com.mc1.OnboardingEntryRetention")
    }
}

@Composable
internal fun OnboardingCompletionRecovery(
    retention: OnboardingEntryRetention,
    entry: OnboardingEntryRetention.Entry,
) {
    val recovery by entry.recovery.collectAsStateWithLifecycle()
    if (!recovery.failed) return
    AlertDialog(
        modifier = Modifier.testTag("onboarding-completion-recovery"),
        onDismissRequest = {
            if (!recovery.restarting && entry is OnboardingEntryRetention.MountedEntry) {
                retention.cancelMounted(entry)
            }
        },
        title = { Text(stringResource(L.commonErrorFailedToLoad)) },
        confirmButton = {
            TextButton(
                onClick = { retention.retryDelivery(entry) },
                enabled = !recovery.restarting,
                modifier = Modifier.testTag("onboarding-completion-retry"),
            ) { Text(stringResource(L.commonTryAgain)) }
        },
        dismissButton = {
            Row {
                if (entry is OnboardingEntryRetention.MountedEntry) {
                    TextButton(
                        onClick = { retention.cancelMounted(entry) },
                        enabled = !recovery.restarting,
                        modifier = Modifier.testTag("onboarding-completion-cancel"),
                    ) { Text(stringResource(L.commonCancel)) }
                }
                TextButton(
                    onClick = { retention.restart(entry) },
                    enabled = !recovery.restarting,
                    modifier = Modifier.testTag("onboarding-completion-restart"),
                ) { Text(stringResource(O.welcomeGetStarted)) }
            }
        },
    )
}
