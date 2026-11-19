// AndroidOnly: WP-215 Tests committed notification delivery without a live subscriber.
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReactionNotificationCustodyTest {
    @Test fun queuedOccurrencesSurviveNoCollectorAndOnlyTransientFailuresRetry() = runTest {
        val custody = ReactionNotificationCustody()
        val radio = RadioId(UUID(0, 1))
        val otherRadio = RadioId(UUID(0, 2))
        val message = UUID(0, 3)
        val transient = UUID(0, 4)
        val suppressed = UUID(0, 5)
        custody.retain(EntityKey(radio, transient), message)
        custody.retain(EntityKey(radio, suppressed), message)
        custody.retain(EntityKey(otherRadio, transient), message)

        val attempted = mutableListOf<UUID>()
        backgroundScope.launch {
            custody.collect(radio, isCurrent = { true }) { target, occurrence ->
                assertEquals(message, target)
                val seen = occurrence in attempted
                attempted += occurrence
                when {
                    occurrence == suppressed -> ReactionNotificationOutcome.SUPPRESSED
                    seen -> ReactionNotificationOutcome.POSTED
                    else -> ReactionNotificationOutcome.RETRY
                }
            }
        }
        runCurrent()
        assertEquals(listOf(transient, suppressed), attempted)
        advanceTimeBy(1_000)
        runCurrent()
        custody.retain(EntityKey(radio, suppressed), message)
        custody.retain(EntityKey(radio, transient), message)
        runCurrent()
        assertEquals(listOf(transient, suppressed, transient), attempted)
    }
}
