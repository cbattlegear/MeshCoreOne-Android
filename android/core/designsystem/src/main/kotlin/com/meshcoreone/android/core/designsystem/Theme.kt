// PortedFrom: MC1/Theme/Theme.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Theme/ThemeRegistry.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Theme/AppColorSchemePreference.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Theme/AvatarCategory.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Theme/CategoryAvatarColors.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Theme/CategoryHues.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Theme/Theme+AvatarColors.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.designsystem

import com.meshcoreone.android.core.model.SnapshotList

enum class ThemeId(val rawValue: String) {
    DEFAULT("default"), EMBER("ember"), FERN("fern"), MARINE("marine"), OLIVE("olive"),
    LAVENDER("lavender"), SAKURA("sakura"), SOLARIZED("solarized"), NORD("nord"), CATPPUCCIN("catppuccin");

    companion object {
        fun fromRawValue(raw: String): ThemeId? = entries.firstOrNull { it.rawValue == raw }
    }
}

enum class AppColorSchemePreference(val rawValue: String, val colorScheme: ColorScheme?) {
    SYSTEM("system", null), LIGHT("light", ColorScheme.LIGHT), DARK("dark", ColorScheme.DARK);

    val id: String get() = rawValue

    companion object {
        fun fromRawValue(raw: String): AppColorSchemePreference? = entries.firstOrNull { it.rawValue == raw }
    }
}

enum class AvatarCategory(val gamutSeed: String) {
    CHANNEL("__avatar_category_channel__"),
    REPEATER("__avatar_category_repeater__"),
    ROOM("__avatar_category_room__");
    companion object {
        val anchorPriority = SnapshotList.of(CHANNEL, REPEATER, ROOM)
    }
}

data class CategoryAvatarColors(val channel: ThemeColor, val repeaterNode: ThemeColor, val room: ThemeColor) {
    fun color(category: AvatarCategory): ThemeColor = when (category) {
        AvatarCategory.CHANNEL -> channel
        AvatarCategory.REPEATER -> repeaterNode
        AvatarCategory.ROOM -> room
    }
}

data class CategoryHues(val channel: Double, val repeater: Double, val room: Double) {
    fun hue(category: AvatarCategory): Double = when (category) {
        AvatarCategory.CHANNEL -> channel
        AvatarCategory.REPEATER -> repeater
        AvatarCategory.ROOM -> room
    }
}

data class ThemeSurfaces(val canvas: AdaptiveColor, val card: AdaptiveColor? = null) {
    fun rowFill(flatten: Boolean): AdaptiveColor? = if (flatten) null else card
}

data class Theme(
    val id: ThemeId,
    val displayNameKey: String?,
    val sourceProductID: String?,
    val accentColor: AdaptiveColor,
    val outgoingTextColor: AdaptiveColor,
    val hashtagColor: AdaptiveColor,
    val preferredColorScheme: ColorScheme?,
    val identityGamut: IdentityGamut,
    val surfaces: ThemeSurfaces? = null,
    val categoryAvatarOverride: CategoryAvatarColors? = null,
    val categoryHues: CategoryHues? = null,
) {
    val chromeTint: AdaptiveColor? get() = if (id == ThemeId.DEFAULT) null else accentColor
    val usesCategoryAvatarOverride: Boolean get() = categoryAvatarOverride != null

    fun effectiveColorScheme(preference: AppColorSchemePreference): ColorScheme? =
        preferredColorScheme ?: preference.colorScheme

    fun categoryHue(category: AvatarCategory): Double =
        categoryHues?.hue(category) ?: identityGamut.distinctAnchorHues(
            AvatarCategory.anchorPriority.map { it.gamutSeed },
        )[AvatarCategory.anchorPriority.indexOf(category)]

    fun resolve(scheme: ColorScheme, highContrast: Boolean): ThemeFrame {
        val effective = preferredColorScheme ?: scheme
        val system = NativeSystemSurfaces.forAppearance(effective, highContrast)
        return ThemeFrame(
            this, effective, highContrast, accentColor.resolve(effective, highContrast),
            outgoingTextColor.resolve(effective, highContrast), hashtagColor.resolve(effective, highContrast),
            surfaces?.canvas?.resolve(effective, highContrast) ?: system.canvas,
            surfaces?.card?.resolve(effective, highContrast) ?: system.card,
            surfaces?.card?.resolve(effective, highContrast) ?: system.incomingBubble,
        )
    }
}

data class ThemeFrame(
    val theme: Theme,
    val colorScheme: ColorScheme,
    val highContrast: Boolean,
    val accent: ThemeColor,
    val outgoingText: ThemeColor,
    val hashtag: ThemeColor,
    val canvas: ThemeColor,
    val card: ThemeColor,
    val incomingBubble: ThemeColor,
) {
    val avatarSurfaceLuminances: SnapshotList<Double> get() = SnapshotList.of(canvas.luminance, incomingBubble.luminance)
    val categorySurfaceLuminance: Double get() = canvas.luminance
    fun identityColor(name: String): ThemeColor =
        theme.identityGamut.color(name, avatarSurfaceLuminances, highContrast)
    fun categoryAvatarColor(category: AvatarCategory): ThemeColor =
        theme.categoryAvatarOverride?.color(category) ?: theme.identityGamut.color(
            category.gamutSeed, listOf(categorySurfaceLuminance), highContrast,
            atHue = theme.categoryHue(category), atVariety = 0.0,
        )
    fun avatarGlyphColor(fill: ThemeColor, usesCategoryOverride: Boolean): ThemeColor =
        if (usesCategoryOverride) ThemeColor.WHITE else IdentityGamut.glyphColor(fill.luminance)
}

object ThemeRegistry {
    val allThemes: SnapshotList<Theme> get() = GeneratedThemes.allThemes
    val default: Theme get() = theme(ThemeId.DEFAULT) ?: throw ThemeResourceFailure("default")
    fun theme(id: ThemeId): Theme? = allThemes.firstOrNull { it.id == id }
    fun theme(raw: String): Theme? = ThemeId.fromRawValue(raw)?.let(::theme)
}

class ThemeResourceFailure(val resource: String) : Exception("Missing theme resource: $resource")
