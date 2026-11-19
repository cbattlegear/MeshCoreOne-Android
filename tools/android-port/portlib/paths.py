import re

from .errors import PortError


def git_path(path: str, subtree: bool = False) -> str:
    if not isinstance(path, str) or not path:
        raise PortError("Empty path")
    if any(ord(c) < 32 or ord(c) == 127 or c in '\\:<>|"'
           for c in path) or path.startswith("/"):
        raise PortError(f"Not a repository-relative Git path: {path!r}")
    if subtree and path.endswith("/**"):
        path = path[:-2]
    if any(c in path for c in "*?[]"):
        raise PortError(f"Only literal paths or trailing /** subtrees are allowed: {path}")
    parts = path.rstrip("/").split("/")
    if any(p in ("", ".", "..") or p.endswith((" ", ".")) for p in parts):
        raise PortError(f"Unsafe path: {path!r}")
    if any(p.casefold() == ".git" for p in parts):
        raise PortError("Git-internal files cannot be leased or used as port inputs")
    if any(re.fullmatch(r"(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?", p)
           for p in parts):
        raise PortError(f"Windows-reserved path: {path}")
    if not subtree and path.endswith("/"):
        raise PortError(f"Expected a file path: {path}")
    return path
