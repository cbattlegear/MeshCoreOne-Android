// PortedFrom: MC1/Views/Components/ErrorAlertModifier.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Components/ErrorBannerModifier.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Components/AsyncActionLabel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Components/DeletingRowOverlay.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Components/SectionReloadButton.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Components/SelectedRowHighlight.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Extensions/View+LiquidGlass.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: Material surfaces and consume-once system/cutout/IME insets replace Apple chrome.
package com.meshcoreone.android.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.designsystem.NativeThemeMetrics
import com.meshcoreone.android.core.designsystem.toComposeColor
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.CommittedBackupPreferenceFailure

val SharedUiErrorCode = SemanticsPropertyKey<String>("SharedUiErrorCode")
var SemanticsPropertyReceiver.sharedUiErrorCode by SharedUiErrorCode
val SharedTipId = SemanticsPropertyKey<String>("SharedTipId")
var SemanticsPropertyReceiver.sharedTipId by SharedTipId

fun Modifier.sharedTouchTarget(): Modifier = sizeIn(
    minWidth = NativeThemeMetrics.MINIMUM_TOUCH_TARGET.dp,
    minHeight = NativeThemeMetrics.MINIMUM_TOUCH_TARGET.dp,
)

@Composable
fun SharedUiScaffold(
    modifier: Modifier = Modifier,
    insets: WindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
            topBar()
            Column(Modifier.weight(1f).fillMaxWidth(), content = content)
            bottomBar()
        }
    }
}

@Composable
fun ErrorAlert(
    error: UiErrorState?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: UiText = UiText.Resource(AppSettingsStrings.alertErrorTitle),
    onRetry: (() -> Unit)? = null,
    recoveryLabel: UiText = UiText.Resource(AppLocalizableStrings.commonTryAgain),
) {
    if (error == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.semantics { sharedUiErrorCode = error.code },
        icon = { Icon(MeshSymbol.ERROR.vector, null) },
        title = { Text(uiString(title)) },
        text = { Text(uiString(error.message)) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sharedTouchTarget()) {
                Text(uiString(UiText.Resource(AppLocalizableStrings.commonOk)))
            }
        },
        dismissButton = onRetry?.let { retry ->
            {
                TextButton(onClick = { onDismiss(); retry() }, modifier = Modifier.sharedTouchTarget()) {
                    Text(uiString(recoveryLabel))
                }
            }
        },
    )
}

data class UiErrorActions(
    val retry: ((Throwable) -> Unit)? = null,
    val completeBackupPreferences: ((CommittedBackupPreferenceFailure) -> Unit)? = null,
    val unlockDevice: (() -> Unit)? = null,
    val restoreSecureData: ((Throwable) -> Unit)? = null,
)

fun PresentedUiError.recoveryAction(actions: UiErrorActions): (() -> Unit)? =
    if (originalFailure is CommittedBackupPreferenceFailure) {
        val failure = originalFailure
        require(content.recovery == UiRecovery.COMPLETE_BACKUP_PREFERENCES &&
            content.committedBackupReceipt == failure.committedReceipt) { "Committed preference recovery cannot retry an import or replace its receipt" }
        actions.completeBackupPreferences?.let { complete -> { complete(failure) } }
    } else if (content.recovery == UiRecovery.RETRY) {
        actions.retry?.let { retry -> { retry(originalFailure) } }
    } else if (content.recovery == UiRecovery.UNLOCK_DEVICE) actions.unlockDevice
    else if (content.recovery == UiRecovery.RESTORE_SECURE_DATA) {
        actions.restoreSecureData?.let { restore -> { restore(originalFailure) } }
    } else null

