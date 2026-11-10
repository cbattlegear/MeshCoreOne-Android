"""AndroidOnly: WP-005 parser, typed format, source coverage and fail-closed drift assertions."""

import copy
import json
import os
from pathlib import Path
import plistlib
import tempfile
import time
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import l10n_convert as c


def plural_definition(value_type="d", outer="%#@count@", **forms):
    return {
        "NSStringLocalizedFormatKey": outer,
        "count": {
            "NSStringFormatSpecTypeKey": "NSStringPluralRuleType",
            "NSStringFormatValueTypeKey": value_type,
            **(forms or {"one": "%d node", "other": "%d nodes"}),
        },
    }


def plurals(definition):
    return c.parse_plurals(plistlib.dumps({"nodes.count": definition}), "fixture.stringsdict")


def catalog(localizations=None):
    return {
        "sourceLanguage": "en", "version": "1.0",
        "strings": {
            "Send ${target} in ${applicationName}": {
                "comment": "Keep the framework's named parameters.",
                "extractionState": "stale",
                "localizations": localizations or {
                    "en": {"stringUnit": {"state": "translated", "value": "Send ${target} in ${applicationName}"}},
                    "ko": {"stringUnit": {"state": "translated", "value": "${applicationName}에서 ${target} 보내기"}},
                },
            },
        },
    }


class OpenStepTest(unittest.TestCase):
    def parse(self, text):
        return c.parse_strings(text.encode(), "fixture.strings")

    def test_comments_whitespace_and_multiple_assignments_per_line(self):
        self.assertEqual({"a": "A", "b": "B"}, self.parse('/* start */ "a"/*key*/=/*value*/"A"; "b"="B";// end'))

    def test_semicolon_and_comment_delimiters_inside_quoted_values(self):
        self.assertEqual({"k": '; // /* not comments */ "quoted"'}, self.parse(r'"k"="; // /* not comments */ \"quoted\"";'))

    def test_newline_tab_apostrophe_backslash_and_quotes(self):
        self.assertEqual({"k": "O'Reilly\n\tC:\\radio \"x\""}, self.parse(r'"k"="O\'Reilly\n\tC:\\radio \"x\"";'))

    def test_multiline_continuations_lf_and_crlf(self):
        self.assertEqual({"k": "abc"}, self.parse('"k"="a\\\nb\\\r\nc";'))

    def test_actual_multiline_values(self):
        self.assertEqual({"k": "first\nsecond"}, self.parse('"k"="first\nsecond";'))

    def test_octal_and_unicode_escapes(self):
        self.assertEqual({"k": "A\n中🙂"}, self.parse(r'"k"="\101\012\U4e2d\Ud83d\Ude42";'))

    def test_utf8_bom_and_utf16_both_endiannesses(self):
        text = '"key"="中文 한국어";'
        for data in (b"\xef\xbb\xbf" + text.encode(), text.encode("utf-16"), b"\xfe\xff" + text.encode("utf-16-be")):
            with self.subTest(data=data[:4]):
                self.assertEqual({"key": "中文 한국어"}, c.parse_strings(data, "encoding.strings"))

    def test_duplicate_keys_even_when_values_match(self):
        for text in ('"k"="v";"k"="v";', '"k"="v";\n"k"="different";'):
            with self.subTest(text=text), self.assertRaisesRegex(c.ConversionError, "fixture.strings:.*duplicate key"):
                self.parse(text)

    def test_unquoted_unsupported_openstep_constructs(self):
        for text in ('key="v";', '{"key"="v";}', '"k"=(1,2);'):
            with self.subTest(text=text), self.assertRaises(c.ConversionError):
                self.parse(text)

    def test_missing_semicolon_or_assignment_and_trailing_garbage(self):
        for text in ('"k"="v"', '"k" "v";', '"k"="v";garbage'):
            with self.subTest(text=text), self.assertRaises(c.ConversionError):
                self.parse(text)

    def test_unterminated_string_comment_and_unknown_escape(self):
        for text in ('"k"="unterminated', '/* unterminated', r'"k"="\q";', '/* nested /* x */'):
            with self.subTest(text=text), self.assertRaises(c.ConversionError):
                self.parse(text)

    def test_unpaired_surrogate_and_bad_unicode_escape(self):
        for text in (r'"k"="\Ud800";', r'"k"="\U12xz";'):
            with self.subTest(text=text), self.assertRaises(c.ConversionError):
                self.parse(text)

    def test_invalid_encoding_and_utf32_are_not_repaired(self):
        for data in (b"\xff", '"k"="v";'.encode("utf-32")):
            with self.subTest(data=data[:4]), self.assertRaises(c.ConversionError):
                c.parse_strings(data, "encoding.strings")


