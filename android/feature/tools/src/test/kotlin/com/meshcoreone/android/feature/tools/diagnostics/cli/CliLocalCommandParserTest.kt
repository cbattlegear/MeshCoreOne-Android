// PortedFrom: MC1Tests/Views/Tools/CLI/CLILocalCommandParserTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.feature.tools.diagnostics.cli.CliLocalCommand.Advert
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliLocalCommand.GetCustomVar
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliLocalCommand.GetKey
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliLocalCommand.SetCustomVar
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliLocalCommandParser.parse
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliLocalParseError.BadArguments
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliLocalParseResult.Command
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliLocalParseResult.Invalid
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliLocalParseResult.NotLocal
import com.meshcoreone.android.feature.tools.diagnostics.support.OriginalCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class CliLocalCommandParserTest {
    private fun assertAll(cases: List<Pair<String, CliLocalParseResult>>) =
        cases.forEach { (line, expected) -> assertEquals(expected, parse(line), "line [$line]") }

    private fun assertEach(lines: List<String>, expected: CliLocalParseResult) =
        assertAll(lines.map { it to expected })

    @Test @OriginalCase("CLILocalCommandParserTests::parses valid commands(line : String , expected : CLILocalCommand)")
    fun `parses valid commands`() = assertAll(
        listOf(
            "clock" to CliLocalCommand.Clock,
            "CLOCK" to CliLocalCommand.Clock,
            "clock sync" to CliLocalCommand.ClockSync,
            "clock SYNC" to CliLocalCommand.ClockSync,
            "ver" to CliLocalCommand.Ver,
            "board" to CliLocalCommand.Board,
            "advert" to Advert(flood = false),
            "advert.zerohop" to Advert(flood = false),
            "floodadv" to Advert(flood = true),
            "reboot" to CliLocalCommand.Reboot,
            "get name" to GetKey(CliLocalKey.NAME),
            "get bat" to GetKey(CliLocalKey.BAT),
            "get public.key" to GetKey(CliLocalKey.PUBLIC_KEY),
            "get multi.acks" to GetKey(CliLocalKey.MULTI_ACKS),
            "get path.hash.mode" to GetKey(CliLocalKey.PATH_HASH_MODE),
            "GET RADIO" to GetKey(CliLocalKey.RADIO),
            "set name Field Node 3" to CliLocalCommand.SetName("Field Node 3"),
            "set tx 22" to CliLocalCommand.SetTxPower(22),
            "set tx -9" to CliLocalCommand.SetTxPower(-9),
            "set lat 47.49" to CliLocalCommand.SetLatitude(47.49),
            "set lon -120.33" to CliLocalCommand.SetLongitude(-120.33),
            "set freq 869.525" to CliLocalCommand.SetFrequency(869.525),
            "set multi.acks 1" to CliLocalCommand.SetMultiAcks(1u),
            "set path.hash.mode 2" to CliLocalCommand.SetPathHashMode(2u),
            "set radio 869.525,250,11,5" to CliLocalCommand.SetRadio(869.525, 250.0, 11u, 5u),
            "set radio 869.525, 250, 11, 5" to CliLocalCommand.SetRadio(869.525, 250.0, 11u, 5u),
        ).map { (line, command) -> line to Command(command) },
    )

    @Test @OriginalCase("CLILocalCommandParserTests::preserves name case and inner spaces()")
    fun `preserves name case and inner spaces`() =
        assertEquals(Command(CliLocalCommand.SetName("Mixed CASE")), parse("set name  Mixed CASE  "))

    @Test @OriginalCase("CLILocalCommandParserTests::unrecognized first words fall through(line : String)")
    fun `unrecognized first words fall through`() = assertEach(
        listOf("", "   ", "help", "clear", "session list", "login node", "logout", "nodes", "channels", "wat"),
        NotLocal,
    )

    @Test @OriginalCase("CLILocalCommandParserTests::dangerous commands require confirmation()")
    fun `dangerous commands require confirmation`() {
        assertTrue(CliLocalCommand.Reboot.requiresConfirmation)
        assertTrue(CliLocalCommand.SetFrequency(869.5).requiresConfirmation)
        assertTrue(CliLocalCommand.SetRadio(869.5, 250.0, 11u, 5u).requiresConfirmation)
        assertFalse(CliLocalCommand.SetTxPower(22).requiresConfirmation)
        assertFalse(CliLocalCommand.Clock.requiresConfirmation)
    }

    @Test @OriginalCase("CLILocalCommandParserTests::get without a key is bad arguments(line : String)")
    fun `get without a key is bad arguments`() = assertEach(listOf("get", "get   "), Invalid(BadArguments(CliLocalUsage.GET)))

    @Test @OriginalCase("CLILocalCommandParserTests::set without value is bad arguments(line : String)")
    fun `set without value is bad arguments`() =
        assertEach(listOf("set", "set name", "set name    "), Invalid(BadArguments(CliLocalUsage.SET)))

    @Test @OriginalCase("CLILocalCommandParserTests::read-only keys are not settable(line : String)")
    fun `read-only keys are not settable`() =
        assertEach(listOf("set public.key abcd", "set bat 42"), Invalid(BadArguments(CliLocalUsage.SET)))

    @Test @OriginalCase("CLILocalCommandParserTests::non-numeric set values are bad arguments(line : String)")
    fun `non-numeric set values are bad arguments`() =
        assertEach(listOf("set tx abc", "set lat notanumber", "set freq xyz"), Invalid(BadArguments(CliLocalUsage.SET)))

    @Test @OriginalCase("CLILocalCommandParserTests::out-of-range set values are reported(line : String)")
    fun `out-of-range set values are reported`() = assertEach(
        listOf("set tx 200", "set lat 200", "set lon -300", "set freq 3000", "set multi.acks 5", "set path.hash.mode 9"),
        Invalid(CliLocalParseError.ValueOutOfRange),
    )

    @Test @OriginalCase("CLILocalCommandParserTests::malformed set radio is bad arguments(line : String)")
    fun `malformed set radio is bad arguments`() = assertEach(
        listOf("set radio 869.525", "set radio 869.525,250,11", "set radio a,b,c,d"),
        Invalid(BadArguments(CliLocalUsage.SET_RADIO)),
    )

    @Test @OriginalCase("CLILocalCommandParserTests::out-of-range set radio fields are reported(line : String)")
    fun `out-of-range set radio fields are reported`() = assertEach(
        listOf("set radio 3000,250,11,5", "set radio 869.525,600,11,5", "set radio 869.525,250,3,5", "set radio 869.525,250,11,9"),
        Invalid(CliLocalParseError.ValueOutOfRange),
    )

    @Test @OriginalCase("CLILocalCommandParserTests::bare get falls through to a custom var(line : String , expected : CLILocalCommand)")
    fun `bare get falls through to a custom var`() = assertAll(
        listOf(
            "get custom" to CliLocalCommand.GetCustomVars,
            "GET CUSTOM" to CliLocalCommand.GetCustomVars,
            "get gps" to GetCustomVar("gps"),
            "get GPS" to GetCustomVar("GPS"),
            "get gps extra" to GetCustomVar("gps"),
            "get _tx" to GetCustomVar("tx"),
            "get _custom" to GetCustomVar("custom"),
            "get __x" to GetCustomVar("_x"),
        ).map { (line, command) -> line to Command(command) },
    )

    @Test @OriginalCase("CLILocalCommandParserTests::bare set falls through to a custom var(line : String , expected : CLILocalCommand)")
    fun `bare set falls through to a custom var`() = assertAll(
        listOf(
            "set gps 1" to SetCustomVar("gps", "1"),
            "set gps_interval 60" to SetCustomVar("gps_interval", "60"),
            "set label Field Node 3" to SetCustomVar("label", "Field Node 3"),
            "set url a:b" to SetCustomVar("url", "a:b"),
            "set wifi_ssid MyNetwork" to SetCustomVar("wifi_ssid", "MyNetwork"),
            "set wifi_ssid -" to SetCustomVar("wifi_ssid", "-"),
            "set _tx 22" to SetCustomVar("tx", "22"),
        ).map { (line, command) -> line to Command(command) },
    )

    @Test @OriginalCase("CLILocalCommandParserTests::typed keys keep priority over custom-var fallthrough()")
    fun `typed keys keep priority over custom-var fallthrough`() {
        assertEquals(Command(GetKey(CliLocalKey.TX)), parse("get tx"))
        assertEquals(Command(CliLocalCommand.SetTxPower(22)), parse("set TX 22"))
    }

    @Test @OriginalCase("CLILocalCommandParserTests::structurally invalid custom-var tokens are reported(line : String)")
    fun `structurally invalid custom-var tokens are reported`() = assertEach(
        listOf("set a,b 1", "set a:b 1", "set gps 1,2", "get bad:key", "get bad,key"),
        Invalid(CliLocalParseError.InvalidCustomVarToken),
    )

    @Test @OriginalCase("CLILocalCommandParserTests::oversized custom-var set pair is rejected by byte length()")
    fun `oversized custom-var set pair is rejected by byte length`() {
        val overValue = "a" + "é".repeat(69)
        assertEquals(141, "k:$overValue".toByteArray(Charsets.UTF_8).size)
        assertEquals(Invalid(CliLocalParseError.InvalidCustomVarToken), parse("set k $overValue"))
    }

    @Test @OriginalCase("CLILocalCommandParserTests::custom-var set pair at the byte limit is accepted()")
    fun `custom-var set pair at the byte limit is accepted`() {
        val atLimit = "a" + "é".repeat(68)
        assertEquals(139, "k:$atLimit".toByteArray(Charsets.UTF_8).size)
        assertEquals(Command(SetCustomVar("k", atLimit)), parse("set k $atLimit"))
    }

    @Test @OriginalCase("CLILocalCommandParserTests::missing custom-var pieces render bad arguments(line : String)")
    fun `missing custom-var pieces render bad arguments`() = listOf("set gps", "set gps   ", "set _ 1", "get _").forEach { line ->
        val usage = if (line.startsWith("get")) CliLocalUsage.GET else CliLocalUsage.SET
        assertEquals(Invalid(BadArguments(usage)), parse(line), "line [$line]")
    }

    @Test @OriginalCase("CLILocalCommandParserTests::sensor is no longer a local command(line : String)")
    fun `sensor is no longer a local command`() = assertEach(listOf("sensor list", "sensor get gps", "sensor set gps 1"), NotLocal)

    // Native WP-316 boundary cases (Swift numeric/whitespace rules, oracle-checked).

    @Test
    fun `numeric parsing follows Swift initializers not JVM parsers`() {
        assertEquals(Invalid(BadArguments(CliLocalUsage.SET)), parse("set lat 1.0f"))
        assertEquals(Invalid(BadArguments(CliLocalUsage.SET)), parse("set tx ٣"))
        assertEquals(Invalid(BadArguments(CliLocalUsage.SET)), parse("set tx 99999999999999999999"))
        assertEquals(Command(CliLocalCommand.SetTxPower(5)), parse("set tx +5"))
        assertEquals(Command(CliLocalCommand.SetLatitude(16.0)), parse("set lat 0x10"))
        assertEquals(Invalid(CliLocalParseError.ValueOutOfRange), parse("set lat nan"))
        assertEquals(Invalid(CliLocalParseError.ValueOutOfRange), parse("set freq inf"))
        assertEquals(Command(CliLocalCommand.SetMultiAcks(0u)), parse("set multi.acks -0"))
        assertEquals(Invalid(CliLocalParseError.ValueOutOfRange), parse("set multi.acks 1.5"))
    }

    @Test
    fun `set radio omits empty comma fields and trims Swift whitespace`() {
        assertEquals(Command(CliLocalCommand.SetRadio(869.525, 250.0, 11u, 5u)), parse("set radio 869.525,,250,11,5"))
        assertEquals(Command(CliLocalCommand.SetRadio(869.525, 250.0, 11u, 5u)), parse("set radio 869.525, 250,11,5"))
        assertEquals(Command(CliLocalCommand.Ver), parse(" ver\t"))
        assertEquals(NotLocal, parse("get\tname"))
    }
}
