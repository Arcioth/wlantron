"""Bring this install up to GitHub main.

A git checkout (this machine's ~/Documents/wlantron) is updated with
`git pull --ff-only`. A curl install has no .git directory, so the package
files are replaced from the main tarball. The password file lives outside
the package and is not touched.
"""

from __future__ import annotations

import io
import os
import py_compile
import shutil
import subprocess
import tarfile
import urllib.request
from pathlib import Path

from .ui import err, info, ok

REPO = "https://github.com/Arcioth/wlantron"
TARBALL = f"{REPO}/archive/refs/heads/main.tar.gz"


def install_root() -> Path:
    return Path(__file__).resolve().parent.parent


def prepare_argv(argv: list[str]) -> list[str]:
    if argv and argv[0] == "update":
        return ["--update", *argv[1:]]
    return argv


def package_rel(name: str) -> Path | None:
    """Path of a tarball member inside the wlantron package, or None."""
    if not name or name.startswith(("/", "\\")):
        return None
    parts = Path(name).parts
    if ".." in parts or "__pycache__" in parts:
        return None
    if parts and ":" in parts[0]:
        return None
    if "wlantron" not in parts:
        return None
    rel = Path(*parts[parts.index("wlantron") :])
    if len(rel.parts) < 2 or rel.suffix == ".pyc":
        return None
    return rel


def apply_tarball(root: Path, blob: bytes) -> int:
    """Replace package files under root/wlantron. Returns how many files changed."""
    if len(blob) < 2 or blob[:2] != b"\x1f\x8b":
        raise RuntimeError("download was not a gzip archive (a portal page, or GitHub was blocked)")
    package = root / "wlantron"
    if not package.is_dir():
        raise RuntimeError(f"Not a wlantron install: {root}")

    written = 0
    with tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz") as tar:
        members = [item for item in tar.getmembers() if item.isfile()]
        planned: list[tuple[tarfile.TarInfo, Path]] = []
        for item in members:
            rel = package_rel(item.name)
            if rel is None:
                continue
            planned.append((item, rel))
        if not any(rel.name == "__main__.py" for _, rel in planned):
            raise RuntimeError("download did not contain the wlantron package")
        for item, rel in planned:
            src = tar.extractfile(item)
            if src is None:
                continue
            data = src.read()
            dest = root / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            tmp = dest.with_suffix(dest.suffix + ".tmp")
            tmp.write_bytes(data)
            os.replace(tmp, dest)
            written += 1
    if written == 0:
        raise RuntimeError("download contained no package files")
    _compile_package(package)
    return written


def _compile_package(package: Path) -> None:
    for path in package.rglob("*.py"):
        if "__pycache__" in path.parts:
            continue
        try:
            py_compile.compile(str(path), doraise=True)
        except py_compile.PyCompileError as exc:
            raise RuntimeError(f"Could not compile {path.name}: {exc}") from exc


def _git_pull(root: Path) -> int:
    git = shutil.which("git")
    if not git:
        info("git is not installed. Downloading the main tarball instead.")
        return _tarball_into(root)
    dirty = subprocess.run(
        [git, "-C", str(root), "status", "--porcelain"],
        capture_output=True,
        text=True,
    )
    if dirty.returncode != 0:
        err((dirty.stderr or dirty.stdout or "git status failed").strip())
        return 1
    if dirty.stdout.strip():
        err(f"{root} has local changes. Commit or stash them, then run wifi-login --update again.")
        return 1
    info(f"Updating git checkout {root}")
    pull = subprocess.run(
        [git, "-C", str(root), "pull", "--ff-only", "origin", "main"],
        capture_output=True,
        text=True,
    )
    if pull.returncode != 0:
        err((pull.stderr or pull.stdout or "git pull failed").strip())
        return 1
    detail = (pull.stdout or "").strip()
    if detail:
        info(detail)
    try:
        _compile_package(root / "wlantron")
    except RuntimeError as exc:
        err(str(exc))
        return 1
    ok(f"Updated {root}. Saved passwords were left in place.")
    return 0


def _tarball_into(root: Path) -> int:
    info(f"Downloading {TARBALL}")
    req = urllib.request.Request(TARBALL, headers={"User-Agent": "wlantron-update"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            blob = resp.read()
    except OSError as exc:
        err(f"Could not download the update: {exc}")
        return 1
    try:
        count = apply_tarball(root, blob)
    except (RuntimeError, tarfile.TarError, OSError) as exc:
        err(str(exc))
        return 1
    ok(f"Updated {count} files in {root / 'wlantron'}. Saved passwords were left in place.")
    return 0


def update_install(root: Path | None = None) -> int:
    root = install_root() if root is None else root
    if (root / ".git").is_dir():
        return _git_pull(root)
    # A snapshot copied by the curl build. Replace the package in place.
    return _tarball_into(root)
