// AndroidOnly: WP-201 Durable local channel occupant epochs; never portable backup or dispatch authority by themselves.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.ColumnInfo
import java.util.UUID

@Entity(tableName = "channel_occupants", primaryKeys = ["radioId", "index"])
data class ChannelOccupantEntity(
    val radioId: UUID, val index: Long, val occupantID: UUID,
    @ColumnInfo(defaultValue = "0") val unresolved: Boolean = false,
)

@Dao
interface ChannelOccupantDao : RowWriter<ChannelOccupantEntity> {
    @Query("SELECT occupantID FROM channel_occupants WHERE radioId = :radioId AND `index` = :index AND unresolved = 0")
    suspend fun forIndex(radioId: UUID, index: Long): UUID?
    @Query("SELECT * FROM channel_occupants WHERE radioId = :radioId AND `index` = :index")
    suspend fun stateForIndex(radioId: UUID, index: Long): ChannelOccupantEntity?
    @Query("DELETE FROM channel_occupants WHERE radioId = :radioId AND `index` = :index")
    suspend fun invalidate(radioId: UUID, index: Long): Int
    @Query("DELETE FROM channel_occupants WHERE radioId = :radioId")
    suspend fun clearRadio(radioId: UUID): Int
}
