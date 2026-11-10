// AndroidOnly: WP-316 Source binding of ported tests to frozen Swift case ids (same mechanism as core:data/core:connectivity).
package com.meshcoreone.android.feature.tools.diagnostics.support

/**
 * Binds a JUnit method to the frozen Swift case id (`Suite::name()` in the pinned Swift sources). The XML names
 * the method; this annotation names the original case and how it was carried:
 * `source-behavior` (same assertions), `adapted-service-fake` (a feature-port fake stands in for
 * the real core:services collaborator), `platform-adaptation` (Android-specific shape, same
 * behavior). Parameterized Swift cases run every source argument inside the one bound method.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
internal annotation class OriginalCase(val value: String, val disposition: String = "source-behavior")
