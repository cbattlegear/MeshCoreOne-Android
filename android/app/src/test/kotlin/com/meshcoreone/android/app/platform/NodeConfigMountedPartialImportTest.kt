// AndroidOnly: WP-210 Mounted settings import through the production service and Room.
package com.meshcoreone.android.app.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.pm.ProviderInfo
import android.content.res.Configuration
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.R as AppR
import com.meshcoreone.android.app.container.AppSettingsFeature
import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.app.navigation.AppSettingsContent
import com.meshcoreone.android.app.navigation.SettingsDetail
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.ContactSaveResult
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.remote.NodeConfigJson
import com.meshcoreone.android.feature.settings.app.nodeconfig.NodeConfigDocuments
import com.meshcoreone.android.feature.settings.R
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@Config(sdk = [31], qualifiers = "w360dp-h900dp")
class NodeConfigMountedPartialImportTest : RoomProcessTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun disconnectedSettingsOffersExplicitIdentityOnlyRecovery() = runTest {
        val context = compose.activity
        val storage = MeshCoreStorage.get(context)
        val oldKey = Bytes(ByteArray(32) { 7 })
        val newKey = Bytes(ByteArray(32) { 19 })
        val currentKey = AtomicReference(oldKey)
        val firstWrite = AtomicBoolean(true)
        val lostReply = AtomicBoolean(false)
        val cancelPick = AtomicBoolean(false)
        val config = MeshCoreNodeConfig(privateKey = "cd".repeat(64))
        val documents = object : NodeConfigDocuments {
            override suspend fun pick(): ByteArray? = if (cancelPick.get()) null else NodeConfigJson.encodeToBytes(config)
            override suspend fun save(name: String, bytes: ByteArray) = false
        }
        val harness = ContainerHarness(this, store, identity = { currentKey.get() })
        harness.links.configureRadio = { radio ->
            radio.respond = { packet ->
                if (packet[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue) {
                    currentKey.set(newKey)
                    radio.publicKey = newKey
                    if (firstWrite.getAndSet(false)) {
                        lostReply.set(true)
                        emptyList()
                    } else listOf(Bytes.of(0))
                } else null
            }
        }
        try {
            harness.manager.connect(harness.target())
            harness.settle()
            harness.assertReady()
            val originalId = checkNotNull(harness.manager.connectedDevice).id
            val feature = AppSettingsFeature.create(harness.container, storage)
            val holder = feature.nodeConfig(context.resources)
            compose.runOnUiThread {
                harness.container.navigation.selectTab(AppTab.SETTINGS)
                harness.container.navigation.navigateToSetting(SettingsDetail.ADVANCED)
                context.setContent {
                    val navigation by harness.container.navigation.state.collectAsState()
                    MeshCoreTheme {
                        AppSettingsContent(feature, navigation.activeStack.last().destination, harness.container.navigation, documents)
                    }
                }
            }
            compose.onNodeWithText(context.getString(S.configImportImportConfig)).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(S.configImportSelectFile)).performClick()
            harness.eventually("identity import preview") { holder.importState.value.config != null }
            val importLabel = context.getString(S.configImportApplyButtonOverwrite)
            compose.onNodeWithText(importLabel).performScrollTo().performClick()
            harness.eventually("identity import confirmation") { holder.importState.value.confirmation }
            compose.onAllNodesWithText(importLabel).onLast().performClick()
            harness.eventually("identity write dispatched") { lostReply.get() }
            harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            harness.eventually("unknown identity outcome reported") {
                !holder.importState.value.applying && holder.importState.value.reconcileRequired &&
                    harness.manager.identityImportRecoveries.value.size == 1
            }
            val warning = context.getString(R.string.node_config_write_outcome_unknown)
            val importError = requireNotNull(holder.importState.value.error)
            assertTrue(importError.contains(warning))
            compose.onNodeWithText(importError).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(context.getString(AppR.string.identity_import_recovery_entry, "TestNode")).assertIsDisplayed().performClick()
            compose.onNodeWithText(context.getString(AppR.string.identity_import_recovery_select)).performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText(context.getString(AppR.string.identity_import_recovery_selected)).fetchSemanticsNodes().isNotEmpty()
            }
            cancelPick.set(true)
            compose.onNodeWithText(context.getString(AppR.string.identity_import_recovery_select)).performClick()
            compose.onNodeWithText(context.getString(AppR.string.identity_import_recovery_selected)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(AppR.string.identity_import_recovery_apply)).assertIsNotEnabled()
            cancelPick.set(false)
            compose.onNodeWithText(context.getString(AppR.string.identity_import_recovery_select)).performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText(context.getString(AppR.string.identity_import_recovery_selected)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(context.getString(AppR.string.identity_import_recovery_apply)).performClick()
            compose.onNodeWithText(context.getString(AppR.string.identity_import_recovery_confirm_title)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonCancel)).performClick()
            assertEquals(1, harness.links.radios.size)
            compose.onNodeWithText(context.getString(AppR.string.identity_import_recovery_apply)).performClick()
            compose.onAllNodesWithText(context.getString(AppR.string.identity_import_recovery_apply)).onLast().performClick()
            compose.waitUntil(30_000) {
                harness.settle()
                compose.onAllNodesWithText(context.getString(AppR.string.identity_import_recovery_complete)).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(originalId, checkNotNull(harness.manager.connectedDevice).id)
            assertEquals(newKey, checkNotNull(harness.manager.connectedDevice).publicKey)
            val retryRadio = harness.links.radios.last()
            assertEquals(1, retryRadio.sent.count { it[0] == CommandCode.IMPORT_PRIVATE_KEY.rawValue })
            assertTrue(retryRadio.sent.none { it[0] == CommandCode.SET_CHANNEL.rawValue || it[0] == CommandCode.UPDATE_CONTACT.rawValue })
        } finally {
            compose.runOnUiThread { context.setContent {} }
            harness.container.close()
            storage.close()
        }
    }

    @Test
    fun unavailableAndRevokedSelectedFileShowRecoveryAndAllowPickerRetry() = runTest {
        val context = compose.activity
        val storage = MeshCoreStorage.get(context)
        val harness = ContainerHarness(this, store)
        val revoked = AtomicBoolean(true)
        val missingOpened = AtomicBoolean(false)
        val available = AtomicBoolean(false)
        val pickerFailure = AtomicBoolean(true)
        val configFile = File.createTempFile("node-config-retry", ".json", context.cacheDir)
        configFile.writeBytes(NodeConfigJson.encodeToBytes(MeshCoreNodeConfig(name = "Retry node")))
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/json"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                               selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
                if (revoked.get()) throw SecurityException("revoked")
                return MatrixCursor(requireNotNull(projection))
            }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                if (available.get()) return ParcelFileDescriptor.open(configFile, ParcelFileDescriptor.MODE_READ_ONLY)
                missingOpened.set(true)
                throw FileNotFoundException("missing")
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("not used")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = "wp318.unavailable" })
        ShadowContentResolver.registerProviderInternal("wp318.unavailable", provider)
        val pickerResult = AtomicReference<Uri?>(Uri.parse("content://wp318.unavailable/config"))
        lateinit var backing: BackupDocuments
        backing = BackupDocuments(context, {}, {
            if (pickerFailure.get()) throw ActivityNotFoundException("picker unavailable")
            backing.result(pickerResult.get())
        }, {})
        try {
            harness.manager.connect(harness.target())
            harness.settle()
            harness.assertReady()
            val feature = AppSettingsFeature.create(harness.container, storage)
            val holder = feature.nodeConfig(context.resources)
            var displayContext by mutableStateOf<Context>(context)
            val frenchConfiguration = Configuration(context.resources.configuration).apply { setLocale(Locale.FRENCH) }
            val french = context.createConfigurationContext(frenchConfiguration)
            compose.runOnUiThread {
                harness.container.navigation.selectTab(AppTab.SETTINGS)
                harness.container.navigation.navigateToSetting(SettingsDetail.ADVANCED)
                context.setContent {
                    val navigation by harness.container.navigation.state.collectAsState()
                    MeshCoreTheme {
                        CompositionLocalProvider(
                            LocalContext provides displayContext,
                            LocalActivityResultRegistryOwner provides context,
                        ) {
                            AppSettingsContent(feature, navigation.activeStack.last().destination,
                                harness.container.navigation, nodeConfigDocuments(backing))
                        }
                    }
                }
            }
            compose.onNodeWithText(context.getString(S.configImportImportConfig)).performScrollTo().performClick()
            val selectFile = context.getString(S.configImportSelectFile)
            val recovery = context.getString(R.string.node_config_document_unavailable)
            compose.onNodeWithText(selectFile).performClick()
            val pickerRecovery = context.getString(R.string.node_config_picker_unavailable)
            harness.eventually("picker launch error") { holder.importState.value.error == pickerRecovery }
            compose.onNodeWithText(pickerRecovery).performScrollTo().assertIsDisplayed()

            pickerFailure.set(false)
            compose.onNodeWithText(selectFile).performScrollTo().performClick()
            harness.eventually("revoked file error") { holder.importState.value.error != null }
            assertEquals(recovery, holder.importState.value.error)
            compose.onNodeWithText(recovery).performScrollTo().assertIsDisplayed()

            compose.runOnUiThread { displayContext = french }
            val frenchRecovery = french.getString(R.string.node_config_document_unavailable)
            compose.onNodeWithText(french.getString(S.configImportSelectFile)).performScrollTo().performClick()
            harness.eventually("localized revoked file error") { holder.importState.value.error == frenchRecovery }
            compose.onNodeWithText(frenchRecovery).performScrollTo().assertIsDisplayed()
            assertTrue(holder === feature.nodeConfig(french.resources))
            compose.runOnUiThread { displayContext = context }

            revoked.set(false)
            compose.onNodeWithText(selectFile).performScrollTo().performClick()
            harness.eventually("missing file error") {
                missingOpened.get() && holder.importState.value.error != null && !holder.importState.value.parsing
            }
            assertEquals(recovery, holder.importState.value.error)
            compose.onNodeWithText(recovery).performScrollTo().assertIsDisplayed()

            pickerResult.set(null)
            compose.onNodeWithText(selectFile).performScrollTo().performClick()
            harness.eventually("cancelled retry") { holder.importState.value.error == null && !holder.importState.value.parsing }
            assertNull(holder.importState.value.config)

            pickerResult.set(Uri.parse("content://wp318.unavailable/config"))
            available.set(true)
            compose.onNodeWithText(selectFile).performScrollTo().performClick()
            harness.eventually("successful retry") { holder.importState.value.config != null }
            assertEquals("Retry node", holder.importState.value.config?.name)
            assertNull(holder.importState.value.error)
        } finally {
            compose.runOnUiThread { context.setContent {} }
            backing.close()
            harness.container.close()
            storage.close()
            configFile.delete()
        }
    }

    @Test
    fun acknowledgedContactWithFailedLocalSaveShowsPartialWarning() = runTest {
        val firstKey = requireNotNull(Bytes.parseHex("cd".repeat(32)))
        val secondKey = requireNotNull(Bytes.parseHex("ef".repeat(32)))
        val failSecondSave = AtomicBoolean(true)
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun saveContact(radioId: RadioId, frame: ContactFrame): ContactSaveResult {
                if (frame.publicKey == secondKey && failSecondSave.get()) throw IllegalStateException("local save failed")
                return store.saveContact(radioId, frame)
            }
        }
        val harness = ContainerHarness(this, store, persistenceOverride = persistence)
        val context = compose.activity
        val storage = MeshCoreStorage.get(context)
        val config = MeshCoreNodeConfig(
            contacts = SnapshotList.of(
                contact(firstKey, "First"),
                contact(secondKey, "Second"),
            ),
        )
        val documents = object : NodeConfigDocuments {
            override suspend fun pick(): ByteArray = NodeConfigJson.encodeToBytes(config)
            override suspend fun save(name: String, bytes: ByteArray) = false
        }
        try {
            harness.manager.connect(harness.target())
            harness.settle()
            harness.assertReady()
            val node = checkNotNull(harness.sessions.current)
            val radio = harness.links.radios.single()
            radio.respond = { packet ->
                if (packet[0] == CommandCode.UPDATE_CONTACT.rawValue) {
                    radio.knownContacts += Bytes.of(3) + packet.slice(1, 144) + Bytes.of(0xE7, 0x03, 0, 0)
                    listOf(Bytes.of(0))
                } else {
                    null
                }
            }
            val beforeImport = radio.sent.size
            val feature = AppSettingsFeature.create(harness.container, storage)
            val holder = feature.nodeConfig(context.resources)
            compose.runOnUiThread {
                harness.container.navigation.selectTab(AppTab.SETTINGS)
                harness.container.navigation.navigateToSetting(SettingsDetail.ADVANCED)
                context.setContent {
                    val navigation by harness.container.navigation.state.collectAsState()
                    MeshCoreTheme {
                        AppSettingsContent(feature, navigation.activeStack.last().destination, harness.container.navigation, documents)
                    }
                }
            }
            compose.onNodeWithText(context.getString(S.configImportImportConfig)).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(S.configImportSelectFile)).performClick()
            harness.eventually("contact import preview") { holder.importState.value.config != null }
            compose.onNodeWithText(context.getString(S.configImportApplyButtonAdd)).performScrollTo().performClick()
            harness.eventually("contact import confirmation") { holder.importState.value.confirmation }
            compose.onAllNodesWithText(context.getString(S.configImportApplyButtonAdd)).onLast().performClick()
            harness.eventually("partial import warning") { holder.importState.value.error != null }
            val error = requireNotNull(holder.importState.value.error)
            val recovery = context.getString(R.string.node_config_contact_save_failed)
            assertEquals(S.configImportFailedPartial(context.resources, recovery), error)
            val progress = requireNotNull(holder.importState.value.progress)
            assertEquals(2, progress.current)
            assertEquals(2, progress.total)
            compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
            assertEquals(2, radio.sent.drop(beforeImport).count { it[0] == CommandCode.UPDATE_CONTACT.rawValue })
            assertNotNull(store.fetchContact(node.token.radioId, firstKey))
            assertNull(store.fetchContact(node.token.radioId, secondKey))

            val first = requireNotNull(store.fetchContact(node.token.radioId, firstKey))
            store.saveContact(first.copy(isFavorite = true, nickname = "Local nickname"))
            failSecondSave.set(false)
            val beforeRetry = radio.sent.size
            compose.onNodeWithText(context.getString(S.configImportApplyButtonAdd)).performScrollTo().performClick()
            harness.eventually("retry confirmation") { holder.importState.value.confirmation }
            compose.onAllNodesWithText(context.getString(S.configImportApplyButtonAdd)).onLast().performClick()
            harness.eventually("retry completion") { holder.importState.value.complete }
            assertEquals(0, radio.sent.drop(beforeRetry).count { it[0] == CommandCode.UPDATE_CONTACT.rawValue })
            assertEquals(999u, requireNotNull(store.fetchContact(node.token.radioId, secondKey)).lastModified)
            val retained = requireNotNull(store.fetchContact(node.token.radioId, firstKey))
            assertTrue(retained.isFavorite)
            assertEquals("Local nickname", retained.nickname)
        } finally {
            compose.runOnUiThread { context.setContent {} }
            harness.container.close()
            storage.close()
        }
    }

    private fun contact(key: Bytes, name: String) = MeshCoreNodeConfig.ContactConfig(
        type = 1u, name = name, publicKey = key.hexString, flags = 0u,
        latitude = "0", longitude = "0", lastAdvert = 0u, lastModified = 0u,
    )
}
