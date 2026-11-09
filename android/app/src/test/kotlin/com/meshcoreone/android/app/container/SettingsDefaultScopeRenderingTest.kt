// AndroidOnly: WP-317 Actual API31 Compose selection semantics for normalized scope identities and full aliases.
package com.meshcoreone.android.app.container

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.settings.device.*
import com.meshcoreone.android.feature.settings.device.ui.FloodScopeSection
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Config(sdk = [31], qualifiers = "w360dp-h900dp")
class SettingsDefaultScopeRenderingTest : RoomProcessTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun normalizedAliasesRenderOneCheckedIdentityAndKeepEveryFullName() = runTest {
        val prefix = "A".repeat(30)
        val device = DeviceDTO(
            radioId = RadioId(UUID(0, 1)), publicKey = Bytes(ByteArray(32) { 1 }), nodeName = "Scope radio",
            firmwareVersion = 11u, defaultFloodScopeName = prefix,
            knownRegions = listOf(prefix, prefix + "one", prefix + "two").snapshot(),
        )
        val devices = MutableStateFlow<DeviceDTO?>(device)
        val connection = object : SettingsConnection {
            override val connectedDevice = devices
            override val connectionState = MutableStateFlow(DeviceConnectionState.READY)
            override val startupReadsAllowed = MutableStateFlow(true)
        }
        val store = object : DeviceSettingsStorePort {
            override suspend fun updateOcvSettings(deviceId: UUID, preset: String, customArray: String?) = error("No OCV action")
            override fun savePreRepeatSettings() = error("No repeat action")
            override fun clearPreRepeatSettings() = error("No repeat action")
            override fun addKnownRegion(region: String) = error("No region edit")
            override fun removeKnownRegion(region: String) = error("No region edit")
        }
        val holder = DefaultFloodScopeStateHolder(
            SettingsEnvironment(backgroundScope), connection, { null },
            RegionDiscoveryPort { _, _ -> error("No discovery action") }, store,
        )
        holder.start()
        runCurrent()
        try {
            compose.runOnUiThread {
                compose.activity.setContent {
                    MeshCoreTheme { FloodScopeSection(holder, SettingsNavigator { error("No navigation action") }) }
                }
            }
            compose.onAllNodesWithText(prefix).assertCountEquals(1)
            compose.onAllNodes(isSelected()).assertCountEquals(1)
            compose.onNode(isSelected()).assert(hasText(prefix))
            compose.onAllNodesWithText(prefix + "one").assertCountEquals(1)
            compose.onAllNodesWithText(prefix + "two").assertCountEquals(1)
            for ((raw, identity) in listOf(
                prefix + "one" to prefix,
                "é".repeat(15) + "suffix" to "é".repeat(15),
                "A".repeat(29) + "é-tail" to "A".repeat(29),
            )) {
                devices.value = device.copy(
                    defaultFloodScopeName = raw, knownRegions = listOf(identity, raw).snapshot(),
                )
                runCurrent()
                compose.onAllNodes(isSelected()).assertCountEquals(1)
                compose.onNode(isSelected()).assert(hasText(identity))
                compose.onAllNodesWithText(raw).assertCountEquals(1)
            }
            devices.value = device.copy(defaultFloodScopeName = null)
            runCurrent()
            compose.onAllNodes(isSelected()).assertCountEquals(1)
            compose.onNode(isSelected()).assert(hasText(compose.activity.getString(S.defaultFloodScopeDisabled)))
        } finally {
            holder.stop()
            compose.runOnUiThread { compose.activity.setContent {} }
        }
    }
}