class FormatTest(unittest.TestCase):
    def test_object_int_and_64bit_widths_are_explicit(self):
        fmt = c.parse_format("%@ %d %ld %lld", "typed")
        self.assertEqual("%1$s %2$d %3$d %4$d", fmt.android)
        self.assertEqual(((1, "string"), (2, "int"), (3, "long"), (4, "long")), fmt.arguments)

    def test_positional_reordering_and_repeated_arguments(self):
        fmt = c.parse_format("%2$@ / %1$lld / %2$@", "positions")
        self.assertEqual("%2$s / %1$d / %2$s", fmt.android)
        self.assertEqual(((1, "long"), (2, "string")), fmt.arguments)

    def test_percent_label_is_not_a_formatter_at_runtime(self):
        self.assertEqual(c.Format("Airtime %", ()), c.parse_format("Airtime %%", "airtime"))
        self.assertEqual("100% / %%", c.parse_format("100%% / %%%%", "literal").android)

    def test_percent_with_arguments_stays_java_escaped(self):
        self.assertEqual("%1$d%% / %2$s", c.parse_format("%d%% / %@", "percent").android)

    def test_trailing_literal_percent(self):
        self.assertEqual("%1$s%%", c.parse_format("%@%", "literal percent").android)

    def test_finite_fixed_precision_float_conversion(self):
        self.assertEqual(c.Format("%1$08.2f", ((1, "double"),)), c.parse_format("%08.2f", "decimal"))
        self.assertEqual("%1$.0f", c.parse_format("%.f", "decimal").android)

    def test_unsupported_lossy_formats_fail_instead_of_blanket_replacement(self):
        for text in ("%s", "%u", "%x", "%g", "%e", "%a", "%c", "%p", "%n", "%*d", "%.*f", "%hd", "%Ld", "%.2d", "%.2@", "%#d", "%+ f", "%-0d", "%0d"):
            with self.subTest(text=text), self.assertRaisesRegex(c.ConversionError, "lossy|unsupported|padding"):
                c.parse_format(text, "bad format")

    def test_mixed_addressing_conflicting_types_and_invalid_positions(self):
        for text in ("%1$@ %d", "%1$@ %1$d", "%0$d", "%33$d"):
            with self.subTest(text=text), self.assertRaises(c.ConversionError):
                c.parse_format(text, "signature")

    def test_incomplete_directive(self):
        with self.assertRaisesRegex(c.ConversionError, "incomplete"):
            c.parse_format("%1$", "signature")

    def test_named_tokens_reorder_by_english_position(self):
        fmt = c.parse_format("${applicationName} -> ${target}", "named", ("target", "applicationName"))
        self.assertEqual("%2$s -> %1$s", fmt.android)
        self.assertEqual(((1, "string"), (2, "string")), fmt.arguments)

    def test_unknown_named_tokens_and_mixed_printf_fail(self):
        for text in ("${other}", "${target", "${target} %@"):
            with self.subTest(text=text), self.assertRaises(c.ConversionError):
                c.parse_format(text, "named", ("target",))

    def test_dotted_keys_domain_namespace_and_collision(self):
        self.assertEqual("l10n_app_settings_region_subdivision_us_ca", c.android_name("app", "Settings", "region.subdivision.US-CA"))
        self.assertNotEqual(c.android_name("app", "Localizable", "Disconnected"), c.android_name("widget", "Localizable", "Disconnected"))
        self.assertEqual(c.android_name("app", "Tools", "a.b"), c.android_name("app", "Tools", "a_b"))

    def test_xml_escaping_preserves_whitespace_reserved_characters_and_newlines(self):
        value = c.android_xml_text("  O'Reilly & <radio> \"x\" \\ @?\n\t中文  ", "XML")
        node = ET.fromstring("<string>" + value + "</string>")
        self.assertEqual('"  O\\\'Reilly & <radio> \\"x\\" \\\\ \\@\\?\\n\\t中文  "', node.text)

    def test_invalid_xml_control_and_surrogate_fail(self):
        for text in ("NUL\0", "\ud800", "\uffff"):
            with self.subTest(text=repr(text)), self.assertRaisesRegex(c.ConversionError, "invalid XML"):
                c.android_xml_text(text, "XML")


