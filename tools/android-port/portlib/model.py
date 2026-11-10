import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

from .errors import PortError
from .paths import git_path
from .schema import check_schema, digest, load_json

REFERENCE_SHA = "db14559b39d32322b06477c6ae676112f583db50"
REFERENCE_ROOTS = ("MC1/", "MC1Tests/", "MC1Widgets/", "MC1Services/", "MeshCore/", "Shared/", "AppIcon.icon/")
SHA = re.compile(r"^[0-9a-f]{40}$")


def git(repo: Path, *arguments: str) -> bytes:
    result = subprocess.run(
        ["git", "-C", str(repo), *arguments], capture_output=True, check=False
    )
    if result.returncode:
        detail = result.stderr.decode("utf-8", "replace").strip().splitlines()
        raise PortError(f"Git command failed: {' '.join(arguments[:2])}: {detail[0] if detail else 'no output'}")
    return result.stdout


def tree(repo: Path, revision: str) -> dict[str, str]:
    if SHA.fullmatch(revision) is None:
        raise PortError("A full immutable commit SHA is required")
    result = {}
    for row in git(repo, "ls-tree", "-rz", "--full-tree", revision).split(b"\0"):
        if not row:
            continue
        metadata, raw_path = row.split(b"\t", 1)
        mode, kind, blob = metadata.decode().split()
        path = raw_path.decode("utf-8")
        git_path(path)
        if kind != "blob" or mode not in ("100644", "100755"):
            raise PortError(f"Unsupported reference entry: {path}")
        result[path] = blob
    if not result:
        raise PortError("Empty reference tree")
    return result


def validate_dag(work_packages: list[dict]):
    lookup = {}
    for wp in work_packages:
        if wp["id"] in lookup:
            raise PortError(f"Duplicate work package: {wp['id']}")
        lookup[wp["id"]] = wp
    visiting, visited = set(), set()

    def visit(wp_id):
        if wp_id not in lookup:
            raise PortError(f"Unknown dependency: {wp_id}")
        if wp_id in visiting:
            raise PortError(f"Dependency cycle at {wp_id}")
        if wp_id in visited:
            return
        visiting.add(wp_id)
        for dependency in lookup[wp_id]["depends_on"]:
            visit(dependency)
        visiting.remove(wp_id)
        visited.add(wp_id)

    for wp_id in lookup:
        visit(wp_id)


@dataclass(frozen=True)
class Manifest:
    data: dict
    exclusions: dict
    repo: Path

    @property
    def sha256(self) -> str:
        return digest(self.data)

    @property
    def work_packages(self) -> dict[str, dict]:
        return {wp["id"]: wp for wp in self.data["work_packages"]}

    def wp(self, wp_id: str) -> dict:
        if wp_id not in self.work_packages:
            raise PortError(f"Unknown work package: {wp_id}")
        return self.work_packages[wp_id]

    def inputs(self, wp_id: str, cross_references: bool = True) -> list[dict]:
        self.wp(wp_id)
        return [
            entry for entry in self.data["inventory"]
            if entry["primary_owner"] == wp_id
            or (cross_references and wp_id in entry["cross_references"])
        ]