@Composable
fun PresentedErrorAlert(
    error: PresentedUiError?,
    onDismiss: () -> Unit,
    actions: UiErrorActions,
    modifier: Modifier = Modifier,
) {
    ErrorAlert(error?.content, onDismiss, modifier, onRetry = error?.recoveryAction(actions),
        recoveryLabel = when (error?.content?.recovery) {
            UiRecovery.COMPLETE_BACKUP_PREFERENCES -> UiText.Resource(R.string.ui_backup_complete_preferences)
            UiRecovery.UNLOCK_DEVICE -> UiText.Resource(R.string.ui_storage_unlock_device)
            UiRecovery.RESTORE_SECURE_DATA -> UiText.Resource(AppSettingsStrings.settingsBackupImportTitle)
            else -> UiText.Resource(AppLocalizableStrings.commonTryAgain)
        })
}

@Composable
fun ErrorBanner(error: UiErrorState?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (error == null) return
    val message = uiString(error.message)
    val hint = uiString(UiText.Resource(AppChatsStrings.chatsErrorBannerDismissAccessibilityHint))
    Surface(
        onClick = onDismiss,
        modifier = modifier.fillMaxWidth().sharedTouchTarget().semantics {
            sharedUiErrorCode = error.code
            liveRegion = LiveRegionMode.Polite
            stateDescription = hint
        },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(MeshSymbol.ERROR.vector, null)
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun AsyncActionLabel(
    isLoading: Boolean,
    showSuccess: Boolean,
    loadingLabel: UiText,
    successLabel: UiText,
    modifier: Modifier = Modifier,
    idle: @Composable RowScope.() -> Unit,
) {
    val status = if (isLoading) uiString(loadingLabel) else if (showSuccess) uiString(successLabel) else null
    Row(modifier.fillMaxWidth().sharedTouchTarget().semantics {
        if (status != null) stateDescription = status
        if (isLoading) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        when {
            isLoading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            showSuccess -> Icon(MeshSymbol.READY.vector, status, tint = MaterialTheme.colorScheme.primary)
            else -> idle()
        }
    }
}

@Composable
fun DeletingRowOverlay(
    isDeleting: Boolean,
    progressLabel: UiText,
    modifier: Modifier = Modifier,
    content: @Composable (enabled: Boolean) -> Unit,
) {
    val label = uiString(progressLabel)
    Box(modifier.semantics {
        if (isDeleting) {
            disabled()
            stateDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        }
    }) {
        content(!isDeleting)
        if (isDeleting) CircularProgressIndicator(
            Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(24.dp),
            strokeWidth = 2.dp,
        )
    }
}

@Composable
fun SectionReloadButton(
    isLoading: Boolean,
    isLoaded: Boolean,
    hasError: Boolean,
    isDisabled: Boolean,
    accessibilityLabel: UiText,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = uiString(accessibilityLabel)
    if (isLoading) {
        Box(modifier.sharedTouchTarget().semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        }, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    } else if (isLoaded || hasError) {
        IconButton(onClick = onReload, enabled = !isDisabled, modifier = modifier.sharedTouchTarget()) {
            Icon(MeshSymbol.SYNC.vector, label)
        }
    }
}

@Composable
fun Modifier.selectedRowHighlight(isSelected: Boolean): Modifier {
    val accent = LocalMeshTheme.current.frame.accent.toComposeColor()
    return semantics { selected = isSelected }.drawBehind {
        if (isSelected) drawRoundRect(
            accent.copy(alpha = 0.18f), Offset(8.dp.toPx(), 2.dp.toPx()),
            Size((size.width - 16.dp.toPx()).coerceAtLeast(0f), (size.height - 4.dp.toPx()).coerceAtLeast(0f)),
            CornerRadius(10.dp.toPx()),
        )
    }
}

@Composable
fun SharedTipContent(tip: SharedTip?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (tip == null) return
    Surface(modifier.fillMaxWidth().semantics { sharedTipId = tip.sourceId },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                uiString(UiText.Resource(tip.titleResource)),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(uiString(UiText.Resource(tip.messageResource)), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).sharedTouchTarget()) {
                Text(uiString(UiText.Resource(AppLocalizableStrings.commonOk)))
            }
        }
    }
}
