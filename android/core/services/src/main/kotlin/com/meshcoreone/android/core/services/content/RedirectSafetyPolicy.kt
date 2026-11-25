// PortedFrom: MC1/Services/RedirectSafetyDelegate.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.content

/**
 * Re-validates every HTTP redirect hop of [LinkPreviewService]'s scrape fetch against the
 * same SSRF allow-list [UrlSafetyChecker] applies to the initial URL. A default platform
 * HTTP client follows 3xx redirects automatically, so without re-validating each hop a URL
 * that passed the initial `isSafe` check could redirect to a private host and still be
 * fetched anyway.
 *
 * This is a pure-JVM policy object, not a platform redirect-handling hook: the Android HTTP
 * adapter (outside `core:services`) is responsible for intercepting each redirect before
 * following it and calling [authorize] with the `Location` target, refusing the hop when the
 * result is `null` - mirroring the original's `URLSessionTaskDelegate` callback, which either
 * passed the redirect request through unchanged or returned `nil` to cancel it.
 */
object RedirectSafetyPolicy {
    /**
     * Returns [redirectUrl] unchanged if it is safe to follow, or `null` if the redirect
     * target must be refused. [isSafe] defaults to the real [UrlSafetyChecker.isSafe] so
     * production callers get the full scheme/private-range/DNS-rebinding check; tests may
     * inject a deterministic override.
     */
    suspend fun authorize(
        redirectUrl: String,
        isSafe: suspend (String) -> Boolean = { url -> UrlSafetyChecker.isSafe(url) },
    ): String? = if (isSafe(redirectUrl)) redirectUrl else null
}
