// PortedFrom: MC1Services/Sources/MC1Services/Services/ChatTimelineWriter.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import java.util.UUID

/**
 * Generation-stamped write capability for a [ChatCoordinator] timeline, minted only by
 * [ChatCoordinator.bindWriter]. The coordinator's mutations are module-internal, so a writer is the only
 * way app code mutates a timeline; once a newer writer is bound every forwarder here no-ops, so a stale
 * prime or a superseded view model can never write over the live conversation. Reads are not gated and
 * the window-lane enqueue is not gated (the mutations inside it are).
 */
class ChatTimelineWriter internal constructor(
    private val coordinator: ChatCoordinator,
    private val generation: ULong,
    /** Role this writer was bound with. Diagnostic only; staleness is decided by generation. */
    val role: ChatWriterRole,
) {
    /** Whether this writer still holds write access. */
    val isCurrent: Boolean get() = generation == coordinator.writerGeneration

    /** Debug seam passthrough (Swift `#if DEBUG`). */
    val testPopulateFetchError: Throwable? get() = coordinator.testPopulateFetchError

    /** Debug seam passthrough (Swift `#if DEBUG`). */
    val testPopulateAfterFetchHook: (suspend () -> Unit)? get() = coordinator.testPopulateAfterFetchHook

    /** Runs [operation] on the coordinator's serialized window lane. */
    suspend fun <T> performWindowOperation(operation: suspend () -> T): T = coordinator.performWindowOperation(operation)

    /** Keeps a consumer's DTO, paging and presentation commit in the same writer generation. */
    fun performMutation(operation: () -> Unit) = ifCurrent("performMutation", operation)

    /**
     * Runs [body] only while this writer is current. A dropped prime write is expected teardown noise; a
     * dropped interactive write almost always means a missed rebind, so it is logged as a warning.
     */
    private inline fun ifCurrent(operation: String, crossinline body: () -> Unit) {
        // The staleness check and the write run as one locked step on the coordinator.
        if (coordinator.ifWriterGeneration(generation) { body() } == null) {
            if (role == ChatWriterRole.INTERACTIVE) {
                coordinator.logger.warning("stale interactive writer dropped $operation; missed rebind?")
            } else {
                coordinator.logger.info("stale ${role.name.lowercase()} writer dropped $operation")
            }
        }
    }

    fun replaceAll(newMessages: List<MessageDTO>) = ifCurrent("replaceAll") { coordinator.replaceAll(newMessages) }

    fun beginLoading() = ifCurrent("beginLoading") { coordinator.beginLoading() }

    fun markLoaded() = ifCurrent("markLoaded") { coordinator.markLoaded() }

    fun prepend(older: List<MessageDTO>) = ifCurrent("prepend") { coordinator.prepend(older) }

    /** False when the message was already present, or when this writer is stale and nothing was appended. */
    fun append(message: MessageDTO): Boolean {
        var appended = false
        ifCurrent("append") { appended = coordinator.append(message) }
        return appended
    }

    fun update(messageID: UUID, transform: (MessageDTO) -> MessageDTO) =
        ifCurrent("update") { coordinator.update(messageID, transform) }

    fun remove(messageID: UUID) = ifCurrent("remove") { coordinator.remove(messageID) }

    fun replaceMessagesPreservingByID(reordered: List<MessageDTO>) =
        ifCurrent("replaceMessagesPreservingByID") { coordinator.replaceMessagesPreservingByID(reordered) }

    fun updateRenderState(transform: (ChatRenderState) -> ChatRenderState) =
        ifCurrent("updateRenderState") { coordinator.updateRenderState(transform) }

    fun appendRenderItem(item: MessageItem) = ifCurrent("appendRenderItem") { coordinator.appendRenderItem(item) }

    fun updateRenderItem(id: UUID, transform: (MessageItem) -> MessageItem) =
        ifCurrent("updateRenderItem") { coordinator.updateRenderItem(id, transform) }

    fun removeRenderItem(id: UUID) = ifCurrent("removeRenderItem") { coordinator.removeRenderItem(id) }

    fun applyStatusUpdate(
        messageID: UUID,
        status: MessageStatus,
        roundTripTime: UInt? = null,
        userInitiated: Boolean = false,
    ) = ifCurrent("applyStatusUpdate") { coordinator.applyStatusUpdate(messageID, status, roundTripTime, userInitiated) }

    /**
     * Scheduling a full build is a write: it bumps the generation counter before capturing it
     * (last-scheduled wins), which is exactly why a stale writer must not reach it.
     */
    fun rebuildItems(
        inputs: List<Pair<MessageDTO, MessageBuildInputs>>,
        envInputs: EnvInputs,
        postApply: (() -> Unit)? = null,
    ) = ifCurrent("rebuildItems") { coordinator.rebuildItems(inputs, envInputs, postApply) }

    fun enqueueReload(updatedMessageIDs: Set<UUID>) =
        ifCurrent("enqueueReload") { coordinator.enqueueReload(updatedMessageIDs) }

    fun enqueueReload(messageID: UUID) = ifCurrent("enqueueReload") { coordinator.enqueueReload(messageID) }
}
