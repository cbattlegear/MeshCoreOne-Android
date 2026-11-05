// AndroidOnly: WP-202 Radio-owned exclusion evidence, not durable ACK ingress.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

private const val MALFORMED_PHYSICAL_EVIDENCE = """
    predictedCode NOT BETWEEN 0 AND 4294967295
    OR (actualCode IS NOT NULL AND actualCode NOT BETWEEN 0 AND 4294967295)
    OR state NOT IN ('MAY_HAVE_WRITTEN','DEFINITELY_UNSENT','ACCEPTED','CONFLICT','CONFLICT_UNSENT')
    OR (state = 'MAY_HAVE_WRITTEN' AND (actualCode IS NOT NULL OR resolutionRequestId IS NOT NULL))
    OR (state = 'DEFINITELY_UNSENT' AND (actualCode IS NOT NULL OR resolutionRequestId IS NULL))
    OR (state = 'ACCEPTED' AND (actualCode IS NULL OR resolutionRequestId IS NULL))
    OR (state IN ('CONFLICT','CONFLICT_UNSENT') AND resolutionRequestId IS NULL)
"""

private const val ACCEPTED_TARGET_CODES = """
    SELECT actualCode FROM direct_ack_emissions
    WHERE radioId = :radio AND claimId = :claim AND state = 'ACCEPTED'
      AND actualCode BETWEEN 0 AND 4294967295 AND predictedCode BETWEEN 0 AND 4294967295
      AND resolutionRequestId IS NOT NULL
"""

private const val COMPLETE_TRACKED_WIRE_PROVENANCE = """
    EXISTS(
        SELECT 1 FROM direct_ack_operations o
        WHERE o.radioId = w.radioId AND o.claimId = w.claimId AND o.requestId = w.requestId
          AND o.kind = 'EMISSION_TRACKING_REQUIRED' AND o.argument = w.predictedCode
          AND o.maximum = 1 AND o.pendingId IS NULL)
    AND EXISTS(
        SELECT 1 FROM direct_ack_operations h
        WHERE h.radioId = w.radioId AND h.claimId = w.claimId
          AND h.kind = 'ACCEPTANCE_HISTORY_REQUIRED:' || lower(w.requestId)
          AND h.argument = w.predictedCode AND h.maximum = 1 AND h.pendingId IS NULL)
"""

private const val GENERIC_ACCEPTANCE_FACTS = """
    SELECT w.radioId,w.claimId,w.requestId,w.predictedCode,a.argument AS actualCode
    FROM direct_ack_wires w JOIN direct_ack_operations a
      ON a.radioId = w.radioId AND a.claimId = w.claimId
      AND a.kind = 'TRACKED_WIRE_ACCEPTANCE:' || lower(w.requestId)
    WHERE a.argument BETWEEN 0 AND 4294967295 AND a.maximum = 1 AND a.pendingId IS NULL
"""

private const val FAILURE_TARGET_ACTUALS = """
    SELECT e.claimId,e.actualCode FROM direct_ack_emissions e
    JOIN direct_ack_claims old ON old.radioId = e.radioId AND old.requestId = e.claimId
    JOIN direct_ack_claims target ON target.radioId = old.radioId
      AND target.messageId = old.messageId AND target.incarnation = old.incarnation
    WHERE target.radioId = :radio AND target.requestId = :claim AND e.state = 'ACCEPTED'
      AND e.actualCode BETWEEN 0 AND 4294967295 AND e.predictedCode BETWEEN 0 AND 4294967295
      AND e.resolutionRequestId IS NOT NULL
    UNION
    SELECT w.claimId,w.acceptedCode FROM direct_ack_wires w
    JOIN direct_ack_claims old ON old.radioId = w.radioId AND old.requestId = w.claimId
    JOIN direct_ack_claims target ON target.radioId = old.radioId
      AND target.messageId = old.messageId AND target.incarnation = old.incarnation
    WHERE target.radioId = :radio AND target.requestId = :claim
      AND w.acceptedCode BETWEEN 0 AND 4294967295
    UNION
    SELECT g.claimId,g.actualCode FROM ($GENERIC_ACCEPTANCE_FACTS) g
    JOIN direct_ack_claims old ON old.radioId = g.radioId AND old.requestId = g.claimId
    JOIN direct_ack_claims target ON target.radioId = old.radioId
      AND target.messageId = old.messageId AND target.incarnation = old.incarnation
    WHERE target.radioId = :radio AND target.requestId = :claim
"""

