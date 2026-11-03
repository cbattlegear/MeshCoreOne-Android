// AndroidOnly: WP-201 Durable local channel occupant epochs; never portable backup or dispatch authority by themselves.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import java.util.UUID

@Entity(tableName = "channel_occupants", primaryKeys = ["radioId", "index"])
data class ChannelOccupantEntity(val radioId: UUID, val index: Long, val occupantID: UUID)

@Dao
interface ChannelOccupantDao : RowWriter<ChannelOccupantEntity> {
    @Query("SELECT occupantID FROM channel_occupants WHERE radioId = :radioId AND `index` = :index")
    suspend fun forIndex(radioId: UUID, index: Long): UUID?
    @Query("DELETE FROM channel_occupants WHERE radioId = :radioId AND `index` = :index")
    suspend fun invalidate(radioId: UUID, index: Long): Int
    @Query("DELETE FROM channel_occupants WHERE radioId = :radioId")
    suspend fun clearRadio(radioId: UUID): Int
}