def validate_manifest(
    data: dict, exclusions: dict, repo: Path, verify_checkout=True, amendments: dict | None = None
) -> Manifest:
    directory = repo / "docs" / "android"
    if amendments is None:
        amendments = {"schema_version": 1, "reference_sha": REFERENCE_SHA, "entries": []}
    check_schema(data, load_json(directory / "port-manifest.schema.json"))
    check_schema(exclusions, load_json(directory / "not-ported.schema.json"))
    check_schema(amendments, load_json(directory / "reference-amendments.schema.json"))
    if (
        data["reference"]["commit"] != REFERENCE_SHA
        or exclusions["reference_sha"] != REFERENCE_SHA
        or amendments["reference_sha"] != REFERENCE_SHA
    ):
        raise PortError(
            "Reference pin mismatch: REFERENCE_SHA, the manifest, not-ported.json and "
            "reference-amendments.json must name the same commit"
        )
    validate_dag(data["work_packages"])
    lookup = {wp["id"]: wp for wp in data["work_packages"]}
    for wp_id, wp in lookup.items():
        if len({a["id"] for a in wp["acceptance"]}) != len(wp["acceptance"]):
            raise PortError(f"{wp_id}: duplicate acceptance IDs")
    expected = tree(repo, REFERENCE_SHA)
    reference = data["reference"]
    if reference["source_inventory_sha256"] != digest(expected):
        raise PortError("Reference inventory digest mismatch")
    if reference["tree_sha"] != git(repo, "rev-parse", f"{REFERENCE_SHA}^{{tree}}").decode().strip():
        raise PortError("Reference tree hash mismatch")
    excluded = {}
    for entry in exclusions["entries"]:
        path = git_path(entry["path"])
        if path in excluded:
            raise PortError(f"Duplicate excluded source: {path}")
        if path not in expected or entry["blob_sha"] != expected[path]:
            raise PortError(f"Unknown or mismatched excluded source: {path}")
        if not entry["review_basis"] or not entry["android_adaptation"]:
            raise PortError(f"Unreviewed exclusion: {path}")
        if entry["adaptation_wp"] not in lookup:
            raise PortError(f"Unknown exclusion adaptation owner: {path}")
        excluded[path] = entry
    seen, folded = set(), set()
    for entry in data["inventory"]:
        path = git_path(entry["path"])
        if path in seen or path.casefold() in folded:
            raise PortError(f"Duplicate source owner/path: {path}")
        seen.add(path)
        folded.add(path.casefold())
        if path not in expected or expected[path] != entry["blob_sha"]:
            raise PortError(f"Unknown source or source hash mismatch: {path}")
        owner = entry["primary_owner"]
        exclusion = entry["exclusion"]
        if (owner is None) == (exclusion is None):
            raise PortError(f"Source requires exactly one primary owner or reviewed exclusion: {path}")
        if owner is not None and owner not in lookup:
            raise PortError(f"Unknown source owner: {path}")
        if exclusion is not None:
            if path not in excluded or excluded[path]["reason_code"] != exclusion:
                raise PortError(f"Missing reviewed exclusion: {path}")
        elif path in excluded:
            raise PortError(f"Source is both owned and excluded: {path}")
        for consumer in entry["cross_references"]:
            if consumer not in lookup or consumer == owner:
                raise PortError(f"Invalid source cross-reference: {path}")
    if seen != expected.keys():
        raise PortError(f"Unowned source files: {sorted(expected.keys() - seen)}")
    if excluded.keys() != {e["path"] for e in data["inventory"] if e["exclusion"] is not None}:
        raise PortError("Exclusion inventory mismatch")
    for wp in lookup.values():
        if not any(e["primary_owner"] == wp["id"] for e in data["inventory"]):
            if not wp["android_only_reason"]:
                raise PortError(f"{wp['id']}: empty ownership without an Android-only charter")
    for mapping in data["implementations"]:
        git_path(mapping["path"])
        if mapping["work_package"] not in lookup:
            raise PortError("Unknown implementation WP")
        for source in mapping["sources"]:
            if source not in seen:
                raise PortError(f"Unknown implementation source: {source}")
    amendment_blobs = {}
    for entry in amendments["entries"]:
        if "/Tests/" not in entry["path"]:
            raise PortError(f"Reference amendment must be a test-only path: {entry['path']}")
        if expected.get(entry["path"]) != entry["original_blob_sha"]:
            raise PortError(f"Reference amendment baseline drift: {entry['path']}")
        if not (repo / entry["adr"]).is_file():
            raise PortError(f"Reference amendment missing its ADR: {entry['path']}")
        amendment_blobs[entry["path"]] = entry["approved_blob_sha"]
    if verify_checkout:
        head = git(repo, "rev-parse", "HEAD").decode().strip()
        current = tree(repo, head)
        readonly = lambda p: p.startswith(REFERENCE_ROOTS) or p in ("LICENSE",)
        changed = sorted(
            p for p in expected.keys() | current.keys()
            if readonly(p) and current.get(p) != expected.get(p)
            and current.get(p) != amendment_blobs.get(p)
        )
        if changed:
            raise PortError(f"Swift reference advanced/changed; do not move the pin: {changed}")
        roots = [p.rstrip("/") for p in REFERENCE_ROOTS] + ["LICENSE"]
        dirty = [
            p for p in git(repo, "diff", "--name-only", REFERENCE_SHA, "--", *roots).decode().splitlines()
            if p not in amendment_blobs
        ]
        unknown = git(repo, "ls-files", "--others", "--exclude-standard", "--", *roots).decode().splitlines()
        if dirty or unknown:
            raise PortError(f"Read-only source drift or unknown source: {sorted(set(dirty + unknown))}")
    return Manifest(data, exclusions, repo)


def load_manifest(repo: Path, verify_checkout=True) -> Manifest:
    directory = repo / "docs" / "android"
    return validate_manifest(
        load_json(directory / "port-manifest.json"),
        load_json(directory / "not-ported.json"),
        repo,
        verify_checkout=verify_checkout,
        amendments=load_json(directory / "reference-amendments.json"),
    )
