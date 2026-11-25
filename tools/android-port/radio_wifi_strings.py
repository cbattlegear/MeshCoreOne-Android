#!/usr/bin/env python3
"""Generate Android radio Wi-Fi strings from the explicitly compared upstream revision."""

import argparse
from pathlib import Path
import subprocess

from l10n_convert import android_xml_text, parse_format, parse_strings

ROOT = Path(__file__).resolve().parents[2]
SOURCE = "5e980a53e077c45a1270170612fdf5dae97792ff"
LOCALES = {
    "en": "values", "de": "values-de", "es": "values-es", "fr": "values-fr",
    "it": "values-it", "ko": "values-ko", "nl": "values-nl", "pl": "values-pl",
    "pt": "values-pt", "ru": "values-ru", "uk": "values-uk", "zh-Hans": "values-b+zh+Hans",
}
KEYS = (
    "radioWiFi.footer", "radioWiFi.status", "radioWiFi.status.connected", "radioWiFi.status.notConnected",
    "radioWiFi.ipAddress", "radioWiFi.network", "radioWiFi.network.notSet", "radioWiFi.enabled",
    "radioWiFi.networkName", "radioWiFi.password", "radioWiFi.showPassword", "radioWiFi.hidePassword",
    "radioWiFi.editor.footer", "radioWiFi.editor.emptyName", "radioWiFi.editor.nameLength",
    "radioWiFi.editor.passwordLength", "radioWiFi.editor.unsupportedCharacter", "radioWiFi.clear",
    "radioWiFi.alert.clear.title", "radioWiFi.alert.clear.message", "radioWiFi.alert.clear.wifiMessage",
    "radioWiFi.rebootToApply", "radioWiFi.alert.save.title", "radioWiFi.alert.turnOff.title",
    "radioWiFi.alert.turnOff.confirm", "radioWiFi.alert.wifiTransport.message", "radioWiFi.alert.reboot.title",
    "radioWiFi.alert.reboot.join", "radioWiFi.alert.reboot.restart", "radioWiFi.alert.reboot.disabled",
    "connection.header", "connection.link.wifi", "connection.link.radioWiFi", "connection.link.inUse", "connection.link.switching",
    "connection.link.switchHint", "connection.link.bluetoothInfo", "connection.link.wifiInfo",
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    signatures = {}
    for locale, qualifier in LOCALES.items():
        path = f"MC1/Resources/Localization/{locale}.lproj/Settings.strings"
        data = subprocess.run(
            ["git", "show", f"{SOURCE}:{path}"], cwd=ROOT, check=True, stdout=subprocess.PIPE,
        ).stdout
        values = parse_strings(data, path)
        lines = [
            f"<!-- GeneratedFrom: tools/android-port/radio_wifi_strings.py; inputs: {path}@{SOURCE} -->",
            "<resources>",
        ]
        for key in KEYS:
            value = parse_format(values[key], f"{path}:{key}")
            if locale == "en":
                signatures[key] = value.arguments
            elif value.arguments != signatures[key]:
                raise SystemExit(f"{path}:{key}: format arguments differ from English")
            name = "radio_wifi_" + key.lower().replace(".", "_")
            formatted = "true" if value.arguments else "false"
            body = android_xml_text(value.android, f"{path}:{key}")
            lines.append(f'    <string name="{name}" formatted="{formatted}">{body}</string>')
        content = "\n".join(lines + ["</resources>", ""])
        target = ROOT / "android" / "app" / "src" / "main" / "res" / qualifier / "radio_wifi_strings.xml"
        if args.check:
            if target.read_text(encoding="utf-8") != content:
                raise SystemExit(f"{target}: generated content differs")
        else:
            target.write_text(content, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