private const val TRACKED_ACCEPTANCE_FACTS = """
    SELECT w.radioId,w.claimId,w.requestId,w.predictedCode,w.acceptedCode AS actualCode
    FROM direct_ack_wires w WHERE w.acceptedCode IS NOT NULL AND ($COMPLETE_TRACKED_WIRE_PROVENANCE)
    UNION
    SELECT g.radioId,g.claimId,g.requestId,g.predictedCode,g.actualCode
    FROM ($GENERIC_ACCEPTANCE_FACTS) g JOIN direct_ack_wires w
      ON w.radioId = g.radioId AND w.claimId = g.claimId AND w.requestId = g.requestId
    WHERE ($COMPLETE_TRACKED_WIRE_PROVENANCE)
"""

private const val INCONSISTENT_ACCEPTED_WIRES = """
    SELECT f.radioId,f.claimId,f.requestId FROM ($TRACKED_ACCEPTANCE_FACTS) f
    WHERE NOT EXISTS(
        SELECT 1 FROM direct_ack_radio_exclusions r WHERE r.radioId = f.radioId AND r.saturated != 0)
      AND NOT EXISTS(
        SELECT 1 FROM direct_ack_emissions e
        WHERE e.radioId = f.radioId AND e.claimId = f.claimId AND e.wireId = f.requestId
      AND e.state IN ('ACCEPTED','CONFLICT','CONFLICT_UNSENT') AND e.actualCode = f.actualCode
          AND e.predictedCode = f.predictedCode AND e.predictedCode BETWEEN 0 AND 4294967295
          AND e.actualCode BETWEEN 0 AND 4294967295 AND e.resolutionRequestId IS NOT NULL)
"""

private const val MALFORMED_ACCEPTANCE_HISTORY = """
    SELECT o.radioId,o.claimId FROM direct_ack_operations o
    WHERE (substr(o.kind,1,24) = 'TRACKED_WIRE_ACCEPTANCE:'
        OR substr(o.kind,1,28) = 'ACCEPTANCE_HISTORY_REQUIRED:')
      AND (o.argument IS NULL OR o.argument NOT BETWEEN 0 AND 4294967295
        OR o.maximum IS NULL OR o.maximum != 1 OR o.pendingId IS NOT NULL OR NOT EXISTS(
            SELECT 1 FROM direct_ack_wires w
            WHERE w.radioId = o.radioId AND w.claimId = o.claimId
              AND (o.kind = 'TRACKED_WIRE_ACCEPTANCE:' || lower(w.requestId)
                OR (o.kind = 'ACCEPTANCE_HISTORY_REQUIRED:' || lower(w.requestId)
                    AND o.argument = w.predictedCode))))
"""

@Entity(tableName = "direct_ack_emissions", primaryKeys = ["radioId", "claimId", "wireId", "markRequestId"])
data class DirectAckEmissionEntity(
    val radioId: UUID,
    val claimId: UUID,
    val wireId: UUID,
    val issuer: UUID,
    val markRequestId: UUID,
    val predictedCode: Long,
    val state: String,
    val actualCode: Long?,
    val resolutionRequestId: UUID?,
)

@Entity(tableName = "direct_ack_radio_exclusions", primaryKeys = ["radioId"])
data class DirectAckRadioExclusionEntity(
    val radioId: UUID,
    val saturated: Boolean,
)

data class DirectAckRadioConfirmationRow(
    val unresolvedPhysicalEmission: Boolean,
    val conflictingPhysicalEvidence: Boolean,
    val untrackedLegacyAncestry: Boolean,
    val stickySaturation: Boolean,
    val malformedPhysicalEvidence: Boolean,
)

