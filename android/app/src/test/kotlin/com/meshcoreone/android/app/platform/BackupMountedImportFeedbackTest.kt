// AndroidOnly: WP-318 Mounted backup import through the production SAF adapter and codec.
package com.meshcoreone.android.app.platform

import android.content.ActivityNotFoundException
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.container.AppBackupFeature
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.data.backup.AppBackupCodec
import com.meshcoreone.android.core.data.backup.AppBackupEnvelope
import com.meshcoreone.android.core.data.backup.AppBackupService
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.settings.R
import com.meshcoreone.android.feature.settings.app.backup.*
import com.meshcoreone.android.feature.settings.app.backup.ui.BackupRestoreScreen
import com.meshcoreone.android.feature.settings.app.backup.ui.ResourceBackupStrings
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@Config(sdk = [31], qualifiers = "w360dp-h900dp")
class BackupMountedImportFeedbackTest : RoomProcessTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private enum class DocumentState { PICKER_UNAVAILABLE, REVOKED, MISSING, MALFORMED, CANCELLED, READABLE }

    @Test fun pickerAndProviderFailuresShowRecoveryAndAllowSuccessfulRetry() {
        val context = compose.activity
        val storage = MeshCoreStorage.get(context)
        val state = AtomicReference(DocumentState.PICKER_UNAVAILABLE)
        val malformed = File.createTempFile("invalid-backup", ".mc1backup", context.cacheDir)
        val readable = File.createTempFile("valid-backup", ".mc1backup", context.cacheDir)
        malformed.writeText("not a backup")
        val radio = RadioId(UUID.randomUUID())
        val contact = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 9 }),
            name = "Retry contact", lastHeardTimestamp = null)
        val envelope = AppBackupEnvelope(
            exportDate = Instant.ofEpochSecond(1_700_000_000), appVersion = "test", appBuild = "1",
            contacts = listOf(contact).snapshot(),
        ).withActualManifest()
        readable.writeBytes(AppBackupCodec().encode(envelope).toByteArray())
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/octet-stream"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                               selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
                if (state.get() == DocumentState.REVOKED) throw SecurityException("revoked")
                return MatrixCursor(requireNotNull(projection))
            }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                val file = when (state.get()) {
                    DocumentState.MISSING -> throw FileNotFoundException("missing")
                    DocumentState.MALFORMED -> malformed
                    DocumentState.READABLE -> readable
                    else -> error("Unexpected document state")
                }
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("not used")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = "wp318.backup.feedback" })
        ShadowContentResolver.registerProviderInternal("wp318.backup.feedback", provider)
        lateinit var documents: BackupDocuments
        documents = BackupDocuments(context, {}, {
            when (state.get()) {
                DocumentState.PICKER_UNAVAILABLE -> throw ActivityNotFoundException("picker unavailable")
                DocumentState.CANCELLED -> documents.result(null)
                else -> documents.result(Uri.parse("content://wp318.backup.feedback/backup"))
            }
        })
        val engine = AppBackupFeature(AppBackupService(storage.backupPreferences, "test", "1"), store) {}
        val dependencies = BackupFeatureDependencies(
            engine, documents,
            object : BackupConnectionGate {
                override val isRadioConnected = MutableStateFlow(false)
                override suspend fun <T : Any> withOfflineImport(action: suspend () -> T): T? = action()
            },
            object : BackupImportEffects {
                override fun restoredDataChanged() = Unit
                override fun channelSlotsAffected(slotsByRadio: Map<UUID, Set<Int>>) = Unit
                override suspend fun refreshBlockedContactsCache() = Unit
            },
            BackupDiagnostics { _, _ -> },
            ResourceBackupStrings(context.resources),
        )
        try {
            compose.runOnUiThread {
                context.setContent { MeshCoreTheme { BackupRestoreScreen(dependencies) } }
            }
            fun select() {
                compose.onNode(hasText(context.getString(S.settingsBackupImportTitle)) and hasClickAction()).performClick()
            }
            fun errorAndDismiss(message: String) {
                compose.waitUntil(10_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(message).assertIsDisplayed()
                compose.onNodeWithText(context.getString(S.settingsBackupImportErrorDismiss)).performClick()
            }

            select()
            errorAndDismiss(context.getString(R.string.node_config_picker_unavailable))
            state.set(DocumentState.REVOKED)
            select()
            errorAndDismiss(context.getString(R.string.node_config_document_unavailable))
            state.set(DocumentState.MISSING)
            select()
            errorAndDismiss(context.getString(R.string.node_config_document_unavailable))
            state.set(DocumentState.MALFORMED)
            select()
            errorAndDismiss(context.getString(S.settingsBackupErrorInvalidFile))
            state.set(DocumentState.CANCELLED)
            select()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.node_config_picker_unavailable)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.node_config_document_unavailable)).assertDoesNotExist()
            state.set(DocumentState.READABLE)
            select()
            val preview = context.getString(S.settingsBackupImportPreviewTitle)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(preview).fetchSemanticsNodes().isNotEmpty() }
            val import = context.getString(S.settingsBackupImportPreviewButton)
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and hasAnyAncestor(isDialog()))
                .performScrollToNode(hasText(import))
            compose.onNodeWithText(import).performClick()
            val success = context.getString(S.settingsBackupImportSuccessTitle)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(success).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(success).assertIsDisplayed()
            assertEquals("Retry contact", runBlocking { store.fetchContacts(radio).single().name })
        } finally {
            compose.runOnUiThread { context.setContent {} }
            documents.close()
            storage.close()
            malformed.delete()
            readable.delete()
        }
    }
}
