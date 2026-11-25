// PortedFrom: MC1/Services/RedirectSafetyDelegate.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// No dedicated Swift test file exists for RedirectSafetyDelegate at the pinned
// commit (MC1Tests has no RedirectSafetyDelegateTests.swift); independently authored to
// cover the policy's real contract against the actual UrlSafetyChecker wiring.
package com.meshcoreone.android.core.services.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class RedirectSafetyPolicyTest {
    @Test
    fun `Authorizes a redirect target the injected safety check approves`() = runTest {
        val url = "https://example.com/next"
        assertEquals(url, RedirectSafetyPolicy.authorize(url) { true })
    }

    @Test
    fun `Refuses a redirect target the injected safety check rejects`() = runTest {
        assertNull(RedirectSafetyPolicy.authorize("https://evil.example/next") { false })
    }

    @Test
    fun `Default wiring delegates to the real UrlSafetyChecker, refusing a loopback redirect`() = runTest {
        // No resolver override - exercises the real default-parameter wiring end-to-end.
        // 127.0.0.1 is rejected by UrlSafetyChecker's IP-literal check alone (no DNS needed).
        assertNull(RedirectSafetyPolicy.authorize("http://127.0.0.1/admin"))
    }

    @Test
    fun `Default wiring delegates to the real UrlSafetyChecker, approving a public IP literal`() = runTest {
        // A dotted-decimal public IP literal bypasses DNS entirely in UrlSafetyChecker, so this
        // exercises the real default wiring without any network access.
        val url = "https://93.184.216.34/page"
        assertEquals(url, RedirectSafetyPolicy.authorize(url))
    }

    @Test
    fun `Refuses a redirect to a non-HTTP scheme via the real default wiring`() = runTest {
        assertNull(RedirectSafetyPolicy.authorize("file:///etc/passwd"))
    }
}
