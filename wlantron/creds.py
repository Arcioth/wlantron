"""Portal username and password.

The file is plaintext. On Linux it is mode 0600 under ~/.config/hho-wifi,
which is where the old wifi-login script already stored it. On Windows it
lives in %APPDATA%\\hho-wifi and the ACL is limited to the current user
when icacls is available.
"""

from __future__ import annotations

import getpass
import json
import os
import subprocess
import sys
from pathlib import Path

from .ui import err, ok


def default_config_dir() -> Path:
    if sys.platform == "win32":
        base = os.environ.get("APPDATA")
        if base:
            return Path(base) / "hho-wifi"
        return Path.home() / "AppData" / "Roaming" / "hho-wifi"
    return Path.home() / ".config" / "hho-wifi"


class CredStore:
    def __init__(self, directory: Path | None = None) -> None:
        self.directory = directory or default_config_dir()
        self.path = self.directory / "credentials.json"

    def load(self) -> tuple[str, str] | None:
        if not self.path.exists():
            return None
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            username = str(data.get("username", "")).strip()
            password = str(data.get("password", ""))
            if username and password:
                return username, password
        except (OSError, json.JSONDecodeError, TypeError):
            return None
        return None

    def save(self, username: str, password: str) -> None:
        self.directory.mkdir(parents=True, exist_ok=True)
        try:
            os.chmod(self.directory, 0o700)
        except OSError:
            pass
        tmp = self.path.with_suffix(".tmp")
        fd = os.open(str(tmp), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump({"username": username, "password": password}, handle, indent=2)
            handle.write("\n")
        os.replace(tmp, self.path)
        self._restrict(self.path)

    def clear(self) -> bool:
        if not self.path.exists():
            return False
        self.path.unlink()
        return True

    def prompt(self, offer_save: bool = True) -> tuple[str, str]:
        existing = self.load()
        default_user = existing[0] if existing else ""
        prompt_u = f"Username [{default_user}]: " if default_user else "Username: "
        username = input(prompt_u).strip() or default_user
        if not username:
            err("Username cannot be empty.")
            raise SystemExit(1)
        password = getpass.getpass("Password: ")
        if not password:
            err("Password cannot be empty.")
            raise SystemExit(1)
        if offer_save:
            ans = input(
                f"Save credentials in {self.path} for instant login next time? [y/N]: "
            ).strip().lower()
            if ans in ("y", "yes"):
                self.save(username, password)
                ok(f"Saved credentials to {self.path}.")
        return username, password

    def _restrict(self, path: Path) -> None:
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass
        if sys.platform != "win32":
            return
        user = os.environ.get("USERNAME") or ""
        if not user:
            return
        # Drop inherited ACEs so other local accounts cannot read the password file.
        subprocess.run(
            ["icacls", str(path), "/inheritance:r", "/grant:r", f"{user}:(R,W)"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