class PluralTest(unittest.TestCase):
    def test_safe_real_plist_one_other(self):
        value = plurals(plural_definition())["nodes.count"]
        self.assertEqual(1, value.quantity)
        self.assertEqual(((1, "int"),), value.arguments)
        self.assertEqual("%1$d node", value.formats["one"].android)

    def test_positional_quantity_is_total_not_removed_count(self):
        value = plurals(plural_definition(outer="%2$#@count@", one="Removed %1$d of %2$d node", other="Removed %1$d of %2$d nodes"))["nodes.count"]
        self.assertEqual(2, value.quantity)
        self.assertEqual(((1, "int"), (2, "int")), value.arguments)

    def test_all_six_quantities_and_omitted_zero_count_are_preserved(self):
        value = plurals(plural_definition(zero="No nodes", one="%d node", two="%d pair", few="%d few", many="%d many", other="%d nodes"))["nodes.count"]
        self.assertEqual(set(c.QUANTITIES), set(value.raw))
        self.assertEqual(((1, "int"),), value.arguments)
        message = c.Message("app", "Tools", "nodes.count", plurals={"en": value})
        root = ET.fromstring(c.resource_xml([message], "en", True))
        self.assertEqual(list(c.QUANTITIES), [item.get("quantity") for item in root[0]])

    def test_long_quantity_keeps_long_signature(self):
        value = plurals(plural_definition("lld", one="%lld node", other="%lld nodes"))["nodes.count"]
        self.assertEqual(((1, "long"),), value.arguments)

    def test_missing_other_bad_metadata_value_type_and_quantity(self):
        fixtures = [
            plural_definition(one="%d node"),
            plural_definition("f"),
            plural_definition(unknown="%d nodes", other="%d nodes"),
        ]
        fixtures.append(plural_definition())
        fixtures[-1]["count"]["NSStringFormatSpecTypeKey"] = "NSStringVariableWidthRuleType"
        for definition in fixtures:
            with self.subTest(definition=definition), self.assertRaises(c.ConversionError):
                plurals(definition)

    def test_quantity_type_and_nonquantity_signatures_cannot_disagree(self):
        for definition in (
            plural_definition("lld"),
            plural_definition(one="%1$d node", other="%1$d nodes for %2$@"),
            plural_definition(one="%d node", other="%lld nodes"),
        ):
            with self.subTest(definition=definition), self.assertRaises(c.ConversionError):
                plurals(definition)

    def test_composed_or_multiple_plural_variables_are_not_silently_dropped(self):
        definition = plural_definition(outer="There are %#@count@")
        with self.assertRaisesRegex(c.ConversionError, "ambiguous composition"):
            plurals(definition)
        definition = plural_definition()
        definition["second"] = definition["count"]
        with self.assertRaisesRegex(c.ConversionError, "multiple plural"):
            plurals(definition)

    def test_duplicate_plist_keys(self):
        data = b'<plist version="1.0"><dict><key>same</key><string>A</string><key>same</key><string>B</string></dict></plist>'
        with self.assertRaisesRegex(c.ConversionError, "duplicate plist key"):
            c.parse_plist(data, "duplicate.stringsdict")

    def test_plist_text_outside_values_is_not_silently_ignored(self):
        for data in (
            b'<plist version="1.0">unexpected<dict/></plist>',
            b'<plist version="1.0"><dict/>unexpected</plist>',
            b'<plist version="1.0"><dict><key>k</key><string>value</string>unexpected</dict></plist>',
        ):
            with self.subTest(data=data), self.assertRaisesRegex(c.ConversionError, "unexpected text"):
                c.parse_plist(data, "text.stringsdict")

    def test_entities_custom_dtd_malformed_xml_and_nonstring_values(self):
        for data in (
            b'<!DOCTYPE plist [<!ENTITY x "xx">]><plist version="1.0"><dict/></plist>',
            b'<!DOCTYPE plist SYSTEM "file:///secret"><plist version="1.0"><dict/></plist>',
            b'<plist><dict>',
            plistlib.dumps({"k": 12}),
            b'<plist version="1.0"><dict><key>k</key></dict></plist>',
        ):
            with self.subTest(data=data[:40]), self.assertRaises(c.ConversionError):
                c.parse_plist(data, "unsafe.stringsdict")

    def test_flat_fallback_argument_types_match_plural_override(self):
        value = plurals(plural_definition())["nodes.count"]
        wrong = c.Text("%lld nodes", "fallback.strings", c.parse_format("%lld nodes", "fallback"))
        with self.assertRaisesRegex(c.ConversionError, "signature"):
            c.Message("app", "Tools", "nodes.count", {"en": wrong}, {"en": value}).validate()


class CatalogTest(unittest.TestCase):
    def test_actual_structure_preserves_named_tokens_and_missing_locale(self):
        result = c.parse_catalog(json.dumps(catalog()).encode(), "fixture.xcstrings")
        texts = next(iter(result.values()))
        self.assertEqual("%2$s에서 %1$s 보내기", texts["ko"].format.android)
        self.assertNotIn("it", texts)
        self.assertEqual(texts["en"].format.arguments, texts["ko"].format.arguments)

    def test_unsupported_catalog_variations_substitutions_and_locales(self):
        original = catalog()
        for mutation in ("variations", "substitutions", "locale"):
            value = copy.deepcopy(original)
            definition = next(iter(value["strings"].values()))
            if mutation == "locale":
                definition["localizations"]["xx"] = definition["localizations"]["en"]
            else:
                definition[mutation] = {}
            with self.subTest(mutation=mutation), self.assertRaises(c.ConversionError):
                c.parse_catalog(json.dumps(value).encode(), "unsupported.xcstrings")

    def test_dropped_named_parameters_fail(self):
        value = catalog()
        next(iter(value["strings"].values()))["localizations"]["ko"]["stringUnit"]["value"] = "${target}"
        with self.assertRaisesRegex(c.ConversionError, "drops/adds"):
            c.parse_catalog(json.dumps(value).encode(), "bad.xcstrings")

    def test_missing_english_untranslated_and_invalid_schema(self):
        for mutation in ("english", "state", "version"):
            value = catalog()
            definition = next(iter(value["strings"].values()))
            if mutation == "english":
                del definition["localizations"]["en"]
            elif mutation == "state":
                definition["localizations"]["ko"]["stringUnit"]["state"] = "needs_review"
            else:
                value["version"] = "2.0"
            with self.subTest(mutation=mutation), self.assertRaises(c.ConversionError):
                c.parse_catalog(json.dumps(value).encode(), "bad.xcstrings")

    def test_duplicate_json_keys(self):
        with self.assertRaisesRegex(c.ConversionError, "duplicate JSON"):
            c.unique_json('{"strings": {}, "strings": {}}', "duplicate.xcstrings")

    def test_catalog_metadata_types_and_extraction_state_are_explicit(self):
        for field, bad in (("comment", 123), ("extractionState", "unknown")):
            value = catalog()
            next(iter(value["strings"].values()))[field] = bad
            with self.subTest(field=field), self.assertRaises(c.ConversionError):
                c.parse_catalog(json.dumps(value).encode(), "metadata.xcstrings")


class SourcePipelineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inputs, cls.provenance = c.pinned_inputs(c.REPOSITORY)
        cls.messages, cls.counts = c.load_messages(cls.inputs, c.load_format_adaptations(c.REPOSITORY))
        cls.outputs, cls.summary = c.generate()
        cls.mapping = json.loads(cls.outputs[c.KEY_MAP])

    def test_all_twelve_locales_all_primary_files_are_parsed_not_skipped(self):
        self.assertEqual(169, self.summary["localization_inputs"])
        self.assertEqual(169, len(self.counts))
        self.assertEqual(set(c.LOCALES), {path.split("/")[-2].removesuffix(".lproj") for path in self.counts if path != c.CATALOG})
        self.assertTrue(all(count > 0 for count in self.counts.values()))
        self.assertEqual([], self.summary["unsupported_inputs"])

    def test_all_generated_xml_names_quantities_and_files_are_legal(self):
        resources = {path: data for path, data in self.outputs.items() if path.endswith(("/l10n_strings.xml", "/l10n_plurals.xml"))}
        self.assertEqual(24, len(resources))
        for path, data in resources.items():
            with self.subTest(path=path):
                c.validate_resource_xml(data, path)
        default_strings = ET.fromstring(resources[c.MODULE + "/src/main/res/values/l10n_strings.xml"])
        default_plurals = ET.fromstring(resources[c.MODULE + "/src/main/res/values/l10n_plurals.xml"])
        self.assertEqual(self.summary["string_keys"], len(default_strings))
        self.assertEqual(self.summary["plural_keys"], len(default_plurals))
        self.assertEqual(self.summary["generated_keys"], len(default_strings) + len(default_plurals))

    def test_generated_headers_pass_the_actual_trusted_traceability_validator(self):
        from portlib.model import load_manifest
        from portmap import port_map
        mapped = {entry["implementation"]: entry for entry in port_map(load_manifest(c.REPOSITORY))}
        for path in self.outputs:
            if path.startswith(c.KOTLIN) and path.endswith(".kt"):
                self.assertTrue(mapped[path]["generated_inputs"], path)
                self.assertEqual([], mapped[path]["sources"])

    def test_source_signature_agreement_all_locales_and_no_unmapped_keys(self):
        for message in self.messages:
            with self.subTest(key=message.key):
                message.validate()
        names = [entry["android_name"] for entry in self.mapping["entries"]]
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(set(names), set(self.mapping["android_to_source"]))
        for entry in self.mapping["entries"]:
            for locale, record in entry["locales"].items():
                source = self.mapping["inputs"][record["source_input"]]
                self.assertIn(source["path"], self.inputs)
                self.assertEqual("localization", source["role"])
                if record.get("english_fallback", False):
                    self.assertEqual("it", locale)
                    self.assertEqual("shortcut", entry["domain"])

    def test_only_the_exact_pinned_chinese_format_is_corrected_and_raw_copy_survives(self):
        with self.assertRaisesRegex(c.ConversionError, "zh-Hans argument signature"):
            c.load_messages(self.inputs)
        message = next(m for m in self.messages if m.domain == "app" and m.key == "contacts.results.comparison")
        value = message.strings["zh-Hans"]
        self.assertEqual("对比 %@ 毫秒在 %@", value.raw)
        self.assertEqual("对比 %1$d 毫秒在 %2$s", value.format.android)
        self.assertEqual(message.strings["en"].format.arguments, value.format.arguments)
        self.assertEqual("WP-005-zh-Hans-comparison-integer", value.adaptation)
        self.assertEqual(1, self.summary["source_format_adaptations"])
        adaptations = copy.deepcopy(c.load_format_adaptations(c.REPOSITORY))
        next(iter(adaptations.values()))["expected_source"] = "stale"
        with self.assertRaisesRegex(c.ConversionError, "stale native-format"):
            c.load_messages(self.inputs, adaptations)
        for field, changed in (
            ("source_blob_sha", "0" * 40),
            ("source_value_sha256", "0" * 64),
            ("expected_android_format", "wrong replacement"),
        ):
            adaptations = copy.deepcopy(c.load_format_adaptations(c.REPOSITORY))
            next(iter(adaptations.values()))[field] = changed
            with self.subTest(field=field), self.assertRaisesRegex(c.ConversionError, "fingerprint|replacement"):
                c.load_messages(self.inputs, adaptations)

    def test_every_source_blob_revision_and_file_record_count_is_accounted(self):
        entries = {entry["path"]: entry for entry in self.mapping["inputs"]}
        self.assertEqual(set(self.inputs), set(entries))
        for path, data in self.inputs.items():
            with self.subTest(path=path):
                self.assertEqual(len(data), entries[path]["bytes"])
                self.assertEqual(self.counts.get(path), entries[path]["records"])
                self.assertEqual(40, len(entries[path]["blob_sha"]))

    def test_existing_scaffold_strings_are_not_generated_or_overwritten(self):
        self.assertNotIn(c.MODULE + "/src/main/res/values/strings.xml", self.outputs)
        scaffold = c.host_path(c.REPOSITORY, c.MODULE + "/src/main/res/values/strings.xml").read_text()
        self.assertIn('name="scaffold_not_yet_ported"', scaffold)
        self.assertIn("MeshCore One", scaffold)

    def test_regional_original_59_by_12_case_family_and_literal_percent(self):
        regions = [m for m in self.messages if m.table == "Settings" and m.key.startswith("region.subdivision.")]
        self.assertEqual(59, len(regions))
        for message in regions:
            self.assertEqual(set(c.LOCALES), set(message.strings))
            self.assertTrue(all(text.raw and text.raw != message.key for text in message.strings.values()))
        airtime = next(m for m in self.messages if m.table == "RemoteNodes" and m.key == "remoteNodes.status.airtimePercent")
        self.assertEqual("Airtime %", airtime.strings["en"].format.android)
        for locale, text in airtime.strings.items():
            self.assertFalse(text.format.arguments)
            self.assertEqual(text.raw.replace("%%", "%"), text.format.android)
        self.assertEqual("通信时间百分比", airtime.strings["zh-Hans"].format.android)

    def test_missing_catalog_italian_is_real_english_fallback_not_an_invented_translation(self):
        shortcuts = [m for m in self.messages if m.domain == "shortcut"]
        self.assertTrue(shortcuts)
        self.assertTrue(all("it" not in m.strings for m in shortcuts))
        missing = self.summary["locale_counts"]["it"]["english_fallback_keys"]
        self.assertEqual({m.name for m in shortcuts}, set(missing))
        italian = ET.fromstring(self.outputs[c.MODULE + "/src/main/res/values-it/l10n_strings.xml"])
        self.assertTrue(all(node.get("name") not in missing for node in italian))
        english = ET.fromstring(self.outputs[c.MODULE + "/src/main/res/values/l10n_strings.xml"])
        disposition = "{http://schemas.android.com/tools}ignore"
        marked = {node.get("name") for node in english if node.get(disposition) == "MissingTranslation"}
        self.assertEqual(set(missing), marked)
        self.assertEqual(7, len(marked))

    def test_locale_script_variant_and_english_fallback_declarations(self):
        self.assertEqual("values-b+zh+Hans", c.LOCALES["zh-Hans"]["directory"])
        self.assertEqual("pt-PT", c.LOCALES["pt"]["tag"])
        self.assertEqual("values-pt", c.LOCALES["pt"]["directory"])
        locales = ET.fromstring(self.outputs[c.MODULE + "/src/main/res/xml/l10n_locales.xml"])
        attr = "{http://schemas.android.com/apk/res/android}name"
        self.assertEqual([value["tag"] for value in c.LOCALES.values()], [node.get(attr) for node in locales])

    def test_billing_exclusions_are_key_level_and_preserve_shared_factual_copy(self):
        excluded = {entry["key"] for entry in self.mapping["exclusions"]}
        included = self.mapping["domains"]["app"]["Settings"]
        self.assertIn("Support.Restore.Button", excluded)
        self.assertIn("Support.Error.PurchaseFailed", excluded)
        for key in ("Support.Title", "Support.Contact.Link", "Support.Themes.AllUnlocked", "Support.Themes.PurchasedFooter", "Support.Theme.Marine", "Appearance.Title", "Appearance.Accessibility.ThemeCard.OwnedHint", "settings.backup.import.success.title"):
            self.assertIn(key, included)
        self.assertEqual(32, len(excluded))
        self.assertTrue(all(entry["reason"] for entry in self.mapping["exclusions"]))

    def test_cjk_long_multiline_and_widget_arguments_exist_in_real_sources(self):
        chinese = [m.strings["zh-Hans"].raw for m in self.messages if "zh-Hans" in m.strings]
        german = [m.strings["de"].raw for m in self.messages if "de" in m.strings]
        self.assertTrue(any("\u4e00" <= char <= "\u9fff" for text in chinese for char in text))
        self.assertGreater(max(map(len, german)), 300)
        self.assertTrue(any("\n" in m.strings["en"].raw for m in self.messages if "en" in m.strings))
        widgets = [m for m in self.messages if m.domain == "widget"]
        self.assertEqual(6, len(widgets))
        packets = next(m for m in widgets if m.key == "%lld packets per minute")
        self.assertEqual(((1, "long"),), packets.base.format.arguments)
        self.assertEqual(2, len([m for m in widgets if m.plurals]))

    def test_deterministic_generation_including_key_map_and_raw_source_oracle(self):
        again, summary = c.generate()
        self.assertEqual(self.outputs, again)
        self.assertEqual(self.summary, summary)
        oracle = json.loads(self.outputs[c.ORACLE])
        self.assertEqual(c.REFERENCE, oracle["source_pin"])
        for locale, info in oracle["locales"].items():
            records = json.loads(self.outputs[c.MODULE + "/src/test/resources/" + info["file"]])["records"]
            self.assertEqual(self.summary["generated_keys"], len(records))
            for record in records:
                message = self.mapping["android_to_source"][record["name"]]
                self.assertIn(message["key"], self.mapping["domains"][message["domain"]][message["table"]])

    def test_measured_pinned_key_counts_and_compatibility_aliases(self):
        self.assertEqual(2406, self.summary["unique_source_keys"])
        self.assertEqual(2374, self.summary["generated_keys"])
        self.assertEqual(2363, self.summary["string_keys"])
        self.assertEqual(11, self.summary["plural_keys"])
        self.assertEqual(77, self.summary["catalog_localized_records"])
        self.assertEqual(set(c.SCAFFOLD_TABS), set(self.mapping["compatibility_aliases"]))
        self.assertFalse(any(path.endswith("/values/l10n_scaffold_tabs.xml") for path in self.outputs))
        for locale, config in c.LOCALES.items():
            if locale != "en":
                root = ET.fromstring(self.outputs[c.MODULE + "/src/main/res/" + config["directory"] + "/l10n_scaffold_tabs.xml"])
                self.assertEqual(5, len(root))
                self.assertEqual(set(c.SCAFFOLD_TABS), {node.get("name") for node in root})

    def test_source_drift_is_read_only_and_line_endings_only_are_tolerated(self):
        original = b'"key"="value";\n'
        c.assert_source_bytes(original.replace(b"\n", b"\r\n"), original, "fixture.strings")
        with self.assertRaisesRegex(c.ConversionError, "read-only source drift"):
            c.assert_source_bytes(b'"key"="changed";\n', original, "fixture.strings")
        utf16 = '"key"="value";'.encode("utf-16")
        with self.assertRaises(c.ConversionError):
            c.assert_source_bytes(original, utf16, "fixture.strings")

    def test_stale_source_pin_fails_before_any_git_or_write(self):
        manifest = json.loads(c.host_path(c.REPOSITORY, "docs/android/port-manifest.json").read_text())
        manifest["reference"]["commit"] = "0" * 40
        with patch.object(Path, "read_text", return_value=json.dumps(manifest)), patch.object(c.subprocess, "run") as process:
            with self.assertRaisesRegex(c.ConversionError, "stale/changed reference"):
                c.pinned_inputs(c.REPOSITORY)
            process.assert_not_called()

    def test_missing_modified_generated_keymap_and_xml_fail(self):
        build = c.host_path(c.REPOSITORY, c.MODULE + "/build/l10n-python-tests")
        build.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=build) as temporary:
            root = Path(temporary)
            outputs = {"generated.xml": b"<resources/>\n", "key-map.json": b"{}\n"}
            with self.assertRaisesRegex(c.ConversionError, "missing/modified"):
                c.check_outputs(root, outputs)
            c.check_outputs(root, outputs, write=True)
            (root / "key-map.json").unlink()
            with self.assertRaises(c.ConversionError):
                c.check_outputs(root, outputs)
            c.check_outputs(root, outputs, write=True)
            (root / "generated.xml").write_text("<modified/>")
            with self.assertRaises(c.ConversionError):
                c.check_outputs(root, outputs)

    def test_collision_duplicate_localization_and_signature_mismatch_fail(self):
        first = "MC1/Resources/Localization/en.lproj/Localizable.strings"
        collisions = {
            f"MC1/Resources/Localization/{locale}.lproj/Localizable.strings": b'"a.b"="A";"a_b"="B";'
            for locale in c.LOCALES
        }
        with self.assertRaisesRegex(c.ConversionError, "collision"):
            c.load_messages(collisions)
        with self.assertRaisesRegex(c.ConversionError, "duplicate key"):
            c.load_messages({first: b'"same"="A";"same"="B";'})
        text = c.Text("%d", "en.strings", c.parse_format("%d", "en"))
        bad = c.Text("%lld", "de.strings", c.parse_format("%lld", "de"))
        with self.assertRaisesRegex(c.ConversionError, "signature"):
            c.Message("app", "Localizable", "typed", {"en": text, "de": bad}).validate()

    def test_unmapped_key_sparse_signature_and_invalid_generated_xml_fail(self):
        value = c.Text("missing", "de.strings", c.parse_format("missing", "de"))
        with self.assertRaisesRegex(c.ConversionError, "unmapped"):
            c.Message("app", "Localizable", "missing", {"de": value}).validate()
        value = c.Text("%2$d", "en.strings", c.parse_format("%2$d", "en"))
        with self.assertRaisesRegex(c.ConversionError, "missing positions"):
            c.Message("app", "Localizable", "sparse", {"en": value}).validate()
        for xml in (b"<resources>", b'<resources><string name="a.b" formatted="false">x</string></resources>', b'<resources><plurals name="good"><item quantity="one">x</item></plurals></resources>'):
            with self.subTest(xml=xml), self.assertRaises(c.ConversionError):
                c.validate_resource_xml(xml, "generated XML")


