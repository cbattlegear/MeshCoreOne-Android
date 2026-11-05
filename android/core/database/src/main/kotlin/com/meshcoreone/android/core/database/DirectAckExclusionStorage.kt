// AndroidOnly: WP-202 Radio-owned exclusion evidence, not durable ACK ingress.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

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

@Dao
interface DirectAckExclusionDao {
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
              AND (w.acceptedCode IS NOT NULL OR NOT EXISTS(
                  SELECT 1 FROM direct_ack_emissions e
                  WHERE e.radioId = w.radioId AND e.claimId = w.claimId AND e.wireId = w.requestId))
              AND (w.predictedCode = :code OR w.acceptedCode = :code)
        )
    """)
    suspend fun competingCode(radio: UUID, claim: UUID, code: Long): Boolean

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM direct_ack_emissions
            WHERE radioId = :radio AND claimId != :claim
              AND (state NOT IN ('ACCEPTED','DEFINITELY_UNSENT') OR (state = 'ACCEPTED' AND actualCode IS NULL))
        ) OR EXISTS(
            SELECT 1 FROM direct_ack_wires w
            WHERE w.radioId = :radio AND w.claimId != :claim AND w.acceptedCode IS NULL
              AND NOT EXISTS(SELECT 1 FROM direct_ack_emissions e
                  WHERE e.radioId = w.radioId AND e.claimId = w.claimId AND e.wireId = w.requestId)
        )
    """)
    suspend fun unresolvedCompetitor(radio: UUID, claim: UUID): Boolean

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM direct_ack_emissions
            WHERE radioId = :radio AND claimId = :claim AND state = 'ACCEPTED' AND actualCode = :code
        ) OR EXISTS(
            SELECT 1 FROM direct_ack_wires w
            WHERE w.radioId = :radio AND w.claimId = :claim AND w.acceptedCode = :code
              AND NOT EXISTS(SELECT 1 FROM direct_ack_emissions e
                  WHERE e.radioId = w.radioId AND e.claimId = w.claimId AND e.wireId = w.requestId)
        )
    """)
    suspend fun acceptedCurrentWire(radio: UUID, claim: UUID, code: Long): Boolean

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM direct_ack_emissions
            WHERE radioId = :radio AND claimId = :claim AND state IN ('CONFLICT','CONFLICT_UNSENT')
        )
    """)
    suspend fun conflictedClaim(radio: UUID, claim: UUID): Boolean

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM direct_ack_emissions
            WHERE radioId = :radio AND claimId = :claim
              AND (state NOT IN ('ACCEPTED','DEFINITELY_UNSENT') OR (state = 'ACCEPTED' AND actualCode IS NULL))
        ) OR EXISTS(
            SELECT 1 FROM direct_ack_wires w
            WHERE w.radioId = :radio AND w.claimId = :claim AND w.acceptedCode IS NULL
              AND NOT EXISTS(SELECT 1 FROM direct_ack_emissions e
                  WHERE e.radioId = w.radioId AND e.claimId = w.claimId AND e.wireId = w.requestId)
        )
    """)
    suspend fun unresolvedCurrentClaim(radio: UUID, claim: UUID): Boolean

    @Query("""
        SELECT COUNT(*) FROM direct_ack_wires w
        LEFT JOIN direct_ack_emissions e
          ON e.radioId = w.radioId AND e.claimId = w.claimId AND e.wireId = w.requestId
        WHERE w.radioId = :radio AND e.state IS NULL
    """)
    suspend fun legacyCount(radio: UUID): Long

    @Query("""
        SELECT w.* FROM direct_ack_wires w
        LEFT JOIN direct_ack_emissions e
          ON e.radioId = w.radioId AND e.claimId = w.claimId AND e.wireId = w.requestId
        WHERE w.radioId = :radio AND e.state IS NULL
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
