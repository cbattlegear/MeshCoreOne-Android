"""AndroidOnly: WP-004 Read-only Git/blob provenance shared by the independent tools."""

import hashlib
import json
import subprocess
from pathlib import Path, PurePosixPath

from portlib.errors import PortError
from portlib.model import load_manifest
from portlib.schema import load_json

SOURCE_SHA = "db14559b39d32322b06477c6ae676112f583db50"
REPO = Path(__file__).resolve().parents[3]


class OracleError(PortError):
    """Missing, ambiguous or changed reference/evidence is a blocking error."""


def git(repo: Path, *arguments: str, data: bytes | None = None) -> bytes:
    result = subprocess.run(
        ["git", "--no-pager", "-C", str(repo), *arguments],
        input=data, capture_output=True, check=False, timeout=120,
    )
    if result.returncode:
        raise OracleError("Read-only Git operation failed: " + result.stderr.decode("utf-8", errors="replace").strip())
    return result.stdout


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git_blob_sha1(data: bytes) -> str:
    return hashlib.sha1(("blob %d\0" % len(data)).encode("ascii") + data).hexdigest()


def json_bytes(value) -> bytes:
    return (json.dumps(value, indent=2, ensure_ascii=True) + "\n").encode("utf-8")


def exact_fields(value, names, label):
    if not isinstance(value, dict) or set(value) != set(names):
        raise OracleError(f"Malformed {label}: expected fields {sorted(names)}")


def repo_path(repo: Path, path: str) -> Path:
    identifier = PurePosixPath(path)
    if not path or "\\" in path or identifier.is_absolute() or ".." in identifier.parts:
        raise OracleError(f"Unsafe Git source identifier: {path!r}")
    return repo.joinpath(*identifier.parts)


def write_or_check(path: Path, data: bytes, *, check: bool):
    if check:
        current = path.read_bytes() if path.is_file() else None
        if path.suffix in (".json", ".tsv", ".swift", ".txt", ".md") and current is not None:
            current = current.replace(b"\r\n", b"\n")
        if current != data:
            raise OracleError(f"Missing or stale generated output: {path}. Review drift before --regenerate.")
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)


class FrozenReference:
    def __init__(self, repo: Path = REPO, source_sha: str = SOURCE_SHA):
        if source_sha != SOURCE_SHA:
            raise OracleError("Reference advancement is forbidden; bump the pin deliberately (docs/android/README.md, Maintenance)")
        self.repo = repo.resolve()
        self.source_sha = source_sha
        manifest = load_manifest(self.repo)
        self.manifest = manifest.data
        self.manifest_sha256 = manifest.sha256
        if self.manifest["reference"]["commit"] != SOURCE_SHA:
            raise OracleError("Manifest/reference pin drift")
        amendments = load_json(self.repo / "docs" / "android" / "reference-amendments.json")
        if amendments["reference_sha"] != SOURCE_SHA:
            raise OracleError("Manifest/reference pin drift")
        self.amendments = {entry["path"]: entry["approved_blob_sha"] for entry in amendments["entries"]}
        self.inventory = {}
        for entry in self.manifest["inventory"]:
            path = entry["path"]
            repo_path(self.repo, path)
            if path in self.inventory:
                raise OracleError(f"Duplicate source inventory path: {path}")
            self.inventory[path] = entry
        tree = git(self.repo, "rev-parse", SOURCE_SHA + "^{tree}").decode("ascii").strip()
        if tree != self.manifest["reference"]["tree_sha"]:
            raise OracleError("Pinned source tree drift")
        self.blobs = {}
        for row in git(self.repo, "ls-tree", "-r", "-z", SOURCE_SHA).split(b"\0"):
            if not row:
                continue
            metadata, raw_path = row.split(b"\t", 1)
            _, kind, blob = metadata.split()
            if kind == b"blob":
                self.blobs[raw_path.decode("utf-8")] = blob.decode("ascii")
        self._cache: dict[str, str] = {}

    def read_many(self, paths) -> dict[str, str]:
        paths = sorted(set(paths))
        missing = [path for path in paths if path not in self._cache]
        for path in paths:
            entry = self.inventory.get(path)
            if entry is None or self.blobs.get(path) != entry["blob_sha"]:
                raise OracleError(f"Unknown or stale source/blob mapping: {path}")
        if missing:
            requests = "".join(self.inventory[path]["blob_sha"] + "\n" for path in missing).encode("ascii")
            output = git(self.repo, "cat-file", "--batch", data=requests)
            offset = 0
            for path in missing:
                end = output.find(b"\n", offset)
                header = output[offset:end].split()
                if end < offset or len(header) != 3 or header[1] != b"blob":
                    raise OracleError(f"Missing pinned Git blob: {path}")
                blob, _, size = header
                if blob.decode("ascii") != self.inventory[path]["blob_sha"]:
                    raise OracleError(f"Git blob response mismatch: {path}")
                offset = end + 1
                content = output[offset:offset + int(size)]
                offset += int(size)
                if len(content) != int(size) or output[offset:offset + 1] != b"\n":
                    raise OracleError(f"Truncated Git blob: {path}")
                offset += 1
                try:
                    self._cache[path] = content.decode("utf-8")
                except UnicodeDecodeError as error:
                    raise OracleError(f"Non-UTF-8 reference source: {path}") from error
            if offset != len(output):
                raise OracleError("Unexpected trailing Git blob response")
        for path in paths:
            local = repo_path(self.repo, path)
            if not local.is_file() or local.is_symlink() or not local.resolve().is_relative_to(self.repo):
                raise OracleError(f"Missing or redirected read-only source: {path}")
            content = local.read_bytes().replace(b"\r\n", b"\n")
            if content != self._cache[path].encode("utf-8"):
                approved = self.amendments.get(path)
                if approved is None or git_blob_sha1(content) != approved:
                    raise OracleError(f"Read-only source drift: {path}@{SOURCE_SHA}")
        return {path: self._cache[path] for path in paths}

    def provenance(self, path: str, text: str) -> dict:
        return {
            "path": path,
            "source_sha": SOURCE_SHA,
            "blob_sha": self.inventory[path]["blob_sha"],
            "utf8_bytes": len(text.encode("utf-8")),
            "sha256": sha256(text.encode("utf-8")),
        }
