// PortedFrom: MC1Tests/Views/Chats/RegionNameValidatorTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.settings

import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RegionNameValidatorOriginalTest(
    private val name: String,
    private val existingRegions: List<String>,
    private val expected: RegionNameValidator.ValidationError?,
) {
    @Test
    fun `validates the original region input`() {
        assertEquals(expected, RegionNameValidator.validate(name, existingRegions))
    }

    @Test
    fun `isValid preserves the original valid or invalid predicate`() {
        assertEquals(expected == null, RegionNameValidator.isValid(name, existingRegions))
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
            arrayOf("", emptyList<String>(), RegionNameValidator.ValidationError.Empty),
            arrayOf("   ", emptyList<String>(), RegionNameValidator.ValidationError.Empty),
            arrayOf("my region", emptyList<String>(), RegionNameValidator.ValidationError.InvalidCharacters),
            arrayOf("\u00CEle-de-France", emptyList<String>(), RegionNameValidator.ValidationError.InvalidCharacters),
            arrayOf("hello!", emptyList<String>(), RegionNameValidator.ValidationError.InvalidCharacters),
            arrayOf("foo@bar", emptyList<String>(), RegionNameValidator.ValidationError.InvalidCharacters),
            arrayOf("a&b", emptyList<String>(), RegionNameValidator.ValidationError.InvalidCharacters),
            arrayOf("test.region", emptyList<String>(), RegionNameValidator.ValidationError.InvalidCharacters),
            arrayOf("#Europe", emptyList<String>(), RegionNameValidator.ValidationError.InvalidCharacters),
            arrayOf("\$secret", emptyList<String>(), RegionNameValidator.ValidationError.InvalidCharacters),
            arrayOf("Europe", listOf("Europe"), RegionNameValidator.ValidationError.Duplicate),
            arrayOf("europe", listOf("Europe"), null),
            arrayOf("a".repeat(30), emptyList<String>(), null),
            arrayOf("a".repeat(31), emptyList<String>(), RegionNameValidator.ValidationError.TooLong(30)),
        )
    }
}