data class DirectAckRadioFailureRow(
    val targetUnresolved: Boolean,
    val targetConflict: Boolean,
    val targetLegacy: Boolean,
    val targetAccepted: Boolean,
    val otherUnresolved: Boolean,
    val otherConflict: Boolean,
    val otherLegacy: Boolean,
    val otherKnownCode: Boolean,
    val saturated: Boolean,
    val sameIncarnationOtherUncertainty: Boolean,
    val sameIncarnationOtherAccepted: Boolean,
)

data class DirectMessageConfirmationRow(
    val targetUnresolved: Boolean,
    val targetConflict: Boolean,
    val targetLegacy: Boolean,
    val otherUnresolved: Boolean,
    val otherConflict: Boolean,
    val otherLegacy: Boolean,
    val otherKnownCode: Boolean,
    val saturated: Boolean,
)

@Dao
interface DirectAckExclusionDao {
    @Query("""
        SELECT EXISTS(SELECT 1 FROM direct_ack_wires WHERE radioId = :radio
            AND (predictedCode NOT BETWEEN 0 AND 4294967295
                OR (acceptedCode IS NOT NULL AND acceptedCode NOT BETWEEN 0 AND 4294967295)))
    """)
    suspend fun malformedRegisteredHistory(radio: UUID): Boolean

    @Query("""
        WITH target_claims AS (
            SELECT requestId FROM direct_ack_claims
            WHERE radioId = :radio AND messageId = :message AND incarnation = :incarnation
        ), target_codes AS (
            SELECT e.claimId,e.actualCode FROM direct_ack_emissions e
            WHERE e.radioId = :radio AND e.claimId IN (SELECT requestId FROM target_claims)
              AND e.state = 'ACCEPTED' AND e.actualCode BETWEEN 0 AND 4294967295
              AND e.predictedCode BETWEEN 0 AND 4294967295 AND e.resolutionRequestId IS NOT NULL
            UNION
            SELECT w.claimId,w.acceptedCode FROM direct_ack_wires w
            WHERE w.radioId = :radio AND w.claimId IN (SELECT requestId FROM target_claims)
              AND w.acceptedCode BETWEEN 0 AND 4294967295
            UNION
            SELECT g.claimId,g.actualCode FROM ($GENERIC_ACCEPTANCE_FACTS) g
            WHERE g.radioId = :radio AND g.claimId IN (SELECT requestId FROM target_claims)
        )
        SELECT
            EXISTS(SELECT 1 FROM direct_ack_emissions
                WHERE radioId = :radio AND claimId IN (SELECT requestId FROM target_claims)
                  AND state = 'MAY_HAVE_WRITTEN') AS targetUnresolved,
            (EXISTS(SELECT 1 FROM direct_ack_emissions
                WHERE radioId = :radio AND claimId IN (SELECT requestId FROM target_claims)
                  AND state IN ('CONFLICT','CONFLICT_UNSENT'))
             OR EXISTS(SELECT 1 FROM ($INCONSISTENT_ACCEPTED_WIRES)
                WHERE radioId = :radio AND claimId IN (SELECT requestId FROM target_claims))) AS targetConflict,
            EXISTS(SELECT 1 FROM direct_ack_wires w
                WHERE w.radioId = :radio AND w.claimId IN (SELECT requestId FROM target_claims)
                  AND NOT ($COMPLETE_TRACKED_WIRE_PROVENANCE)) AS targetLegacy,
            EXISTS(SELECT 1 FROM direct_ack_emissions e
                WHERE e.radioId = :radio AND e.state = 'MAY_HAVE_WRITTEN'
                  AND (NOT EXISTS(SELECT 1 FROM target_claims)
                    OR EXISTS(SELECT 1 FROM target_claims t WHERE t.requestId != e.claimId))) AS otherUnresolved,
            (EXISTS(SELECT 1 FROM direct_ack_emissions e
                WHERE e.radioId = :radio AND e.state IN ('CONFLICT','CONFLICT_UNSENT')
                  AND (NOT EXISTS(SELECT 1 FROM target_claims)
                    OR EXISTS(SELECT 1 FROM target_claims t WHERE t.requestId != e.claimId)))
             OR EXISTS(SELECT 1 FROM ($INCONSISTENT_ACCEPTED_WIRES) f
                WHERE f.radioId = :radio
                  AND (NOT EXISTS(SELECT 1 FROM target_claims)
                    OR EXISTS(SELECT 1 FROM target_claims t WHERE t.requestId != f.claimId)))) AS otherConflict,
            EXISTS(SELECT 1 FROM direct_ack_wires w
                WHERE w.radioId = :radio AND NOT ($COMPLETE_TRACKED_WIRE_PROVENANCE)
                  AND (NOT EXISTS(SELECT 1 FROM target_claims)
                    OR EXISTS(SELECT 1 FROM target_claims t WHERE t.requestId != w.claimId))) AS otherLegacy,
            (EXISTS(SELECT 1 FROM target_codes t JOIN direct_ack_emissions e
                ON e.radioId = :radio AND e.claimId != t.claimId AND e.state != 'DEFINITELY_UNSENT'
                  AND (e.predictedCode = t.actualCode OR e.actualCode = t.actualCode))
             OR EXISTS(SELECT 1 FROM target_codes t JOIN direct_ack_wires w
                ON w.radioId = :radio AND w.claimId != t.claimId
                  AND (w.acceptedCode IS NOT NULL OR NOT ($COMPLETE_TRACKED_WIRE_PROVENANCE))
                  AND (w.predictedCode = t.actualCode OR w.acceptedCode = t.actualCode))
             OR EXISTS(SELECT 1 FROM target_codes t JOIN ($GENERIC_ACCEPTANCE_FACTS) g
                ON g.radioId = :radio AND g.claimId != t.claimId AND g.actualCode = t.actualCode)) AS otherKnownCode,
            EXISTS(SELECT 1 FROM direct_ack_radio_exclusions
                WHERE radioId = :radio AND saturated != 0) AS saturated
    """)
    suspend fun messageConfirmationEvidence(radio: UUID, message: UUID, incarnation: String): DirectMessageConfirmationRow

