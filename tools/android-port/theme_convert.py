"""WP-301: frozen Git theme/assets -> immutable Kotlin palettes and native provenance.

No network, Apple artwork extraction, unpinned source inputs, shared output writes,
golden updates, or third-party Python packages.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import math
from pathlib import Path
import plistlib
import re
import subprocess
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "android-port"))
from portlib.errors import PortError
from portlib.model import load_manifest

PIN = "db14559b39d32322b06477c6ae676112f583db50"
TREE = "8918fdc604341e6996a68c88f6bb1c02b9c2f87e"
GENERATOR = "tools/android-port/theme_convert.py"
MODULE = "android/core/designsystem"
KOTLIN = MODULE + "/src/main/kotlin/com/meshcoreone/android/core/designsystem/generated"
SOURCE_MAP = MODULE + "/src/main/assets/theme-source-map.json"
RESOURCE_ROOT = "MC1/Resources/Assets.xcassets/"
THEME_SOURCE = "MC1/Theme/Theme.swift"
IDS = ("default", "ember", "fern", "marine", "olive", "lavender", "sakura", "solarized", "nord", "catppuccin")
LOCALES = {
    "en": "values", "de": "values-de", "es": "values-es", "fr": "values-fr", "it": "values-it",
    "ko": "values-ko", "nl": "values-nl", "pl": "values-pl", "pt": "values-pt",
    "ru": "values-ru", "uk": "values-uk", "zh-Hans": "values-b+zh+Hans",
}
ICON_MEANINGS = {
    "message.fill": ("MESSAGES", "Chats and messages", "MC1/ContentView.swift"),
    "flipphone": ("NODES", "Companion radio and nodes", "MC1/ContentView.swift"),
    "map.fill": ("MAP", "Map", "MC1/ContentView.swift"),
    "wrench.and.screwdriver": ("TOOLS", "Tools", "MC1/ContentView.swift"),
    "gear": ("SETTINGS", "Settings", "MC1/ContentView.swift"),
    "globe": ("GLOBE", "Public channel", "MC1/Views/Chats/ChannelAvatar.swift"),
    "number": ("HASHTAG", "Hashtag channel", "MC1/Views/Chats/ChannelAvatar.swift"),
    "lock": ("LOCK", "Private channel; never a theme entitlement", "MC1/Views/Chats/ChannelAvatar.swift"),
    "antenna.radiowaves.left.and.right": ("RADIO", "Repeater or companion radio", "MC1/Views/Components/NodeAvatar.swift"),
    "door.left.hand.closed": ("ROOM", "Room server", "MC1/Views/Components/NodeAvatar.swift"),
    "checkmark": ("CHECK", "Selected theme", "MC1/Views/Appearance/Components/ThemeSelectionCard.swift"),
    "checkmark.circle": ("READY", "Ready status", "MC1/State/StatusPillState.swift"),
    "exclamationmark.triangle": ("WARNING", "Disconnected or warning status", "MC1/State/StatusPillState.swift"),
    "exclamationmark.triangle.fill": ("ERROR", "Failure status", "MC1/State/StatusPillState.swift"),
    "arrow.trianglehead.2.clockwise": ("SYNC", "Connecting or syncing status", "MC1/State/StatusPillState.swift"),
    "repeat": ("REPEAT", "Client repeat mode", "MC1/Views/Components/BLEStatusIndicatorView.swift"),
    "cellularbars": ("SIGNAL", "Signal quality", "MC1Services/Sources/MC1Services/Models/Rendering/SNRQuality.swift"),
}


class ThemeConversionError(ValueError):
    pass


def fail(location, message):
    raise ThemeConversionError(f"{location}: {message}")


def git(*args):
    result = subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, check=False)
    if result.returncode:
        fail("Git", "required immutable input is unavailable: " + " ".join(args[:2]))
    return result.stdout


def host_path(path):
    if not isinstance(path, str) or any(part in ("", ".", "..") for part in path.split("/")):
        fail("path", "unsafe Git-relative path")
    return ROOT.joinpath(*path.split("/"))


def blob(path, revision=PIN):
    return git("show", revision + ":" + path)


def identity(path, revision=PIN):
    return git("rev-parse", revision + ":" + path).decode("ascii").strip()


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=True, allow_nan=False) + "\n").encode("utf8")


def unique_json(data, location):
    def pairs(values):
        result = {}
        for key, value in values:
            if key in result:
                fail(location, "duplicate JSON key " + key)
            result[key] = value
        return result
    def invalid(value):
        fail(location, "non-finite JSON value " + value)
    try:
        result = json.loads(data, object_pairs_hook=pairs, parse_constant=invalid)
    except (UnicodeError, json.JSONDecodeError) as error:
        fail(location, str(error))
    if not isinstance(result, dict):
        fail(location, "expected object")
    return result


def component(value, location):
    if not isinstance(value, str):
        fail(location, "source components must be strings")
    if re.fullmatch(r"0x[0-9a-fA-F]{2}", value):
        result = int(value[2:], 16) / 255
    elif re.fullmatch(r"(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)", value):
        result = float(value)
    else:
        fail(location, "malformed decimal or two-digit hexadecimal component")
    if not math.isfinite(result) or not 0 <= result <= 1:
        fail(location, "component outside 0...1")
    return result


def parse_color_asset(data, location):
    asset = unique_json(data, location)
    info = asset.get("info")
    if (set(asset) - {"colors", "info"} or not isinstance(info, dict) or
            set(info) != {"author", "version"} or info.get("version") != 1 or info.get("author") != "xcode"):
        fail(location, "unsupported color asset structure")
    colors = asset.get("colors")
    if not isinstance(colors, list) or not colors:
        fail(location, "missing or zero color variants")
    variants = {}
    for entry in colors:
        if not isinstance(entry, dict) or set(entry) - {"idiom", "color", "appearances"} or entry.get("idiom") != "universal":
            fail(location, "unsupported color idiom/entry")
        appearance = {}
        appearances = entry.get("appearances", [])
        if not isinstance(appearances, list):
            fail(location, "appearances must be an array")
        for item in appearances:
            if not isinstance(item, dict) or set(item) != {"appearance", "value"}:
                fail(location, "malformed appearance")
            key, value = item["appearance"], item["value"]
            if key in appearance or (key, value) not in {("luminosity", "dark"), ("contrast", "high")}:
                fail(location, "unknown or duplicate appearance")
            appearance[key] = value
        key = ("dark" if "luminosity" in appearance else "light", "high" if "contrast" in appearance else "standard")
        if key in variants:
            fail(location, "duplicate effective appearance")
        color = entry.get("color")
        if not isinstance(color, dict) or set(color) != {"color-space", "components"}:
            fail(location, "missing/unknown color fields")
        if color["color-space"] not in ("srgb", "display-p3"):
            fail(location, "unsupported color space")
        components = color["components"]
        if not isinstance(components, dict) or set(components) != {"red", "green", "blue", "alpha"}:
            fail(location, "missing/unknown RGBA components")
        variants[key] = {
            "color_space": color["color-space"],
            "raw_components": components,
            "components": {name: component(components[name], location) for name in ("red", "green", "blue", "alpha")},
        }
    if ("light", "standard") not in variants:
        fail(location, "missing universal fallback")
    effective = {}
    for scheme in ("light", "dark"):
        for contrast in ("standard", "high"):
            # A missing dark/high specialization retains the dark appearance, not the light/high palette.
            candidates = ((scheme, contrast), (scheme, "standard"), ("light", contrast), ("light", "standard"))
            source = next(candidate for candidate in candidates if candidate in variants)
            effective[scheme + "_" + contrast] = {**variants[source], "selected_variant": "_".join(source)}
    return {
        "variants": {"_".join(key): value for key, value in variants.items()},
        "effective": effective,
    }


def quoted(block, label):
    match = re.search(r"\b" + re.escape(label) + r':\s*"([^"]+)"', block)
    return match[1] if match else None


def parse_themes(text, products, default_id):
    matches = list(re.finditer(r"static let (`default`|[a-z]+) = Theme\(", text))
    if tuple(match[1].strip("`") for match in matches) != IDS or default_id != "default":
        fail(THEME_SOURCE, "unknown/missing theme identity or ordering")
    result = []
    for index, match in enumerate(matches):
        block = text[match.end():matches[index + 1].start() if index + 1 < len(matches) else len(text)]
        theme_id = match[1].strip("`")
        raw_id = quoted(block, "id")
        if theme_id != "default" and raw_id != theme_id:
            fail(THEME_SOURCE, "raw ID drift")
        anchors = re.search(r"hueAnchors:\s*\[([^]]+)\]", block)
        saturation = re.search(r"saturation:\s*([0-9.]+)\.\.\.([0-9.]+)", block)
        if anchors is None or saturation is None:
            fail(THEME_SOURCE, "missing gamut")
        hues = [float(item.strip()) for item in anchors[1].split(",")]
        band = [float(saturation[1]), float(saturation[2])]
        if not hues or len(set(hues)) != len(hues) or not all(0 <= hue < 360 and math.isfinite(hue) for hue in hues):
            fail(THEME_SOURCE, "invalid hue anchors")
        if not 0 <= band[0] < band[1] <= 1:
            fail(THEME_SOURCE, "invalid saturation")
        colors = {}
        for label in ("accentColor", "outgoingTextColor", "hashtagColor"):
            color = re.search(r"\b" + label + r':\s*(Color\("([^"]+)"\)|\.(white|black))', block)
            if color is None:
                fail(THEME_SOURCE, "missing/unknown " + label)
            colors[label] = color[2] if color[2] else color[3]
        preferred = re.search(r"preferredColorScheme:\s*(nil|\.(dark|light))", block)
        if preferred is None:
            fail(THEME_SOURCE, "missing scheme policy")
        canvas = re.search(r"surfaces:\s*\.init\(canvas:\s*(Color\(\"([^\"]+)\"\)|\.(black|white))", block)
        card = re.search(r"card:\s*Color\(\"([^\"]+)\"\)", block)
        categories = re.search(r"categoryHues:\s*CategoryHues\(channel:\s*(\d+),\s*repeater:\s*(\d+),\s*room:\s*(\d+)\)", block)
        overrides = re.search(
            r"categoryAvatarOverride:\s*CategoryAvatarColors\(\s*channel:\s*Color\(hex:\s*(0x[0-9A-F]+)\),"
            r"\s*repeaterNode:\s*Color\(hex:\s*(0x[0-9A-F]+)\),\s*room:\s*Color\(hex:\s*(0x[0-9A-F]+)\)", block,
        )
        product = re.search(r"productID:\s*StoreCatalog\.Theme\.([a-z]+)", block)
        if theme_id != "default" and (product is None or product[1] not in products):
            fail(THEME_SOURCE, "missing original product provenance")
        result.append({
            "id": theme_id, "display_name_key": quoted(block, "displayNameKey"),
            "source_product_id": products[product[1]] if product else None,
            "preferred_scheme": preferred[2], "colors": colors, "hue_anchors": hues, "saturation": band,
            "canvas": (canvas[2] or canvas[3]) if canvas else None, "card": card[1] if card else None,
            "category_hues": [float(categories[n]) for n in (1, 2, 3)] if categories else None,
            "category_override": [int(overrides[n], 16) for n in (1, 2, 3)] if overrides else None,
        })
    if [(theme["id"], theme["preferred_scheme"]) for theme in result if theme["preferred_scheme"] is not None] != [("ember", "dark")]:
        fail(THEME_SOURCE, "forced-scheme rules changed")
    return result


def kotlin_color(color):
    space = "DISPLAY_P3" if color["color_space"] == "display-p3" else "SRGB"
    values = ", ".join(repr(color["components"][key]) for key in ("red", "green", "blue", "alpha"))
    return f"ThemeColor({values}, ThemeColorSpace.{space})"


def kotlin_catalog(themes, assets):
    lines = [
        f"// GeneratedBy: {GENERATOR}; DO NOT EDIT.",
        f"// GeneratedFrom: {GENERATOR}; inputs: MC1/Theme/Theme.swift@{PIN}, MC1/Resources/Assets.xcassets/AppAccentColor.colorset/Contents.json@{PIN}",
        f"// SourcePin: {PIN}; SourceMap: {SOURCE_MAP}",
        "package com.meshcoreone.android.core.designsystem", "",
        "import com.meshcoreone.android.core.model.SnapshotList",
        "import com.meshcoreone.android.core.model.snapshotMap", "",
        "internal object GeneratedThemeColors {",
        "    private val values = mapOf(",
    ]
    for name, asset in sorted(assets.items()):
        values = [kotlin_color(asset["effective"][key]) for key in ("light_standard", "dark_standard", "light_high", "dark_high")]
        lines.append(f'        "{name}" to AdaptiveColor({", ".join(values)}),')
    lines.extend([
        "    ).snapshotMap()",
        "    fun get(name: String): AdaptiveColor = values[name] ?: throw ThemeResourceFailure(name)",
        "}", "", "internal object GeneratedThemes {", "    val allThemes = SnapshotList.of(",
    ])
    def color(value):
        return "AdaptiveColor(ThemeColor." + value.upper() + ")" if value in ("white", "black") else f'GeneratedThemeColors.get("{value}")'
    for theme in themes:
        nullable = lambda value: "null" if value is None else json.dumps(value)
        lines.extend([
            "        Theme(",
            "            id = ThemeId." + theme["id"].upper() + ",",
            "            displayNameKey = " + nullable(theme["display_name_key"]) + ",",
            "            sourceProductID = " + nullable(theme["source_product_id"]) + ",",
            "            accentColor = " + color(theme["colors"]["accentColor"]) + ",",
            "            outgoingTextColor = " + color(theme["colors"]["outgoingTextColor"]) + ",",
            "            hashtagColor = " + color(theme["colors"]["hashtagColor"]) + ",",
            "            preferredColorScheme = " + ("ColorScheme." + theme["preferred_scheme"].upper() if theme["preferred_scheme"] else "null") + ",",
            "            identityGamut = IdentityGamut(listOf(" + ", ".join(repr(h) for h in theme["hue_anchors"]) + "), " +
                ", ".join(repr(s) for s in theme["saturation"]) + "),",
        ])
        if theme["canvas"]:
            lines.append("            surfaces = ThemeSurfaces(" + color(theme["canvas"]) +
                         (", " + color(theme["card"]) if theme["card"] else "") + "),")
        if theme["category_override"]:
            lines.append("            categoryAvatarOverride = CategoryAvatarColors(" +
                         ", ".join("ThemeColor.hex(" + hex(value) + ")" for value in theme["category_override"]) + "),")
        if theme["category_hues"]:
            lines.append("            categoryHues = CategoryHues(" + ", ".join(repr(h) for h in theme["category_hues"]) + "),")
        lines.extend(["        ),"])
    lines.extend(["    )", "}", ""])
    return "\n".join(lines).encode("utf8")


def existing_l10n_reader():
    path = "tools/android-port/l10n_convert.py"
    spec = importlib.util.spec_from_file_location("wp301_l10n_reader", host_path(path))
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def outputs():
    if git("rev-parse", PIN + "^{tree}").decode().strip() != TREE:
        fail("reference", "frozen tree mismatch")
    try:
        catalog = load_manifest(ROOT)
    except PortError as error:
        fail("manifest", str(error))
    manifest = catalog.data
    owned = [entry for entry in manifest["inventory"] if entry["primary_owner"] == "WP-301"]
    if len(owned) != 89:
        fail("ownership", "missing/unknown source inputs")
    inputs = {}
    assets = {}
    resource_dispositions = {}
    for entry in owned:
        path = entry["path"]
        actual = identity(path)
        if actual != entry["blob_sha"]:
            fail(path, "frozen blob mismatch")
        inputs[path] = actual
        if entry["kind"] != "resource":
            continue
        data = blob(path)
        if ".colorset/Contents.json" in path:
            name = path.removeprefix(RESOURCE_ROOT).removesuffix(".colorset/Contents.json")
            assets[name] = {"path": path, "blob_sha": actual, **parse_color_asset(data, path)}
            resource_dispositions[path] = "converted-exact-components-and-effective-variants"
        elif path.endswith("Contents.json"):
            descriptor = unique_json(data, path)
            if set(descriptor) - {"info", "properties", "images"}:
                fail(path, "unknown namespace/icon descriptor")
            resource_dispositions[path] = "source-namespace-or-native-launcher-reference"
        elif path.startswith("AppIcon.icon/Assets/"):
            if not data.startswith(b"\x89PNG\r\n\x1a\n"):
                fail(path, "invalid incumbent PNG")
            resource_dispositions[path] = "verbatim-licensed-incumbent-native-brand-resource"
        elif path == "AppIcon.icon/icon.json":
            unique_json(data, path)
            resource_dispositions[path] = "incumbent-icon-recipe-reference-no-Apple-glass-extraction"
        else:
            fail(path, "unaccounted resource")
    if len(assets) != 44 or len(resource_dispositions) != 59:
        fail("resources", "missing/unknown/zero palette or resource family")
    def read(path):
        inputs[path] = identity(path)
        return blob(path)
    product_path = "MC1Services/Sources/MC1Services/Services/StoreCatalog.swift"
    products = dict(re.findall(r'public static let ([a-z]+) = "([^"]+)"', read(product_path).decode("utf8")))
    env_path = "MC1Services/Sources/MC1Services/Models/Rendering/EnvInputs.swift"
    default = re.search(r'defaultThemeID = "([^"]+)"', read(env_path).decode("utf8"))
    if default is None:
        fail(env_path, "missing default theme ID")
    themes = parse_themes(read(THEME_SOURCE).decode("utf8"), products, default[1])
    referenced = {
        color for theme in themes for color in [*theme["colors"].values(), theme["canvas"], theme["card"]]
        if color not in (None, "white", "black")
    }
    if referenced != assets.keys():
        fail("palette references", "unknown, unused or missing named colors")
    result = {KOTLIN + "/GeneratedThemes.kt": kotlin_catalog(themes, assets)}
    result[MODULE + "/src/test/resources/theme-source-inputs.json"] = json_bytes({
        "source_pin": PIN,
        "source_tree": TREE,
        "assets": {
            name: {"path": value["path"], "blob_sha": value["blob_sha"],
                   "raw": blob(value["path"]).decode("utf8")}
            for name, value in assets.items()
        },
        "theme_source_blob": inputs[THEME_SOURCE],
        "theme_source": blob(THEME_SOURCE).decode("utf8"),
    })
    reader = existing_l10n_reader()
    recovery = {}
    key = "Appearance.Accessibility.ThemeReverted"
    for locale, directory in LOCALES.items():
        path = "MC1/Resources/Localization/" + locale + ".lproj/Settings.strings"
        values = reader.parse_strings(read(path), path)
        if key not in values or not values[key]:
            fail(path, "missing/empty approved recovery copy")
        value = values[key]
        recovery[locale] = {"path": path, "blob_sha": inputs[path], "key": key, "text": value}
        xml = ('<?xml version="1.0" encoding="utf-8"?>\n' +
               f'<!-- GeneratedBy: {GENERATOR}; SourcePin: {PIN}; SourceMap: {SOURCE_MAP} -->\n' +
               '<resources>\n    <string name="theme_reverted">' +
               reader.android_xml_text(value, path) + "</string>\n</resources>\n")
        result[MODULE + "/src/main/res/" + directory + "/theme_recovery.xml"] = xml.encode("utf8")
    result[KOTLIN + "/ThemeRecoveryStrings.kt"] = (
        f"// GeneratedBy: {GENERATOR}; DO NOT EDIT.\n" +
        f"// GeneratedFrom: {GENERATOR}; inputs: MC1/Resources/Localization/en.lproj/Settings.strings@{PIN}\n" +
        f"// SourcePin: {PIN}; SourceMap: {SOURCE_MAP}\n" +
        "package com.meshcoreone.android.core.designsystem\n\n" +
        "object ThemeRecoveryStrings {\n    val themeReverted: Int get() = R.string.theme_reverted\n}\n"
    ).encode("utf8")
    for name in ("app-icon", "background"):
        path = "AppIcon.icon/Assets/" + name + ".png"
        result[MODULE + "/src/main/res/drawable-nodpi/meshcore_one_" +
               ("mark" if name == "app-icon" else "background") + ".png"] = read(path)
    for name in ("Catppuccin", "Nord", "Solarized"):
        path = "MC1/Settings.bundle/Packages/" + name + ".plist"
        data = plistlib.loads(read(path))
        notices = [item["FooterText"] for item in data["PreferenceSpecifiers"] if "FooterText" in item]
        if len(notices) != 1 or "Permission is hereby granted" not in notices[0]:
            fail(path, "missing actual MIT palette notice")
        result[MODULE + "/src/main/assets/licenses/WP-301/" + name + "-MIT.txt"] = notices[0].encode("utf8")
    result[MODULE + "/src/main/assets/licenses/WP-301/GPL-3.0-app.txt"] = read("LICENSE")
    icon_map = {
        "schema_version": 1, "generator": GENERATOR, "source_pin": PIN,
        "implementation": MODULE + "/src/main/kotlin/com/meshcoreone/android/core/designsystem/MeshIcons.kt",
        "artwork": "Original GPLv3 application geometry; meaning references only, no Apple artwork/font or downloaded icon pack.",
        "icons": [],
    }
    for symbol, (native, meaning, path) in ICON_MEANINGS.items():
        data = read(path).decode("utf8")
        if symbol not in data:
            fail(path, "missing source symbol meaning " + symbol)
        icon_map["icons"].append({
            "source_symbol": symbol, "source_path": path, "source_blob": inputs[path],
            "native_symbol": native, "meaning": meaning, "license": "GPL-3.0-only",
        })
    result["docs/android/generated/icon-map.json"] = json_bytes(icon_map)
    result[SOURCE_MAP] = json_bytes({
        "schema_version": 1, "generator": GENERATOR,
        "reference_commit": PIN, "reference_tree": TREE,
        "primary_owned_inputs": {entry["path"]: entry["blob_sha"] for entry in owned},
        "all_input_blobs": inputs, "resource_dispositions": resource_dispositions,
        "themes": themes, "color_assets": assets, "recovery": recovery,
        "asset_fallback": "exact appearance; same luminosity standard; universal high; universal standard",
        "system_adaptation": "Native neutral/card tiers are runtime tokens, never replacements for named source components.",
        "entitlements": "All ten themes are unlocked. Original product IDs are inert provenance, not permissions.",
    })
    return result


def verify(write=False):
    expected = outputs()
    for path, data in expected.items():
        if not (path.startswith(MODULE + "/") or path == "docs/android/generated/icon-map.json"):
            fail(path, "generator wrote outside its output locations")
        target = host_path(path)
        if write:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        elif not target.is_file() or target.read_bytes().replace(b"\r\n", b"\n") != data.replace(b"\r\n", b"\n"):
            fail(path, "generated output drift or absence; run python tools/android-port/theme_convert.py --write")
    return {"generated_outputs": len(expected), "themes": 10, "color_assets": 44, "primary_inputs": 89, "recovery_locales": 12}


def self_test():
    path = host_path(MODULE + "/src/test/python/test_theme_convert.py")
    if not path.is_file():
        fail("self-test", "missing declared converter suite")
    spec = importlib.util.spec_from_file_location("wp301_converter_tests", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    suite = unittest.defaultTestLoader.loadTestsFromModule(module)
    if suite.countTestCases() == 0:
        fail("self-test", "zero discovered assertions")
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    if not result.wasSuccessful() or result.skipped:
        fail("self-test", "mandatory converter cases failed or skipped")
    return {"discovered": result.testsRun, "passed": result.testsRun, "failed": 0, "errors": 0, "skipped": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.write and args.check:
        parser.error("--write and --check are mutually exclusive")
    try:
        result = {"result": "passed", **verify(write=args.write)}
        if args.self_test:
            result["converter_cases"] = self_test()
        print(json.dumps(result, sort_keys=True))
        return 0
    except (ThemeConversionError, OSError, plistlib.InvalidFileException) as error:
        print("BLOCKED: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
