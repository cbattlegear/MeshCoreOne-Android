// PortedFrom: MC1Tests/Views/Tools/CLI/CLICompletionEngineTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.feature.tools.diagnostics.support.OriginalCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class CliCompletionEngineTest {
    private val engine = CliCompletionEngine()

    private fun local(input: String) = engine.completions(input, isLocal = true)

    private fun remote(input: String) = engine.completions(input, isLocal = false)

    private fun node(input: String) = engine.completions(input, isLocal = false, includeSessionCommands = false)

    private fun assertContainsAll(suggestions: List<String>, vararg expected: String) =
        expected.forEach { assertTrue(it in suggestions, "missing $it in $suggestions") }

    private fun assertContainsNone(suggestions: List<String>, vararg unexpected: String) =
        unexpected.forEach { assertFalse(it in suggestions, "unexpected $it in $suggestions") }

    // MARK: - Command completion

    @Test @OriginalCase("CLICompletionEngineTests::Empty input returns all commands()")
    fun `Empty input returns all commands`() = assertContainsAll(local(""), "help", "clear", "login", "session")

    @Test @OriginalCase("CLICompletionEngineTests::Partial command returns matching commands()")
    fun `Partial command returns matching commands`() = assertEquals(listOf("help"), local("hel"))

    @Test @OriginalCase("CLICompletionEngineTests::Session subcommands complete after 'session '()")
    fun `Session subcommands complete after 'session '`() = assertContainsAll(local("session "), "list", "local")

    @Test @OriginalCase("CLICompletionEngineTests::Repeater commands available in remote session()")
    fun `Repeater commands available in remote session`() = assertContainsAll(remote("v"), "ver")

    @Test @OriginalCase("CLICompletionEngineTests::Login not available in remote session()")
    fun `Login not available in remote session`() {
        val suggestions = remote("log")
        assertContainsNone(suggestions, "login")
        assertContainsAll(suggestions, "logout", "log")
    }

    // MARK: - Session command exclusion (node CLI)

    @Test @OriginalCase("CLICompletionEngineTests::Node CLI completions exclude app-CLI session commands()")
    fun `Node CLI completions exclude app-CLI session commands`() {
        val suggestions = node("")
        assertContainsNone(suggestions, "session", "logout")
        assertContainsAll(suggestions, "help", "clear", "ver")
    }

    @Test @OriginalCase("CLICompletionEngineTests::Node CLI does not suggest logout for a 'log' prefix()")
    fun `Node CLI does not suggest logout for a 'log' prefix`() {
        val suggestions = node("log")
        assertContainsNone(suggestions, "logout")
        assertContainsAll(suggestions, "log")
    }

    @Test @OriginalCase("CLICompletionEngineTests::App CLI still offers session commands by default()")
    fun `App CLI still offers session commands by default`() = assertContainsAll(remote(""), "session", "logout")

    @Test @OriginalCase("CLICompletionEngineTests::Region subcommands complete after 'region '()")
    fun `Region subcommands complete after 'region '`() =
        assertContainsAll(remote("region "), "load", "get", "put", "def", "home", "default", "save")

    @Test @OriginalCase("CLICompletionEngineTests::GPS subcommands complete after 'gps '()")
    fun `GPS subcommands complete after 'gps '`() = assertContainsAll(remote("gps "), "on", "off", "sync", "advert")

    @Test @OriginalCase("CLICompletionEngineTests::Get/set completes all parameters()")
    fun `Get or set completes all parameters`() = assertContainsAll(remote("get "), "name", "radio", "flood.max", "bridge.enabled")

    @Test @OriginalCase("CLICompletionEngineTests::Clear subcommands complete()")
    fun `Clear subcommands complete`() = assertContainsAll(remote("clear "), "stats")

    // MARK: - Log, powersaving, gps advert

    @Test @OriginalCase("CLICompletionEngineTests::Log subcommands complete after 'log '()")
    fun `Log subcommands complete after 'log '`() = assertContainsAll(remote("log "), "start", "stop", "erase")

    @Test @OriginalCase("CLICompletionEngineTests::Log subcommand filters by prefix()")
    fun `Log subcommand filters by prefix`() {
        val suggestions = remote("log st")
        assertContainsAll(suggestions, "start", "stop")
        assertContainsNone(suggestions, "erase")
    }

    @Test @OriginalCase("CLICompletionEngineTests::Powersaving values complete after 'powersaving '()")
    fun `Powersaving values complete after 'powersaving '`() = assertContainsAll(remote("powersaving "), "on", "off")

    @Test @OriginalCase("CLICompletionEngineTests::Powersaving filters by prefix()")
    fun `Powersaving filters by prefix`() = assertContainsAll(remote("powersaving o"), "on", "off")

    @Test @OriginalCase("CLICompletionEngineTests::GPS advert values complete for third argument()")
    fun `GPS advert values complete for third argument`() = assertContainsAll(remote("gps advert "), "none", "share", "prefs")

    @Test @OriginalCase("CLICompletionEngineTests::GPS advert filters third argument by prefix()")
    fun `GPS advert filters third argument by prefix`() {
        val suggestions = remote("gps advert s")
        assertContainsAll(suggestions, "share")
        assertContainsNone(suggestions, "none", "prefs")
    }

    // MARK: - Node names

    @Test @OriginalCase("CLICompletionEngineTests::updateNodeNames stores node names()")
    fun `updateNodeNames stores node names`() {
        assertTrue(engine.nodeNames.isEmpty())
        engine.updateNodeNames(listOf("Alpha", "Bravo", "Charlie"))
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), engine.nodeNames)
    }

    @Test @OriginalCase("CLICompletionEngineTests::updateNodeNames replaces previous names()")
    fun `updateNodeNames replaces previous names`() {
        engine.updateNodeNames(listOf("Alpha", "Bravo"))
        engine.updateNodeNames(listOf("Delta", "Echo"))
        assertEquals(listOf("Delta", "Echo"), engine.nodeNames)
    }

    @Test @OriginalCase("CLICompletionEngineTests::Login completes with node names()")
    fun `Login completes with node names`() {
        engine.updateNodeNames(listOf("Alpha", "Bravo", "Charlie"))
        assertContainsAll(local("login "), "Alpha", "Bravo", "Charlie")
    }

    @Test @OriginalCase("CLICompletionEngineTests::Login filters node names by prefix()")
    fun `Login filters node names by prefix`() {
        engine.updateNodeNames(listOf("Alpha", "Bravo", "Charlie"))
        val suggestions = local("login a")
        assertContainsAll(suggestions, "Alpha")
        assertContainsNone(suggestions, "Bravo", "Charlie")
    }

    @Test @OriginalCase("CLICompletionEngineTests::Session includes node names in suggestions()")
    fun `Session includes node names in suggestions`() {
        engine.updateNodeNames(listOf("TestNode"))
        assertContainsAll(local("session "), "list", "local", "TestNode")
    }

    @Test @OriginalCase("CLICompletionEngineTests::Node name completion is case-insensitive()")
    fun `Node name completion is case-insensitive`() {
        engine.updateNodeNames(listOf("MyRepeater"))
        assertContainsAll(local("login my"), "MyRepeater")
    }

    @Test @OriginalCase("CLICompletionEngineTests::Empty node names returns empty for login()")
    fun `Empty node names returns empty for login`() = assertTrue(local("login ").isEmpty())

    // MARK: - Command arity

    @Test @OriginalCase("CLICompletionEngineTests::Login returns empty after node name complete()")
    fun `Login returns empty after node name complete`() {
        engine.updateNodeNames(listOf("MyRepeater"))
        assertTrue(local("login MyRepeater ").isEmpty())
    }

    @Test @OriginalCase("CLICompletionEngineTests::Session returns empty after subcommand complete()")
    fun `Session returns empty after subcommand complete`() = assertTrue(local("session list ").isEmpty())

    @Test @OriginalCase("CLICompletionEngineTests::Get returns empty after parameter complete()")
    fun `Get returns empty after parameter complete`() = assertTrue(remote("get name ").isEmpty())

    @Test @OriginalCase("CLICompletionEngineTests::GPS advert returns empty after value complete()")
    fun `GPS advert returns empty after value complete`() = assertTrue(remote("gps advert share ").isEmpty())

    @Test @OriginalCase("CLICompletionEngineTests::GPS on returns empty after subcommand complete()")
    fun `GPS on returns empty after subcommand complete`() = assertTrue(remote("gps on ").isEmpty())

    @Test @OriginalCase("CLICompletionEngineTests::Clear returns empty after stats complete()")
    fun `Clear returns empty after stats complete`() = assertTrue(remote("clear stats ").isEmpty())

    @Test @OriginalCase("CLICompletionEngineTests::Log returns empty after subcommand complete()")
    fun `Log returns empty after subcommand complete`() = assertTrue(remote("log start ").isEmpty())

    @Test @OriginalCase("CLICompletionEngineTests::Powersaving returns empty after value complete()")
    fun `Powersaving returns empty after value complete`() = assertTrue(remote("powersaving on ").isEmpty())

    @Test @OriginalCase("CLICompletionEngineTests::Region returns empty after subcommand complete()")
    fun `Region returns empty after subcommand complete`() = assertTrue(remote("region load ").isEmpty())

    @Test @OriginalCase("CLICompletionEngineTests::Clock subcommands complete after 'clock '()")
    fun `Clock subcommands complete after 'clock '`() = assertContainsAll(remote("clock "), "sync")

    @Test @OriginalCase("CLICompletionEngineTests::Clock returns empty after subcommand complete()")
    fun `Clock returns empty after subcommand complete`() = assertTrue(remote("clock sync ").isEmpty())

    // MARK: - Partial input and case

    @Test @OriginalCase("CLICompletionEngineTests::Login partial input still suggests()")
    fun `Login partial input still suggests`() {
        engine.updateNodeNames(listOf("MyRepeater"))
        assertContainsAll(local("login MyRep"), "MyRepeater")
    }

    @Test @OriginalCase("CLICompletionEngineTests::GPS advert partial input still suggests()")
    fun `GPS advert partial input still suggests`() = assertContainsAll(remote("gps advert sh"), "share")

    @Test @OriginalCase("CLICompletionEngineTests::Uppercase command still respects arity()")
    fun `Uppercase command still respects arity`() {
        engine.updateNodeNames(listOf("MyRepeater"))
        assertTrue(local("LOGIN MyRepeater ").isEmpty())
    }

    // MARK: - v1.14.0 commands

    @Test @OriginalCase("CLICompletionEngineTests::advert.zerohop appears in remote session suggestions()")
    fun `advert_zerohop appears in remote session suggestions`() = assertContainsAll(remote("advert"), "advert", "advert.zerohop")

    @Test @OriginalCase("CLICompletionEngineTests::discover.neighbors appears in remote session suggestions()")
    fun `discover_neighbors appears in remote session suggestions`() = assertContainsAll(remote("disc"), "discover.neighbors")

    @Test @OriginalCase("CLICompletionEngineTests::New get/set params appear in completions()")
    fun `New get or set params appear in completions`() = assertContainsAll(remote("get "), "path.hash.mode", "loop.detect", "bootloader.ver")

    @Test @OriginalCase("CLICompletionEngineTests::set loop.detect suggests values()")
    fun `set loop_detect suggests values`() = assertEquals(listOf("minimal", "moderate", "off", "strict"), remote("set loop.detect "))

    @Test @OriginalCase("CLICompletionEngineTests::set path.hash.mode suggests values()")
    fun `set path_hash_mode suggests values`() = assertEquals(listOf("0", "1", "2"), remote("set path.hash.mode "))

    @Test @OriginalCase("CLICompletionEngineTests::set loop.detect returns empty after value complete()")
    fun `set loop_detect returns empty after value complete`() = assertTrue(remote("set loop.detect off ").isEmpty())

    @Test @OriginalCase("CLICompletionEngineTests::get loop.detect returns empty after param (no value completion for get)()")
    fun `get loop_detect returns empty after param (no value completion for get)`() = assertTrue(remote("get loop.detect ").isEmpty())

    @Test @OriginalCase("CLICompletionEngineTests::set loop.detect filters values by prefix()")
    fun `set loop_detect filters values by prefix`() = assertEquals(listOf("minimal", "moderate"), remote("set loop.detect m"))

    @Test @OriginalCase("CLICompletionEngineTests::Uppercase GPS advert still suggests values()")
    fun `Uppercase GPS advert still suggests values`() = assertContainsAll(remote("GPS ADVERT "), "none", "prefs", "share")

    // MARK: - start ota

    @Test @OriginalCase("CLICompletionEngineTests::start appears in remote session suggestions()")
    fun `start appears in remote session suggestions`() = assertContainsAll(remote("st"), "start")

    @Test @OriginalCase("CLICompletionEngineTests::start ota subcommand completes()")
    fun `start ota subcommand completes`() = assertEquals(listOf("ota"), remote("start "))

    @Test @OriginalCase("CLICompletionEngineTests::start returns empty after ota complete()")
    fun `start returns empty after ota complete`() = assertTrue(remote("start ota ").isEmpty())

    // MARK: - region list

    @Test @OriginalCase("CLICompletionEngineTests::Region list appears in subcommands()")
    fun `Region list appears in subcommands`() = assertContainsAll(remote("region "), "list", "load", "get")

    @Test @OriginalCase("CLICompletionEngineTests::Region list completes with allowed and denied()")
    fun `Region list completes with allowed and denied`() = assertEquals(listOf("allowed", "denied"), remote("region list "))

    @Test @OriginalCase("CLICompletionEngineTests::Region list filters values by prefix()")
    fun `Region list filters values by prefix`() = assertEquals(listOf("allowed"), remote("region list a"))

    @Test @OriginalCase("CLICompletionEngineTests::Region list returns empty after value complete()")
    fun `Region list returns empty after value complete`() = assertTrue(remote("region list allowed ").isEmpty())

    @Test @OriginalCase("CLICompletionEngineTests::Region non-list subcommands return empty at position 2()")
    fun `Region non-list subcommands return empty at position 2`() = assertTrue(remote("region get ").isEmpty())

    // MARK: - New get/set parameters

    @Test @OriginalCase("CLICompletionEngineTests::New get/set params include owner.info, radio.rxgain, bridge.channel, pwrmgt()")
    fun `New get or set params include owner_info, radio_rxgain, bridge_channel, pwrmgt`() = assertContainsAll(
        remote("get "),
        "owner.info", "radio.rxgain", "bridge.channel", "pwrmgt.support", "pwrmgt.source",
        "pwrmgt.bootreason", "pwrmgt.bootmv", "cad", "radio.fem.rxgain", "extra.sf",
    )

    @Test @OriginalCase("CLICompletionEngineTests::set cad and radio.fem.rxgain suggest on/off()")
    fun `set cad and radio_fem_rxgain suggest on or off`() {
        assertEquals(listOf("off", "on"), remote("set cad "))
        assertEquals(listOf("off", "on"), remote("set radio.fem.rxgain "))
    }

    @Test @OriginalCase("CLICompletionEngineTests::remote command list includes room.post()")
    fun `remote command list includes room_post`() = assertContainsAll(remote("room"), "room.post")

    @Test @OriginalCase("CLICompletionEngineTests::local session does not offer room.post()")
    fun `local session does not offer room_post`() = assertContainsNone(local("room"), "room.post")

    @Test @OriginalCase("CLICompletionEngineTests::pwrmgt prefix filters to four params()")
    fun `pwrmgt prefix filters to four params`() {
        val suggestions = remote("get pwrmgt.")
        assertEquals(4, suggestions.size)
        assertContainsAll(suggestions, "pwrmgt.support", "pwrmgt.source", "pwrmgt.bootreason", "pwrmgt.bootmv")
    }

    // MARK: - Set value completion

    @Test @OriginalCase("CLICompletionEngineTests::set repeat suggests on/off()")
    fun `set repeat suggests on or off`() = assertEquals(listOf("off", "on"), remote("set repeat "))

    @Test @OriginalCase("CLICompletionEngineTests::set allow.read.only suggests on/off()")
    fun `set allow_read_only suggests on or off`() = assertEquals(listOf("off", "on"), remote("set allow.read.only "))

    @Test @OriginalCase("CLICompletionEngineTests::set bridge.enabled suggests on/off()")
    fun `set bridge_enabled suggests on or off`() = assertEquals(listOf("off", "on"), remote("set bridge.enabled "))

    @Test @OriginalCase("CLICompletionEngineTests::set radio.rxgain suggests on/off()")
    fun `set radio_rxgain suggests on or off`() = assertEquals(listOf("off", "on"), remote("set radio.rxgain "))

    @Test @OriginalCase("CLICompletionEngineTests::set multi.acks suggests 0/1()")
    fun `set multi_acks suggests 0 or 1`() = assertEquals(listOf("0", "1"), remote("set multi.acks "))

    @Test @OriginalCase("CLICompletionEngineTests::set bridge.source suggests tx/rx()")
    fun `set bridge_source suggests tx or rx`() = assertEquals(listOf("rx", "tx"), remote("set bridge.source "))

    // MARK: - Serial-only exclusion

    @Test @OriginalCase("CLICompletionEngineTests::get excludes serial-only params prv.key and acl()")
    fun `get excludes serial-only params prv_key and acl`() {
        val suggestions = remote("get ")
        assertContainsNone(suggestions, "prv.key", "acl")
        assertContainsAll(suggestions, "freq")
    }

    @Test @OriginalCase("CLICompletionEngineTests::get prefix matching still excludes serial-only params()")
    fun `get prefix matching still excludes serial-only params`() {
        assertContainsNone(remote("get prv"), "prv.key")
        assertContainsNone(remote("get a"), "acl")
    }

    @Test @OriginalCase("CLICompletionEngineTests::set excludes serial-only param freq()")
    fun `set excludes serial-only param freq`() {
        val suggestions = remote("set ")
        assertContainsNone(suggestions, "freq")
        assertContainsAll(suggestions, "prv.key")
    }

    @Test @OriginalCase("CLICompletionEngineTests::set prefix matching still excludes serial-only param freq()")
    fun `set prefix matching still excludes serial-only param freq`() = assertContainsNone(remote("set f"), "freq")

    @Test @OriginalCase("CLICompletionEngineTests::set repeat returns empty after value complete()")
    fun `set repeat returns empty after value complete`() = assertTrue(remote("set repeat on ").isEmpty())

    // MARK: - Local radio vocabulary

    @Test @OriginalCase("CLICompletionEngineTests::Local session suggests local radio commands()")
    fun `Local session suggests local radio commands`() = assertContainsAll(local(""), "floodadv", "reboot", "get", "board")

    @Test @OriginalCase("CLICompletionEngineTests::Local session does not suggest repeater-only commands()")
    fun `Local session does not suggest repeater-only commands`() = assertContainsNone(local(""), "neighbors", "password", "setperm")

    @Test @OriginalCase("CLICompletionEngineTests::Remote session does not suggest floodadv()")
    fun `Remote session does not suggest floodadv`() = assertContainsNone(remote(""), "floodadv")

    @Test @OriginalCase("CLICompletionEngineTests::get keys differ per session()")
    fun `get keys differ per session`() {
        val localKeys = local("get ")
        val remoteKeys = remote("get ")
        assertContainsAll(localKeys, "bat")
        assertContainsNone(localKeys, "role")
        assertContainsAll(remoteKeys, "role")
        assertContainsNone(remoteKeys, "bat")
    }

    @Test @OriginalCase("CLICompletionEngineTests::local set keys exclude read-only keys()")
    fun `local set keys exclude read-only keys`() {
        val suggestions = local("set ")
        assertContainsAll(suggestions, "name", "multi.acks")
        assertContainsNone(suggestions, "public.key", "bat")
    }

    @Test @OriginalCase("CLICompletionEngineTests::clock completes sync on local session()")
    fun `clock completes sync on local session`() = assertContainsAll(local("clock "), "sync")

    // MARK: - Custom vars and sensor

    @Test @OriginalCase("CLICompletionEngineTests::sensor is remote-only()")
    fun `sensor is remote-only`() {
        assertContainsNone(local("sen"), "sensor")
        assertContainsAll(remote("sen"), "sensor")
    }

    @Test @OriginalCase("CLICompletionEngineTests::sensor subcommands complete on a remote session only()")
    fun `sensor subcommands complete on a remote session only`() {
        assertEquals(listOf("get", "list", "set"), remote("sensor "))
        assertTrue(local("sensor ").isEmpty())
    }

    @Test @OriginalCase("CLICompletionEngineTests::bare get on local offers typed keys, the dump verb, and learned custom keys()")
    fun `bare get on local offers typed keys, the dump verb, and learned custom keys`() {
        engine.updateCustomVarKeys(listOf("gps", "wifi_ssid"))
        val suggestions = local("get ")
        assertContainsAll(suggestions, "custom", "name", "gps", "wifi_ssid")
        assertEquals(suggestions.sorted(), suggestions)
    }

    @Test @OriginalCase("CLICompletionEngineTests::bare set on local offers typed and learned custom keys but not the dump verb()")
    fun `bare set on local offers typed and learned custom keys but not the dump verb`() {
        engine.updateCustomVarKeys(listOf("gps"))
        val suggestions = local("set ")
        assertContainsAll(suggestions, "name", "gps")
        assertContainsNone(suggestions, "custom")
    }

    @Test @OriginalCase("CLICompletionEngineTests::bare get and set custom keys match case-insensitively but suggest verbatim()")
    fun `bare get and set custom keys match case-insensitively but suggest verbatim`() {
        engine.updateCustomVarKeys(listOf("WiFi_SSID"))
        assertEquals(listOf("WiFi_SSID"), local("get wifi"))
        assertEquals(listOf("WiFi_SSID"), local("set wifi"))
    }

    @Test @OriginalCase("CLICompletionEngineTests::bare get dedupes a custom key that collides with a typed key()")
    fun `bare get dedupes a custom key that collides with a typed key`() {
        engine.updateCustomVarKeys(listOf("name"))
        assertEquals(listOf("name"), local("get name"))
    }

    @Test @OriginalCase("CLICompletionEngineTests::bare get and set value position offers no completion()")
    fun `bare get and set value position offers no completion`() {
        engine.updateCustomVarKeys(listOf("gps"))
        assertTrue(local("get gps ").isEmpty())
        assertTrue(local("set gps ").isEmpty())
    }

    @Test @OriginalCase("CLICompletionEngineTests::updateCustomVarKeys replaces previous keys()")
    fun `updateCustomVarKeys replaces previous keys`() {
        engine.updateCustomVarKeys(listOf("a", "b"))
        engine.updateCustomVarKeys(listOf("c"))
        assertEquals(listOf("c"), engine.customVarKeys)
    }

    // Native WP-316 boundary: Swift scalar ordering, not UTF-16 order.

    @Test
    fun `suggestions sort by Unicode scalar like Swift sorted`() {
        engine.updateNodeNames(listOf("😀node", "Ａnode", "Zed", "alpha"))
        assertEquals(listOf("Zed", "alpha", "Ａnode", "😀node"), engine.completions("login ", isLocal = true))
    }
}
