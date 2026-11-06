// AndroidOnly: WP-202 #602 real Room controls for the restored-simulator identity safety adaptation.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.SnapshotList
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SimulatorDeviceReconciliationTest : RepositoryTest() {
    private val canonical = device(id = RADIO_A.value).copy(isActive = true)

    @Test fun restoredDevicePreferencesAndRadioDataSurviveRepeatedCanonicalSeeding() = runTest(scheduler) {
        val imported = canonical.copy(
            id = UUID.randomUUID(),
            isActive = false,
            nodeName = "Imported name",
            firmwareVersion = 2u,
            frequency = 868_000u,
            latitude = 12.0,
            defaultFloodScopeName = "Home",
            preRepeatFrequency = 868_000u,
            preRepeatBandwidth = 125_000u,
            preRepeatSpreadingFactor = 7u,
            preRepeatCodingRate = 8u,
            lastContactSync = 123u,
            ocvPreset = "custom",
            appliedRadioPresetID = "restored-preset",
            customOCVArrayString = "[4200,4100,4000]",
            knownRegions = SnapshotList.of("US915"),
        )
        val contact = contact(name = "Restored contact")
        val message = message(text = "Restored simulator conversation")
        val unrelated = device(RADIO_B).copy(publicKey = key(0x55))
        store.saveDevice(imported)
        store.saveDevice(unrelated)
        store.saveContact(contact)
        store.saveMessage(message)
        val expected = imported.copy(
            id = canonical.id,
            isActive = canonical.isActive,
            nodeName = canonical.nodeName,
            firmwareVersion = canonical.firmwareVersion,
            frequency = canonical.frequency,
            latitude = canonical.latitude,
        )

        repeat(3) {
            store.saveSimulatorDevice(canonical)
            assertEquals(expected, store.fetchDevice(canonical.id))
            assertEquals(canonical.id, assertNotNull(store.fetchDevice(RADIO_A)).id)
            assertNull(store.fetchDevice(imported.id))
            assertEquals(setOf(canonical.id, unrelated.id), store.fetchDevices().map { it.id }.toSet())
            assertEquals(unrelated, store.fetchDevice(unrelated.id))
            assertEquals(contact.name, assertNotNull(store.fetchContact(entity())).name)
            assertEquals(message.text, assertNotNull(store.fetchMessage(entity(id = message.id))).text)
        }
    }

    @Test fun existingPreferencesWinAndRestoredPreferencesFillMissingValues() = runTest(scheduler) {
        val current = canonical.copy(
            ocvPreset = "current",
            preRepeatFrequency = 915_000u,
            lastContactSync = 456u,
            knownRegions = SnapshotList.of("EU868", "US915"),
        )
        val imported = canonical.copy(
            id = UUID.randomUUID(),
            isActive = false,
            ocvPreset = "restored",
            appliedRadioPresetID = "restored-preset",
            preRepeatFrequency = 868_000u,
            lastContactSync = 123u,
            knownRegions = SnapshotList.of("US915", "AU915"),
        )
        store.saveDevice(current)
        store.saveDevice(imported)

        store.saveSimulatorDevice(canonical)

        assertEquals(
            current.copy(
                appliedRadioPresetID = "restored-preset",
                knownRegions = SnapshotList.of("EU868", "US915", "AU915"),
            ),
            store.fetchDevice(canonical.id),
        )
        assertEquals(1, store.fetchDevices().size)
    }

    @Test fun matchingPublicKeyOnAnotherRadioIsNotAdoptedOrDeleted() = runTest(scheduler) {
        val foreign = device(RADIO_B).copy(knownRegions = SnapshotList.of("Foreign region"))
        store.saveDevice(foreign)

        store.saveSimulatorDevice(canonical)

        assertEquals(canonical, store.fetchDevice(canonical.id))
        assertEquals(foreign, store.fetchDevice(foreign.id))
        assertEquals(2, store.fetchDevices().size)
    }

    @Test fun partialRecoveryAndOcvSnapshotsAreNotCompletedWithAnotherRowsValues() = runTest(scheduler) {
        val current = canonical.copy(preRepeatFrequency = 915_000u, ocvPreset = "custom")
        val imported = canonical.copy(
            id = UUID.randomUUID(),
            isActive = false,
            preRepeatBandwidth = 125_000u,
            preRepeatSpreadingFactor = 7u,
            preRepeatCodingRate = 8u,
            customOCVArrayString = "[4200,4100,4000]",
        )
        store.saveDevice(current)
        store.saveDevice(imported)

        store.saveSimulatorDevice(canonical)

        val actual = assertNotNull(store.fetchDevice(canonical.id))
        assertEquals(current, actual)
        assertFalse(actual.hasPreRepeatSettings)
        assertNull(actual.customOCVArrayString)
        assertEquals(1, store.fetchDevices().size)
    }

    @Test fun multipleRestoredCandidatesFailWithoutSelectingOrMutatingAnyRow() = runTest(scheduler) {
        val first = canonical.copy(id = UUID.randomUUID(), isActive = false, knownRegions = SnapshotList.of("A"))
        val second = first.copy(id = UUID.randomUUID(), knownRegions = SnapshotList.of("B"))
        store.saveDevice(first)
        store.saveDevice(second)
        val before = store.fetchDevices().toSet()

        val failure = assertFailsWith<PersistenceStoreException> { store.saveSimulatorDevice(canonical) }

        assertEquals(PersistenceStoreError.InvalidData, failure.error)
        assertEquals(listOf(failure), issues)
        assertEquals(before, store.fetchDevices().toSet())
        assertNull(store.fetchDevice(canonical.id))
    }

    @Test fun anotherPublicKeyCannotShareTheSimulatorRadioPartition() = runTest(scheduler) {
        val foreign = device().copy(publicKey = key(0x55))
        store.saveDevice(foreign)

        val failure = assertFailsWith<PersistenceStoreException> { store.saveSimulatorDevice(canonical) }

        assertEquals(PersistenceStoreError.InvalidData, failure.error)
        assertEquals(listOf(foreign), store.fetchDevices())
        assertNull(store.fetchDevice(canonical.id))
    }

    @Test fun anActiveMatchingDeviceCannotBeRetiredAsAnImportedGhost() = runTest(scheduler) {
        val active = canonical.copy(id = UUID.randomUUID())
        store.saveDevice(active)

        val failure = assertFailsWith<PersistenceStoreException> { store.saveSimulatorDevice(canonical) }

        assertEquals(PersistenceStoreError.InvalidData, failure.error)
        assertEquals(listOf(active), store.fetchDevices())
    }

    @Test fun connectionHandlesOnAMatchingRowAreNeverDiscarded() = runTest(scheduler) {
        for (method in listOf(
            ConnectionMethod.Bluetooth(UUID.randomUUID()),
            ConnectionMethod.WiFi("192.0.2.1", 5000u),
        )) {
            val paired = canonical.copy(
                id = UUID.randomUUID(), isActive = false, connectionMethods = SnapshotList.of(method),
            )
            store.saveDevice(paired)

            val failure = assertFailsWith<PersistenceStoreException> { store.saveSimulatorDevice(canonical) }

            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertEquals(listOf(paired), store.fetchDevices())
            store.deleteDevice(paired.id)
        }
    }

    @Test fun fixedIdCollisionsCannotOverwriteAnotherRadioOrPublicKey() = runTest(scheduler) {
        for (collision in listOf(canonical.copy(radioId = RADIO_B), canonical.copy(publicKey = key(0x55)))) {
            store.saveDevice(collision)

            val failure = assertFailsWith<PersistenceStoreException> { store.saveSimulatorDevice(canonical) }

            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertEquals(listOf(collision), store.fetchDevices())
            store.deleteDevice(collision.id)
        }
    }

    @Test fun failedRetirementRollsBackCanonicalInsertionAndPreservesRestoredChildren() = runTest(scheduler) {
        val imported = canonical.copy(id = UUID.randomUUID(), isActive = false)
        val contact = contact(name = "Restored contact")
        store.saveDevice(imported)
        store.saveContact(contact)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_simulator_retirement BEFORE DELETE ON devices " +
                "BEGIN SELECT RAISE(ABORT, 'controlled simulator retirement failure'); END",
        )

        val failure = assertFailsWith<PersistenceStoreException> { store.saveSimulatorDevice(canonical) }

        assertEquals(PersistenceStoreError.SaveFailed("saveSimulatorDevice"), failure.error)
        assertEquals(listOf(failure), issues)
        assertEquals(listOf(imported), store.fetchDevices())
        assertNull(store.fetchDevice(canonical.id))
        assertEquals(contact.name, assertNotNull(store.fetchContact(entity())).name)
    }
}
