# AndroidOnly: WP-301 Independent source-color/shape/fallback and fail-closed converter assertions.
import importlib.util
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[6]
sys.path.insert(0, str(ROOT / "tools" / "android-port"))
import theme_convert as converter


def asset(colors):
    return json.dumps({"info": {"author": "xcode", "version": 1}, "colors": colors}).encode()


def color(appearances=(), red="0.141", space="srgb", alpha="1.000"):
    result = {
        "idiom": "universal",
        "color": {"color-space": space, "components": {"red": red, "green": "0.388", "blue": "0.922", "alpha": alpha}},
    }
    if appearances:
        result["appearances"] = [{"appearance": key, "value": value} for key, value in appearances]
    return result


class ConverterTest(unittest.TestCase):
    def parse(self, colors):
        return converter.parse_color_asset(asset(colors), "independent-test.colorset")

    def test_exact_decimal_source_components_not_hex_rounding(self):
        parsed = self.parse([color()])
        self.assertEqual(0.141, parsed["effective"]["light_standard"]["components"]["red"])
        self.assertNotEqual(36 / 255, parsed["effective"]["light_standard"]["components"]["red"])

    def test_display_p3_is_preserved_not_relabelled_srgb(self):
        self.assertEqual("display-p3", self.parse([color(space="display-p3")])["effective"]["dark_high"]["color_space"])

    def test_alpha_and_hex_components_are_exact(self):
        self.assertEqual(1.0, converter.component("0xFF", "test"))
        self.assertEqual(0.0, converter.component("0x00", "test"))
        self.assertEqual(0.5, self.parse([color(alpha="0.500")])["effective"]["light_high"]["components"]["alpha"])

    def test_missing_dark_high_retains_dark_not_universal_high(self):
        parsed = self.parse([
            color(), color((("luminosity", "dark"),), red="0.500"),
            color((("contrast", "high"),), red="0.200"),
        ])
        self.assertEqual("dark_standard", parsed["effective"]["dark_high"]["selected_variant"])
        self.assertEqual(0.5, parsed["effective"]["dark_high"]["components"]["red"])
        self.assertEqual(0.2, parsed["effective"]["light_high"]["components"]["red"])

    def test_exact_dark_high_specialization_wins(self):
        parsed = self.parse([
            color(), color((("luminosity", "dark"),), red="0.500"),
            color((("luminosity", "dark"), ("contrast", "high")), red="0.750"),
        ])
        self.assertEqual("dark_high", parsed["effective"]["dark_high"]["selected_variant"])
        self.assertEqual(0.75, parsed["effective"]["dark_high"]["components"]["red"])

    def test_universal_fallback_covers_all_four_states(self):
        parsed = self.parse([color()])
        self.assertEqual(4, len(parsed["effective"]))
        self.assertTrue(all(value["selected_variant"] == "light_standard" for value in parsed["effective"].values()))

    def test_zero_variants_fail(self):
        with self.assertRaises(converter.ThemeConversionError):
            self.parse([])

    def test_missing_universal_fallback_fails(self):
        with self.assertRaises(converter.ThemeConversionError):
            self.parse([color((("luminosity", "dark"),))])

    def test_duplicate_variant_fails(self):
        with self.assertRaises(converter.ThemeConversionError):
            self.parse([color(), color()])

    def test_duplicate_json_key_fails(self):
        with self.assertRaises(converter.ThemeConversionError):
            converter.unique_json(b'{"colors":[],"colors":[]}', "test")

    def test_unknown_appearance_idiom_and_space_fail(self):
        for data in (color((("luminosity", "bright"),)), color(space="xyz"), {**color(), "idiom": "watch"}):
            with self.subTest(data=data), self.assertRaises(converter.ThemeConversionError):
                self.parse([data])

    def test_missing_or_unknown_components_fail(self):
        for field in ("red", "green", "blue", "alpha"):
            data = color()
            del data["color"]["components"][field]
            with self.subTest(field=field), self.assertRaises(converter.ThemeConversionError):
                self.parse([data])
        data = color()
        data["color"]["components"]["white"] = "1.000"
        with self.assertRaises(converter.ThemeConversionError):
            self.parse([data])

    def test_nonfinite_out_of_range_and_malformed_hex_fail(self):
        for value in ("NaN", "Infinity", "-0.1", "1.001", "0x1", "0x100", "0xGG", "", 0.5):
            with self.subTest(value=value), self.assertRaises(converter.ThemeConversionError):
                converter.component(value, "test")

    def test_bad_json_and_unknown_fields_fail(self):
        for raw in (b'{"x":NaN}', b"[]", b"{", b'{"info":{"version":1},"colors":[],"other":1}'):
            with self.subTest(raw=raw), self.assertRaises(converter.ThemeConversionError):
                converter.parse_color_asset(raw, "test")

    def test_malformed_info_and_appearance_types_are_typed_failures(self):
        for info in (None, [], {"version": 1}, {"author": "other", "version": 1}):
            with self.subTest(info=info), self.assertRaises(converter.ThemeConversionError):
                converter.parse_color_asset(json.dumps({"info": info, "colors": [color()]}).encode(), "test")
        for appearances in (None, "dark", {}):
            value = color()
            value["appearances"] = appearances
            with self.subTest(appearances=appearances), self.assertRaises(converter.ThemeConversionError):
                self.parse([value])

    def test_frozen_theme_catalog_and_registry_order_are_independent(self):
        source = converter.blob(converter.THEME_SOURCE).decode()
        products = {name: "io.pocketmesh.app.theme." + name for name in converter.IDS[1:]}
        parsed = converter.parse_themes(source, products, "default")
        self.assertEqual(list(converter.IDS), [theme["id"] for theme in parsed])
        self.assertEqual([0.0, 8.0, 12.0, 24.0, 18.0, 345.0], parsed[1]["hue_anchors"])
        self.assertEqual([0.5, 0.82], parsed[1]["saturation"])
        self.assertEqual([0x336688, 0x00AAFF, 0xFF8800], parsed[0]["category_override"])
        self.assertEqual(["ember"], [theme["id"] for theme in parsed if theme["preferred_scheme"]])

    def test_missing_theme_and_unknown_forced_scheme_fail(self):
        source = converter.blob(converter.THEME_SOURCE).decode()
        products = {name: name for name in converter.IDS[1:]}
        for text in (source.replace('static let fern = Theme(', 'static let fern2 = Theme('),
                     source.replace('preferredColorScheme: .dark', 'preferredColorScheme: .bright'),
                     source.replace('preferredColorScheme: .dark', 'preferredColorScheme: .light')):
            with self.subTest(text=text[:30]), self.assertRaises(converter.ThemeConversionError):
                converter.parse_themes(text, products, "default")

    def test_generation_is_byte_identical_and_has_complete_frozen_inputs(self):
        first = converter.outputs()
        self.assertEqual(first, converter.outputs())
        provenance = json.loads(first[converter.SOURCE_MAP])
        self.assertEqual(converter.PIN, provenance["reference_commit"])
        self.assertEqual(converter.TREE, provenance["reference_tree"])
        self.assertEqual(89, len(provenance["primary_owned_inputs"]))
        self.assertEqual(59, len(provenance["resource_dispositions"]))
        self.assertEqual(44, len(provenance["color_assets"]))
        self.assertEqual(12, len(provenance["recovery"]))
        self.assertTrue(all(len(blob) == 40 for blob in provenance["all_input_blobs"].values()))

    def test_recovery_copy_is_exact_source_and_not_entitlement_copy(self):
        generated = converter.outputs()
        provenance = json.loads(generated[converter.SOURCE_MAP])
        self.assertEqual("Theme reverted to the default.", provenance["recovery"]["en"]["text"])
        for resource in (data for path, data in generated.items() if path.endswith("theme_recovery.xml")):
            self.assertNotIn(b"ThemeNotOwned", resource)
            self.assertNotIn(b"Purchase", resource)

    def test_icons_have_real_source_meanings_and_no_apple_artwork(self):
        result = json.loads(converter.outputs()["docs/android/generated/icon-map.json"])
        self.assertEqual(17, len(result["icons"]))
        self.assertEqual(17, len({item["native_symbol"] for item in result["icons"]}))
        self.assertTrue(all(item["license"] == "GPL-3.0-only" and len(item["source_blob"]) == 40 for item in result["icons"]))

    def test_output_paths_never_escape_module_or_owned_icon_map(self):
        for path in converter.outputs():
            self.assertTrue(path.startswith(converter.MODULE + "/") or path == "docs/android/generated/icon-map.json", path)
