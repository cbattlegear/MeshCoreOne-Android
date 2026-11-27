// AndroidOnly: WP-101 Prizm Mobile Observer claim prompt, node-type picker and takeover confirmation.
package com.meshcoreone.android.feature.settings.prizm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PrizmClaimPhase {
    /** Nothing in progress. The prompt shows when [PrizmObserverState.claimPending] is true. */
    data object Idle : PrizmClaimPhase
    data object PickType : PrizmClaimPhase
    data class Working(val type: PrizmNodeType) : PrizmClaimPhase
    data class ConfirmTakeover(val type: PrizmNodeType, val ownerLabel: String?) : PrizmClaimPhase
    data class Finished(val result: PrizmClaimResult) : PrizmClaimPhase
}

/** Prompt -> node type -> claim (the port signs with the node) -> optional takeover confirmation -> result. */
class PrizmClaimStateHolder(private val port: PrizmObserverPort, private val scope: CoroutineScope) {
    private val phaseState = MutableStateFlow<PrizmClaimPhase>(PrizmClaimPhase.Idle)
    val phase: StateFlow<PrizmClaimPhase> = phaseState.asStateFlow()

    /** "Claim" on the prompt. */
    fun accept() { if (phaseState.value == PrizmClaimPhase.Idle) phaseState.value = PrizmClaimPhase.PickType }

    /** "Not now": hides the prompt for this connection. */
    fun notNow() { phaseState.value = PrizmClaimPhase.Idle; port.dismissClaimPrompt() }

    fun choose(type: PrizmNodeType) {
        if (phaseState.value != PrizmClaimPhase.PickType) return
        run(type, confirmTakeover = false)
    }

    fun confirmTakeover() {
        val current = phaseState.value as? PrizmClaimPhase.ConfirmTakeover ?: return
        run(current.type, confirmTakeover = true)
    }

    /** Dismisses a takeover question, a result or the picker without claiming. */
    fun dismiss() { phaseState.value = PrizmClaimPhase.Idle }

    private fun run(type: PrizmNodeType, confirmTakeover: Boolean) {
        phaseState.value = PrizmClaimPhase.Working(type)
        scope.launch {
            val result = try {
                port.claim(type, confirmTakeover)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PrizmClaimResult.NetworkFailed(error.message ?: error.javaClass.simpleName)
            }
            phaseState.value = when (result) {
                is PrizmClaimResult.NeedsConfirmation -> PrizmClaimPhase.ConfirmTakeover(type, result.ownerLabel)
                else -> PrizmClaimPhase.Finished(result)
            }
        }
    }
}
