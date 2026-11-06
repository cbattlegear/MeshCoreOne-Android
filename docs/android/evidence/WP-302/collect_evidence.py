"""WP-302 fail-closed original-family/native-state/render evidence; no device or gate authority."""
from __future__ import annotations

import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import uuid
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "tools" / "android-port"))
from controller.ci_evidence import read_xml, suite_counts
from controller.errors import PortError
from controller.model import load_manifest

PNG_HELPERS = ("docs/android/evidence/WP-304/collect_evidence.py", "docs/android/evidence/WP-304/source_inventory.py")
sys.path.insert(0, str((ROOT / PNG_HELPERS[0]).parent))
PNG_SPEC = importlib.util.spec_from_file_location("wp302_shared_png_reader", ROOT / PNG_HELPERS[0])
PNG_READER = importlib.util.module_from_spec(PNG_SPEC)
PNG_SPEC.loader.exec_module(PNG_READER)

PIN = "db14559b39d32322b06477c6ae676112f583db50"
APP = ROOT / "android" / "app"
OUT = APP / "build" / "reports" / "wp302"
NAV = Path("kotlin/com/meshcoreone/android/app/navigation")
TEST_METHOD = re.compile(r"@Test\s+fun\s+(?:`([^`]+)`|(\w+))")
MANDATORY_NATIVE_SUITES = {
    "NavigationActivityTest.kt": "com.meshcoreone.android.app.navigation.NavigationActivityTest",
    "NavigationComposeTest.kt": "com.meshcoreone.android.app.navigation.NavigationComposeTest",
    "NavigationFlowIntegrationTest.kt": "com.meshcoreone.android.app.container.NavigationFlowIntegrationTest",
    "NavigationStateTest.kt": "com.meshcoreone.android.app.navigation.NavigationStateTest",
    "OriginalNavigationTest.kt": "com.meshcoreone.android.app.navigation.cases.OriginalNavigationTest",
}
# The one-to-one native method renames in e9f0febc7e53b09adb00ac56928e7073366023f6.
SOURCE_CASE_METHODS = {
    "NavigationCoordinatorNotificationTests::DM notification tap navigates to chat with contact()": "dmNotificationTapNavigatesToChatWithContact",
    "NavigationCoordinatorNotificationTests::New contact notification with manualAddContacts navigates to discovery()": "newContactNotificationWithManualAddContactsNavigatesToDiscovery",
    "NavigationCoordinatorNotificationTests::New contact notification without manualAddContacts navigates to contact detail()": "newContactNotificationWithoutManualAddContactsNavigatesToContactDetail",
    "NavigationCoordinatorNotificationTests::Channel notification tap navigates to channel()": "channelNotificationTapNavigatesToChannel",
    "NavigationCoordinatorNotificationTests::Reaction notification on DM navigates to chat with scrollToMessageID()": "reactionNotificationOnDmNavigatesToChatWithScrollToMessageId",
    "NavigationCoordinatorNotificationTests::Reaction notification on channel navigates to channel with scrollToMessageID()": "reactionNotificationOnChannelNavigatesToChannelWithScrollToMessageId",
    "NavigationCoordinatorMapTests::navigateToMap sets pendingMapFocus and selects the map tab()": "navigateToMapSetsPendingMapFocusAndSelectsTheMapTab",
    "NavigationCoordinatorMapTests::ChatViewModel.navigateToMap forwards the coordinate to the navigation sink()": "chatConsumerForwardsMapCoordinateToNavigationSink",
    "NavigationCoordinatorMapTests::clearPendingMapFocus resets the pending focus()": "clearPendingMapFocusResetsThePendingFocus",
    "NavigationCoordinatorSettingsTests::navigateToSetting sets selectedSetting and selects the settings tab()": "navigateToSettingSetsSelectedSettingAndSelectsTheSettingsTab",
    "NavigationCoordinatorPendingLinkTests::pendingContactLink starts nil and clears via helper()": "pendingContactLinkStartsNilAndClearsViaHelper",
    "NavigationCoordinatorPendingLinkTests::pendingChannelLink starts nil and clears via helper()": "pendingChannelLinkStartsNilAndClearsViaHelper",
    "NavigationCoordinatorPendingLinkTests::pendingHashtag starts nil and clears via helper()": "pendingHashtagStartsNilAndClearsViaHelper",
    "NavigationCoordinatorPendingLinkTests::clearPendingLinks clears the hoisted Nodes selected contact()": "clearPendingLinksClearsTheHoistedNodesSelectedContact",
    "NavigationCoordinatorPendingLinkTests::clearPendingLinks clears the hoisted Nodes discovery flag()": "clearPendingLinksClearsTheHoistedNodesDiscoveryFlag",
    "NavigationCoordinatorPendingLinkTests::clearPendingLinks clears every staged per-radio field at once()": "clearPendingLinksClearsEveryStagedPerRadioFieldAtOnce",
    "NavigationCoordinatorPendingLinkTests::clearPerRadioSelection clears the hoisted Chats route()": "clearPerRadioSelectionClearsTheHoistedChatsRoute",
    "NavigationCoordinatorPendingLinkTests::clearPerRadioSelection clears a radio-requiring tool()": "clearPerRadioSelectionClearsARadioRequiringTool",
    "NavigationCoordinatorPendingLinkTests::clearPerRadioSelection preserves the offline Line of Sight tool()": "clearPerRadioSelectionPreservesTheOfflineLineOfSightTool",
    "NavigationCoordinatorPendingLinkTests::clearPerRadioSelection clears a per-device settings page()": "clearPerRadioSelectionClearsAPerDeviceSettingsPage",
    "NavigationCoordinatorPendingLinkTests::clearPerRadioSelection preserves a device-independent settings page()": "clearPerRadioSelectionPreservesADeviceIndependentSettingsPage",
    "NavigationCoordinatorPendingLinkTests::language settings page is device-independent()": "languageSettingsPageIsDeviceIndependent",
    "NavigationStateTests::Default navigation state is tab 0 with no pending navigation()": "defaultNavigationStateIsTabZeroWithNoPendingNavigation",
    "NavigationStateTests::navigateToChat sets contact, route, and tab()": "navigateToChatSetsContactRouteAndTab",
    "NavigationStateTests::navigateToChat with scrollToMessageID sets message ID()": "navigateToChatWithScrollToMessageIdSetsMessageId",
    "NavigationStateTests::navigateToChat switches to Chats tab from another tab()": "navigateToChatSwitchesToChatsTabFromAnotherTab",
    "NavigationStateTests::navigateToRoom sets session, route, and tab()": "navigateToRoomSetsSessionRouteAndTab",
    "NavigationStateTests::navigateToChannel sets channel, route, and tab()": "navigateToChannelSetsChannelRouteAndTab",
    "NavigationStateTests::navigateToChannel with scrollToMessageID sets message ID()": "navigateToChannelWithScrollToMessageIdSetsMessageId",
    "NavigationStateTests::navigateToDiscovery sets pending flag and contacts tab()": "navigateToDiscoverySetsPendingFlagAndContactsTab",
    "NavigationStateTests::navigateToDiscovery does not hide tab bar()": "navigateToDiscoveryDoesNotHideTabBar",
    "NavigationStateTests::navigateToContacts switches to contacts tab()": "navigateToContactsSwitchesToContactsTab",
    "NavigationStateTests::navigateToContactDetail sets contact and contacts tab()": "navigateToContactDetailSetsContactAndContactsTab",
    "NavigationStateTests::clearPendingNavigation clears chat contact()": "clearPendingNavigationClearsChatContact",
    "NavigationStateTests::clearPendingRoomNavigation clears room session()": "clearPendingRoomNavigationClearsRoomSession",
    "NavigationStateTests::clearPendingRoomAuthentication clears room auth session()": "clearPendingRoomAuthenticationClearsRoomAuthSession",
    "NavigationStateTests::clearPendingChannelNavigation clears channel()": "clearPendingChannelNavigationClearsChannel",
    "NavigationStateTests::clearPendingDiscoveryNavigation clears discovery flag()": "clearPendingDiscoveryNavigationClearsDiscoveryFlag",
    "NavigationStateTests::clearPendingScrollToMessage clears message ID()": "clearPendingScrollToMessageClearsMessageId",
    "NavigationStateTests::clearPendingContactDetailNavigation clears contact detail()": "clearPendingContactDetailNavigationClearsContactDetail",
    "NavigationStateTests::navigateToChat from contacts tab hides tab bar and switches tab()": "navigateToChatFromContactsTabHidesTabBarAndSwitchesTab",
    "NavigationStateTests::Multiple navigation calls overwrite pending state()": "multipleNavigationCallsOverwritePendingState",
    "NavigationStateTests::Device menu tip donation is pending by default when false()": "deviceMenuTipDonationIsPendingByDefaultWhenFalse",
    "SidebarNavigationLayoutTests::Section sidebar is narrow enough that 11-inch portrait tiles all three columns()": "sectionSidebarIsNarrowEnoughThatElevenInchPortraitTilesAllThreeColumns",
    "SidebarNavigationLayoutTests::iPad mini portrait intentionally collapses rather than tiling()": "iPadMiniPortraitIntentionallyCollapsesRatherThanTiling",
    "SidebarNavigationLayoutTests::Sidebar width is in icon-only range, not a full sidebar()": "sidebarWidthIsInIconOnlyRangeNotAFullSidebar",
    "SidebarNavigationLayoutTests::A sidebar-collapsing tool collapses the sidebar even when the container is wide()": "aSidebarCollapsingToolCollapsesTheSidebarEvenWhenTheContainerIsWide",
    "SidebarNavigationLayoutTests::A wide container tiles the sidebar when no sidebar-collapsing tool is open()": "aWideContainerTilesTheSidebarWhenNoSidebarCollapsingToolIsOpen",
    "SidebarNavigationLayoutTests::A narrow container collapses to the section's hidden shape()": "aNarrowContainerCollapsesToTheSectionsHiddenShape",
    "SidebarNavigationLayoutTests::Line of Sight and Trace Path collapse the sidebar; other tools keep it()": "lineOfSightAndTracePathCollapseSidebarOtherToolsKeepIt",
}


