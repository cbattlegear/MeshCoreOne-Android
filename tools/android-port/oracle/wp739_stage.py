"""AndroidOnly: WP-203 Stage an isolated interop test against the explicit upstream v2 revision."""

import shutil
import subprocess
import sys
from pathlib import Path

SOURCE = "5e980a53e077c45a1270170612fdf5dae97792ff"
ROOT = Path(__file__).resolve().parents[3]
DOCC = '.package(url: "https://github.com/apple/swift-docc-plugin", from: "1.4.0")'
OLD_TARGET = '''      ],
      resources: [
        .process("MECP")
      ]
    )'''
NEW_TARGET = '''      ],
      path: "Interop739"
    )'''


def replace_once(path, old, new):
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise RuntimeError(f"Unexpected upstream package declaration in {path}")
    path.write_text(text.replace(old, new), encoding="utf-8")


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Expected the isolated upstream checkout path")
    upstream = Path(sys.argv[1]).resolve(strict=True)
    if upstream == ROOT or ROOT in upstream.parents:
        raise SystemExit("Upstream must be an isolated checkout outside the Android repository")
    commit = subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip()
    if commit != SOURCE:
        raise SystemExit(f"Unexpected upstream revision: {commit}")
    test_dir = upstream / "MC1Services" / "Interop739"
    test_dir.mkdir()
    for name in ("DeviceDTO+Testing.swift", "NodeStatusSnapshotDTO+Testing.swift", "AppBackupEnvelope+Testing.swift"):
        shutil.copy2(upstream / "MC1Services" / "Tests" / "MC1ServicesTests" / "Helpers" / name, test_dir / name)
    shutil.copy2(ROOT / "docs" / "android" / "evidence" / "WP-203" / "WP739InteropTests.swift", test_dir)
    replace_once(upstream / "MeshCore" / "Package.swift", DOCC, "")
    replace_once(upstream / "MC1Services" / "Package.swift", OLD_TARGET, NEW_TARGET)


if __name__ == "__main__":
    main()
