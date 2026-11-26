// PortedFrom: MC1/Utilities/PingHelper.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.detail

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.feature.nodes.deps.NodesAdvertEvent
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.selects.select

/** Timed result of a zero-hop ping. */
sealed interface PingResult {
    data class Success(val latencyMs: Long, val snrThere: Double, val snrBack: Double) : PingResult
    data class Error(val message: NodesMessage) : PingResult
}

/** Why a ping produced no result. */
sealed class PingFailure(message: String) : Exception(message) {
    class NotConnected : PingFailure("Not connected")
    class Timeout : PingFailure("Ping timed out")
    class NoResponse : PingFailure("Event stream ended without a response")
}

/** Zero-hop trace ping to a contact with its SNR in both directions. */
class PingHelper(
    private val dependencies: NodesFeatureDependencies,
    private val nextTag: () -> UInt = { Random.nextUInt(0u, UInt.MAX_VALUE) },
) {
    /** Sends the trace, waits for the matching SNR observation, and announces the outcome. */
    suspend fun zeroHopPing(contact: ContactDTO): PingResult {
        val session = dependencies.session
        val clock = dependencies.clock
        val start = clock.elapsed
        val tag = nextTag()
        return try {
            val traceService = session.traceService() ?: throw PingFailure.NotConnected()
            val advertisementService = session.advertisementService() ?: throw PingFailure.NotConnected()
            val device = session.connectedDevice()
            val path = contact.publicKey.prefix((device?.traceHashSize ?: 1L).toInt())
            // Subscribe before sending: registration is synchronous, so a fast response cannot be missed.
            val subscription = advertisementService.events()
            val (snrThere, snrBack) = subscription.use {
                coroutineScope {
                    val response = async {
                        val observed = subscription.events.filterIsInstance<NodesAdvertEvent.TraceSnrObserved>().firstOrNull { it.tag == tag }
                            ?: throw PingFailure.NoResponse()
                        (observed.remoteSnr ?: 0.0) to observed.localSnr
                    }
                    try {
                        val sent = traceService.sendTrace(tag, device?.pathHashMode ?: 0u, path)
                        val timeout = async { clock.sleep(dependencies.timeouts.zeroHopSeconds(sent.suggestedTimeoutMs).seconds) }
                        select {
                            response.onAwait { timeout.cancel(); it }
                            timeout.onAwait { throw PingFailure.Timeout() }
                        }
                    } finally {
                        response.cancel()
                    }
                }
            }
            val latencyMs = (clock.elapsed - start).inWholeMilliseconds
            stampHeard(contact)
            dependencies.announcer.announce(NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_pingsuccessannouncement, latencyMs))
            PingResult.Success(latencyMs, snrThere, snrBack)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            dependencies.announcer.announce(NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_pingfailureannouncement))
            PingResult.Error(NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_pingnoresponse))
        }
    }

    /** Best-effort last-heard stamp; a failure never fails the ping. */
    private suspend fun stampHeard(contact: ContactDTO) {
        val session = dependencies.session
        val radioId = session.connectedDevice()?.radioId ?: return
        try {
            session.servicesDataStore()?.touchContactHeard(radioId, contact.publicKey, dependencies.clock.wallNow)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The source only logs this failure.
        }
    }
}
