// AndroidOnly: WP-317 Source binding of ported tests to frozen Swift case ids (same mechanism as feature:tools / core:data).
package com.meshcoreone.android.feature.settings.device.support

/**
 * Binds a JUnit method to the frozen Swift case id (`Suite::name()` in the pinned Swift sources). Dispositions:
 * `source-behavior` (same assertions), `adapted-service-fake` (a feature-port fake stands in for the real
 * collaborator), `platform-adaptation` (Android-specific shape, same behavior).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
internal annotation class OriginalCase(val value: String, val disposition: String = "source-behavior")
