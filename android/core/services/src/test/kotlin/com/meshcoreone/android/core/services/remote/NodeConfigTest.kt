// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/NodeConfigTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.model.ConfigSections
import com.meshcoreone.android.core.model.MeshCoreNodeConfig
import com.meshcoreone.android.core.model.MeshCoreNodeConfig.ContactConfig
import com.meshcoreone.android.core.model.MeshCoreNodeConfig.OtherSettings
import com.meshcoreone.android.core.model.MeshCoreNodeConfig.PositionSettings
import com.meshcoreone.android.core.model.MeshCoreNodeConfig.RadioSettings
import com.meshcoreone.android.core.model.SnapshotList
import kotlin.test.*
import org.junit.jupiter.api.TestFactory

class NodeConfigTest {
    private val fullConfigJSON = """
    {
      "name": "TestNode-2",
      "public_key": "d4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9",
      "private_key": "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d",
      "radio_settings": {
        "frequency": 910525,
        "bandwidth": 62500,
        "spreading_factor": 7,
        "coding_rate": 5,
        "tx_power": 22
      },
      "position_settings": {
        "latitude": "0.0",
        "longitude": "0.0"
      },
      "other_settings": {
        "manual_add_contacts": 0,
        "advert_location_policy": 0
      },
      "channels": [
        { "name": "General", "secret": "aa11bb22cc33dd44ee55ff6600778899" },
        { "name": "Alpha", "secret": "11223344556677889900aabbccddeeff" },
        { "name": "#bravo", "secret": "ffeeddccbbaa99887766554433221100" },
        { "name": "Charlie", "secret": "abcdef0123456789abcdef0123456789" },
        { "name": "#delta", "secret": "0123456789abcdef0123456789abcdef" },
        { "name": "Echo", "secret": "deadbeef01234567deadbeef01234567" }
      ],
      "contacts": [
        {
          "type": 3,
          "name": "Base-W (Room)",
          "custom_name": null,
          "public_key": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
          "flags": 0,
          "latitude": "40.7128",
          "longitude": "-74.006",
          "last_advert": 1767392516,
          "last_modified": 1767392535,
          "out_path": null
        },
        {
          "type": 2,
          "name": "Base-NW (Repeater)",
          "custom_name": null,
          "public_key": "f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5",
          "flags": 0,
          "latitude": "34.0522",
          "longitude": "-118.2437",
          "last_advert": 1768515147,
          "last_modified": 1768515165,
          "out_path": null
        },
        {
          "type": 1,
          "name": "TestNode-1",
          "custom_name": null,
          "public_key": "0102030405060708091011121314151617181920212223242526272829303132",
          "flags": 0,
          "latitude": "0.0",
          "longitude": "0.0",
          "last_advert": 1770439152,
          "last_modified": 1770439154,
          "out_path": ""
        }
      ]
    }
    """.trimIndent()

    private val channelsOnlyJSON = """
    {
      "channels": [
        { "name": "General", "secret": "aa11bb22cc33dd44ee55ff6600778899" },
        { "name": "Alpha", "secret": "11223344556677889900aabbccddeeff" },
        { "name": "#bravo", "secret": "ffeeddccbbaa99887766554433221100" },
        { "name": "Charlie", "secret": "abcdef0123456789abcdef0123456789" },
        { "name": "#delta", "secret": "0123456789abcdef0123456789abcdef" },
        { "name": "Echo", "secret": "deadbeef01234567deadbeef01234567" }
      ]
    }
    """.trimIndent()

    private val contactsOnlyJSON = """
    {
      "contacts": [
        {
          "type": 3,
          "name": "Base-W (Room)",
          "custom_name": null,
          "public_key": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
          "flags": 0,
          "latitude": "40.7128",
          "longitude": "-74.006",
          "last_advert": 1767392516,
          "last_modified": 1767392535,
          "out_path": null
        },
        {
          "type": 1,
          "name": "TestNode-1",
          "custom_name": null,
          "public_key": "0102030405060708091011121314151617181920212223242526272829303132",
          "flags": 0,
          "latitude": "0.0",
          "longitude": "0.0",
          "last_advert": 1770439152,
          "last_modified": 1770439154,
          "out_path": ""
        }
      ]
    }
    """.trimIndent()

