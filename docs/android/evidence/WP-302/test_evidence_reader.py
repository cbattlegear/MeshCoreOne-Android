"""WP-302 reader regressions only: synthetic XML never supplies native acceptance."""
import importlib.util
import base64
import struct
import zlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("wp302_evidence", Path(__file__).with_name("collect_evidence.py"))
EVIDENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVIDENCE)
CLASS = "com.meshcoreone.android.app.navigation.NavigationStateTest"
IDENTITY = (CLASS, "realCase", "jvm")
BINDING = {"nonce": "nonce", "head": "head", "tree": "tree", "inputs_sha256": "digest"}
MARKER = "WP302_EXECUTION|nonce|head|tree|digest|" + CLASS + "|realCase|jvm"
FLOW_CLASS = "com.meshcoreone.android.app.container.NavigationFlowIntegrationTest"
FLOW_EXPECTED = {(FLOW_CLASS, "mounted", sdk) for sdk in ("31", "37")}


class ReaderTest(unittest.TestCase):
    @staticmethod
    def png(width=1, height=1, pixels=b"\0\xff\xff\xff\xff"):
        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
        header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
        return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b"")

    def reports(self, output="", nodes=None, tests=1, skipped=0):
        nodes = nodes if nodes is not None else '<testcase classname="' + CLASS + '" name="realCase"/>'
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        (root / "TEST-navigation.xml").write_text(
            f'<testsuite name="navigation" tests="{tests}" failures="0" errors="0" skipped="{skipped}">'
            + nodes + "<system-out>" + output + "</system-out></testsuite>", encoding="utf8",
        )
        return root

    def full_render_markers(self):
        data = self.png()
        checksum = EVIDENCE.digest(data)
        encoded = base64.b64encode(data).decode()
        states = {f"width-{width}" for width in (360, 599, 600, 744, 779, 780, 834, 1080)} | {
            "tabs-selected-settings", "resize-expanded-draft", "resize-compact-draft", "collapse-tracepath",
            "collapse-lineofsight", "native-back-root", "font200-rtl-cjk", "font200-rtl-rail",
        }
        states |= {
            f"theme-{theme}-{scheme}-{contrast}"
            for theme in ("default", "ember", "fern", "marine", "olive", "lavender", "sakura", "solarized", "nord", "catppuccin")
            for scheme in (("dark",) if theme == "ember" else ("light", "dark"))
            for contrast in ("standard", "hc")
        }
        names = {f"sdk-{sdk}-{state}" for sdk in ("31", "37") for state in states}
        self.assertEqual(108, len(names))
        return "\n".join(f"WP302_RENDER|{name}|1|1|{checksum}|{encoded}" for name in sorted(names))

    @staticmethod
    def flow_marker(name, sdk):
        return f"WP302_EXECUTION|nonce|head|tree|digest|{FLOW_CLASS}|{name}|{sdk}"

    @staticmethod
    def flow_nodes():
        return (
            f'<testcase classname="{FLOW_CLASS}" name="mounted[31]"/>'
            f'<testcase classname="{FLOW_CLASS}" name="mounted"/>'
        )

    def rejects(self, directory):
        with self.assertRaises(EVIDENCE.PortError):
            EVIDENCE.native_reports(directory, BINDING, {IDENTITY})

    def test_actual_static_inventory_maps_all_fifty_but_is_not_execution(self):
        _, source, originals, mapped, expected = EVIDENCE.inventory()
        self.assertEqual(10, len(source)); self.assertEqual(50, len(originals)); self.assertEqual(50, len(mapped))
        self.assertEqual(set(EVIDENCE.SOURCE_CASE_METHODS), set(mapped))
        for case, method in EVIDENCE.SOURCE_CASE_METHODS.items():
            self.assertEqual(method, mapped[case]["method"])
            for sdk in ("31", "37"):
                self.assertIn((mapped[case]["class"], method, sdk), expected)
        self.assertEqual(411, len(expected))
        flow = {node for node in expected if node[0] == FLOW_CLASS}
        self.assertEqual(198, len(flow))
        self.assertEqual({"31", "37"}, {node[2] for node in flow})
        for unrelated in ("MessageBubbleConfigurationTest", "ChannelAvatarConsumerTest", "ComposerPromptComposeTest"):
            self.assertFalse(any(node[0].endswith("." + unrelated) for node in expected))

    def test_inventory_rejects_missing_mandatory_suite_or_execution_hook(self):
        navigation = EVIDENCE.APP / "src/test" / EVIDENCE.NAV
        original_glob = Path.glob

        def missing(path, pattern):
            entries = original_glob(path, pattern)
            if path == navigation and pattern == "*Test.kt":
                return (entry for entry in entries if entry.name != "NavigationFlowIntegrationTest.kt")
            return entries

        with patch.object(Path, "glob", new=missing):
            with self.assertRaisesRegex(EVIDENCE.PortError, "missing mandatory native suites"):
                EVIDENCE.inventory()

        original_read = Path.read_text

        def unbound(path, *args, **kwargs):
            content = original_read(path, *args, **kwargs)
            if path == navigation / "NavigationFlowIntegrationTest.kt":
                return content.replace("emitNavigationExecutionBinding(", "missingExecutionBinding(")
            return content

        with patch.object(Path, "read_text", new=unbound):
            with self.assertRaisesRegex(EVIDENCE.PortError, "missing native execution binding"):
                EVIDENCE.inventory()

    def test_original_family_mapping_rejects_missing_extra_or_duplicate_entries(self):
        complete = EVIDENCE.SOURCE_CASE_METHODS
        first, second = list(complete)[:2]
        missing = dict(complete)
        del missing[first]
        extra = dict(complete, **{"ExtraSuite::untracked case()": "untrackedCase"})
        duplicate = dict(complete)
        duplicate[second] = duplicate[first]
        for label, mapping in (("missing", missing), ("extra", extra), ("duplicate", duplicate)):
            with self.subTest(label=label), patch.object(EVIDENCE, "SOURCE_CASE_METHODS", mapping):
                with self.assertRaisesRegex(EVIDENCE.PortError, "missing, extra or non-bijective"):
                    EVIDENCE.inventory()

    def test_original_family_mapping_rejects_missing_native_assertion(self):
        mapping = dict(EVIDENCE.SOURCE_CASE_METHODS)
        mapping[next(iter(mapping))] = "missingNativeAssertion"
        with patch.object(EVIDENCE, "SOURCE_CASE_METHODS", mapping):
            with self.assertRaisesRegex(EVIDENCE.PortError, "missing/duplicate original assertion body"):
                EVIDENCE.inventory()

    def test_original_family_mapping_rejects_duplicate_native_assertion(self):
        shared = EVIDENCE.APP / "src/androidTest" / EVIDENCE.NAV / "cases/OriginalNavigationCases.kt"
        read_text = Path.read_text
        method = next(iter(EVIDENCE.SOURCE_CASE_METHODS.values()))

        def duplicate(path, *args, **kwargs):
            content = read_text(path, *args, **kwargs)
            return content + f"\n@Test fun {method}() {{}}\n" if path == shared else content

        with patch.object(Path, "read_text", new=duplicate):
            with self.assertRaisesRegex(EVIDENCE.PortError, "missing/duplicate original assertion body"):
                EVIDENCE.inventory()

    def test_missing_directory_fails(self):
        self.rejects(Path("nonexistent-wp302-test-reports"))

    def test_zero_tests_fails(self):
        self.rejects(self.reports(nodes="", tests=0))

    def test_malformed_xml_fails(self):
        directory = self.reports()
        (directory / "TEST-navigation.xml").write_text("<testsuite", encoding="utf8")
        self.rejects(directory)

    def test_declared_count_disagrees_with_actual_nodes(self):
        self.rejects(self.reports(tests=2))

    def test_skipped_mandatory_node_fails(self):
        self.rejects(self.reports(nodes=f'<testcase classname="{CLASS}" name="realCase"><skipped/></testcase>', skipped=1))

    def test_missing_execution_binding_fails(self):
        self.rejects(self.reports())

    def test_stale_nonce_fails(self):
        self.rejects(self.reports(MARKER.replace("|nonce|", "|stale|")))

    def test_duplicate_execution_fails(self):
        self.rejects(self.reports(MARKER + "\n" + MARKER))

    def test_missing_native_screens_fails_even_with_valid_bound_node(self):
        self.rejects(self.reports(MARKER))

    def test_mounted_suite_in_container_package_binds_both_actual_sdk_nodes(self):
        output = "\n".join((
            self.flow_marker("mounted[31]", "31"),
            self.flow_marker("mounted", "37"),
            self.full_render_markers(),
        ))
        counts, _, renders = EVIDENCE.native_reports(
            self.reports(output, self.flow_nodes(), tests=2), BINDING, FLOW_EXPECTED,
        )
        self.assertEqual(2, counts["passed"])
        self.assertEqual(108, len(renders))

    def test_unrelated_junit_cases_are_checked_without_receiving_wp302_credit(self):
        nodes = self.flow_nodes() + '<testcase classname="other.FeatureTest" name="unrelated"/>'
        output = "\n".join((
            self.flow_marker("mounted[31]", "31"),
            self.flow_marker("mounted", "37"),
            self.full_render_markers(),
        ))
        counts, _, _ = EVIDENCE.native_reports(self.reports(output, nodes, tests=3), BINDING, FLOW_EXPECTED)
        self.assertEqual(3, counts["passed"])
        failed = nodes.replace('name="unrelated"/>', 'name="unrelated"><failure/></testcase>')
        with self.assertRaises(EVIDENCE.PortError):
            EVIDENCE.native_reports(self.reports(output, failed, tests=3), BINDING, FLOW_EXPECTED)

    def test_two_sdk31_xml_nodes_cannot_stand_in_for_sdk37(self):
        nodes = (
            f'<testcase classname="{FLOW_CLASS}" name="mounted[31]"/>'
            f'<testcase classname="{FLOW_CLASS}" name="mounted[31]()"/>'
        )
        output = "\n".join((
            self.flow_marker("mounted[31]", "31"),
            self.flow_marker("mounted", "37"),
            self.full_render_markers(),
        ))
        with self.assertRaisesRegex(EVIDENCE.PortError, "duplicate native XML assertion"):
            EVIDENCE.native_reports(self.reports(output, nodes, tests=2), BINDING, FLOW_EXPECTED)

    def test_execution_marker_must_match_its_sdk_label(self):
        output = self.flow_marker("mounted[31]", "37")
        with self.assertRaisesRegex(EVIDENCE.PortError, "SDK label and execution binding disagree"):
            EVIDENCE.native_reports(self.reports(output, self.flow_nodes(), tests=2), BINDING, FLOW_EXPECTED)

    def test_mounted_sdk_node_without_its_binding_is_rejected(self):
        with self.assertRaisesRegex(EVIDENCE.PortError, "missing current assertions"):
            EVIDENCE.native_reports(
                self.reports(self.flow_marker("mounted[31]", "31"), self.flow_nodes(), tests=2),
                BINDING, FLOW_EXPECTED,
            )

    def test_wrong_or_unknown_sdk_fails(self):
        self.rejects(self.reports(MARKER.removesuffix("jvm") + "30"))

    def test_malformed_render_bytes_fail(self):
        self.rejects(self.reports(MARKER + "\nWP302_RENDER|sdk-31-bad|10|10|bad|AAAA"))

    def test_shared_png_decoder_accepts_valid_pixels_without_granting_native_execution(self):
        self.assertEqual((1, 1), EVIDENCE.native_png_shape(self.png()))

    def test_png_header_alone_crc_truncation_and_wrong_pixels_fail_at_the_decoder(self):
        png = self.png()
        changed_crc = bytearray(png)
        changed_crc[29] ^= 1
        malformed = (
            b"\x89PNG\r\n\x1a\n" + b"\0" * 8 + struct.pack(">II", 1, 1),
            png[:-1], bytes(changed_crc), self.png(2, 2),
            self.png(pixels=b"\x05\xff\xff\xff\xff"), self.png(pixels=b"\0" * 50),
        )
        for data in malformed:
            with self.subTest(bytes=len(data)):
                with self.assertRaisesRegex(EVIDENCE.PortError, "invalid native PNG"):
                    EVIDENCE.native_png_shape(data)

    def test_malformed_png_with_a_matching_hash_fails_before_screen_inventory(self):
        data = self.png()[:-1]
        render = "\nWP302_RENDER|sdk-31-bad|1|1|" + EVIDENCE.digest(data) + "|" + base64.b64encode(data).decode()
        with self.assertRaisesRegex(EVIDENCE.PortError, "invalid native PNG"):
            EVIDENCE.native_reports(self.reports(MARKER + render), BINDING, {IDENTITY})

    def test_valid_pixels_do_not_replace_the_missing_native_screen_matrix(self):
        data = self.png()
        render = "\nWP302_RENDER|sdk-31-width-360|1|1|" + EVIDENCE.digest(data) + "|" + base64.b64encode(data).decode()
        with self.assertRaisesRegex(EVIDENCE.PortError, "missing/unexpected.*screen states"):
            EVIDENCE.native_reports(self.reports(MARKER + render), BINDING, {IDENTITY})

    def test_missing_binding_fields_are_not_success_shaped(self):
        with self.assertRaises(KeyError):
            EVIDENCE.verify_binding({})

    def test_changed_compiled_input_binding_fails(self):
        value = dict(BINDING, schema_version=1, wp="WP-302", source_sha=EVIDENCE.PIN, inputs={})
        with patch.object(EVIDENCE, "git", side_effect=[b"head\n", b"tree\n"]), \
                patch.object(EVIDENCE, "current_inputs", return_value={"changed.kt": {"size": 1, "sha256": "changed"}}):
            with self.assertRaises(EVIDENCE.PortError):
                EVIDENCE.verify_binding(value)

    def test_method_name_normalization_preserves_original_family_identity(self):
        for name in ("case", "case()", "case[31]", "case()[31]", "case[31]()"):
            self.assertEqual("case", EVIDENCE.method_name(name))


if __name__ == "__main__":
    unittest.main()
