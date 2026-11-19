// AndroidOnly: WP-217 Resolve conflicting simulator device snapshots through an explicit user choice.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.data.repository.SimulatorDevicePreferenceConflict
import com.meshcoreone.android.core.data.repository.SimulatorPreferenceChoice
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.core.ui.UiText
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SimulatorPreferenceRecoveryCancelled : CancellationException("Simulator preference recovery cancelled")

internal data class SimulatorPreferenceRecoveryPrompt(
    val id: UUID,
    val conflict: SimulatorDevicePreferenceConflict,
    val isApplying: Boolean = false,
    val failure: UiText? = null,
)

internal class SimulatorPreferenceRecovery(
    private val load: suspend (DeviceDTO) -> SimulatorDevicePreferenceConflict?,
    private val resolve: suspend (DeviceDTO, SimulatorDevicePreferenceConflict, SimulatorPreferenceChoice) -> Unit,
) {
    constructor(store: RoomPersistenceStore) : this(
        store::simulatorDevicePreferenceConflict,
        store::resolveSimulatorDevicePreferenceConflict,
    )

    private data class Request(
        val id: UUID,
        val answer: CompletableDeferred<SimulatorPreferenceChoice?>,
    )

    private val lock = Any()
    private val mutex = Mutex()
    private val mutablePrompt = MutableStateFlow<SimulatorPreferenceRecoveryPrompt?>(null)
    val prompt: StateFlow<SimulatorPreferenceRecoveryPrompt?> = mutablePrompt.asStateFlow()
    private var request: Request? = null
    private var revision = 0L
    private var closed = false

    suspend fun seed(device: DeviceDTO, action: suspend () -> Unit) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val observed = synchronized(lock) {
                checkOpen()
                revision
            }
            try {
                action()
                currentCoroutineContext().ensureActive()
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: PersistenceStoreException) {
                if (failure.error != PersistenceStoreError.InvalidData) throw failure
                mutex.withLock { recover(device, failure, observed) }
            }
        }
    }

    fun choose(id: UUID, choice: SimulatorPreferenceChoice): Boolean = respond(id, choice)

    fun cancel(id: UUID): Boolean = respond(id, null)

    private fun respond(id: UUID, choice: SimulatorPreferenceChoice?): Boolean {
        val answer = synchronized(lock) {
            val pending = request?.takeIf { it.id == id } ?: return false
            request = null
            if (choice != null) {
                mutablePrompt.value = mutablePrompt.value?.takeIf { it.id == id }?.copy(isApplying = true)
            }
            pending.answer
        }
        return answer.complete(choice)
    }

    private suspend fun recover(device: DeviceDTO, original: PersistenceStoreException, observed: Long) {
        synchronized(lock) { checkOpen() }
        var conflict = load(device) ?: run {
            if (synchronized(lock) { revision != observed }) return
            throw original
        }
        var failure: UiText? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val pending = Request(UUID.randomUUID(), CompletableDeferred())
            synchronized(lock) {
                checkOpen()
                request = pending
                mutablePrompt.value = SimulatorPreferenceRecoveryPrompt(pending.id, conflict, failure = failure)
            }
            try {
                val choice = pending.answer.await() ?: throw SimulatorPreferenceRecoveryCancelled()
                currentCoroutineContext().ensureActive()
                try {
                    resolve(device, conflict, choice)
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        checkOpen()
                        revision = Math.incrementExact(revision)
                    }
                    return
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (problem: PersistenceStoreException) {
                    val refreshed = try {
                        load(device)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (refreshFailure: Exception) {
                        if (refreshFailure !== problem) refreshFailure.addSuppressed(problem)
                        throw refreshFailure
                    }
                    conflict = refreshed ?: throw problem
                    failure = UiErrorMapper().message(problem)
                }
            } finally {
                synchronized(lock) {
                    if (request === pending) request = null
                    if (mutablePrompt.value?.id == pending.id) mutablePrompt.value = null
                }
                pending.answer.cancel()
            }
        }
    }

    fun close() {
        val pending = synchronized(lock) {
            closed = true
            mutablePrompt.value = null
            request.also { request = null }
        }
        pending?.answer?.cancel()
    }

    private fun checkOpen() {
        if (closed) throw CancellationException("Simulator preference recovery is closed")
    }
}