    private fun singleContact(customName: String, outPath: String) = """
    {
      "contacts": [{
        "type": 1, "name": "Test", "custom_name": $customName,
        "public_key": "aabb", "flags": 0,
        "latitude": "0.0", "longitude": "0.0",
        "last_advert": 0, "last_modified": 0,
        "out_path": $outPath
      }]
    }
    """.trimIndent()

    private fun encodedObject(json: String) = NodeConfigJsonReader.parse(json) as NodeConfigJsonValue.Object

    @TestFactory
    fun sourceCases() = nodeConfigSourceCases(
        "NodeConfigTests",
        "Full config decodes all top-level fields" to {
            val config = NodeConfigJson.decode(fullConfigJSON)
            assertEquals("TestNode-2", config.name)
            assertEquals("d4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9", config.publicKey)
            assertEquals("1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d", config.privateKey)
            assertNotNull(config.radioSettings)
            assertNotNull(config.positionSettings)
            assertNotNull(config.otherSettings)
            assertEquals(6, config.channels?.size)
            assertEquals(3, config.contacts?.size)
        },
        "Full config decodes radio settings" to {
            val radio = assertNotNull(NodeConfigJson.decode(fullConfigJSON).radioSettings)
            assertEquals(910_525u, radio.frequency)
            assertEquals(62_500u, radio.bandwidth)
            assertEquals(7.toUByte(), radio.spreadingFactor)
            assertEquals(5.toUByte(), radio.codingRate)
            assertEquals(22.toByte(), radio.txPower)
        },
        "Full config decodes position settings with isZero" to {
            val position = assertNotNull(NodeConfigJson.decode(fullConfigJSON).positionSettings)
            assertEquals("0.0", position.latitude)
            assertEquals("0.0", position.longitude)
            assertTrue(position.isZero)
        },
        "Full config decodes companion-app other settings (2 of 7 fields)" to {
            val other = assertNotNull(NodeConfigJson.decode(fullConfigJSON).otherSettings)
            assertEquals(0.toUByte(), other.manualAddContacts)
            assertEquals(0.toUByte(), other.advertLocationPolicy)
            assertNull(other.telemetryModeBase)
            assertNull(other.telemetryModeLocation)
            assertNull(other.telemetryModeEnvironment)
            assertNull(other.multiAcks)
            assertNull(other.advertisementType)
        },
        "Full config decodes channel names and secrets" to {
            val channels = assertNotNull(NodeConfigJson.decode(fullConfigJSON).channels)
            assertEquals("General", channels[0].name)
            assertEquals("aa11bb22cc33dd44ee55ff6600778899", channels[0].secret)
            assertEquals("#bravo", channels[2].name)
            assertEquals("Echo", channels[5].name)
        },
        "Full config decodes contacts with varying types and positions" to {
            val contacts = assertNotNull(NodeConfigJson.decode(fullConfigJSON).contacts)
            val room = contacts[0]
            assertEquals(3.toUByte(), room.type)
            assertEquals("Base-W (Room)", room.name)
            assertNull(room.customName)
            assertEquals("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6", room.publicKey)
            assertEquals(0.toUByte(), room.flags)
            assertEquals("40.7128", room.latitude)
            assertEquals("-74.006", room.longitude)
            assertEquals(1_767_392_516u, room.lastAdvert)
            assertEquals(1_767_392_535u, room.lastModified)
            assertNull(room.outPath)
            assertEquals(2.toUByte(), contacts[1].type)
            assertEquals("Base-NW (Repeater)", contacts[1].name)
            assertEquals(1.toUByte(), contacts[2].type)
            assertEquals("TestNode-1", contacts[2].name)
            assertEquals("", contacts[2].outPath)
        },
        "Channels-only config has nil for absent sections" to {
            val config = NodeConfigJson.decode(channelsOnlyJSON)
            assertNull(config.name); assertNull(config.publicKey); assertNull(config.privateKey)
            assertNull(config.radioSettings); assertNull(config.positionSettings); assertNull(config.otherSettings)
            assertNull(config.contacts)
            val channels = assertNotNull(config.channels)
            assertEquals(6, channels.size)
            assertEquals("General", channels[0].name)
            assertEquals("#delta", channels[4].name)
        },
        "Contacts-only config has nil for absent sections" to {
            val config = NodeConfigJson.decode(contactsOnlyJSON)
            assertNull(config.name); assertNull(config.publicKey); assertNull(config.privateKey)
            assertNull(config.radioSettings); assertNull(config.positionSettings); assertNull(config.otherSettings)
            assertNull(config.channels)
            val contacts = assertNotNull(config.contacts)
            assertEquals(2, contacts.size)
            assertEquals("Base-W (Room)", contacts[0].name)
            assertNull(contacts[0].outPath)
            assertEquals("TestNode-1", contacts[1].name)
            assertEquals("", contacts[1].outPath)
        },
        "Round-trip encode/decode preserves all fields" to {
            val original = NodeConfigJson.decode(fullConfigJSON)
            // Swift uses a default JSONEncoder() here: compact, declaration-ordered keys.
            val decoded = NodeConfigJson.decode(NodeConfigJson.encode(original, prettyPrinted = false, sortedKeys = false))
            assertEquals(original.name, decoded.name)
            assertEquals(original.publicKey, decoded.publicKey)
            assertEquals(original.privateKey, decoded.privateKey)
            assertEquals(original.radioSettings, decoded.radioSettings)
            assertEquals(original.positionSettings, decoded.positionSettings)
            assertEquals(original.otherSettings, decoded.otherSettings)
            assertEquals(original.channels, decoded.channels)
            assertEquals(original.contacts, decoded.contacts)
        },
        "Contact with null out_path decodes as nil" to {
            assertNull(assertNotNull(NodeConfigJson.decode(singleContact("null", "null")).contacts?.first()).outPath)
        },
        "Contact with empty string out_path decodes as empty string" to {
            assertEquals("", assertNotNull(NodeConfigJson.decode(singleContact("null", "\"\"")).contacts?.first()).outPath)
        },
        "Contact with null custom_name decodes as nil" to {
            assertNull(assertNotNull(NodeConfigJson.decode(singleContact("null", "null")).contacts?.first()).customName)
        },
        "Contact with non-null custom_name decodes correctly" to {
            val contact = assertNotNull(NodeConfigJson.decode(singleContact("\"My Custom Name\"", "null")).contacts?.first())
            assertEquals("My Custom Name", contact.customName)
        },
        "Empty JSON object decodes with all nil fields" to {
            assertEquals(MeshCoreNodeConfig(), NodeConfigJson.decode("{}"))
        },
        "ContactConfig encodes nil customName and outPath as explicit null" to {
            val contact = ContactConfig(
                type = 1u, name = "Test", publicKey = "aabb", flags = 0u,
                latitude = "0.0", longitude = "0.0", lastAdvert = 0u, lastModified = 0u,
            )
            val json = encodedObject(NodeConfigJson.encodeContact(contact))
            assertTrue(json.containsKey("custom_name")); assertEquals(NodeConfigJsonValue.Null, json["custom_name"])
            assertTrue(json.containsKey("out_path")); assertEquals(NodeConfigJsonValue.Null, json["out_path"])
        },
        "ContactConfig encodes non-nil customName and outPath as values" to {
            val contact = ContactConfig(
                type = 1u, name = "Test", customName = "Nick", publicKey = "aabb", flags = 0u,
                latitude = "0.0", longitude = "0.0", lastAdvert = 0u, lastModified = 0u, outPath = "aabbcc",
            )
            val json = encodedObject(NodeConfigJson.encodeContact(contact))
            assertEquals(NodeConfigJsonValue.RawText("Nick"), json["custom_name"])
            assertEquals(NodeConfigJsonValue.RawText("aabbcc"), json["out_path"])
        },
        "Position isZero returns false for non-zero coordinates" to {
            assertFalse(PositionSettings(latitude = "40.7128", longitude = "-74.006").isZero)
        },
        "OtherSettings with all 7 fields round-trips correctly" to {
            val json = """
            {
              "other_settings": {
                "manual_add_contacts": 1,
                "advert_location_policy": 2,
                "telemetry_mode_base": 3,
                "telemetry_mode_location": 4,
                "telemetry_mode_environment": 5,
                "multi_acks": 6,
                "advertisement_type": 7
              }
            }
            """.trimIndent()
            val config = NodeConfigJson.decode(json)
            val other = assertNotNull(config.otherSettings)
            assertEquals(OtherSettings(1u, 2u, 3u, 4u, 5u, 6u, 7u), other)
            val decoded = NodeConfigJson.decode(NodeConfigJson.encode(config, prettyPrinted = false, sortedKeys = false))
            assertEquals(other, decoded.otherSettings)
        },
        "ConfigSections defaults to all false" to {
            val sections = ConfigSections()
            assertFalse(sections.nodeIdentity); assertFalse(sections.radioSettings); assertFalse(sections.positionSettings)
            assertFalse(sections.otherSettings); assertFalse(sections.channels); assertFalse(sections.contacts)
            assertFalse(sections.allSelected); assertFalse(sections.anySectionSelected)
        },
        "ConfigSections allSelected is false when any section is false" to {
            assertFalse(ConfigSections().selectAll().copy(channels = false).allSelected)
        },
        "ConfigSections allSelected is false when only one section is true" to {
            assertFalse(ConfigSections(channels = true).allSelected)
        },
        "ConfigSections selectAll sets all to true" to {
            val sections = ConfigSections().selectAll()
            assertTrue(sections.allSelected); assertTrue(sections.anySectionSelected)
        },
        "ConfigSections deselectAll sets all to false" to {
            val sections = ConfigSections().selectAll().deselectAll()
            assertFalse(sections.allSelected); assertFalse(sections.anySectionSelected)
        },
    )