    @Query("""
        SELECT
            EXISTS(SELECT 1 FROM direct_ack_emissions
                WHERE radioId = :radio AND claimId = :claim AND state = 'MAY_HAVE_WRITTEN'
                  AND NOT ($MALFORMED_PHYSICAL_EVIDENCE)) AS targetUnresolved,
            (EXISTS(SELECT 1 FROM direct_ack_emissions
                WHERE radioId = :radio AND claimId = :claim
                  AND (state IN ('CONFLICT','CONFLICT_UNSENT') OR ($MALFORMED_PHYSICAL_EVIDENCE)))
             OR EXISTS(SELECT 1 FROM ($INCONSISTENT_ACCEPTED_WIRES)
                WHERE radioId = :radio AND claimId = :claim)
             OR EXISTS(SELECT 1 FROM ($MALFORMED_ACCEPTANCE_HISTORY)
                WHERE radioId = :radio AND claimId = :claim)) AS targetConflict,
            EXISTS(SELECT 1 FROM direct_ack_wires w
                WHERE w.radioId = :radio AND w.claimId = :claim
                  AND NOT ($COMPLETE_TRACKED_WIRE_PROVENANCE)) AS targetLegacy,
            EXISTS($ACCEPTED_TARGET_CODES) AS targetAccepted,
            EXISTS(SELECT 1 FROM direct_ack_emissions
                WHERE radioId = :radio AND claimId != :claim AND state = 'MAY_HAVE_WRITTEN'
                  AND NOT ($MALFORMED_PHYSICAL_EVIDENCE)) AS otherUnresolved,
            (EXISTS(SELECT 1 FROM direct_ack_emissions
                WHERE radioId = :radio AND claimId != :claim
                  AND (state IN ('CONFLICT','CONFLICT_UNSENT') OR ($MALFORMED_PHYSICAL_EVIDENCE)))
             OR EXISTS(SELECT 1 FROM ($INCONSISTENT_ACCEPTED_WIRES)
                WHERE radioId = :radio AND claimId != :claim)
             OR EXISTS(SELECT 1 FROM ($MALFORMED_ACCEPTANCE_HISTORY)
                WHERE radioId = :radio AND claimId != :claim)) AS otherConflict,
            EXISTS(SELECT 1 FROM direct_ack_wires w
                WHERE w.radioId = :radio AND w.claimId != :claim
                  AND NOT ($COMPLETE_TRACKED_WIRE_PROVENANCE)) AS otherLegacy,
            (EXISTS(SELECT 1 FROM ($FAILURE_TARGET_ACTUALS) t JOIN direct_ack_emissions e
                ON e.radioId = :radio AND e.claimId != :claim AND e.claimId != t.claimId
                  AND e.state != 'DEFINITELY_UNSENT'
                  AND (e.predictedCode = t.actualCode OR e.actualCode = t.actualCode))
             OR EXISTS(SELECT 1 FROM ($FAILURE_TARGET_ACTUALS) t JOIN direct_ack_wires w
                ON w.radioId = :radio AND w.claimId != :claim AND w.claimId != t.claimId
                  AND (w.acceptedCode IS NOT NULL OR NOT ($COMPLETE_TRACKED_WIRE_PROVENANCE))
                  AND (w.predictedCode = t.actualCode OR w.acceptedCode = t.actualCode))
             OR EXISTS(SELECT 1 FROM ($FAILURE_TARGET_ACTUALS) t JOIN ($GENERIC_ACCEPTANCE_FACTS) g
                ON g.radioId = :radio AND g.claimId != :claim AND g.claimId != t.claimId AND g.actualCode = t.actualCode))
                AS otherKnownCode,
            EXISTS(SELECT 1 FROM direct_ack_radio_exclusions
                WHERE radioId = :radio AND saturated != 0) AS saturated,
            EXISTS(SELECT 1 FROM direct_ack_claims old
                JOIN direct_ack_claims target
                  ON target.radioId = old.radioId AND target.messageId = old.messageId
                    AND target.incarnation = old.incarnation AND target.requestId != old.requestId
                WHERE target.radioId = :radio AND target.requestId = :claim AND (
                    EXISTS(SELECT 1 FROM direct_ack_emissions
                        WHERE radioId = old.radioId AND claimId = old.requestId
                          AND (state NOT IN ('ACCEPTED','DEFINITELY_UNSENT') OR ($MALFORMED_PHYSICAL_EVIDENCE)))
                    OR EXISTS(SELECT 1 FROM direct_ack_wires w
                        WHERE w.radioId = old.radioId AND w.claimId = old.requestId
                          AND NOT ($COMPLETE_TRACKED_WIRE_PROVENANCE))
                    OR EXISTS(SELECT 1 FROM ($INCONSISTENT_ACCEPTED_WIRES) f
                        WHERE f.radioId = old.radioId AND f.claimId = old.requestId)
                    OR EXISTS(SELECT 1 FROM ($MALFORMED_ACCEPTANCE_HISTORY) h
                        WHERE h.radioId = old.radioId AND h.claimId = old.requestId)
                )) AS sameIncarnationOtherUncertainty,
            EXISTS(SELECT 1 FROM ($FAILURE_TARGET_ACTUALS)
                WHERE claimId != :claim) AS sameIncarnationOtherAccepted
    """)
    suspend fun failureEvidence(radio: UUID, claim: UUID): DirectAckRadioFailureRow