def fail(message):
    raise PortError("WP-302: " + message)


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def method_name(value):
    return re.sub(r"\[(?:31|37)\]$", "", value.removesuffix("()")).removesuffix("()")


def native_png_shape(data):
    try:
        return PNG_READER.png_shape(data)
    except (PNG_READER.EvidenceError, struct.error, zlib.error) as error:
        fail("invalid native PNG: " + str(error))


def inventory():
    manifest = load_manifest(ROOT)
    owned = [entry for entry in manifest.data["inventory"] if entry["primary_owner"] == "WP-302"]
    inputs = {entry["path"]: entry["blob_sha"] for entry in owned}
    retained = json.loads(Path(__file__).with_name("source-inventory.json").read_text(encoding="utf8"))
    if len(inputs) != 10 or inputs != {entry["path"]: entry["blob_sha"] for entry in retained["inputs"]}:
        fail("primary source inventory changed")
    for path, expected in inputs.items():
        for revision in (PIN, "HEAD"):
            if git("rev-parse", revision + ":" + path).decode().strip() != expected:
                fail("source drift at " + revision + ":" + path)
    catalog = json.loads((ROOT / "docs/android/test-cases.json").read_text(encoding="utf8"))
    originals = {}
    for entry in catalog["entries"]:
        if entry["path"] in inputs and entry["path"].endswith("Tests.swift"):
            if entry["blob_sha"] != inputs[entry["path"]] or not entry["has_assertions"]:
                fail("invalid original assertion inventory")
            for case in entry["cases"]:
                if case["id"] in originals:
                    fail("duplicate original family")
                originals[case["id"]] = {"source_path": entry["path"], **case}
    if len(originals) != 50:
        fail("all fifty original families are mandatory")
    if set(SOURCE_CASE_METHODS) != set(originals) or len(set(SOURCE_CASE_METHODS.values())) != len(originals):
        fail("original family method mapping is missing, extra or non-bijective")
    shared = APP / "src/androidTest" / NAV / "cases/OriginalNavigationCases.kt"
    names = [a or b for a, b in TEST_METHOD.findall(shared.read_text(encoding="utf8"))]
    mapped = {}
    for case in originals:
        original_name = case.split("::", 1)[1].removesuffix("()")
        native = SOURCE_CASE_METHODS[case]
        if names.count(native) != 1:
            fail("missing/duplicate original assertion body: " + case)
        mapped[case] = {
            "class": "com.meshcoreone.android.app.navigation.cases.OriginalNavigationTest", "method": native,
            "classification": "extracted-map-consumer-equivalent-pending-review" if original_name.startswith("ChatViewModel.")
                else "native-layout-adaptation" if case.startswith("SidebarNavigationLayoutTests::") else "ported",
        }
    if set(names) != {value["method"] for value in mapped.values()}:
        fail("original bodies do not match the full catalog")
    expected = {(value["class"], value["method"], sdk) for value in mapped.values() for sdk in ("31", "37")}
    seen_suites = set()
    for path in sorted((APP / "src/test" / NAV).glob("*Test.kt")):
        content = path.read_text(encoding="utf8")
        references = re.findall(r"^// PortedFrom: ([^@\r\n]+)@", content, re.M)
        if not (path.name in MANDATORY_NATIVE_SUITES
                or re.search(r"^// AndroidOnly: WP-302\b", content, re.M)
                or set(references) & inputs.keys()):
            continue
        cls = re.search(r"^class (\w+)", content, re.M)
        package = re.search(r"^package ([\w.]+)", content, re.M)
        if cls is None or package is None:
            fail("malformed native class: " + str(path))
        class_name = package[1] + "." + cls[1]
        if path.name in MANDATORY_NATIVE_SUITES:
            if class_name != MANDATORY_NATIVE_SUITES[path.name]:
                fail("mandatory native suite identity changed: " + path.name)
            seen_suites.add(path.name)
        if "emitNavigationExecutionBinding(" not in content:
            fail("missing native execution binding: " + path.name)
        config = re.search(r"@Config\s*\(\s*sdk\s*=\s*\[([^\]]+)\]", content)
        sdks = tuple(item.strip() for item in config[1].split(",")) if config else ("jvm",)
        if sdks not in (("31", "37"), ("jvm",)):
            fail("unsupported native SDK inventory: " + path.name)
        methods = TEST_METHOD.findall(content)
        if not methods and path.name != "OriginalNavigationTest.kt":
            fail("missing native assertions: " + path.name)
        for a, b in methods:
            for sdk in sdks:
                identity = (class_name, a or b, sdk)
                if identity in expected:
                    fail("duplicate declared native assertion")
                expected.add(identity)
    if seen_suites != MANDATORY_NATIVE_SUITES.keys():
        fail("missing mandatory native suites: " + repr(sorted(MANDATORY_NATIVE_SUITES.keys() - seen_suites)))
    if len(expected) <= 100:
        fail("missing native adaptation/assertion suites")
    return manifest.sha256, inputs, originals, mapped, expected


