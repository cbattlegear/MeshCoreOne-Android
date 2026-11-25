"""Android-only WP-002 pinned notice/incumbent artwork generation; no reference modifications."""

import argparse
import hashlib
from pathlib import Path
import subprocess
import sys
import zipfile


REFERENCE = "5e980a53e077c45a1270170612fdf5dae97792ff"
REPOSITORY = Path(__file__).resolve().parents[2]
OUTPUTS = {
    "LICENSE": ("android/app/src/main/assets/licenses/GPL-3.0.txt",),
    "MeshCore/LICENSE": (
        "android/core/protocol/LICENSE",
        "android/app/src/main/assets/licenses/MeshCore-MIT.txt",
    ),
    "AppIcon.icon/Assets/app-icon.png": ("android/app/src/main/res/drawable-nodpi/scaffold_icon_background.png",),
    "AppIcon.icon/Assets/background.png": ("android/app/src/main/res/drawable-nodpi/scaffold_icon_foreground.png",),
}
GRADLE_SHA256 = "bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c"
APACHE_SHA256 = "f4cff4b801084da2f1df33afead47f475910b441feea6a5183097e8deb97f0fa"
APACHE_OUTPUTS = (
    "android/gradle/licenses/Apache-2.0.txt",
    "android/app/src/main/assets/licenses/Apache-2.0.txt",
)


def sync(write: bool, gradle_archive: Path | None):
    for source, destinations in OUTPUTS.items():
        content = subprocess.run(
            ["git", "show", f"{REFERENCE}:{source}"],
            cwd=REPOSITORY, capture_output=True, check=True,
        ).stdout
        binary = source.endswith(".png")
        current = (REPOSITORY / source).read_bytes() if binary else (REPOSITORY / source).read_text(encoding="utf-8")
        expected = content if binary else content.decode("utf-8")
        if current != expected:
            raise ValueError(f"Read-only notice drift: {source}")
        for destination in destinations:
            output = REPOSITORY / destination
            if write:
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(content)
            actual = output.read_bytes() if binary and output.is_file() else output.read_text(encoding="utf-8") if output.is_file() else None
            if actual != expected:
                raise ValueError(f"Missing/modified generated notice: {destination}")
            if binary and write:
                legacy = output.parent.parent / "drawable" / output.name
                if legacy.is_file():
                    if legacy.read_bytes() != content:
                        raise ValueError(f"Modified legacy artwork must not be removed: {legacy.name}")
                    legacy.unlink()
    if gradle_archive is not None:
        with gradle_archive.open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != GRADLE_SHA256:
                raise ValueError("Unverified Gradle distribution notice input")
        with zipfile.ZipFile(gradle_archive) as archive:
            apache = archive.read("gradle-9.8.0/LICENSE")
        if hashlib.sha256(apache).hexdigest() != APACHE_SHA256:
            raise ValueError("Unexpected pinned Apache license bytes")
        if write:
            for destination in APACHE_OUTPUTS:
                output = REPOSITORY / destination
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(apache)
    for destination in APACHE_OUTPUTS:
        output = REPOSITORY / destination
        if not output.is_file() or hashlib.sha256(output.read_text(encoding="utf-8").encode()).hexdigest() != APACHE_SHA256:
            raise ValueError(f"Missing/modified pinned Apache notice: {destination}")
    print("Pinned GPLv3/MIT/Apache notices and incumbent artwork match; legal/dependency approval is not implied")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Generate only the seven declared notice/artwork outputs")
    parser.add_argument("--gradle-archive", type=Path, help="Checksum-pinned Gradle 9.8.0 binary distribution notice input")
    arguments = parser.parse_args()
    try:
        sync(arguments.write, arguments.gradle_archive)
    except (OSError, UnicodeError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        sys.exit(2)
