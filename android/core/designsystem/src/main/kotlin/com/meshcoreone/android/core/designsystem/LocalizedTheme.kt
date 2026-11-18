// PortedFrom: MC1/Theme/Theme+LocalizedName.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Extensions/SNRQuality+Color.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.designsystem

import android.content.res.Resources
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings

fun Theme.localizedName(resources: Resources): String = when (id) {
    ThemeId.DEFAULT -> resources.getString(AppSettingsStrings.supportThemeDefault)
    ThemeId.EMBER -> resources.getString(AppSettingsStrings.supportThemeEmber)
    ThemeId.FERN -> resources.getString(AppSettingsStrings.supportThemeFern)
    ThemeId.MARINE -> resources.getString(AppSettingsStrings.supportThemeMarine)
    ThemeId.OLIVE -> resources.getString(AppSettingsStrings.supportThemeOlive)
    ThemeId.LAVENDER -> resources.getString(AppSettingsStrings.supportThemeLavender)
    ThemeId.SAKURA -> "Sakura"
    ThemeId.SOLARIZED -> "Solarized"
    ThemeId.NORD -> "Nord"
    ThemeId.CATPPUCCIN -> "Catppuccin"
    ThemeId.GRADIENT -> resources.getString(AndroidThemeStrings.gradientName)
}

val SignalColorRole.labelResource: Int get() = when (this) {
    SignalColorRole.EXCELLENT -> AppChatsStrings.chatsSignalExcellent
    SignalColorRole.GOOD -> AppChatsStrings.chatsSignalGood
    SignalColorRole.FAIR -> AppChatsStrings.chatsSignalFair
    SignalColorRole.POOR -> AppChatsStrings.chatsSignalPoor
    SignalColorRole.UNKNOWN -> AppChatsStrings.chatsPathHopSignalUnknown
}

val RadioColorRole.labelResource: Int get() = when (this) {
    RadioColorRole.DISCONNECTED -> AppSettingsStrings.bleStatusStatusDisconnected
    RadioColorRole.CONNECTING -> AppSettingsStrings.bleStatusStatusConnecting
    RadioColorRole.CONNECTED -> AppSettingsStrings.bleStatusStatusConnected
    RadioColorRole.SYNCING -> AppSettingsStrings.bleStatusStatusSyncing
    RadioColorRole.READY -> AppSettingsStrings.bleStatusStatusReady
    RadioColorRole.REPEAT -> AppSettingsStrings.bleStatusRepeatModeActive
    RadioColorRole.FAILED -> AppLocalizableStrings.commonErrorFailedToLoad
}

val AppColorSchemePreference.labelResource: Int get() = when (this) {
    AppColorSchemePreference.SYSTEM -> AppSettingsStrings.appearanceSchemeSystem
    AppColorSchemePreference.LIGHT -> AppSettingsStrings.appearanceSchemeLight
    AppColorSchemePreference.DARK -> AppSettingsStrings.appearanceSchemeDark
}
