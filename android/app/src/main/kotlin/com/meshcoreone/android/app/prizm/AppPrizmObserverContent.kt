// AndroidOnly: WP-101 Prizm Mobile Observer Compose host: permissions, holders and the global claim prompt.
package com.meshcoreone.android.app.prizm

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.meshcoreone.android.app.MeshCoreApplication
import com.meshcoreone.android.feature.settings.prizm.PrizmObserverStateHolder
import com.meshcoreone.android.feature.settings.prizm.PrizmPermissionRequester
import com.meshcoreone.android.feature.settings.prizm.PrizmPrivacyStateHolder
import com.meshcoreone.android.feature.settings.prizm.ui.PrizmClaimDialogs
import com.meshcoreone.android.feature.settings.prizm.ui.PrizmObserverScreen
import com.meshcoreone.android.feature.settings.prizm.ui.PrizmPrivacyScreen

/** Which runtime permissions an action needs; kept pure for tests. */
internal object PrizmPermissions {
    fun required(needsLocation: Boolean, needsNotifications: Boolean, sdk: Int): List<String> = buildList {
        if (needsLocation) add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needsNotifications && sdk >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** Settings > Prizm Observer and, one level down, Privacy. */
@Composable
internal fun AppPrizmObserverContent(onDone: () -> Unit) {
    val context = LocalContext.current
    val handle = (context.applicationContext as? MeshCoreApplication)?.prizm
    if (handle == null) { onDone(); return }
    val scope = rememberCoroutineScope()
    var privacy by remember { mutableStateOf(false) }
    var pendingResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        pendingResult?.invoke(granted.values.all { it }); pendingResult = null
    }
    val requester = remember(launcher) {
        PrizmPermissionRequester { location, notifications, onResult ->
            val needed = PrizmPermissions.required(location, notifications, Build.VERSION.SDK_INT)
                .filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (needed.isEmpty()) onResult(true) else { pendingResult = onResult; launcher.launch(needed.toTypedArray()) }
        }
    }
    val holder = remember(handle) { PrizmObserverStateHolder(handle.runtime, scope, requester) }
    val privacyHolder = remember(handle) { PrizmPrivacyStateHolder(handle.runtime, scope) }
    BackHandler(privacy) { privacy = false }
    if (privacy) PrizmPrivacyScreen(privacyHolder) { privacy = false }
    else PrizmObserverScreen(holder, handle.claim, onOpenPrivacy = { privacy = true }, onDone = onDone)
}

/** Draws the claim dialogs on top of whatever is showing, once a signed-in user connects an unclaimed node. */
@Composable
internal fun PrizmClaimHost() {
    val handle = (LocalContext.current.applicationContext as? MeshCoreApplication)?.prizm ?: return
    val state by handle.runtime.state.collectAsState()
    PrizmClaimDialogs(state, handle.claim)
}
