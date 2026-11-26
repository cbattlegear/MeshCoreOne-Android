// PortedFrom: MC1Tests/Utilities/URLSafetyCheckerTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.content

import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlSafetyCheckerTest {
    /** Builds a fake resolved [InetAddress] for a dotted-decimal IPv4 test vector (no DNS). */
    private fun addr(literal: String): InetAddress =
        InetAddress.getByAddress(literal, literal.split(".").map { it.toInt().toByte() }.toByteArray())

    // MARK: - Scheme validation

    @Test
    fun `Allows HTTPS URLs`() = runTest {
        assertTrue(UrlSafetyChecker.isSafe("https://example.com/page") { listOf(addr("93.184.216.34")) })
    }

    @Test
    fun `Allows HTTP URLs`() = runTest {
        assertTrue(UrlSafetyChecker.isSafe("http://example.com/page") { listOf(addr("93.184.216.34")) })
    }

    @Test
    fun `Rejects FTP scheme`() = runTest {
        assertFalse(UrlSafetyChecker.isSafe("ftp://example.com/file"))
    }

    @Test
    fun `Rejects file scheme`() = runTest {
        assertFalse(UrlSafetyChecker.isSafe("file:///etc/passwd"))
    }

    @Test
    fun `Rejects javascript scheme`() = runTest {
        assertFalse(UrlSafetyChecker.isSafe("javascript:alert(1)"))
    }

    // MARK: - Host validation

    @Test
    fun `Rejects URL with no host`() = runTest {
        assertFalse(UrlSafetyChecker.isSafe("https://"))
    }

    // MARK: - Allow-listed hosts

    @Test
    fun `Allows media giphy com`() = runTest {
        assertTrue(UrlSafetyChecker.isSafe("https://media.giphy.com/media/abc123/giphy.gif"))
    }

    @Test
    fun `Allows i giphy com`() = runTest {
        assertTrue(UrlSafetyChecker.isSafe("https://i.giphy.com/media/abc123/giphy.gif"))
    }

    // MARK: - Private-reserved IP detection (IPv4)

    @Test
    fun `Detects loopback 127 0 0 1`() = assertTrue(UrlSafetyChecker.isPrivateOrReserved("127.0.0.1"))

    @Test
    fun `Detects loopback 127 255 255 255`() = assertTrue(UrlSafetyChecker.isPrivateOrReserved("127.255.255.255"))

    @Test
    fun `Detects 10_0_0_0 slash8`() {
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("10.0.0.1"))
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("10.255.255.255"))
    }

    @Test
    fun `Detects 172_16_0_0 slash12`() {
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("172.16.0.1"))
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("172.31.255.255"))
        assertFalse(UrlSafetyChecker.isPrivateOrReserved("172.32.0.1"))
    }

    @Test
    fun `Detects 192_168_0_0 slash16`() {
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("192.168.0.1"))
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("192.168.255.255"))
    }

    @Test
    fun `Detects link-local 169_254_0_0 slash16`() {
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("169.254.1.1"))
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("169.254.169.254"))
    }

    @Test
    fun `Detects 0_0_0_0`() = assertTrue(UrlSafetyChecker.isPrivateOrReserved("0.0.0.0"))

    @Test
    fun `Detects multicast 224_0_0_0 slash4`() {
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("224.0.0.1"))
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("239.255.255.255"))
    }

    @Test
    fun `Detects reserved 240_0_0_0 slash4`() {
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("240.0.0.1"))
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("255.255.255.254"))
    }

    @Test
    fun `Allows public IPv4 addresses`() {
        assertFalse(UrlSafetyChecker.isPrivateOrReserved("8.8.8.8"))
        assertFalse(UrlSafetyChecker.isPrivateOrReserved("1.1.1.1"))
        assertFalse(UrlSafetyChecker.isPrivateOrReserved("93.184.216.34"))
    }

    // MARK: - Private-reserved IP detection (IPv6)

    @Test
    fun `Detects IPv6 loopback colon colon1`() = assertTrue(UrlSafetyChecker.isPrivateOrReserved("::1"))

    @Test
    fun `Detects IPv6 unspecified colon colon`() = assertTrue(UrlSafetyChecker.isPrivateOrReserved("::"))

    @Test
    fun `Detects IPv6 link-local fe80 colon colon`() {
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("fe80::1"))
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("fe80::abcd:ef01:2345:6789"))
    }

    @Test
    fun `Detects IPv6 unique local fc00 colon colon slash7`() {
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("fc00::1"))
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("fd00::1"))
    }

    @Test
    fun `Detects IPv4-mapped IPv6 addresses`() {
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("::ffff:127.0.0.1"))
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("::ffff:192.168.1.1"))
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("::ffff:10.0.0.1"))
        assertFalse(UrlSafetyChecker.isPrivateOrReserved("::ffff:8.8.8.8"))
    }

    @Test
    fun `Detects IPv6 multicast ff00 colon colon slash8`() {
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("ff02::1"))
        assertTrue(UrlSafetyChecker.isPrivateOrReserved("ff05::2"))
    }

    @Test
    fun `Allows public IPv6 addresses`() {
        assertFalse(UrlSafetyChecker.isPrivateOrReserved("2001:4860:4860::8888"))
        assertFalse(UrlSafetyChecker.isPrivateOrReserved("2606:4700:4700::1111"))
    }

    // MARK: - Non-IP hostnames

    @Test
    fun `Non-IP strings are not private`() {
        assertFalse(UrlSafetyChecker.isPrivateOrReserved("example.com"))
        assertFalse(UrlSafetyChecker.isPrivateOrReserved("localhost"))
    }

    // MARK: - IP literal URLs

    @Test
    fun `Rejects URL with private IP literal`() = runTest {
        assertFalse(UrlSafetyChecker.isSafe("http://192.168.1.1/admin"))
    }

    @Test
    fun `Rejects URL with loopback IP literal`() = runTest {
        assertFalse(UrlSafetyChecker.isSafe("http://127.0.0.1:8080/api"))
    }

    @Test
    fun `Rejects metadata endpoint`() = runTest {
        assertFalse(UrlSafetyChecker.isSafe("http://169.254.169.254/latest/meta-data/"))
    }

    @Test
    fun `Rejects private IP with port`() = runTest {
        assertFalse(UrlSafetyChecker.isSafe("http://10.0.0.1:3000/api"))
    }

    // MARK: - DNS resolution (injected resolver; no real network access)

    @Test
    fun `Rejects host resolving only to private addresses`() = runTest {
        assertFalse(UrlSafetyChecker.isSafe("https://rebinds-to-private.example/") { listOf(addr("10.1.2.3")) })
    }

    @Test
    fun `Rejects host resolving to any private address among several`() = runTest {
        assertFalse(
            UrlSafetyChecker.isSafe("https://multi.example/") {
                listOf(addr("8.8.8.8"), addr("127.0.0.1"))
            },
        )
    }

    @Test
    fun `Allows host resolving only to public addresses`() = runTest {
        assertTrue(UrlSafetyChecker.isSafe("https://multi.example/") { listOf(addr("8.8.8.8"), addr("1.1.1.1")) })
    }

    @Test
    fun `Fails closed on DNS resolution failure`() = runTest {
        assertFalse(
            UrlSafetyChecker.isSafe("https://does-not-resolve.example/") {
                throw java.net.UnknownHostException("test")
            },
        )
    }
}
