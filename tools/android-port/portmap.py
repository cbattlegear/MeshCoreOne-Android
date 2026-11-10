"""Check that every Kotlin file under android/ says where it came from.

Each file carries one disposition header:

    // PortedFrom: <Swift reference path>@<pinned reference commit>
    // AndroidOnly: WP-xxx <reason>
    // GeneratedFrom: <generator under tools/android-port/>; inputs: <reference path>@<pinned commit>, ...

Headers are traceability. They do not establish behavioral parity; tests do.
"""

import argparse
import json
import re
from pathlib import Path

from portlib.errors import PortError
from portlib.model import load_manifest
from portlib.paths import git_path

HEADER = re.compile(r"^// PortedFrom: (.+)@([0-9a-f]{40})$", re.MULTILINE)
ANDROID_ONLY = re.compile(r"^// AndroidOnly: (WP-\d{3}) (.+)$", re.MULTILINE)
GENERATED = re.compile(r"^// GeneratedFrom: (.+)$", re.MULTILINE)
HINT = "add `// PortedFrom: <path>@<pinned sha>`, `// AndroidOnly: WP-xxx <reason>` or a GeneratedFrom header"


def port_map(manifest):
    directory = manifest.repo / "android"
    if not directory.exists():
        return []
    known = {e["path"] for e in manifest.data["inventory"]}
    pin = manifest.data["reference"]["commit"]
    results, problems = [], []
    for path in sorted(directory.rglob("*.kt")):
        relative = path.relative_to(manifest.repo).as_posix()
        if any(part in ("build", ".gradle") for part in path.relative_to(directory).parts):
            continue
        content = path.read_text(encoding="utf-8")
        sources = HEADER.findall(content)
        android_only = ANDROID_ONLY.findall(content)
        generated = GENERATED.findall(content)
        if not sources and not android_only and not generated:
            problems.append(f"{relative}: missing traceability header ({HINT})")
            continue
        if sources and (android_only or generated):
            problems.append(f"{relative}: conflicting traceability dispositions")
        if len(set(sources)) != len(sources):
            problems.append(f"{relative}: duplicate source header")
        for source, sha in sources:
            if source not in known or sha != pin:
                problems.append(f"{relative}: unknown source or stale pin: {source}@{sha}")
        for wp_id, reason in android_only:
            if wp_id not in manifest.work_packages or not reason.strip():
                problems.append(f"{relative}: AndroidOnly needs a known WP and a reason: {wp_id}")
        for declaration in generated:
            generator, separator, declared_inputs = declaration.partition("; inputs: ")
            if not separator or not declared_inputs.strip():
                problems.append(f"{relative}: GeneratedFrom must name the generator and its pinned inputs")
                continue
            try:
                git_path(generator)
            except PortError as error:
                problems.append(f"{relative}: {error}")
                continue
            if not generator.startswith("tools/android-port/") or not (manifest.repo / Path(generator)).is_file():
                problems.append(f"{relative}: unknown generator {generator}")
            for value in declared_inputs.split(", "):
                source, separator, sha = value.rpartition("@")
                if not separator or source not in known or sha != pin:
                    problems.append(f"{relative}: unknown generated input or stale pin: {value}")
        results.append({
            "implementation": relative,
            "sources": [source for source, _ in sources],
            "android_only": [wp_id for wp_id, _ in android_only],
            "generated_inputs": generated,
            "feature_acceptance": "not established by headers",
        })
    if problems:
        shown = "\n  ".join(problems[:50])
        more = f"\n  ... and {len(problems) - 50} more" if len(problems) > 50 else ""
        raise PortError(f"{len(problems)} traceability problem(s):\n  {shown}{more}")
    return results


def main():
    parser = argparse.ArgumentParser(description="Check Kotlin traceability headers against the pinned Swift reference")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--json", action="store_true", help="print the derived source-to-implementation map")
    args = parser.parse_args()
    try:
        mapping = port_map(load_manifest(args.repo))
    except (PortError, OSError) as error:
        parser.exit(2, f"portmap: {error}\n")
    if args.json:
        print(json.dumps(mapping, indent=2))
    else:
        print(f"portmap: {len(mapping)} Kotlin files have valid traceability headers")


if __name__ == "__main__":
    main()