    @Query("""
        SELECT
            EXISTS(SELECT 1 FROM direct_ack_emissions
                WHERE radioId = :radio
                  AND (state NOT IN ('ACCEPTED','DEFINITELY_UNSENT','CONFLICT','CONFLICT_UNSENT')
                    OR (state = 'ACCEPTED' AND actualCode IS NULL))) AS unresolvedPhysicalEmission,
            (EXISTS(SELECT 1 FROM direct_ack_emissions
                WHERE radioId = :radio AND state IN ('CONFLICT','CONFLICT_UNSENT'))
             OR EXISTS(SELECT 1 FROM ($INCONSISTENT_ACCEPTED_WIRES)
                WHERE radioId = :radio)) AS conflictingPhysicalEvidence,
            EXISTS(SELECT 1 FROM direct_ack_wires w
                WHERE w.radioId = :radio AND NOT ($COMPLETE_TRACKED_WIRE_PROVENANCE)) AS untrackedLegacyAncestry,
            EXISTS(SELECT 1 FROM direct_ack_radio_exclusions
                WHERE radioId = :radio AND saturated != 0) AS stickySaturation,
            EXISTS(SELECT 1 FROM direct_ack_emissions
                WHERE radioId = :radio AND ($MALFORMED_PHYSICAL_EVIDENCE)) AS malformedPhysicalEvidence
    """)
    suspend fun confirmationEvidence(radio: UUID): DirectAckRadioConfirmationRow

