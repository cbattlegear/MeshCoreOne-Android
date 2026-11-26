// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/MessageTranslationChrome.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

/**
 * Placeholder for WP-406: the in-bubble Translation offer value that WP-213 carries from
 * [MessageBuildInputs] onto [MessageTextPayload]. Only the value shape is ported here; the Swift
 * `resolved(detected:phase:preferredLanguageCode:)` decision depends on `DetectedLanguage` and
 * `MessageLanguageDetector`, which WP-406 owns and must add. Stored message text is never replaced.
 */
data class MessageTranslationChrome(val phase: Phase, val sourceLanguageCode: String) {
    sealed interface Phase {
        data object Offer : Phase
        data object InProgress : Phase
        data class Showing(val translatedText: String, val targetLanguageCode: String) : Phase
    }
}
