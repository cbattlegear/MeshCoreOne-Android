// PortedFrom: MC1Tests/Views/Chats/RegionNameValidatorTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.ui

import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RegionNameValidatorOriginalTest(
    private val name: String,
    private val existingRegions: List<String>,
    private val expected: RegionValidationError?,
) {
    @Test
    fun `validates the original region input`() {
        assertEquals(expected, RegionNameValidator.validate(name, existingRegions))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: name={0}, existing={1}, expected={2}")
        fun cases(): List<Array<Any?>> = listOf(
            arrayOf("Europe", emptyList<String>(), null),
            arrayOf("UK", emptyList<String>(), null),
            arrayOf("France", emptyList<String>(), null),
            arrayOf("sample-city", emptyList<String>(), null),
            arrayOf("region-1", emptyList<String>(), null),
            arrayOf("", emptyList<String>(), RegionValidationError.Empty),
            arrayOf("   ", emptyList<String>(), RegionValidationError.Empty),
            arrayOf("my region", emptyList<String>(), RegionValidationError.InvalidCharacters),
            arrayOf("\u00CEle-de-France", emptyList<String>(), RegionValidationError.InvalidCharacters),
            arrayOf("hello!", emptyList<String>(), RegionValidationError.InvalidCharacters),
            arrayOf("foo@bar", emptyList<String>(), RegionValidationError.InvalidCharacters),
            arrayOf("a&b", emptyList<String>(), RegionValidationError.InvalidCharacters),
            arrayOf("test.region", emptyList<String>(), RegionValidationError.InvalidCharacters),
            arrayOf("#Europe", emptyList<String>(), RegionValidationError.InvalidCharacters),
            arrayOf("\$secret", emptyList<String>(), RegionValidationError.InvalidCharacters),
            arrayOf("Europe", listOf("Europe"), RegionValidationError.Duplicate),
            arrayOf("europe", listOf("Europe"), null),
            arrayOf("a".repeat(30), emptyList<String>(), null),
            arrayOf("a".repeat(31), emptyList<String>(), RegionValidationError.TooLong(30)),
        )
    }
}