class EvidenceTest(unittest.TestCase):
    def test_real_nonzero_junit_cases_are_required(self):
        build = c.host_path(c.REPOSITORY, c.MODULE + "/build/l10n-python-tests")
        build.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=build) as temporary:
            directory = Path(temporary)
            with self.assertRaisesRegex(c.ConversionError, "missing"):
                c.check_android_tests(directory, {"fixture"})
            report = directory / "TEST-fixture.xml"
            report.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="fixture" name="assertion"/></testsuite>')
            self.assertEqual(1, c.check_android_tests(directory, {"fixture"})["discovered"])
            for content in (
                '<testsuite tests="0" failures="0" errors="0" skipped="0"/>',
                '<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase classname="fixture" name="assertion"/></testsuite>',
                '<testsuite tests="1" failures="0" errors="0" skipped="1"><testcase classname="fixture" name="assertion"><skipped/></testcase></testsuite>',
                '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="fixture" name="assertion"><failure/></testcase></testsuite>',
                '<testsuite',
            ):
                report.write_text(content)
                with self.subTest(content=content), self.assertRaises(c.ConversionError):
                    c.check_android_tests(directory, {"fixture"})

    def test_duplicate_and_incomplete_original_families_fail(self):
        build = c.host_path(c.REPOSITORY, c.MODULE + "/build/l10n-python-tests")
        build.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=build) as temporary:
            directory = Path(temporary)
            report = directory / "TEST-fixture.xml"
            report.write_text('<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase classname="fixture" name="same"/><testcase classname="fixture" name="same"/></testsuite>')
            with self.assertRaisesRegex(c.ConversionError, "duplicate testcase"):
                c.check_android_tests(directory, {"fixture"})
            for name in c.ANDROID_SUITES:
                (directory / f"TEST-{name}.xml").write_text(f'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="{name}" name="fixture"/></testsuite>')
            report.unlink()
            with self.assertRaisesRegex(c.ConversionError, "incomplete Android case-family"):
                c.check_android_tests(directory)
            for name, count in c.ANDROID_CASE_MINIMUMS.items():
                cases = "".join(f'<testcase classname="{name}" name="fixture{index}"/>' for index in range(count))
                (directory / f"TEST-{name}.xml").write_text(f'<testsuite tests="{count}" failures="0" errors="0" skipped="0">{cases}</testsuite>')
            with self.assertRaisesRegex(c.ConversionError, "missing original case"):
                c.check_android_tests(directory)


