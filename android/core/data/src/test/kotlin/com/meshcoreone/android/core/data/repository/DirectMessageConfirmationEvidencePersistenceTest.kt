// AndroidOnly: WP-202 Real Room current-incarnation diagnostics and strict physical prehistory.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.data.backup.AppBackupCodec
import com.meshcoreone.android.core.data.backup.AppBackupService
import com.meshcoreone.android.core.data.backup.BackupImportHooks
import com.meshcoreone.android.core.data.backup.envelope
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.*
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DirectMessageConfirmationEvidencePersistenceTest : RepositoryTest() {
    private data class Claim(val lease: DirectAckLease, val id: UUID, val wire: UUID, val attempt: DirectAckAttempt)
    private val epoch = ProcessEpoch(UUID.randomUUID())
    private var generation = 0L

    private suspend fun activate(target: RoomPersistenceStore = store) =
        assertIs<DirectAckActivationResult.Activated>(target.activateDirectAckSession(
            SessionToken(epoch, Generation(++generation), RADIO_A), UUID.randomUUID(),
        )).lease

    private suspend fun begin(
        row: MessageDTO,
        lease: DirectAckLease,
        target: RoomPersistenceStore = store,
        save: Boolean = true,
        mode: DirectAckMode = DirectAckMode.SEND,
    ): Claim {
        if (save) target.saveMessage(row)
        val id = UUID.randomUUID()
        val wire = UUID.randomUUID()
        val intent = assertIs<DirectAckIntentResult.Captured>(target.captureDirectAckIntent(
            lease, EntityKey(RADIO_A, row.id), id, mode, null,
        )).intent
        val attempt = assertIs<DirectAckBeginResult.Begun>(
            target.beginDirectAckAttemptRequiringEmissionTracking(intent, wire, 123u),
        ).attempt
        return Claim(lease, id, wire, attempt)
    }

    private suspend fun mark(claim: Claim, target: RoomPersistenceStore = store) =
        assertIs<DirectAckEmissionMarkResult.Marked>(
            target.markDirectAckWireMayHaveWritten(claim.attempt, claim.wire, UUID.randomUUID()),
        )

    private suspend fun accept(claim: Claim, actual: UInt, target: RoomPersistenceStore = store) {
        val marker = mark(claim, target)
        assertIs<DirectAckEmissionMutationResult.Applied>(target.resolveDirectAckWireEmission(
            marker.handle, UUID.randomUUID(), DirectAckEmissionResolution.Accepted(actual),
        ))
    }

    private suspend fun present(row: MessageDTO, target: RoomPersistenceStore = store) =
        assertIs<DirectMessageConfirmationEvidence.Present>(
            target.inspectDirectMessageConfirmationEvidence(EntityKey(row.radioId, row.id)),
        ).also {
            assertEquals(row.status, it.status)
            assertTrue(DirectMessageConfirmationUncertaintyReason.TARGET_PROVENANCE_UNPROVEN in it.reasons)
            assertTrue(DirectMessageConfirmationUncertaintyReason.PREAUTHORITY_HISTORY_UNKNOWN in it.reasons)
        }

    private suspend fun unknown(target: RoomPersistenceStore, radio: RadioId) {
        val evidence = assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(
            target.inspectDirectAckRadioConfirmationEvidence(radio),
        )
        assertEquals(radio, evidence.radioId)
        assertTrue(DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN in evidence.reasons)
        assertTrue(assertIs<DirectAckRadioEvidenceObservation.Found>(
            target.inspectDirectAckRadioEvidence(radio),
        ).snapshot.preauthorityHistoryUnknown)
    }

    @Test fun emptyFreshStoreAndUnknownRadioRetainDurableUncertaintyWithoutFabricatedPhysicalRows() = runTest {
        assertEquals(listOf(DirectAckPrehistoryEntity(1, "UNKNOWN")), db.directAckPrehistory().rows())
        for (radio in listOf(RADIO_A, RADIO_B, RadioId(UUID.randomUUID()))) {
            unknown(store, radio)
            val diagnostic = assertIs<DirectAckRadioEvidenceObservation.Found>(
                store.inspectDirectAckRadioEvidence(radio),
            ).snapshot
            assertTrue(diagnostic.wires.isEmpty())
            assertEquals(DirectAckRadioEvidenceCoverage.COMPLETE, diagnostic.coverage)
            assertEquals(0L, db.directAckExclusions().count(radio.value))
            val events = store.observeDirectAckRadioConfirmationEvidence(radio).take(2).toList()
            assertEquals(DirectAckRadioConfirmationObservation.Inspecting(radio), events.first())
            assertTrue(DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN in
                assertIs<DirectAckRadioConfirmationEvidence.PersistedWildcard>(
                    assertIs<DirectAckRadioConfirmationObservation.Snapshot>(events.last()).evidence,
                ).reasons)
        }
        assertTrue(issues.isEmpty())
    }

    @Test fun newUnmarkedAllUnsentAndAcceptedTargetsCannotFailOrSettleDespiteCompleteRetainedRows() = runTest {
        val lease = activate()
        for (kind in listOf("UNMARKED", "UNSENT", "ACCEPTED")) {
            val row = message(status = MessageStatus.PENDING)
            val claim = begin(row, lease)
            when (kind) {
                "UNSENT" -> {
                    val marker = mark(claim)
                    assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
                        marker.handle, UUID.randomUUID(), DirectAckEmissionResolution.DefinitelyUnsent,
                    ))
                }
                "ACCEPTED" -> accept(claim, 999u)
            }
            val identity = db.directAcks().incarnation(RADIO_A.value, row.id, "message")
            val before = db.directAcks().operations(RADIO_A.value, claim.id)
            val failure = assertIs<DirectAckRadioFailureResult.Nonfailing>(
                store.recordDirectFailureExcludingRadioHistory(claim.attempt),
            )
            assertTrue(DirectAckRadioNonfailingReason.PREAUTHORITY_HISTORY_UNKNOWN in failure.reasons)
            val settlement = assertIs<DirectAckRadioSettlementResult.Nonsettling>(
                store.settleDirectAckExcludingRadioHistory(claim.attempt, 999u, 42u),
            )
            assertTrue(DirectAckRadioNonsettlingReason.PREAUTHORITY_HISTORY_UNKNOWN in settlement.reasons)
            assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertEquals(identity, db.directAcks().incarnation(RADIO_A.value, row.id, "message"))
            assertEquals(before, db.directAcks().operations(RADIO_A.value, claim.id))
            assertNull(db.directAcks().delivery(RADIO_A.value, claim.id))
            assertTrue(db.directAcks().radioFailures(RADIO_A.value, claim.id).isEmpty())
        }
    }

    @Test fun olderSameIncarnationMayBlocksUnmarkedAndUnsentNewClaimsEvenWithInheritedGenericFailed() = runTest {
        for (inheritedFailure in listOf(false, true)) for (newUnsent in listOf(false, true)) {
            val row = message(status = MessageStatus.PENDING)
            val old = begin(row, activate())
            val marker = mark(old)
            val born = assertNotNull(db.directAcks().incarnation(RADIO_A.value, row.id, "message"))
            if (inheritedFailure) assertIs<DirectAckMutationResult.Applied>(store.recordDirectFailure(old.attempt))
            val actual = assertNotNull(store.fetchMessage(EntityKey(RADIO_A, row.id)))
            val next = begin(actual, activate(), save = false,
                mode = if (inheritedFailure) DirectAckMode.RESEND else DirectAckMode.SEND)
            if (newUnsent) {
                val unsent = mark(next)
                assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
                    unsent.handle, UUID.randomUUID(), DirectAckEmissionResolution.DefinitelyUnsent,
                ))
            }
            assertEquals(born.incarnation, db.directAcks().claim(RADIO_A.value, next.id)?.incarnation)
            assertEquals(born.incarnation, db.directAcks().claim(RADIO_A.value, old.id)?.incarnation)
            assertTrue(db.directAckExclusions().failureEvidence(RADIO_A.value, next.id).sameIncarnationOtherUncertainty)
            val before = db.directAcks().operations(RADIO_A.value, next.id)
            val identity = db.directAcks().incarnation(RADIO_A.value, row.id, "message")
            val failure = assertIs<DirectAckRadioFailureResult.Nonfailing>(
                store.recordDirectFailureExcludingRadioHistory(next.attempt),
            )
            assertTrue(DirectAckRadioNonfailingReason.UNRESOLVED_OTHER_CLAIM_EMISSION in failure.reasons)
            assertTrue(DirectAckRadioNonfailingReason.PREAUTHORITY_HISTORY_UNKNOWN in failure.reasons)
            assertEquals(actual, store.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertEquals(identity, db.directAcks().incarnation(RADIO_A.value, row.id, "message"))
            assertEquals(before, db.directAcks().operations(RADIO_A.value, next.id))
            assertTrue(db.directAcks().radioFailures(RADIO_A.value, next.id).isEmpty())
            assertEquals("MAY_HAVE_WRITTEN", db.directAckExclusions().emission(
                RADIO_A.value, old.id, old.wire, marker.receipt.emissionRequestId,
            )?.state)
        }
    }

    @Test fun sameIncarnationAcceptedActualOpensForeignChecksWithoutCollidingWithItsOwnSourceClaim() = runTest {
        var actual = 900u
        for (foreignKind in listOf("NONE", "MAY", "KNOWN")) for (newUnsent in listOf(false, true)) {
            actual++
            val row = message(status = MessageStatus.PENDING)
            val old = begin(row, activate())
            accept(old, actual)
            val nextLease = activate()
            val current = begin(row, nextLease, save = false)
            if (newUnsent) {
                val marker = mark(current)
                assertIs<DirectAckEmissionMutationResult.Applied>(store.resolveDirectAckWireEmission(
                    marker.handle, UUID.randomUUID(), DirectAckEmissionResolution.DefinitelyUnsent,
                ))
            }
            val beforeForeign = db.directAckExclusions().failureEvidence(RADIO_A.value, current.id)
            assertTrue(beforeForeign.sameIncarnationOtherAccepted)
            if (foreignKind == "NONE") {
                assertFalse(beforeForeign.otherKnownCode)
                assertFalse(beforeForeign.otherUnresolved)
                assertFalse(beforeForeign.sameIncarnationOtherUncertainty)
            } else {
                val foreign = begin(message(contactID = UUID.randomUUID()), nextLease)
                if (foreignKind == "MAY") mark(foreign) else accept(foreign, actual)
            }
            val before = db.directAcks().operations(RADIO_A.value, current.id)
            val identity = db.directAcks().incarnation(RADIO_A.value, row.id, "message")
            val outcome = assertIs<DirectAckRadioFailureResult.Nonfailing>(
                store.recordDirectFailureExcludingRadioHistory(current.attempt),
            )
            assertTrue(DirectAckRadioNonfailingReason.PREAUTHORITY_HISTORY_UNKNOWN in outcome.reasons)
            if (foreignKind == "MAY") {
                assertTrue(DirectAckRadioNonfailingReason.UNRESOLVED_OTHER_CLAIM_EMISSION in outcome.reasons)
            }
            if (foreignKind == "KNOWN") {
                assertTrue(DirectAckRadioNonfailingReason.KNOWN_OTHER_CLAIM_CODE in outcome.reasons)
            }
            assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
            assertEquals(identity, db.directAcks().incarnation(RADIO_A.value, row.id, "message"))
            assertEquals(before, db.directAcks().operations(RADIO_A.value, current.id))
            assertTrue(db.directAcks().radioFailures(RADIO_A.value, current.id).isEmpty())
        }
    }

    @Test fun diagnosticMissingIneligibleAndIdenticalReplacementPreserveLiteralActualIdentity() = runTest {
        val row = message()
        val key = EntityKey(RADIO_A, row.id)
        assertEquals(DirectMessageConfirmationEvidence.Missing(key), store.inspectDirectMessageConfirmationEvidence(key))
        store.saveMessage(row)
        val first = present(row)
        assertEquals(db.directAcks().incarnation(RADIO_A.value, row.id, "message")?.incarnation, first.identity.incarnationId)
        store.deleteMessage(key)
        store.saveMessage(row)
        assertEquals(row, store.fetchMessage(key))
        val replacement = present(row)
        assertNotEquals(first.identity.incarnationId, replacement.identity.incarnationId)
        store.saveMessage(message(id = row.id, contactID = null, channelIndex = 0u))
        assertEquals(DirectMessageConfirmationEvidence.NotEligible(key), store.inspectDirectMessageConfirmationEvidence(key))
        assertEquals(DirectMessageConfirmationEvidence.Missing(EntityKey(RADIO_B, row.id)),
            store.inspectDirectMessageConfirmationEvidence(EntityKey(RADIO_B, row.id)))
    }

    @Test fun equalLookingReplacementDoesNotAdoptTheDeletedIncarnationsMayAsWholeMessageAncestry() = runTest {
        val row = message(status = MessageStatus.PENDING)
        val old = begin(row, activate())
        mark(old)
        val previous = assertNotNull(db.directAcks().incarnation(RADIO_A.value, row.id, "message"))
        store.deleteMessage(EntityKey(RADIO_A, row.id))
        store.saveMessage(row)
        val current = begin(row, activate(), save = false)
        val replacement = assertNotNull(db.directAcks().incarnation(RADIO_A.value, row.id, "message"))
        assertNotEquals(previous.incarnation, replacement.incarnation)
        val history = db.directAckExclusions().failureEvidence(RADIO_A.value, current.id)
        assertTrue(history.otherUnresolved)
        assertFalse(history.targetAccepted)
        assertFalse(history.sameIncarnationOtherUncertainty)
        assertFalse(history.sameIncarnationOtherAccepted)
        val evidence = present(row)
        assertTrue(DirectMessageConfirmationUncertaintyReason.UNRESOLVED_OTHER_CLAIM_EMISSION in evidence.reasons)
        assertFalse(DirectMessageConfirmationUncertaintyReason.TARGET_UNRESOLVED_PHYSICAL_EMISSION in evidence.reasons)
        val failure = assertIs<DirectAckRadioFailureResult.Nonfailing>(
            store.recordDirectFailureExcludingRadioHistory(current.attempt),
        )
        assertTrue(DirectAckRadioNonfailingReason.PREAUTHORITY_HISTORY_UNKNOWN in failure.reasons)
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
    }

    @Test fun fileReopenRetainsDiagnosticIdentity() = runTest {
        val name = "confirmation-current-identity-${UUID.randomUUID()}.db"
        var file = MeshCoreDatabase.open(context, name)
        var target = RoomPersistenceStore(file, owner, clock, RepositoryIssueReporter { _, error -> issues += error })
        try {
            val lease = activate(target)
            val row = message()
            val current = begin(row, lease, target)
            accept(current, 999u, target)
            val foreign = begin(message(contactID = UUID.randomUUID()), lease, target)
            accept(foreign, 999u, target)
            val before = present(row, target)
            assertTrue(DirectMessageConfirmationUncertaintyReason.KNOWN_OTHER_CLAIM_CODE in before.reasons)
            target.close()
            file.close()
            file = MeshCoreDatabase.open(context, name)
            target = RoomPersistenceStore(file, owner, clock, RepositoryIssueReporter { _, error -> issues += error })
            assertEquals(before, present(row, target))
            assertNull(file.directAcks().claim(RADIO_A.value, current.id)?.snapshot)
            assertEquals(DirectAckRejection.INVALID_HANDLE, assertIs<DirectAckRadioFailureResult.Rejected>(
                target.recordDirectFailureExcludingRadioHistory(current.attempt),
            ).reason)
            unknown(target, RADIO_A)
            assertEquals(row, target.fetchMessage(EntityKey(RADIO_A, row.id)))
        } finally { target.close(); file.close(); context.deleteDatabase(name) }
    }

    @Test fun retainedGeneric999Physical888HistoryAndDifferentContactCollisionRemainVisibleWithoutPublicReceiptLeakage() = runTest {
        val lease = activate()
        val oldRow = message()
        val old = begin(oldRow, lease)
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(old.attempt, old.wire, 999u))
        accept(old, 888u)
        val currentRow = message(contactID = UUID.randomUUID())
        val current = begin(currentRow, lease)
        accept(current, 999u)
        val evidence = present(currentRow)
        assertTrue(DirectMessageConfirmationUncertaintyReason.KNOWN_OTHER_CLAIM_CODE in evidence.reasons)
        assertTrue(DirectMessageConfirmationUncertaintyReason.CONFLICTING_OTHER_CLAIM_EVIDENCE in evidence.reasons)
        val oldEvidence = present(oldRow)
        assertTrue(DirectMessageConfirmationUncertaintyReason.TARGET_CONFLICTING_PHYSICAL_EVIDENCE in oldEvidence.reasons)
        assertTrue(DirectMessageConfirmationUncertaintyReason.KNOWN_OTHER_CLAIM_CODE in oldEvidence.reasons)
        store.deleteMessage(EntityKey(RADIO_A, oldRow.id))
        assertEquals(evidence, present(currentRow))
        val receipt = assertIs<DirectAckObservation.Found>(store.inspectDirectAckAttempt(lease, current.id)).receipt
        assertTrue(receipt.bookkeeping.isEmpty())
        assertTrue(receipt.pendingCompletions.isEmpty())
        assertTrue(db.directAcks().receiptOperations(RADIO_A.value, current.id).isEmpty())
        val failure = assertIs<DirectAckRadioFailureResult.Nonfailing>(
            store.recordDirectFailureExcludingRadioHistory(current.attempt),
        )
        assertTrue(DirectAckRadioNonfailingReason.KNOWN_OTHER_CLAIM_CODE in failure.reasons)
        assertTrue(DirectAckRadioNonfailingReason.PREAUTHORITY_HISTORY_UNKNOWN in failure.reasons)
    }

    @Test fun genericDeliveredRemainsDeliveredAndDiagnosticUnprovenAfterSameMessageClaimsAndMutation() = runTest {
        val row = message()
        val lease = activate()
        val old = begin(row, lease)
        accept(old, 999u)
        val current = begin(row, lease, save = false)
        accept(current, 999u)
        assertTrue(DirectMessageConfirmationUncertaintyReason.KNOWN_OTHER_CLAIM_CODE in present(row).reasons)
        assertIs<DirectAckMutationResult.Applied>(store.settleDirectAck(current.attempt, 999u, 42u))
        val delivered = assertNotNull(store.fetchMessage(EntityKey(RADIO_A, row.id)))
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(999u, delivered.ackCode)
        assertEquals(42u, delivered.roundTripTime)
        val evidence = present(delivered)
        assertTrue(DirectMessageConfirmationUncertaintyReason.KNOWN_OTHER_CLAIM_CODE in evidence.reasons)
        assertEquals(evidence, present(delivered))
        assertIs<DirectAckRadioFailureResult.AlreadyDelivered>(
            store.recordDirectFailureExcludingRadioHistory(current.attempt),
        )
        assertEquals(DirectAckRejection.CONFLICT, assertIs<DirectAckRadioSettlementResult.Rejected>(
            store.settleDirectAckExcludingRadioHistory(current.attempt, 999u, 42u),
        ).reason)
        assertEquals(delivered, store.fetchMessage(EntityKey(RADIO_A, row.id)))
    }

    @Test fun fullyCoveredSaturatedAcceptanceIsDiagnosableButMalformedPhysicalDataIsNotMaskedByUnknown() = runTest {
        val row = message()
        val claim = begin(row, activate())
        db.directAckExclusions().radio(DirectAckRadioExclusionEntity(RADIO_A.value, true))
        assertIs<DirectAckEmissionMarkResult.CoveredBySaturation>(
            store.markDirectAckWireMayHaveWritten(claim.attempt, claim.wire, UUID.randomUUID()),
        )
        assertIs<DirectAckMutationResult.Applied>(store.recordDirectSendAccepted(claim.attempt, claim.wire, 999u))
        val evidence = present(row)
        assertTrue(DirectMessageConfirmationUncertaintyReason.RADIO_HISTORY_SATURATED in evidence.reasons)
        assertFalse(DirectMessageConfirmationUncertaintyReason.TARGET_CONFLICTING_PHYSICAL_EVIDENCE in evidence.reasons)
        assertTrue(db.directAckExclusions().emissions(RADIO_A.value, 10).isEmpty())
        db.directAckExclusions().emission(DirectAckEmissionEntity(
            RADIO_A.value, claim.id, claim.wire, UUID.randomUUID(), UUID.randomUUID(),
            123, "ACCEPTED", null, UUID.randomUUID(),
        ))
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> {
            store.inspectDirectMessageConfirmationEvidence(EntityKey(RADIO_A, row.id))
        }.error)
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
    }

    @Test fun oldTrackedCoverageRemainsLegacyWithMatchingPhysicalAcceptanceAndSaturationWithoutJournalBackfill() = runTest {
        val row = message()
        val claim = begin(row, activate())
        val initial = db.directAcks().operations(RADIO_A.value, claim.id)
        val birth = initial.single { it.kind == "ACCEPTANCE_HISTORY_REQUIRED:${claim.wire}" }
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM direct_ack_operations WHERE radioId=? AND claimId=? AND requestId=?",
            arrayOf<Any?>(RADIO_A.value.toString().uppercase(), claim.id.toString().uppercase(),
                birth.requestId.toString().uppercase()),
        )
        accept(claim, 999u)
        db.directAckExclusions().radio(DirectAckRadioExclusionEntity(RADIO_A.value, true))
        val before = db.directAcks().operations(RADIO_A.value, claim.id)
        val own = present(row)
        assertTrue(DirectMessageConfirmationUncertaintyReason.TARGET_UNTRACKED_LEGACY_ANCESTRY in own.reasons)
        assertTrue(DirectMessageConfirmationUncertaintyReason.RADIO_HISTORY_SATURATED in own.reasons)
        assertFalse(DirectMessageConfirmationUncertaintyReason.TARGET_CONFLICTING_PHYSICAL_EVIDENCE in own.reasons)
        val foreignRow = message(contactID = UUID.randomUUID())
        store.saveMessage(foreignRow)
        assertTrue(DirectMessageConfirmationUncertaintyReason.UNTRACKED_OTHER_CLAIM_ANCESTRY in
            present(foreignRow).reasons)
        assertEquals(before, db.directAcks().operations(RADIO_A.value, claim.id))
        assertTrue(before.none { it.kind == "ACCEPTANCE_HISTORY_REQUIRED:${claim.wire}" })
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> {
            store.inspectDirectAckRadioEvidence(RADIO_A)
        }.error)
    }

    @Test fun outOfRangeRetainedRegisteredActualDoesNotDisappearAsMissingOrUnprovenOnly() = runTest {
        val row = message()
        val claim = begin(row, activate())
        accept(claim, 999u)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE direct_ack_wires SET acceptedCode=4294967296 WHERE radioId=? AND claimId=? AND requestId=?",
            arrayOf<Any?>(RADIO_A.value.toString().uppercase(), claim.id.toString().uppercase(),
                claim.wire.toString().uppercase()),
        )
        val before = db.directAcks().operations(RADIO_A.value, claim.id)
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> {
            store.inspectDirectMessageConfirmationEvidence(EntityKey(RADIO_A, row.id))
        }.error)
        assertEquals(before, db.directAcks().operations(RADIO_A.value, claim.id))
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
        assertNull(db.directAcks().delivery(RADIO_A.value, claim.id))
    }

    @Test fun corruptStoredIdentityOrMissingImmutableMetadataFailsExplicitlyInsteadOfClaimingMissingOrClean() = runTest {
        val row = message()
        store.saveMessage(row)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE direct_ack_incarnations SET revision=-1 WHERE radioId=? AND id=? AND kind='message'",
            arrayOf<Any?>(RADIO_A.value.toString().uppercase(), row.id.toString().uppercase()),
        )
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> {
            store.inspectDirectMessageConfirmationEvidence(EntityKey(RADIO_A, row.id))
        }.error)
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
        assertFailsWith<android.database.sqlite.SQLiteException> {
            db.openHelper.writableDatabase.execSQL("DELETE FROM direct_ack_prehistory")
        }
        assertEquals(listOf(DirectAckPrehistoryEntity(1, "UNKNOWN")), db.directAckPrehistory().rows())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER preauthority_history_update")
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException> {
            store.inspectDirectAckRadioConfirmationEvidence(RADIO_B)
        }.error)
        assertEquals(listOf(DirectAckPrehistoryEntity(1, "UNKNOWN")), db.directAckPrehistory().rows())
    }

    @Test fun restoreAndRemapNeverSupplyPrehistoryProof() = runTest {
        val name = "prehistory-restore-${UUID.randomUUID()}.db"
        var file = MeshCoreDatabase.open(context, name)
        var target = RoomPersistenceStore(file, owner, clock, RepositoryIssueReporter { _, error -> issues += error })
        val storage = MeshCoreStorage.get(context)
        try {
            val localDevice = device()
            target.saveDevice(localDevice)
            for (outcome in listOf("commit", "failure", "cancel", "empty")) {
                val importedRadio = RadioId(UUID.randomUUID())
                val imported = localDevice.copy(id = UUID.randomUUID(), radioId = importedRadio)
                val importedMessage = message(radio = importedRadio, contactID = null)
                val backup = if (outcome == "empty") envelope()
                    else envelope(devices = listOf(imported), messages = listOf(importedMessage))
                var reached = false
                val service = AppBackupService(AppBackupCodec(), storage.backupPreferences, "1.0", "1",
                    clock, BackupImportHooks(beforeCommit = {
                        reached = true
                        assertEquals(listOf(DirectAckPrehistoryEntity(1, "UNKNOWN")), file.directAckPrehistory().rows())
                        when (outcome) {
                            "failure" -> throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                            "cancel" -> throw CancellationException("Controlled import rollback")
                        }
                    }))
                when (outcome) {
                    "failure" -> assertFailsWith<AppBackupException> { service.importBackup(backup, target) }
                    "cancel" -> assertFailsWith<CancellationException> { service.importBackup(backup, target) }
                    else -> service.importBackup(backup, target)
                }
                assertTrue(reached)
                unknown(target, RADIO_A)
                unknown(target, importedRadio)
                if (outcome == "commit") {
                    assertNotNull(target.fetchMessage(EntityKey(RADIO_A, importedMessage.id)))
                    assertNull(target.fetchMessage(EntityKey(importedRadio, importedMessage.id)))
                } else if (outcome != "empty") {
                    assertNull(target.fetchMessage(EntityKey(RADIO_A, importedMessage.id)))
                }
                target.close()
                file.close()
                file = MeshCoreDatabase.open(context, name)
                target = RoomPersistenceStore(file, owner, clock, RepositoryIssueReporter { _, error -> issues += error })
                unknown(target, RADIO_A)
                unknown(target, importedRadio)
                assertEquals(listOf(DirectAckPrehistoryEntity(1, "UNKNOWN")), file.directAckPrehistory().rows())
            }
        } finally { target.close(); file.close(); storage.close(); context.deleteDatabase(name) }
    }
}
