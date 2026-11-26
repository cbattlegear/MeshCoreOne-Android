// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/ChatRenderState.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotMap
import com.meshcoreone.android.core.model.indexByID
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.snapshotMap
import java.util.UUID

/**
 * Immutable snapshot of the chat timeline as the view sees it. Rebuilt (never mutated in place) by
 * [ChatCoordinator] on every load or mutation.
 */
data class ChatRenderState(
    val items: SnapshotList<MessageItem>,
    /** Message id to row index; built with the core-model `indexByID` (later duplicates win). */
    val itemIndexByID: SnapshotMap<UUID, Long>,
    val hasMoreMessages: Boolean,
    val isLoadingOlder: Boolean,
    val totalFetchedCount: Long,
    val phase: LoadPhase = LoadPhase.UNINITIALIZED,
) {
    /**
     * Distinguishes "not loaded yet" from "loaded and empty": views gate the empty-state placeholder on
     * `phase == LOADED && items.isEmpty()` so an in-flight first fetch never flashes "No messages".
     */
    enum class LoadPhase { UNINITIALIZED, LOADING, LOADED }

    /** Returns a new render state with the supplied fields overridden. */
    fun with(
        items: SnapshotList<MessageItem>? = null,
        itemIndexByID: SnapshotMap<UUID, Long>? = null,
        hasMoreMessages: Boolean? = null,
        isLoadingOlder: Boolean? = null,
        totalFetchedCount: Long? = null,
        phase: LoadPhase? = null,
    ): ChatRenderState = ChatRenderState(
        items = items ?: this.items,
        itemIndexByID = itemIndexByID ?: this.itemIndexByID,
        hasMoreMessages = hasMoreMessages ?: this.hasMoreMessages,
        isLoadingOlder = isLoadingOlder ?: this.isLoadingOlder,
        totalFetchedCount = totalFetchedCount ?: this.totalFetchedCount,
        phase = phase ?: this.phase,
    )

    /** Replaces one item by id. Returns `this` when the id is absent or the transform yields an equal item. */
    fun updatingItem(id: UUID, transform: (MessageItem) -> MessageItem): ChatRenderState {
        val index = itemIndexByID[id]?.toInt() ?: return this
        val updated = transform(items[index])
        if (updated == items[index]) return this
        val newItems = items.mapIndexed { position, item -> if (position == index) updated else item }
        return with(items = newItems.snapshot())
    }

    /** Removes one item by id and rebuilds the index (positions shift). Returns `this` when absent. */
    fun removingItem(id: UUID): ChatRenderState {
        if (itemIndexByID[id] == null) return this
        val newItems = items.filter { it.id != id }
        return with(items = newItems.snapshot(), itemIndexByID = newItems.indexByID { it.id })
    }

    /** Appends one item (caller guarantees the id is new) and bumps [totalFetchedCount]. */
    fun appendingItem(item: MessageItem): ChatRenderState {
        val newItems = items + item
        val newIndex = itemIndexByID + (item.id to (newItems.size - 1).toLong())
        return with(
            items = newItems.snapshot(),
            itemIndexByID = newIndex.snapshotMap(),
            totalFetchedCount = totalFetchedCount + 1,
        )
    }

    companion object {
        val EMPTY: ChatRenderState = ChatRenderState(
            items = SnapshotList.empty(),
            itemIndexByID = emptyMap<UUID, Long>().snapshotMap(),
            hasMoreMessages = true,
            isLoadingOlder = false,
            totalFetchedCount = 0,
            phase = LoadPhase.UNINITIALIZED,
        )
    }
}
