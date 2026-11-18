// AndroidOnly: WP-202 #605 transaction-scoped simulator contact preparation and application.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.ContactEntity
import com.meshcoreone.android.core.database.toDTO
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ProtocolLimits
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface SimulatorContactSeedPlan {
    val existing: ContactEntity?
    val expectedInsert: ContactEntity?
}

private class PreparedSimulatorContactSeed(
    val transaction: RepositoryTransaction,
    val job: Job,
    val fixture: ContactDTO,
    override val existing: ContactEntity?,
    override val expectedInsert: ContactEntity?,
) : SimulatorContactSeedPlan

internal sealed interface SimulatorContactSeedResult {
    val parent: ContactEntity
    val mutated: Boolean

    data class Created(override val parent: ContactEntity) : SimulatorContactSeedResult {
        override val mutated: Boolean = true
    }

    data class Existing(override val parent: ContactEntity, override val mutated: Boolean) : SimulatorContactSeedResult
}

internal suspend fun RepositoryTransaction.prepareSimulatorContact(dto: ContactDTO): SimulatorContactSeedPlan {
    val coroutine = currentCoroutineContext()
    coroutine.ensureActive()
    val job = coroutine[Job]
    if (job == null || !database.inTransaction() || dto.publicKey.size != ProtocolLimits.PUBLIC_KEY_SIZE) {
        throw PersistenceStoreException(PersistenceStoreError.InvalidData)
    }
    val identified = database.contacts().byId(dto.radioId.value, dto.id)
    val identityCount = database.contacts().countSimulatorIdentity(dto.radioId.value, dto.id)
    val matches = database.contacts().simulatorPublicKeyCohort(dto.radioId.value, dto.publicKey)
    val existing = matches.singleOrNull()
    if (identityCount != (if (identified == null) 0L else 1L) ||
        matches.size > 1 || identified?.let { it.publicKey != dto.publicKey } == true ||
        (identified != null && identified != existing) ||
        (existing != null && (database.contacts().byId(dto.radioId.value, existing.id) != existing ||
            database.contacts().countSimulatorIdentity(dto.radioId.value, existing.id) != 1L))) {
        throw PersistenceStoreException(PersistenceStoreError.InvalidData)
    }
    // Use the reader's checked decoding before accepting stored values, including UInt32 freshness.
    existing?.toDTO()
    val insert = if (existing == null) {
        if (dto.avatarImageData != null) {
            throw PersistenceStoreException(PersistenceStoreError.InvalidData)
        }
        dto.toEntity()
    } else null
    return PreparedSimulatorContactSeed(this, job, dto, existing, insert)
}

internal suspend fun RepositoryTransaction.applySimulatorContact(
    plan: SimulatorContactSeedPlan,
): SimulatorContactSeedResult {
    val coroutine = currentCoroutineContext()
    coroutine.ensureActive()
    if (plan !is PreparedSimulatorContactSeed || plan.transaction !== this ||
        plan.job !== coroutine[Job] || !database.inTransaction()) {
        throw PersistenceStoreException(PersistenceStoreError.InvalidData)
    }
    val current = prepareSimulatorContact(plan.fixture)
    if (current.existing != plan.existing || current.expectedInsert != plan.expectedInsert) {
        throw PersistenceStoreException(PersistenceStoreError.SaveFailed("saveSimulatorContact"))
    }
    val existing = plan.existing
    val row = if (existing != null) {
        val timestamp = plan.fixture.lastModified.toLong()
        if (timestamp <= existing.lastModified) return SimulatorContactSeedResult.Existing(existing, mutated = false)
        val changed = database.contacts().advanceSimulatorLastModified(
            existing.radioId, existing.id, existing.publicKey, existing.lastModified, timestamp,
        )
        if (changed != 1) {
            throw PersistenceStoreException(PersistenceStoreError.SaveFailed("saveSimulatorContact"))
        }
        existing.copy(lastModified = timestamp)
    } else {
        val insert = plan.expectedInsert
            ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData)
        database.contacts().insert(insert)
        insert
    }
    if (database.contacts().byId(row.radioId, row.id) != row ||
        database.contacts().countSimulatorIdentity(row.radioId, row.id) != 1L ||
        database.contacts().simulatorPublicKeyCohort(plan.fixture.radioId.value, plan.fixture.publicKey)
            .singleOrNull() != row ||
        (row.id != plan.fixture.id &&
            database.contacts().countSimulatorIdentity(plan.fixture.radioId.value, plan.fixture.id) != 0L)) {
        throw PersistenceStoreException(PersistenceStoreError.SaveFailed("saveSimulatorContact"))
    }
    return if (existing == null) SimulatorContactSeedResult.Created(row)
    else SimulatorContactSeedResult.Existing(row, mutated = true)
}
