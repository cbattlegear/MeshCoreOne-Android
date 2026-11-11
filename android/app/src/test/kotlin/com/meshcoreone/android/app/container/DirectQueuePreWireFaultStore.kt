// AndroidOnly: WP-208 Inject a definitive pre-wire queue failure while retaining real Room authority.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import java.util.UUID

internal class DirectQueuePreWireFaultStore(
    private val store: RoomPersistenceStore,
    private val onCapture: suspend () -> Nothing,
) : PersistenceStoreProtocol by store,
    DirectAckAuthority by store,
    DirectPendingCompletionAuthority by store,
    DirectQueueAuthority by store,
    DirectMessageCreationAuthority by store,
    ChannelHistoryPersisting by store,
    ChannelDraftRecoveryPersisting by store,
    QueuedChannelTerminalPersisting by store,
    QueuedChannelReadinessPersisting by store,
    InlineChannelAttemptPersisting by store,
    ChannelSubmissionPersisting by store {
    override suspend fun captureDirectQueueIntent(
        selection: DirectQueueSelection, requestId: UUID, mode: DirectAckMode,
    ): DirectAckIntentResult = onCapture()
}
