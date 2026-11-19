// AndroidOnly: WP-201 New journal contracts must fail loudly in the rendering-only WP-213 fixture.
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.QueuedChannelAttemptKey
import com.meshcoreone.android.core.model.QueuedChannelAttemptOutcome
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RenderingInMemoryJournalRejectionTest {
    @Test fun everyJournalOperationRejectsInsteadOfPretendingToPersistAnAttempt() = runTest {
        val store = RenderingInMemoryMessageStore()
        val key = QueuedChannelAttemptKey(
            RadioId(UUID.randomUUID()), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        )
        assertFailsWith<UnsupportedOperationException> {
            store.beginQueuedChannelAttempt(key, UUID.randomUUID(), 1u)
        }
        assertFailsWith<UnsupportedOperationException> {
            store.settleQueuedChannelAttempt(key, QueuedChannelAttemptOutcome.ACCEPTED)
        }
        assertFailsWith<UnsupportedOperationException> { store.completeAcceptedQueuedChannelAttempt(key) }
        assertFailsWith<UnsupportedOperationException> { store.fetchQueuedChannelAttempt(key) }
        assertFailsWith<UnsupportedOperationException> {
            store.fetchQueuedChannelAttempts(EntityKey(key.radioId, key.pendingID))
        }
    }
}