def current_inputs():
    paths = set(git("ls-files", "-z", "--", "android").decode().split("\0")) - {""}
    paths = {path for path in paths if "/src/" in path or path.endswith((".gradle.kts", ".toml", ".lockfile"))
             or path.endswith("verification-metadata.xml")}
    paths |= {"docs/android/evidence/WP-302/collect_evidence.py", "docs/android/evidence/WP-302/source-inventory.json"}
    paths.update(PNG_HELPERS)
    result = {}
    for relative in sorted(paths):
        raw = (ROOT / relative).read_bytes()
        committed = git("show", "HEAD:" + relative)
        if raw != committed:
            fail("compiled checkout bytes differ from candidate Git input: " + relative)
        result[relative] = {"sha256": digest(raw), "size": len(raw)}
    return result


def bind_inputs(path):
    manifest, source, _, _, _ = inventory()
    inputs = current_inputs()
    value = {
        "schema_version": 1, "wp": "WP-302", "nonce": str(uuid.uuid4()),
        "head": git("rev-parse", "HEAD").decode().strip(), "tree": git("rev-parse", "HEAD^{tree}").decode().strip(),
        "source_sha": PIN, "manifest_sha256": manifest, "source_inputs": source,
        "inputs": inputs, "inputs_sha256": digest(canonical(inputs)),
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf8")
    path.with_suffix(".properties").write_text(
        "".join(key + "=" + value[key] + "\n" for key in ("nonce", "head", "tree", "inputs_sha256")), encoding="ascii",
    )
    return value


def verify_binding(binding):
    if binding["schema_version"] != 1 or binding["wp"] != "WP-302" or binding["source_sha"] != PIN:
        fail("wrong pre-test binding")
    if binding["head"] != git("rev-parse", "HEAD").decode().strip() or binding["tree"] != git("rev-parse", "HEAD^{tree}").decode().strip():
        fail("stale candidate binding")
    inputs = current_inputs()
    if binding["inputs"] != inputs or binding["inputs_sha256"] != digest(canonical(inputs)):
        fail("stale/altered compiled-input binding")


def report_identity(class_name, raw_name, expected):
    name = method_name(raw_name)
    sdks = {sdk for cls, method, sdk in expected if cls == class_name and method == name}
    suffix = re.search(r"\[(31|37)\]$", raw_name.removesuffix("()"))
    if suffix:
        sdk = suffix[1]
    elif sdks == {"31", "37"}:
        sdk = "37"
    elif sdks == {"jvm"}:
        sdk = "jvm"
    else:
        fail("unknown or ambiguous native test identity: " + repr((class_name, raw_name)))
    identity = (class_name, name, sdk)
    if identity not in expected:
        fail("unknown native test identity: " + repr(identity))
    return identity


def native_reports(directory, binding, expected):
    counts = suite_counts(directory, 1)
    reports, nodes, executed, renders = [], set(), set(), {}
    classes = {cls for cls, _, _ in expected}
    for path in sorted(directory.glob("TEST-*.xml")):
        root = read_xml(path)
        reports.append({"name": path.name, "bytes": path.stat().st_size, "sha256": digest(path.read_bytes())})
        for case in root.iter("testcase"):
            cls, name = case.get("classname", ""), case.get("name", "")
            if cls in classes:
                identity = report_identity(cls, name, expected)
                if identity in nodes:
                    fail("duplicate native XML assertion: " + repr(identity))
                nodes.add(identity)
        for output in root.iter("system-out"):
            for line in (output.text or "").splitlines():
                if line.startswith("WP302_EXECUTION|"):
                    parts = line.split("|")
                    if len(parts) != 8 or parts[1:5] != [binding[key] for key in ("nonce", "head", "tree", "inputs_sha256")]:
                        fail("malformed/stale per-test execution binding")
                    identity = report_identity(parts[5], parts[6], expected)
                    if identity[2] != parts[7]:
                        fail("native SDK label and execution binding disagree")
                    if identity not in expected or identity in executed:
                        fail("unknown/duplicate actual native assertion: " + repr(identity))
                    executed.add(identity)
                elif line.startswith("WP302_RENDER|"):
                    parts = line.split("|")
                    if len(parts) != 6 or parts[1] in renders or not re.fullmatch(r"[a-z0-9-]+", parts[1]):
                        fail("malformed/duplicate render")
                    name, width, height, checksum = parts[1], int(parts[2]), int(parts[3]), parts[4]
                    data = base64.b64decode(parts[5], validate=True)
                    if not 0 < len(data) < 16 * 1024 * 1024 or not 0 < width <= 4096 or not 0 < height <= 4096:
                        fail("invalid native image bounds")
                    if digest(data) != checksum or native_png_shape(data) != (width, height):
                        fail("native image bytes/dimensions/hash disagree")
                    renders[name] = {"width": width, "height": height, "sha256": checksum, "bytes": len(data), "data": data}
    if executed != expected:
        fail("missing current assertions: " + repr(sorted(expected - executed)))
    if nodes != expected:
        fail("XML testcase nodes and bound executions disagree")
    states = {"width-" + str(width) for width in (360, 599, 600, 744, 779, 780, 834, 1080)} | {
        "tabs-selected-settings", "resize-expanded-draft", "resize-compact-draft", "collapse-tracepath",
        "collapse-lineofsight", "native-back-root", "font200-rtl-cjk", "font200-rtl-rail",
    }
    states |= {
        "theme-" + theme + "-" + scheme + "-" + contrast
        for theme in ("default", "ember", "fern", "marine", "olive", "lavender", "sakura", "solarized", "nord", "catppuccin")
        for scheme in (("dark",) if theme == "ember" else ("light", "dark")) for contrast in ("standard", "hc")
    }
    required = {"sdk-" + sdk + "-" + state for sdk in ("31", "37") for state in states}
    if renders.keys() != required:
        fail("missing/unexpected SDK31/37 native screen states")
    return counts, reports, renders


def collect(junit, binding_path):
    raw = OUT / "raw-junit"
    raw.mkdir(parents=True, exist_ok=True)
    for path in sorted(junit.glob("TEST-*.xml")):
        (raw / path.name).write_bytes(path.read_bytes())
    manifest, source, originals, mapped, expected = inventory()
    binding = json.loads(binding_path.read_text(encoding="utf8"))
    verify_binding(binding)
    if binding["manifest_sha256"] != manifest or binding["source_inputs"] != source:
        fail("source/manifest binding changed")
    counts, reports, renders = native_reports(junit, binding, expected)
    OUT.mkdir(parents=True, exist_ok=True)
    raw = OUT / "raw-junit"
    raw.mkdir(exist_ok=True)
    for report in reports:
        data = (junit / report["name"]).read_bytes()
        if len(data) != report["bytes"] or digest(data) != report["sha256"]:
            fail("raw report changed during retention")
        (raw / report["name"]).write_bytes(data)
    for name, value in renders.items():
        target = OUT / "screens" / (name + ".png")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(value["data"])
    value = {
        "schema_version": 1, "wp": "WP-302", "binding": binding, "original_families": 50,
        "actual_native_nodes": len(expected), "counts": counts, "raw_junit": reports,
        "families": [{"id": case, **originals[case], **mapped[case], "actual_sdks": [31, 37]} for case in originals],
        "renders": {name: {k: v for k, v in item.items() if k != "data"} for name, item in renders.items()},
        "limits": "Local native Robolectric/SQLite/Compose only; not instrumentation, OS IME/predictive gestures, iOS, physical/OEM, license/signing/release or formal parity approval.",
    }
    (OUT / "source-case-execution.json").write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf8")
    return {"result": "passed-local-native-only", "original_families": 50, "actual_native_nodes": len(expected), "renders": len(renders)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind-inputs", action="store_true")
    parser.add_argument("--inventory", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--junit", type=Path, default=APP / "build/test-results/testDebugUnitTest")
    parser.add_argument("--binding", type=Path, default=OUT / "input-binding.json")
    args = parser.parse_args()
    try:
        if args.self_test:
            spec = importlib.util.spec_from_file_location("wp302_reader_tests", Path(__file__).with_name("test_evidence_reader.py"))
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            suite = unittest.defaultTestLoader.loadTestsFromModule(module)
            if suite.countTestCases() == 0:
                fail("zero reader regressions")
            result = unittest.TextTestRunner(verbosity=1).run(suite)
            if not result.wasSuccessful() or result.skipped:
                fail("failed/skipped reader regressions")
        if args.bind_inputs:
            value = bind_inputs(args.binding)
            result = {"result": "bound-not-executed", "head": value["head"], "nonce": value["nonce"]}
        elif args.inventory:
            _, _, _, _, expected = inventory()
            result = {"result": "static-only-not-execution", "original_families": 50, "declared_native_nodes": len(expected)}
        else:
            result = collect(args.junit, args.binding)
        print(json.dumps(result, sort_keys=True))
        return 0
    except (PortError, OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print("BLOCKED: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
