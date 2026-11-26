// PortedFrom: MC1/Views/PathEditing/PathManagementViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.path

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.feature.nodes.deps.NodesContactService
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The discovery half of `PathManagementViewModel`: send, wait for the firmware-suggested budget with
 * optional retransmits, count down, and resolve from a push response.
 */
internal class PathDiscoveryRunner(
    private val dependencies: NodesFeatureDependencies,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PathManagementState>,
    private val onContactNeedsRefresh: () -> Unit,
) {
    private var discoveryJob: Job? = null
    private var countdownJob: Job? = null
    private var discoveryStart: Duration? = null
    private var discoveryTimeoutSeconds: Double? = null
    private val clock get() = dependencies.clock

    fun start(contact: ContactDTO, contactService: NodesContactService) {
        discoveryJob?.cancel()
        state.update { it.copy(isDiscovering = true, discoveryResult = null, errorMessage = null) }
        discoveryJob = scope.launch {
            try {
                val sent = contactService.sendPathDiscovery(contact.radioId, contact.publicKey)
                val timeoutSeconds = dependencies.timeouts.pathDiscoverySeconds(sent.suggestedTimeoutMs)
                discoveryTimeoutSeconds = timeoutSeconds
                discoveryStart = clock.elapsed
                state.update { it.copy(discoverySecondsRemaining = timeoutSeconds.toInt()) }
                startCountdown()
                // handleResponse cancels this job on success; a missing hint uses one send for the whole budget.
                waitForResponse(timeoutSeconds, sent.suggestedTimeoutMs, contactService, contact)
                // A response that landed at expiry cancelled this job; do not overwrite it.
                ensureActive()
                state.update { it.copy(discoveryResult = PathDiscoveryResult.NoPathFound, showDiscoveryResult = true) }
            } catch (cancelled: CancellationException) {
                // Cancel, success, or a fresh discovery already owns the state.
                throw cancelled
            } catch (error: Exception) {
                val message = dependencies.messages.message(error)
                state.update { it.copy(discoveryResult = PathDiscoveryResult.Failed(message), showDiscoveryResult = true) }
            }
            state.update { it.copy(isDiscovering = false) }
            cleanupCountdown()
        }
    }

    /** Waits out the budget, resending at the retransmit spacing when the firmware gave a hint. */
    private suspend fun waitForResponse(
        timeoutSeconds: Double,
        suggestedTimeoutMs: UInt,
        contactService: NodesContactService,
        contact: ContactDTO,
    ) {
        val deadline = clock.elapsed + timeoutSeconds.seconds
        val retransmitInterval = dependencies.timeouts.pathDiscoveryRetransmitInterval(suggestedTimeoutMs)
        while (currentCoroutineContext().isActive) {
            val remaining = deadline - clock.elapsed
            if (!remaining.isPositive()) return
            val sleepFor = retransmitInterval?.let { minOf(remaining, it) } ?: remaining
            clock.sleep(sleepFor)
            if (clock.elapsed >= deadline) return
            if (retransmitInterval == null) return
            try {
                contactService.sendPathDiscovery(contact.radioId, contact.publicKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed resend keeps waiting for the remaining budget.
            }
        }
    }

    /** Updates the remaining seconds every five seconds; cancels any prior countdown first. */
    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            while (true) {
                val timeout = discoveryTimeoutSeconds ?: break
                val start = discoveryStart ?: break
                clock.sleep(COUNTDOWN_INTERVAL)
                ensureActive()
                val elapsed = (clock.elapsed - start).inWholeNanoseconds / NANOS_PER_SECOND
                state.update { it.copy(discoverySecondsRemaining = maxOf(0, (timeout - elapsed).toInt())) }
            }
        }
    }

    private fun cleanupCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        discoveryStart = null
        discoveryTimeoutSeconds = null
        state.update { it.copy(discoverySecondsRemaining = null) }
    }

    fun cancel() {
        discoveryJob?.cancel()
        discoveryJob = null
        state.update { it.copy(isDiscovering = false) }
        cleanupCountdown()
    }

    fun handleResponse(hopCount: Int?) {
        if (!state.value.isDiscovering) return
        discoveryJob?.cancel()
        cleanupCountdown()
        val result = hopCount?.let { PathDiscoveryResult.Success(it) } ?: PathDiscoveryResult.NoPathFound
        state.update { it.copy(isDiscovering = false, discoveryResult = result, showDiscoveryResult = true) }
        onContactNeedsRefresh()
    }

    private companion object {
        val COUNTDOWN_INTERVAL = 5.seconds
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

/** Seconds-remaining caption shown under "Discovering path" while positive. */
fun discoveryRemainingCaption(remaining: Int?): NodesMessage? =
    remaining?.takeIf { it > 0 }?.let {
        NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_secondsremaining, it)
    }
