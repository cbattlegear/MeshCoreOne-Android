// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/EnvInputs.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import java.util.Locale

/**
 * The `AppStorageKey.default*` values `EnvInputs` uses. core:services cannot read core:datastore, so the
 * Swift defaults are restated here verbatim and the live values arrive through [EnvInputs] parameters.
 */
object EnvInputsPreferenceDefaults {
    const val AUTO_PLAY_GIFS: Boolean = true
    const val SHOW_INCOMING_PATH: Boolean = false
    const val SHOW_INCOMING_HOP_COUNT: Boolean = false
    const val SHOW_INCOMING_REGION: Boolean = false
    const val SHOW_INCOMING_HEARD_COUNT: Boolean = false
    const val SHOW_INCOMING_SEND_TIME: Boolean = false
    const val LINK_PREVIEWS_ENABLED: Boolean = false
    const val SHOW_MAP_PREVIEW_THUMBNAILS: Boolean = true
    const val TRANSLATION_OFFERS_ENABLED: Boolean = true
}

/**
 * Environment-derived inputs that influence [MessageItem] content (preferences, contrast, appearance,
 * device name). Any field change makes the value unequal, which forces a full rebuild.
 */
data class EnvInputs(
    val autoPlayGIFs: Boolean,
    val showIncomingPath: Boolean,
    val showIncomingHopCount: Boolean,
    val showIncomingRegion: Boolean,
    val showIncomingHeardCount: Boolean,
    val showIncomingSendTime: Boolean,
    val previewsEnabled: Boolean,
    val isHighContrast: Boolean,
    /** Light/dark appearance, threaded into [MapPreviewFragmentState]. */
    val isDark: Boolean,
    /** Privacy gate: when false the builder emits no map-preview fragment (no third-party tile request). */
    val showMapPreviews: Boolean,
    /** True while offline, so map snapshots use the offline style and cache key. */
    val isOffline: Boolean,
    val currentUserName: String,
    /** Active theme identifier token (never a UI colour). */
    val themeID: String,
    /** Dynamic-type size token; a change forces a full rebuild. */
    val contentSizeCategory: String,
    /** App-locale language code (`en`, `de`, `zh`), never a region qualifier. */
    val preferredLanguageCode: String,
    /** User toggle for in-bubble Translate offers. */
    val translationOffersEnabled: Boolean = EnvInputsPreferenceDefaults.TRANSLATION_OFFERS_ENABLED,
) {
    companion object {
        /** Identifier of the built-in default theme. */
        const val DEFAULT_THEME_ID: String = "default"

        /** The unscaled dynamic-type baseline token. */
        const val DEFAULT_CONTENT_SIZE_CATEGORY: String = "large"

        /** Fallback when the locale carries no language code. */
        const val DEFAULT_PREFERRED_LANGUAGE_CODE: String = "en"

        /** App-locale language subtag used as the Translation target (`en-US` → `en`, `zh-Hans` → `zh`). */
        fun preferredLanguageCode(locale: Locale): String = locale.language.ifEmpty { DEFAULT_PREFERRED_LANGUAGE_CODE }

        val DEFAULT: EnvInputs = EnvInputs(
            autoPlayGIFs = EnvInputsPreferenceDefaults.AUTO_PLAY_GIFS,
            showIncomingPath = EnvInputsPreferenceDefaults.SHOW_INCOMING_PATH,
            showIncomingHopCount = EnvInputsPreferenceDefaults.SHOW_INCOMING_HOP_COUNT,
            showIncomingRegion = EnvInputsPreferenceDefaults.SHOW_INCOMING_REGION,
            showIncomingHeardCount = EnvInputsPreferenceDefaults.SHOW_INCOMING_HEARD_COUNT,
            showIncomingSendTime = EnvInputsPreferenceDefaults.SHOW_INCOMING_SEND_TIME,
            previewsEnabled = EnvInputsPreferenceDefaults.LINK_PREVIEWS_ENABLED,
            isHighContrast = false,
            isDark = false,
            showMapPreviews = EnvInputsPreferenceDefaults.SHOW_MAP_PREVIEW_THUMBNAILS,
            isOffline = false,
            currentUserName = "",
            themeID = DEFAULT_THEME_ID,
            contentSizeCategory = DEFAULT_CONTENT_SIZE_CATEGORY,
            preferredLanguageCode = DEFAULT_PREFERRED_LANGUAGE_CODE,
        )
    }
}