    /** The exact text Foundation's `JSONEncoder` with `[.prettyPrinted, .sortedKeys]` writes (iOS export). */
    private val iosExportFixture = """
    {
      "channels" : [
        {
          "name" : "#bravo",
          "secret" : "ffeeddccbbaa99887766554433221100"
        }
      ],
      "contacts" : [
        {
          "custom_name" : null,
          "flags" : 0,
          "last_advert" : 1767392516,
          "last_modified" : 1767392535,
          "latitude" : "40.7128",
          "longitude" : "-74.006",
          "name" : "Base-W (Room)",
          "out_path" : null,
          "public_key" : "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
          "type" : 3
        },
        {
          "custom_name" : "Nick",
          "flags" : 2,
          "last_advert" : 0,
          "last_modified" : 4294967295,
          "latitude" : "1e-05",
          "longitude" : "0.0001",
          "name" : "Relay\/1 \"é\"",
          "out_path" : "aabbccdd",
          "path_hash_mode" : 1,
          "public_key" : "0102030405060708091011121314151617181920212223242526272829303132",
          "type" : 2
        }
      ],
      "name" : "TestNode-2",
      "other_settings" : {
        "advert_location_policy" : 0,
        "manual_add_contacts" : 1
      },
      "position_settings" : {
        "latitude" : "47.6062",
        "longitude" : "-122.3321"
      },
      "private_key" : "1a2b",
      "public_key" : "d4f5",
      "radio_settings" : {
        "bandwidth" : 62500,
        "coding_rate" : 5,
        "frequency" : 910525,
        "spreading_factor" : 7,
        "tx_power" : -9
      }
    }
    """.trimIndent()