    @Query("""
        SELECT EXISTS(SELECT 1 FROM ($INCONSISTENT_ACCEPTED_WIRES) WHERE radioId = :radio)
    """)
    suspend fun inconsistentAcceptedHistory(radio: UUID): Boolean

    @Query("""
        SELECT EXISTS(SELECT 1 FROM ($MALFORMED_ACCEPTANCE_HISTORY) WHERE radioId = :radio)
    """)
    suspend fun malformedAcceptanceHistory(radio: UUID): Boolean

    @Query("""
        SELECT EXISTS(SELECT 1 FROM direct_ack_wires w WHERE w.radioId = :radio
            AND EXISTS(SELECT 1 FROM direct_ack_operations o
                WHERE o.radioId = w.radioId AND o.claimId = w.claimId AND o.requestId = w.requestId
                  AND o.kind = 'EMISSION_TRACKING_REQUIRED' AND o.argument = w.predictedCode
                  AND o.maximum = 1 AND o.pendingId IS NULL)
            AND NOT ($COMPLETE_TRACKED_WIRE_PROVENANCE))
    """)
    suspend fun incompleteAcceptanceCoverageHistory(radio: UUID): Boolean

    @Query("""
        SELECT * FROM direct_ack_emissions
        WHERE radioId = :radio AND claimId = :claim AND wireId = :wire AND markRequestId = :mark
    """)
    suspend fun emission(radio: UUID, claim: UUID, wire: UUID, mark: UUID): DirectAckEmissionEntity?

    @Query("SELECT * FROM direct_ack_emissions WHERE radioId = :radio AND markRequestId = :mark LIMIT 2")
    suspend fun emissionRequests(radio: UUID, mark: UUID): List<DirectAckEmissionEntity>

    @Upsert suspend fun emission(row: DirectAckEmissionEntity)

    @Query("SELECT COUNT(*) FROM direct_ack_emissions WHERE radioId = :radio")
    suspend fun count(radio: UUID): Long

