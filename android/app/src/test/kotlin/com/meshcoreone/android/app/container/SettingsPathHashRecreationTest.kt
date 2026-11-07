// AndroidOnly: WP-317 Actual Settings composition disposal/recreation preserves session-owned radio persistence uncertainty.
package com.meshcoreone.android.app.container

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.navigation.AppSettingsContent
import com.meshcoreone.android.app.navigation.SettingsDetail
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.DeviceEditPersisting
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.sync.SyncState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Config(sdk = [31], qualifiers = "w360dp-h900dp")
class SettingsPathHashRecreationTest : RoomProcessTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun disposedSettingsCanRestorePreviousModeAfterVerifiedRadioAndHeldRoomFailure() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var armed = false
        val held = object : PersistenceStoreProtocol by store, DeviceEditPersisting by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (armed && dto.pathHashMode == 2u.toUByte()) {
                    armed = false
                    entered.complete(Unit)
                    return withContext(NonCancellable) {
                        release.await()
                        try { store.saveDeviceIfUnchanged(dto, expected) }
                        finally { finished.complete(Unit) }
                    }
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = held)
        val context = compose.activity
        val storage = MeshCoreStorage.get(context)
        var mode = 0
        try {
            h.links.configureRadio = { radio ->
                radio.firmwareVersion = 10u
                radio.respond = { command ->
                    when (command[0].toInt()) {
                        0x1f -> listOf(Bytes.of(0x12, command[1].toInt()) + Bytes(ByteArray(48)))
                        0x3d -> { mode = command[2].toInt(); listOf(Bytes.of(0)) }
                        0x16 -> listOf(
                            Bytes.of(13, 10, 50, 8) + le32(0) + Bytes.utf8("01 Jan 2025").paddedOrTruncated(12) +
                                Bytes.utf8("T-Deck").paddedOrTruncated(40) +
                                Bytes.utf8("v1.13.0").paddedOrTruncated(20) + Bytes.of(0, mode),
                        )
                        else -> null
                    }
                }
            }
            h.container.foreground.activityStarted()
            h.manager.connect(h.target())
            h.eventually("startup completed") {
                h.sessions.current?.syncCoordinator?.state == SyncState.Synced &&
                    h.appState.canRunSettingsStartupReads
            }
            val before = assertNotNull(h.manager.connectedDevice)
            val service = assertNotNull(h.sessions.current).settingsService
            val feature = AppSettingsFeature.create(h.container, storage)
            fun mount() {
                compose.runOnUiThread {
                    h.container.navigation.selectTab(AppTab.SETTINGS)
                    h.container.navigation.navigateToSetting(SettingsDetail.RADIO)
                    context.setContent {
                        val navigation by h.container.navigation.state.collectAsState()
                        MeshCoreTheme {
                            AppSettingsContent(feature, navigation.activeStack.last().destination, h.container.navigation)
                        }
                    }
                }
            }
            fun choose(text: Int) {
                try {
                    h.eventually("Hash Size row composed") {
                        compose.waitForIdle()
                        compose.onAllNodesWithText(context.getString(S.pathHashModeLabel)).fetchSemanticsNodes().isNotEmpty()
                    }
                } catch (missing: AssertionError) {
                    throw AssertionError("Mounted tree: ${compose.onRoot(useUnmergedTree = true).printToString(40)}", missing)
                }
                compose.onNodeWithText(context.getString(S.pathHashModeLabel)).performScrollTo().performClick()
                compose.onAllNodesWithText(context.getString(text)).onLast().performClick()
            }
            mount()
            choose(S.pathHashModeOneByte)
            h.settle()
            assertTrue(h.links.radios.single().sent.none { it[0] == 0x3d.toUByte() })
            assertFalse(service.pathHashModeNeedsRecovery)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_recreated_hash BEFORE UPDATE ON devices " +
                    "BEGIN SELECT RAISE(FAIL, 'recreated Settings storage failure'); END",
            )
            armed = true
            choose(S.pathHashModeThreeBytes)
            h.eventually("verified radio change waiting on Room") { entered.isCompleted }
            assertEquals(2, mode)
            assertEquals(before.pathHashMode, store.fetchDevice(before.id)!!.pathHashMode)
            compose.runOnUiThread { context.setContent {} }
            compose.waitForIdle()
            assertTrue(service.pathHashModeNeedsRecovery)
            release.complete(Unit)
            h.eventually("held Room write failed after disposal") { finished.isCompleted }
            assertEquals(before.pathHashMode, store.fetchDevice(before.id)!!.pathHashMode)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_recreated_hash")
            mount()
            choose(S.pathHashModeOneByte)
            h.eventually("fresh actual Settings restores and acknowledges mode zero") {
                mode == 0 && !service.pathHashModeNeedsRecovery
            }
            assertEquals(before.pathHashMode, store.fetchDevice(before.id)!!.pathHashMode)
            assertEquals(before.pathHashMode, h.manager.connectedDevice?.pathHashMode)
            assertEquals(listOf(2, 0), h.links.radios.single().sent
                .filter { it[0] == 0x3d.toUByte() }.map { it[2].toInt() })
        } finally {
            release.complete(Unit)
            compose.runOnUiThread { context.setContent {} }
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_recreated_hash")
            h.container.close()
            storage.close()
        }
    }
}