    private val iosExportConfig = MeshCoreNodeConfig(
        name = "TestNode-2", publicKey = "d4f5", privateKey = "1a2b",
        radioSettings = RadioSettings(910_525u, 62_500u, 7u, 5u, -9),
        positionSettings = PositionSettings("47.6062", "-122.3321"),
        otherSettings = OtherSettings(manualAddContacts = 1u, advertLocationPolicy = 0u),
        channels = SnapshotList.of(MeshCoreNodeConfig.ChannelConfig("#bravo", "ffeeddccbbaa99887766554433221100")),
        contacts = SnapshotList.of(
            ContactConfig(
                type = 3u, name = "Base-W (Room)", publicKey = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
                flags = 0u, latitude = "40.7128", longitude = "-74.006", lastAdvert = 1_767_392_516u, lastModified = 1_767_392_535u,
            ),
            ContactConfig(
                type = 2u, name = "Relay/1 \"é\"", customName = "Nick",
                publicKey = "0102030405060708091011121314151617181920212223242526272829303132", flags = 2u,
                latitude = "1e-05", longitude = "0.0001", lastAdvert = 0u, lastModified = UInt.MAX_VALUE,
                outPath = "aabbccdd", pathHashMode = 1u,
            ),
        ),
    )

    @TestFactory
    fun nativeCases() = nodeConfigNativeCases(
        "export encoding is byte-identical to the iOS pretty-printed sorted-key fixture" to {
            assertEquals(iosExportFixture, NodeConfigJson.encode(iosExportConfig))
            assertContentEquals(iosExportFixture.toByteArray(Charsets.UTF_8), NodeConfigJson.encodeToBytes(iosExportConfig))
        },
        "the iOS export fixture decodes to the same config and re-encodes unchanged" to {
            val decoded = NodeConfigJson.decode(iosExportFixture.toByteArray(Charsets.UTF_8))
            assertEquals(iosExportConfig, decoded)
            assertEquals(iosExportFixture, NodeConfigJson.encode(decoded))
        },
        "compact unsorted encoding follows Swift CodingKeys and custom contact encode order" to {
            val config = MeshCoreNodeConfig(name = "N", contacts = SnapshotList.of(iosExportConfig.contacts!![0]))
            assertEquals(
                "{\"name\":\"N\",\"contacts\":[{\"type\":3,\"name\":\"Base-W (Room)\",\"custom_name\":null," +
                    "\"public_key\":\"a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6\",\"flags\":0," +
                    "\"latitude\":\"40.7128\",\"longitude\":\"-74.006\",\"last_advert\":1767392516," +
                    "\"last_modified\":1767392535,\"out_path\":null}]}",
                NodeConfigJson.encode(config, prettyPrinted = false, sortedKeys = false),
            )
        },
        "empty containers and an empty config use Foundation's pretty layout" to {
            assertEquals("{\n\n}", NodeConfigJson.encode(MeshCoreNodeConfig()))
            assertEquals("{\n  \"channels\" : [\n\n  ]\n}", NodeConfigJson.encode(MeshCoreNodeConfig(channels = SnapshotList.empty())))
        },
        "strings escape quotes, backslashes, slashes and control characters like JSONEncoder" to {
            val encoded = NodeConfigJson.encode(MeshCoreNodeConfig(name = "a\"b\\c/d\n\t\u0001\u001F\u000C\b\r🙂"), prettyPrinted = false)
            assertEquals("{\"name\":\"a\\\"b\\\\c\\/d\\n\\t\\u0001\\u001f\\f\\b\\r🙂\"}", encoded)
            assertEquals("a\"b\\c/d\n\t\u0001\u001F\u000C\b\r🙂", NodeConfigJson.decode(encoded).name)
        },
        "integer fields accept exact integral doubles and reject fractions and overflow" to {
            val radio = """{"radio_settings":{"frequency":9.10525e5,"bandwidth":62500.0,"spreading_factor":7,"coding_rate":5,"tx_power":-9}}"""
            assertEquals(RadioSettings(910_525u, 62_500u, 7u, 5u, -9), NodeConfigJson.decode(radio).radioSettings)
            val fraction = assertFailsWith<NodeConfigDecodingException.DataCorrupted> {
                NodeConfigJson.decode("""{"other_settings":{"multi_acks":1.5}}""")
            }
            assertEquals("other_settings.multi_acks", fraction.codingPath)
            assertFailsWith<NodeConfigDecodingException.DataCorrupted> { NodeConfigJson.decode("""{"other_settings":{"multi_acks":256}}""") }
            assertFailsWith<NodeConfigDecodingException.DataCorrupted> {
                NodeConfigJson.decode("""{"radio_settings":{"frequency":1,"bandwidth":1,"spreading_factor":7,"coding_rate":5,"tx_power":128}}""")
            }
            assertFailsWith<NodeConfigDecodingException.DataCorrupted> {
                NodeConfigJson.decode("""{"contacts":[${contactWith("\"last_advert\": 4294967296")}]}""")
            }
        },
        "required keys, nulls and wrong types map to Swift DecodingError cases" to {
            val missing = assertFailsWith<NodeConfigDecodingException.KeyNotFound> {
                NodeConfigJson.decode("""{"channels":[{"name":"x"}]}""")
            }
            assertEquals("secret", missing.key); assertEquals("channels[0]", missing.codingPath)
            assertFailsWith<NodeConfigDecodingException.ValueNotFound> { NodeConfigJson.decode("""{"channels":[{"name":null,"secret":"00"}]}""") }
            assertFailsWith<NodeConfigDecodingException.TypeMismatch> { NodeConfigJson.decode("""{"name":5}""") }
            assertFailsWith<NodeConfigDecodingException.TypeMismatch> { NodeConfigJson.decode("""{"contacts":[${contactWith("\"type\": \"1\"")}]}""") }
            assertFailsWith<NodeConfigDecodingException.TypeMismatch> { NodeConfigJson.decode("""{"channels":{}}""") }
            assertFailsWith<NodeConfigDecodingException.TypeMismatch> { NodeConfigJson.decode("[]") }
            assertNull(NodeConfigJson.decode("""{"radio_settings":null,"name":null,"unknown":[1,{"a":true}]}""").radioSettings)
        },
        "malformed JSON text is reported as corrupted data" to {
            for (text in listOf("", "{", "{\"name\":\"\\ud800\"}", "{} x", "{'a':1}", "{\"name\":\"\u0001\"}")) {
                assertFailsWith<NodeConfigDecodingException.DataCorrupted>(text) { NodeConfigJson.decode(text) }
            }
            assertFailsWith<NodeConfigDecodingException.DataCorrupted> { NodeConfigJson.decode(byteArrayOf(0x7B, 0xC3.toByte(), 0x7D)) }
            assertEquals("x", NodeConfigJson.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{\"name\":\"x\"}".toByteArray()).name)
        },
        "a duplicated key resolves to its first occurrence like Foundation's JSONDecoder" to {
            // Oracle: swiftc + Foundation (macOS 26) decoding MeshCoreNodeConfig, at every nesting level.
            assertEquals("first", NodeConfigJson.decode("""{"name":"first","name":"second"}""").name)
            assertEquals("a", NodeConfigJson.decode("""{"name":"a","x":1,"name":"b"}""").name)
            assertEquals("a", NodeConfigJson.decode("""{"name":"a","name":null}""").name)
            assertNull(NodeConfigJson.decode("""{"name":null,"name":"b"}""").name)
            assertEquals(
                1u,
                NodeConfigJson.decode(
                    """{"radio_settings":{"frequency":1,"bandwidth":2,"spreading_factor":7,"coding_rate":5,"tx_power":1,"frequency":9}}""",
                ).radioSettings?.frequency,
            )
            assertEquals(listOf("x"), NodeConfigJson.decode("""{"channels":[{"name":"x","secret":"s"}],"channels":[{"name":"y","secret":"s"}]}""").channels?.map { it.name })
            // The shadowed duplicate is never validated.
            assertEquals("a", NodeConfigJson.decode("""{"name":"a","name":"\x"}""").name)
            assertEquals("a", NodeConfigJson.decode("""{"name":"a","name":01}""").name)
            assertFailsWith<NodeConfigDecodingException.DataCorrupted> { NodeConfigJson.decode("""{"name":"\x","name":"a"}""") }
        },
        "trailing commas are accepted like Foundation's JSONDecoder, empty-comma containers are not" to {
            assertEquals("a", NodeConfigJson.decode("""{"name":"a",}""").name)
            assertEquals("a", NodeConfigJson.decode("{\"name\":\"a\" , \n}").name)
            assertEquals(1, NodeConfigJson.decode("""{"channels":[{"name":"a","secret":"b"},]}""").channels?.size)
            assertNull(NodeConfigJson.decode("""{"u":[1 , ],"v":{"k":1,}}""").name)
            for (text in listOf("""{,}""", """{"channels":[,]}""", """{"name":"a",,}""", """{"u":[,1]}""", """{"name":"a",}x""")) {
                assertFailsWith<NodeConfigDecodingException.DataCorrupted>(text) { NodeConfigJson.decode(text) }
            }
        },
        "string and number contents are validated only when decoded, like Foundation's JSONDecoder" to {
            // Unknown keys may hold invalid strings and number-ish runs; Foundation never reads them.
            for (text in listOf(
                "{\"u\":\"a\nb\"}", """{"u":"\x"}""", """{"u":"\uD800"}""", """{"u":01}""", """{"u":1.}""", """{"u":-}""",
                """{"u":--}""", """{"u":1.2.3}""", """{"u":1e5e5}""", """{"u":1+}""", """{"u":0-}""", """{"u":1E}""", """{"u":[01,"\q"]}""",
                """{"other_settings":{"multi_acks":1,"u":01}}""", "{\"u\":\"a\u0001\"}", """{"u\n":1}""",
            )) {
                NodeConfigJson.decode(text)
            }
            // Structural errors are still eager.
            for (text in listOf("""{"u":+1}""", """{"u":tru}""", """{"u":1x}""", """{"u":-a}""", """{"u":1 2}""", "{\"a\nb\":1}", """{"\x":1}""", """{"\uD800":1}""")) {
                assertFailsWith<NodeConfigDecodingException.DataCorrupted>(text) { NodeConfigJson.decode(text) }
            }
            // Read fields are validated when decoded.
            for (text in listOf(
                "{\"name\":\"a\nb\"}", "{\"name\":\"a\tb\"}", "{\"name\":\"a\u0001b\"}", "{\"name\":\"a\u001Fb\"}", "{\"name\":\"a\rb\"}",
                """{"name":"\x"}""", """{"name":"\u12"}""", """{"name":"\uDE00"}""", """{"name":"\uD83Dx"}""", """{"name":"\'"}""",
                """{"other_settings":{"multi_acks":01}}""", """{"other_settings":{"multi_acks":00}}""", """{"other_settings":{"multi_acks":-01}}""",
                """{"other_settings":{"multi_acks":.5}}""", """{"other_settings":{"multi_acks":1.}}""", """{"other_settings":{"multi_acks":1e}}""",
                """{"other_settings":{"multi_acks":0x1}}""", """{"other_settings":{"multi_acks":NaN}}""", """{"other_settings":{"multi_acks":1e400}}""",
                """{"other_settings":{"multi_acks":255.0000000000001}}""", """{"other_settings":{"multi_acks":1.0000000000000002}}""",
            )) {
                assertFailsWith<NodeConfigDecodingException.DataCorrupted>(text) { NodeConfigJson.decode(text) }
            }
            assertFailsWith<NodeConfigDecodingException.TypeMismatch> { NodeConfigJson.decode("""{"name":01}""") }
            assertFailsWith<NodeConfigDecodingException.TypeMismatch> { NodeConfigJson.decode("""{"name":true}""") }
            assertEquals("a\u007Fb", NodeConfigJson.decode("{\"name\":\"a\u007Fb\"}").name)
            assertEquals("k", NodeConfigJson.decode("""{"na\u006de":"k"}""").name)
            val acks = { literal: String -> NodeConfigJson.decode("""{"other_settings":{"multi_acks":$literal}}""").otherSettings?.multiAcks }
            assertEquals(100u.toUByte(), acks("1E2")); assertEquals(100u.toUByte(), acks("1e+2")); assertEquals(1u.toUByte(), acks("1e0"))
            assertEquals(0u.toUByte(), acks("-0")); assertEquals(0u.toUByte(), acks("-0.0")); assertEquals(0u.toUByte(), acks("-1e-400"))
            assertEquals(2u.toUByte(), acks("2.0000000000000001")); assertEquals(255u.toUByte(), acks("254.99999999999999"))
        },
        "encoding writes DEL, U+2028 and astral characters raw and NUL as a lowercase escape like JSONEncoder" to {
            assertEquals(
                "{\n  \"name\" : \"\\u0000\\b\\f\\r\u007F\u2028\uD83D\uDE00\\/\"\n}",
                NodeConfigJson.encode(MeshCoreNodeConfig(name = "\u0000\b\u000C\r\u007F\u2028\uD83D\uDE00/")),
            )
        },
    )

    private fun contactWith(override: String): String {
        val fields = linkedMapOf(
            "type" to "1", "name" to "\"T\"", "public_key" to "\"aa\"", "flags" to "0", "latitude" to "\"0\"",
            "longitude" to "\"0\"", "last_advert" to "0", "last_modified" to "0",
        )
        val (key, value) = override.split(":", limit = 2).map { it.trim() }
        fields[key.trim('"')] = value
        return fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }
}
