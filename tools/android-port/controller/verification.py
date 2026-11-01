"""AndroidOnly: WP-003 Shared declared software verification for Android work packages."""

from .errors import PortError
from .model import Manifest

OBSOLETE_BOOTSTRAP_BLOCKER = (
    "Exact module commands, trusted check identities and original-case inventory must be approved by "
    "supervised CI/test WPs; Gradle/Android CI do not exist in WP-000."
)


def _verification_configuration(wp: dict):
    verification = wp["verification"]
    if (
        not isinstance(verification, dict) or type(verification.get("configured")) is not bool
        or not isinstance(verification.get("commands"), list)
        or not isinstance(verification.get("blocker"), str)
        or any(
            not isinstance(command, list) or not command
            or any(not isinstance(argument, str) or not argument for argument in command)
            for command in verification["commands"]
        )
    ):
        raise PortError("Malformed work-package verification configuration")
    return verification


def _obsolete_bootstrap_entry(verification: dict) -> bool:
    return (
        not verification["configured"] and not verification["commands"]
        and verification["blocker"] == OBSOLETE_BOOTSTRAP_BLOCKER
    )


def require_shared_whole_lane(manifest: Manifest, wp: dict):
    verification = _verification_configuration(wp)
    if not _obsolete_bootstrap_entry(verification):
        raise PortError(verification["blocker"] or "Shared whole-WP verification requires an empty entry")
    if (
        "docs/android/test-cases.json" in wp["write_paths"]
        or not any(path.startswith(("android/app/", "android/core/", "android/feature/", "android/platform/"))
                   for path in wp["write_paths"])
    ):
        raise PortError("This non-module or original-inventory WP requires its own verification contract")
    if not any(entry["exclusion"] is None for entry in manifest.inputs(wp["id"], False)):
        raise PortError("This WP has no primary-owned source inputs for the shared verification contract")
    return declared_software_lane(manifest)


def work_package_software_lane(manifest: Manifest, wp: dict):
    verification = _verification_configuration(wp)
    if verification["configured"] and verification["commands"] and not verification["blocker"]:
        return verification["commands"]
    if _obsolete_bootstrap_entry(verification):
        return require_shared_whole_lane(manifest, wp)
    raise PortError(verification["blocker"] or "Required work-package verification remains unconfigured")


def dispatch_software_lane(manifest: Manifest, wp: dict):
    verification = _verification_configuration(wp)
    if _obsolete_bootstrap_entry(verification):
        return declared_software_lane(manifest)
    return work_package_software_lane(manifest, wp)


def declared_software_lane(manifest: Manifest) -> list[list[str]]:
    ci = "tools/android-port/controller/ci.py"
    required = [
        ["python", ci, "preflight"],
        ["python", ci, "run", "--stage", "scaffold"],
        ["python", ci, "inspect"],
    ]
    verification = manifest.wp("WP-003")["verification"]
    if (
        not isinstance(verification, dict) or verification.get("configured") is not True
        or not isinstance(verification.get("commands"), list)
        or any(command not in verification["commands"] for command in required)
    ):
        raise PortError("The declared project-wide software verification lane is missing or unconfigured")
    return [command.copy() for command in required]
