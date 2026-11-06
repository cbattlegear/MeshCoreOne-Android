// AndroidOnly: WP-201 Historical channel inspection value/shape controls, not storage proof.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TextType
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ChannelMessageInspectionAuthorityTest {
    private val key = EntityKey(RadioId(UUID(0, 1)), UUID(0, 2))
    private val origin = ChannelHistoryScope(key.radioId, 0u, UUID(0, 3))
    private val creation = ChannelMessageCreation(key.id, origin, "original channel text", TextType.PLAIN)
    private val createdAt = Instant.ofEpochSecond(42, 123_456_789)
    private val message = MessageDTO(
        id = key.id,
        radioId = key.radioId,
        channelIndex = origin.channelIndex,
        text = creation.text,
        textType = creation.textType,
        timestamp = 42u,
        createdAt = createdAt,
    )
    private val observation = ChannelMessageInspectionReceipt(
        key, origin, ChannelMessageStoredState.ORIGINAL,
        ChannelMessageOriginCurrentness.UNVERIFIED, message,
    )
    private val exact = ChannelMessageCreationInspectionReceipt(creation, 42u, createdAt, observation)

    @Test
    fun exactCommittedHistoryBindsOriginalIntentAndNanosecondTimestamp() {
        val result = ChannelMessageCreationObservation.Committed(exact)

        assertEquals(creation, result.receipt.creation)
        assertEquals(42u, result.receipt.timestamp)
        assertEquals(42L, result.receipt.createdAt.epochSecond)
        assertEquals(123_456_789, result.receipt.createdAt.nano)
        assertEquals(key, result.receipt.observation.key)
        assertEquals(origin, result.receipt.observation.origin)
    }

    @Test
    fun committedCreationSurvivesMissingInvalidatedOrUnverifiedCanonicalPayload() {
        for (state in listOf(
            ChannelMessageStoredState.MISSING,
            ChannelMessageStoredState.INVALIDATED,
            ChannelMessageStoredState.UNVERIFIED,
        )) {
            val historical = observation.copy(storedState = state, canonicalMessage = null)
            val result = ChannelMessageCreationObservation.Committed(exact.copy(observation = historical))

            assertEquals(creation, result.receipt.creation)
            assertEquals(createdAt, result.receipt.createdAt)
            assertEquals(state, result.receipt.observation.storedState)
            assertNull(result.receipt.observation.canonicalMessage)
        }
    }

    @Test
    fun readableOriginalPayloadDoesNotImplyCurrentOccupantAuthority() {
        for (currentness in ChannelMessageOriginCurrentness.entries) {
            val result = ChannelMessageOriginObservation.Found(
                observation.copy(originCurrentness = currentness),
            )

            assertEquals(ChannelMessageStoredState.ORIGINAL, result.receipt.storedState)
            assertEquals(message, result.receipt.canonicalMessage)
            assertEquals(currentness, result.receipt.originCurrentness)
        }
    }

    @Test
    fun retiredOriginalScopeNeverBecomesReplacementScopeInHistory() {
        val replacement = origin.copy(expectedOccupantID = UUID(0, 4))
        val retired = observation.copy(
            originCurrentness = ChannelMessageOriginCurrentness.RETIRED,
        )

        assertEquals(origin, retired.origin)
        assertNotEquals(replacement, retired.origin)
        assertEquals(message, retired.canonicalMessage)
        assertNotEquals(retired, retired.copy(origin = replacement))
    }

    @Test
    fun conflictPreservesKnownDifferentCreationWithoutInventingOriginalPayload() {
        val unknownOriginal = ChannelMessageCreationObservation.Conflict(key, null)
        val knownOriginal = ChannelMessageCreationObservation.Conflict(
            key,
            observation.copy(
                storedState = ChannelMessageStoredState.INVALIDATED, canonicalMessage = null,
            ),
        )

        assertEquals(key, unknownOriginal.key)
        assertNull(unknownOriginal.original)
        assertEquals(origin, knownOriginal.original?.origin)
        assertNull(knownOriginal.original?.canonicalMessage)
        assertNotEquals<ChannelMessageCreationObservation>(
            ChannelMessageCreationObservation.Committed(exact), knownOriginal,
        )
    }

    @Test
    fun notRecordedIsDistinctFromKnownCommitAndKnownConflict() {
        val missing = ChannelMessageCreationObservation.NotRecorded(key)

        assertEquals(key, missing.key)
        assertEquals(key, ChannelMessageOriginObservation.NotRecorded(key).key)
        assertNotEquals<ChannelMessageCreationObservation>(
            ChannelMessageCreationObservation.Committed(exact), missing,
        )
        assertNotEquals<ChannelMessageCreationObservation>(
            ChannelMessageCreationObservation.Conflict(key, null), missing,
        )
    }

    @Test
    fun exactIntentSeparatesAllCallerFieldsAndFullUIntTimestamp() {
        for (timestamp in listOf(0u, 42u, UInt.MAX_VALUE)) {
            assertEquals(timestamp, exact.copy(timestamp = timestamp).timestamp)
        }
        assertNotEquals(exact, exact.copy(createdAt = createdAt.plusNanos(1)))
        assertNotEquals(exact, exact.copy(timestamp = 43u))
        assertNotEquals(exact, exact.copy(creation = creation.copy(text = "different")))
        assertNotEquals(exact, exact.copy(creation = creation.copy(textType = TextType.SIGNED_PLAIN)))
        assertNotEquals(exact, exact.copy(creation = creation.copy(messageID = UUID(0, 5))))
        assertNotEquals(exact, exact.copy(creation = creation.copy(origin = origin.copy(channelIndex = 1u))))
        assertNotEquals(exact, exact.copy(creation = creation.copy(
            origin = origin.copy(expectedOccupantID = UUID(0, 6)),
        )))
    }

    @Test
    fun radioAndMessageIdentityRemainExplicitAcrossHistory() {
        val otherRadio = key.copy(radioId = RadioId(UUID(0, 7)))
        val otherMessage = key.copy(id = UUID(0, 8))

        assertNotEquals(observation, observation.copy(key = otherRadio))
        assertNotEquals(observation, observation.copy(key = otherMessage))
        assertEquals(key, exact.observation.key)
        assertEquals(key.radioId, exact.creation.origin.radioId)
    }

    @Test
    fun originAndCreationQueriesRetainTypedRejections() {
        for (reason in DirectAckRejection.entries) {
            assertEquals(reason, ChannelMessageOriginObservation.Rejected(reason).reason)
            assertEquals(reason, ChannelMessageCreationObservation.Rejected(reason).reason)
        }
    }

    @Test
    fun bothInspectionMethodsAreSuspendingReadsWithoutLeaseOrHandleParameters() {
        val methods = ChannelMessageInspectionAuthority::class.java.declaredMethods
        val originRead = methods.single { it.name.substringBefore('-') == "inspectChannelMessageOrigin" }
        val creationRead = methods.single { it.name.substringBefore('-') == "inspectChannelMessageCreation" }

        assertEquals(2, originRead.parameterCount)
        assertEquals(EntityKey::class.java, originRead.parameterTypes.first())
        assertEquals(4, creationRead.parameterCount)
        assertEquals(ChannelMessageCreation::class.java, creationRead.parameterTypes.first())
        for (method in listOf(originRead, creationRead)) {
            assertEquals("kotlin.coroutines.Continuation", method.parameterTypes.last().name)
            assertEquals(false, method.parameterTypes.any {
                it == DirectAckLease::class.java || it == ChannelFailureAttempt::class.java
            })
        }
    }
}
