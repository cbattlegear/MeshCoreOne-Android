// AndroidOnly: WP-210 Mounted radio CLI authorization over the production settings screen, Room and protocol.
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.app.navigation.AppSettingsExternal
import com.meshcoreone.android.app.navigation.AppSettingsSelectionHost
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.ContactRemoteCLIUpdateResult
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.settings.device.SettingsExternalDestination
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteCLIContactsMountedTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.windowFocusChanged(true)
    }

    @After fun destroyHost() {
        host.windowFocusChanged(false)
        host.pause().stop().destroy()
    }

    private fun frame(seed: Int, name: String, type: Int = 1, flags: Int = 0): Bytes {
        val original = contactFrame(key(seed), name)
        return original.slice(0, 33) + Bytes.of(type, flags) + original.slice(35, original.size)
    }

    private suspend fun TestScope.mount(
        harness: ContainerHarness,
        storage: MeshCoreStorage,
    ): AppSettingsFeature {
        val feature = AppSettingsFeature.create(harness.container, storage)
        compose.runOnUiThread {
            host.get().setContent {
                MeshCoreTheme(motionScale = 0f) {
                    AppSettingsExternal(feature, SettingsExternalDestination.RemoteCLIContacts) {}
                    AppSettingsSelectionHost(feature)
                }
            }
        }
        return feature
    }

    private fun awaitUi(harness: ContainerHarness, condition: () -> Boolean) {
        compose.waitUntil(30_000) {
            harness.settle()
            condition()
        }
    }

    private fun switch(name: String) = compose.onNodeWithContentDescription(name)
    private fun dismissError() {
        compose.onNode(hasText(host.get().getString(AppLocalizableStrings.commonOk)) and hasAnyAncestor(isDialog()))
            .performClick()
    }

    @Test fun selectionAndFailedSecondWritePreserveTheFirstGrantAndOtherFlagBits() = runTest {
        val h = ContainerHarness(this, store)
        val storage = MeshCoreStorage.get(host.get())
        val rejectBob = AtomicBoolean(true)
        h.links.configureRadio = { radio ->
            radio.firmwareVersion = 14u
            radio.knownContacts += listOf(
                frame(20, "Alice", flags = 0xa5),
                frame(21, "Bob", flags = 0xc3),
                frame(22, "Blocked"),
                frame(23, "Sensor", type = 4),
            )
            radio.respond = { packet ->
                if (packet[0].toInt() and 0xff == 0x09) {
                    val publicKey = packet.slice(1, 33)
                    if (publicKey == key(21) && rejectBob.get()) listOf(Bytes.of(1, 6))
                    else {
                        val index = radio.knownContacts.indexOfFirst { it.slice(1, 33) == publicKey }
                        check(index >= 0)
                        radio.knownContacts[index] = Bytes.of(3) + packet.slice(1, 144) + le32(999)
                        listOf(Bytes.of(0))
                    }
                } else null
            }
        }
        try {
            h.manager.connect(h.target())
            h.eventually("ready") { h.manager.connectionState == DeviceConnectionState.READY }
            val radioId = requireNotNull(h.appState.currentRadioId)
            val blocked = requireNotNull(store.fetchContact(radioId, key(22)))
            store.saveContact(blocked.copy(isBlocked = true))
            val feature = mount(h, storage)
            awaitUi(h) { compose.onAllNodesWithContentDescription("Alice").fetchSemanticsNodes().isNotEmpty() }
            switch("Alice").assertIsDisplayed().assertIsOff().assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
            h.eventually("Alice permission committed") {
                runBlocking { store.fetchContact(radioId, key(20))?.flags == 0xb5u.toUByte() }
            }
            awaitUi(h) {
                compose.onAllNodesWithContentDescription("Alice").fetchSemanticsNodes()
                    .any { it.config.getOrNull(SemanticsProperties.ToggleableState) == androidx.compose.ui.state.ToggleableState.On }
            }
            switch("Alice").assertIsOn()
            switch("Bob").assertIsOff()
            switch("Blocked").assertDoesNotExist()
            switch("Sensor").assertDoesNotExist()
            switch("Bob").performClick()
            awaitUi(h) { feature.failure.value != null }
            compose.onNode(isDialog()).assertIsDisplayed()
            assertEquals(0xb5u.toUByte(), store.fetchContact(radioId, key(20))?.flags)
            assertEquals(0xc3u.toUByte(), store.fetchContact(radioId, key(21))?.flags)
            dismissError()
            awaitUi(h) { compose.onAllNodesWithContentDescription("Bob").fetchSemanticsNodes().isNotEmpty() }
            switch("Alice").assertIsOn()
            switch("Bob").assertIsOff()
            rejectBob.set(false)
            switch("Bob").performClick()
            h.eventually("Bob retry committed") {
                runBlocking { store.fetchContact(radioId, key(21))?.flags == 0xd3u.toUByte() }
            }
            awaitUi(h) {
                compose.onAllNodesWithContentDescription("Bob").fetchSemanticsNodes()
                    .any { it.config.getOrNull(SemanticsProperties.ToggleableState) == androidx.compose.ui.state.ToggleableState.On }
            }
            switch("Alice").performClick()
            h.eventually("Alice revoke committed") {
                runBlocking { store.fetchContact(radioId, key(20))?.flags == 0xa5u.toUByte() }
            }
            assertEquals(0xd3u.toUByte(), store.fetchContact(radioId, key(21))?.flags)
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
            storage.close()
        }
    }

    @Test fun acceptedRadioWriteWithFailedLocalCompletionHidesUncertainSelection() = runTest {
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun updateContactRemoteCLI(
                key: EntityKey, expectedPublicKey: Bytes, granted: Boolean, isCurrent: () -> Boolean,
            ): ContactRemoteCLIUpdateResult {
                throw IllegalStateException("local permission update failed")
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        val storage = MeshCoreStorage.get(host.get())
        h.links.configureRadio = { radio ->
            radio.firmwareVersion = 14u
            radio.knownContacts += frame(20, "Alice", flags = 0xa5)
            radio.respond = { packet ->
                if (packet[0].toInt() and 0xff == 0x09) {
                    radio.knownContacts[0] = Bytes.of(3) + packet.slice(1, 144) + le32(999)
                    listOf(Bytes.of(0))
                } else null
            }
        }
        try {
            h.manager.connect(h.target())
            h.eventually("ready") { h.manager.connectionState == DeviceConnectionState.READY }
            val radioId = requireNotNull(h.appState.currentRadioId)
            val feature = mount(h, storage)
            awaitUi(h) { compose.onAllNodesWithContentDescription("Alice").fetchSemanticsNodes().isNotEmpty() }
            switch("Alice").assertIsOff().performClick()
            awaitUi(h) { feature.failure.value != null }
            dismissError()
            val warning = host.get().getString(R.string.radio_cli_recovery)
            awaitUi(h) { compose.onAllNodesWithText(warning).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(warning).assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            switch("Alice").assertDoesNotExist()
            assertTrue(requireNotNull(h.sessions.current).contactService.needsContactWriteReconciliation(radioId))
            assertEquals(0xa5u.toUByte(), store.fetchContact(radioId, key(20))?.flags)
            assertEquals(0xb5, h.links.radios.single().knownContacts.single()[34].toInt() and 0xff)
            compose.onNodeWithText(host.get().getString(R.string.radio_cli_read_radio)).assertIsEnabled().performClick()
            val observation = host.get().getString(R.string.radio_cli_observation)
            awaitUi(h) {
                compose.onAllNodesWithText(observation).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithContentDescription("Alice").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(observation)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            switch("Alice").assertIsOn().assertIsNotEnabled()
            assertEquals(0xa5u.toUByte(), store.fetchContact(radioId, key(20))?.flags)
            assertTrue(requireNotNull(h.sessions.current).contactService.needsContactWriteReconciliation(radioId))
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
            storage.close()
        }
    }

    @Test fun persistedRecoveryAfterRecreationCanReadRadioWithoutClearingUncertainty() = runTest {
        val flagStore = SharedPreferencesContactFlags(host.get())
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun updateContactRemoteCLI(
                key: EntityKey, expectedPublicKey: Bytes, granted: Boolean, isCurrent: () -> Boolean,
            ): ContactRemoteCLIUpdateResult = throw IllegalStateException("local permission update failed")
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence, contactPreferences = flagStore)
        val storage = MeshCoreStorage.get(host.get())
        val target = h.target()
        var radioId: RadioId? = null
        var replacement: ContainerHarness? = null
        h.links.configureRadio = { radio ->
            radio.firmwareVersion = 14u
            radio.knownContacts += frame(20, "Alice", flags = 0xa5)
            radio.respond = { packet ->
                if (packet[0].toInt() and 0xff == 0x09) {
                    radio.knownContacts[0] = Bytes.of(3) + packet.slice(1, 144) + le32(999)
                    listOf(Bytes.of(0))
                } else null
            }
        }
        try {
            h.manager.connect(target)
            h.eventually("ready") { h.manager.connectionState == DeviceConnectionState.READY }
            val originalRadio = requireNotNull(h.appState.currentRadioId)
            radioId = originalRadio
            val feature = mount(h, storage)
            awaitUi(h) { compose.onAllNodesWithContentDescription("Alice").fetchSemanticsNodes().isNotEmpty() }
            switch("Alice").performClick()
            awaitUi(h) { feature.failure.value != null }
            dismissError()
            awaitUi(h) {
                compose.onAllNodesWithText(host.get().getString(R.string.radio_cli_recovery)).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(flagStore.bool("contactWriteReconciliation.${originalRadio.value}"))
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()

            val fresh = ContainerHarness(this, store, contactPreferences = SharedPreferencesContactFlags(host.get()))
            replacement = fresh
            fresh.links.configureRadio = { radio ->
                radio.firmwareVersion = 14u
                radio.knownContacts += frame(20, "Alice", flags = 0xb5)
            }
            fresh.manager.connect(target)
            fresh.eventually("recovery sync failed") {
                fresh.sessions.current != null && fresh.manager.connectionState == DeviceConnectionState.SYNCING &&
                    fresh.manager.snapshot.value.issue != null
            }
            mount(fresh, storage)
            val warning = host.get().getString(R.string.radio_cli_recovery)
            awaitUi(fresh) { compose.onAllNodesWithText(warning).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(host.get().getString(R.string.radio_cli_read_radio)).assertIsEnabled().performClick()
            val observation = host.get().getString(R.string.radio_cli_observation)
            awaitUi(fresh) {
                compose.onAllNodesWithText(observation).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithContentDescription("Alice").fetchSemanticsNodes().isNotEmpty()
            }
            switch("Alice").assertIsOn().assertIsNotEnabled()
            assertTrue(requireNotNull(fresh.sessions.current).contactService.needsContactWriteReconciliation(originalRadio))
            assertEquals(0xa5u.toUByte(), store.fetchContact(originalRadio, key(20))?.flags)
            assertTrue(flagStore.bool("contactWriteReconciliation.${originalRadio.value}"))
            fresh.manager.disconnect()
            fresh.eventually("disconnected") { fresh.manager.connectionState == DeviceConnectionState.DISCONNECTED }
            awaitUi(fresh) { compose.onAllNodesWithText(observation).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText(host.get().getString(R.string.radio_cli_read_radio)).assertIsNotEnabled()
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            replacement?.container?.close()
            h.container.close()
            radioId?.let { flagStore.set("contactWriteReconciliation.${it.value}", false) }
            storage.close()
        }
    }

    @Test fun incompleteRadioObservationReportsFailureAndCanBeRetried() = runTest {
        val flagStore = SharedPreferencesContactFlags(host.get())
        val h = ContainerHarness(this, store, contactPreferences = flagStore)
        val storage = MeshCoreStorage.get(host.get())
        val partial = AtomicBoolean(false)
        var radioId: RadioId? = null
        h.links.configureRadio = { radio ->
            radio.firmwareVersion = 14u
            radio.knownContacts += frame(20, "Alice")
            radio.respond = { packet ->
                if (packet[0].toInt() and 0xff == 0x04 && partial.get()) {
                    listOf(Bytes.of(2) + le32(2), radio.knownContacts.single(), Bytes.of(4) + le32(0))
                } else null
            }
        }
        try {
            h.manager.connect(h.target())
            h.eventually("ready") { h.manager.connectionState == DeviceConnectionState.READY }
            val currentRadio = requireNotNull(h.appState.currentRadioId)
            radioId = currentRadio
            flagStore.set("contactWriteReconciliation.${currentRadio.value}", true)
            partial.set(true)
            val feature = mount(h, storage)
            awaitUi(h) {
                compose.onAllNodesWithText(host.get().getString(R.string.radio_cli_recovery)).fetchSemanticsNodes().isNotEmpty()
            }
            val read = host.get().getString(R.string.radio_cli_read_radio)
            val observation = host.get().getString(R.string.radio_cli_observation)
            compose.onNodeWithText(read).performClick()
            awaitUi(h) { feature.failure.value != null }
            dismissError()
            compose.onNodeWithText(observation).assertDoesNotExist()
            assertTrue(requireNotNull(h.sessions.current).contactService.needsContactWriteReconciliation(currentRadio))
            partial.set(false)
            compose.onNodeWithText(read).performClick()
            awaitUi(h) {
                compose.onAllNodesWithText(observation).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithContentDescription("Alice").fetchSemanticsNodes().isNotEmpty()
            }
            switch("Alice").assertIsOff().assertIsNotEnabled()
            assertTrue(flagStore.bool("contactWriteReconciliation.${currentRadio.value}"))
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
            radioId?.let { flagStore.set("contactWriteReconciliation.${it.value}", false) }
            storage.close()
        }
    }

    @Test fun loadFailureAndEmptyStateAreAccessibleAndRetryable() = runTest {
        val failLoad = AtomicBoolean(false)
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun fetchContacts(radioId: RadioId): SnapshotList<ContactDTO> {
                if (failLoad.get()) throw IllegalStateException("contact load failed")
                return store.fetchContacts(radioId)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        val storage = MeshCoreStorage.get(host.get())
        h.links.configureRadio = { it.firmwareVersion = 14u }
        try {
            h.manager.connect(h.target())
            h.eventually("ready") { h.manager.connectionState == DeviceConnectionState.READY }
            failLoad.set(true)
            val feature = mount(h, storage)
            awaitUi(h) { feature.failure.value != null }
            compose.onNode(isDialog()).assertIsDisplayed()
            dismissError()
            val error = host.get().getString(AppLocalizableStrings.commonErrorFailedToLoad)
            compose.onNodeWithText(error).assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            failLoad.set(false)
            compose.onNodeWithText(host.get().getString(R.string.radio_cli_retry)).assertHasClickAction().performClick()
            val empty = host.get().getString(R.string.radio_cli_empty)
            awaitUi(h) { compose.onAllNodesWithText(empty).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(empty).assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
            storage.close()
        }
    }

    @Test fun disconnectedAndUnsupportedReplacementCannotWriteTheOldRadioContact() = runTest {
        val firstId = UUID.randomUUID()
        val h = ContainerHarness(this, store, identity = { target ->
            if ((target as? ConnectionTarget.Bluetooth)?.deviceId == firstId) key(7) else key(8)
        })
        val storage = MeshCoreStorage.get(host.get())
        h.links.configureRadio = { radio ->
            if (radio.publicKey == key(7)) {
                radio.firmwareVersion = 14u
                radio.knownContacts += frame(20, "Old radio contact")
            } else {
                radio.firmwareVersion = 13u
                radio.knownContacts += frame(21, "New radio contact")
            }
        }
        try {
            h.manager.connect(h.target(firstId))
            h.eventually("first radio ready") { h.manager.connectionState == DeviceConnectionState.READY }
            mount(h, storage)
            awaitUi(h) { compose.onAllNodesWithContentDescription("Old radio contact").fetchSemanticsNodes().isNotEmpty() }
            switch("Old radio contact").assertIsEnabled()
            h.manager.disconnect()
            h.eventually("disconnected") { h.manager.connectionState == DeviceConnectionState.DISCONNECTED }
            awaitUi(h) {
                compose.onAllNodesWithContentDescription("Old radio contact").fetchSemanticsNodes().isNotEmpty()
            }
            switch("Old radio contact").assertIsNotEnabled()
            h.manager.connect(h.target())
            h.eventually("replacement ready") { h.manager.connectionState == DeviceConnectionState.READY }
            awaitUi(h) { compose.onAllNodesWithContentDescription("New radio contact").fetchSemanticsNodes().isNotEmpty() }
            switch("Old radio contact").assertDoesNotExist()
            switch("New radio contact").assertIsNotEnabled()
            assertTrue(h.links.radios.all { radio -> radio.sent.none { it[0].toInt() and 0xff == 0x09 } })
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
            storage.close()
        }
    }
}
