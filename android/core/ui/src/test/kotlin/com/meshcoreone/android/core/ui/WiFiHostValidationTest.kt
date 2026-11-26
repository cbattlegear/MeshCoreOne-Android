// PortedFrom: MC1Tests/Views/Components/WiFiHostValidationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ui

import kotlin.test.*
import org.junit.Test

class WiFiHostValidationTest : SourceCaseProof() {
    @OriginalCase("WiFiHostValidationTests::classifies host(_ host : String , _ expected : Bool)", 9)
    @Test fun classifiesSourceHosts() = prove(9) {
        for ((host, expected) in listOf("192.168.1.50" to true, "radio.local" to true,
            "example.com" to true, "repeater" to true, "  radio.local  " to true, "" to false,
            "999.999.999.999" to false, "192.168.1" to false, "http://radio.local" to false)) {
            assertEquals(expected, WiFiAddressValidation.isValidHost(host), host)
        }
    }

    @Test fun hostnamePortWhitespaceAndKeyboardReplacementBoundariesAreExplicit() {
        for (host in listOf("-radio.local", "radio-.local", "radio..local", "radio.local..",
            "\u706f\u706b.local", "http://radio", "radio/local", "a".repeat(64) + ".local")) {
            assertFalse(WiFiAddressValidation.isValidHost(host), host)
        }
        assertTrue(WiFiAddressValidation.isValidHost("a".repeat(63) + ".local."))
        assertTrue(WiFiAddressValidation.isValidHost("\n radio.local \t"))
        assertTrue(WiFiAddressValidation.isValidIPAddress("0.0.0.0"))
        assertTrue(WiFiAddressValidation.isValidIPAddress("255.255.255.255"))
        assertFalse(WiFiAddressValidation.isValidIPAddress("256.1.1.1"))
        for (port in listOf("", "0", "-1", "65536", "5000 ", "5.0", "999999999999999999999")) {
            assertFalse(WiFiAddressValidation.isValidPort(port), port)
        }
        for (port in listOf("1", "5000", "65535", "+5000")) assertTrue(WiFiAddressValidation.isValidPort(port))
        assertEquals("192.168.1.50", WiFiAddressValidation.replaceCommas("192,168,1,50"))
    }
}