    @Query("SELECT * FROM direct_ack_emissions WHERE radioId = :radio ORDER BY claimId,wireId,markRequestId LIMIT :maximum")
    suspend fun emissions(radio: UUID, maximum: Int): List<DirectAckEmissionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM direct_ack_radio_exclusions WHERE radioId = :radio AND saturated != 0)")
    suspend fun saturated(radio: UUID): Boolean

    @Upsert suspend fun radio(row: DirectAckRadioExclusionEntity)

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM direct_ack_emissions
            WHERE radioId = :radio AND claimId != :claim AND state != 'DEFINITELY_UNSENT'
              AND (predictedCode = :code OR actualCode = :code)
        ) OR EXISTS(
            SELECT 1 FROM direct_ack_wires w
            WHERE w.radioId = :radio AND w.claimId != :claim
              AND (w.acceptedCode IS NOT NULL OR NOT ($COMPLETE_TRACKED_WIRE_PROVENANCE))
              AND (w.predictedCode = :code OR w.acceptedCode = :code)
        ) OR EXISTS(
            SELECT 1 FROM ($GENERIC_ACCEPTANCE_FACTS)
            WHERE radioId = :radio AND claimId != :claim AND actualCode = :code
        )
    """)
    suspend fun competingCode(radio: UUID, claim: UUID, code: Long): Boolean

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM direct_ack_emissions
            WHERE radioId = :radio AND claimId != :claim
              AND (state NOT IN ('ACCEPTED','DEFINITELY_UNSENT') OR ($MALFORMED_PHYSICAL_EVIDENCE))
        ) OR EXISTS(
            SELECT 1 FROM direct_ack_wires w
            WHERE w.radioId = :radio AND w.claimId != :claim
              AND NOT ($COMPLETE_TRACKED_WIRE_PROVENANCE)
        ) OR EXISTS(
            SELECT 1 FROM ($INCONSISTENT_ACCEPTED_WIRES) WHERE radioId = :radio AND claimId != :claim
        )
    """)
    suspend fun unresolvedCompetitor(radio: UUID, claim: UUID): Boolean

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM direct_ack_emissions e
            JOIN direct_ack_wires w ON w.radioId = e.radioId AND w.claimId = e.claimId AND w.requestId = e.wireId
            WHERE e.radioId = :radio AND e.claimId = :claim AND e.state = 'ACCEPTED' AND e.actualCode = :code
              AND e.resolutionRequestId IS NOT NULL
              AND e.predictedCode BETWEEN 0 AND 4294967295 AND e.actualCode BETWEEN 0 AND 4294967295
              AND ($COMPLETE_TRACKED_WIRE_PROVENANCE)
        )
    """)
    suspend fun acceptedCurrentWire(radio: UUID, claim: UUID, code: Long): Boolean

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM direct_ack_emissions
            WHERE radioId = :radio AND claimId = :claim AND state IN ('CONFLICT','CONFLICT_UNSENT')
        ) OR EXISTS(
            SELECT 1 FROM direct_ack_wires w
            WHERE w.radioId = :radio AND w.claimId = :claim
              AND NOT ($COMPLETE_TRACKED_WIRE_PROVENANCE)
        ) OR EXISTS(
            SELECT 1 FROM ($INCONSISTENT_ACCEPTED_WIRES) WHERE radioId = :radio AND claimId = :claim
        )
    """)
    suspend fun conflictedClaim(radio: UUID, claim: UUID): Boolean

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM direct_ack_emissions
            WHERE radioId = :radio AND claimId = :claim
              AND (state NOT IN ('ACCEPTED','DEFINITELY_UNSENT') OR ($MALFORMED_PHYSICAL_EVIDENCE))
        ) OR EXISTS(
            SELECT 1 FROM direct_ack_wires w
            WHERE w.radioId = :radio AND w.claimId = :claim
              AND NOT ($COMPLETE_TRACKED_WIRE_PROVENANCE)
        ) OR EXISTS(
            SELECT 1 FROM ($INCONSISTENT_ACCEPTED_WIRES) WHERE radioId = :radio AND claimId = :claim
        )
    """)
    suspend fun unresolvedCurrentClaim(radio: UUID, claim: UUID): Boolean

    @Query("""
        SELECT COUNT(*) FROM direct_ack_wires w
        WHERE w.radioId = :radio AND NOT ($COMPLETE_TRACKED_WIRE_PROVENANCE)
    """)
    suspend fun legacyCount(radio: UUID): Long

    @Query("""
        SELECT w.* FROM direct_ack_wires w
        WHERE w.radioId = :radio AND NOT ($COMPLETE_TRACKED_WIRE_PROVENANCE)
        ORDER BY w.claimId,w.requestId LIMIT :maximum
    """)
    suspend fun legacyWires(radio: UUID, maximum: Int): List<DirectAckWireEntity>
}

object DirectAckExclusionStorage {
    val migration2To3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS direct_ack_emissions (" +
                    "radioId TEXT NOT NULL,claimId TEXT NOT NULL,wireId TEXT NOT NULL,issuer TEXT NOT NULL," +
                    "markRequestId TEXT NOT NULL,predictedCode INTEGER NOT NULL,state TEXT NOT NULL," +
                    "actualCode INTEGER,resolutionRequestId TEXT,PRIMARY KEY(radioId,claimId,wireId,markRequestId))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS direct_ack_radio_exclusions (" +
                    "radioId TEXT NOT NULL,saturated INTEGER NOT NULL,PRIMARY KEY(radioId))",
            )
        }
    }
}