class JUnitRetentionTest(unittest.TestCase):
    def setUp(self):
        build = c.host_path(c.REPOSITORY, c.MODULE + "/build/l10n-python-tests")
        build.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=build)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.output = self.root / "retained"
        self.source.mkdir()
        self.write_reports()

    def tearDown(self):
        self.temporary.cleanup()

    def write_reports(self):
        timestamp = max(time.time_ns(), max(path.stat().st_mtime_ns for path in c.android_test_inputs()) + 1_000_000)
        for name, count in c.ANDROID_CASE_MINIMUMS.items():
            methods = [f"assertion{index}" for index in range(count)]
            originals = sorted(method for cls, method in c.ORIGINAL_CASES if cls == name)
            methods[:len(originals)] = originals
            cases = "".join(f'<testcase classname="{name}" name="{method}"/>\r\n' for method in methods)
            raw = b"\xef\xbb\xbf" + (
                f'<testsuite name="{name}" tests="{count}" failures="0" errors="0" skipped="0">\r\n'
                + cases + "</testsuite>\r\n"
            ).encode()
            path = self.source / f"TEST-{name}.xml"
            path.write_bytes(raw)
            os.utime(path, ns=(timestamp, timestamp))

    def test_missing_recovery_suite_is_rejected(self):
        (self.source / "TEST-com.meshcoreone.android.core.l10n.RecoveredDraftResourceTest.xml").unlink()
        with self.assertRaisesRegex(c.ConversionError, "Android suite inventory mismatch"):
            c.check_android_tests(self.source)

    def test_each_recovery_case_family_is_required(self):
        name = "com.meshcoreone.android.core.l10n.RecoveredDraftResourceTest"
        report = self.source / f"TEST-{name}.xml"
        for cls, method in sorted(c.ORIGINAL_CASES):
            if cls != name:
                continue
            with self.subTest(method=method):
                self.write_reports()
                suite = ET.fromstring(report.read_bytes())
                for case in suite.findall("testcase"):
                    if case.get("name") == method:
                        case.set("name", "unrelatedAssertion")
                report.write_bytes(ET.tostring(suite))
                with self.assertRaisesRegex(c.ConversionError, "missing original case family"):
                    c.check_android_tests(self.source)

    def test_complete_raw_reports_are_byte_identical_and_summary_is_typed(self):
        result = c.copy_android_junit(self.source, self.output)
        self.assertEqual(27, result["android_tests"]["discovered"])
        self.assertEqual(5, result["android_tests"]["suites"])
        self.assertEqual(c.ANDROID_CASE_MINIMUMS, result["android_tests"]["class_counts"])
        self.assertRegex(result["candidate_head"], r"^[0-9a-f]{40}$")
        self.assertEqual(c.REFERENCE, result["source_pin"])
        self.assertTrue(result["source_sha256"])
        expected = {path.name for path in self.source.glob("TEST-*.xml")} | {"summary.json"}
        self.assertEqual(expected, {path.name for path in self.output.iterdir()})
        for source in self.source.glob("TEST-*.xml"):
            self.assertEqual(source.read_bytes(), (self.output / source.name).read_bytes())
        self.assertEqual(result, json.loads((self.output / "summary.json").read_text(encoding="utf-8")))
        self.assertEqual(result, c.copy_android_junit(self.source, self.output))
        self.assertEqual(c.check_android_tests(self.source), c.check_android_tests(self.output))

    def test_missing_suite_rejects_retention_without_creating_output(self):
        next(self.source.glob("TEST-*.xml")).unlink()
        with self.assertRaisesRegex(c.ConversionError, "suite inventory"):
            c.copy_android_junit(self.source, self.output)
        self.assertFalse(self.output.exists())

    def test_zero_skipped_and_malformed_reports_never_create_passing_artifacts(self):
        first = next(self.source.glob("TEST-*.xml"))
        for failure in ("zero", "skipped", "malformed"):
            self.write_reports()
            suite = ET.fromstring(first.read_bytes())
            if failure == "zero":
                for case in list(suite):
                    suite.remove(case)
                suite.set("tests", "0")
            elif failure == "skipped":
                suite.set("skipped", "1")
                ET.SubElement(suite[0], "skipped")
            else:
                suite.set("tests", "not-an-integer")
            first.write_bytes(ET.tostring(suite))
            with self.subTest(failure=failure), self.assertRaises(c.ConversionError):
                c.copy_android_junit(self.source, self.output)
            self.assertFalse(self.output.exists())

    def test_duplicate_cases_and_unknown_suites_reject_retention(self):
        first = next(self.source.glob("TEST-*.xml"))
        duplicate = self.source / "TEST-duplicate.xml"
        duplicate.write_bytes(first.read_bytes())
        with self.assertRaisesRegex(c.ConversionError, "duplicate testcase"):
            c.copy_android_junit(self.source, self.output)
        duplicate.unlink()
        duplicate.write_bytes(b'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="unknown" name="assertion"/></testsuite>')
        with self.assertRaisesRegex(c.ConversionError, "suite inventory"):
            c.copy_android_junit(self.source, self.output)
        self.assertFalse(self.output.exists())

    def test_stale_source_reports_cannot_be_relabelled_as_current_evidence(self):
        older = min(path.stat().st_mtime_ns for path in c.android_test_inputs()) - 1_000_000_000
        for path in self.source.glob("TEST-*.xml"):
            os.utime(path, ns=(older, older))
        with self.assertRaisesRegex(c.ConversionError, "stale JUnit evidence"):
            c.copy_android_junit(self.source, self.output)
        self.assertFalse(self.output.exists())

    def test_stale_destination_refuses_overwrite_and_preserves_old_bytes(self):
        c.copy_android_junit(self.source, self.output)
        summary = self.output / "summary.json"
        value = json.loads(summary.read_text(encoding="utf-8"))
        value["candidate_head"] = "0" * 40
        summary.write_bytes(c.json_bytes(value))
        previous = summary.read_bytes()
        with self.assertRaisesRegex(c.ConversionError, "stale/missing/modified"):
            c.copy_android_junit(self.source, self.output)
        self.assertEqual(previous, summary.read_bytes())

    def test_relative_readonly_source_and_overlapping_output_paths_are_rejected(self):
        for destination in (
            Path("relative-junit"),
            c.host_path(c.REPOSITORY, "MC1/Resources/Localization/junit"),
            c.host_path(c.REPOSITORY, "docs/android/evidence/WP-005/junit"),
            self.source,
            self.source / "child",
            self.root,
        ):
            with self.subTest(path=str(destination)), self.assertRaises(c.ConversionError):
                c.copy_android_junit(self.source, destination)
        self.assertFalse(self.output.exists())

    def test_linked_output_path_and_changed_report_fail_explicitly(self):
        original = Path.is_symlink
        with patch.object(Path, "is_symlink", lambda path: path == self.output or original(path)):
            with self.assertRaisesRegex(c.ConversionError, "symlink/junction"):
                c.copy_android_junit(self.source, self.output)
        check = c.check_android_tests

        def changed_after_validation(directory):
            result = check(directory)
            first = next(directory.glob("TEST-*.xml"))
            first.write_bytes(first.read_bytes() + b" ")
            return result

        with patch.object(c, "check_android_tests", side_effect=changed_after_validation):
            with self.assertRaisesRegex(c.ConversionError, "changed after validation"):
                c.copy_android_junit(self.source, self.output)
        self.assertFalse(self.output.exists())

    def test_invalid_ci_binding_fails_without_passing_output(self):
        for values in (
            {"GITHUB_REPOSITORY": "wrong/repository"},
            {"GITHUB_RUN_ID": "12", "GITHUB_RUN_ATTEMPT": "0"},
        ):
            with self.subTest(values=values), patch.dict(c.os.environ, values):
                with self.assertRaisesRegex(c.ConversionError, "identity"):
                    c.copy_android_junit(self.source, self.output)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
