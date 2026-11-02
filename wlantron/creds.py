"""Portal username and password.

The file is plaintext. On Linux it is mode 0600 under ~/.config/hho-wifi,
which is where the old wifi-login script already stored it. On Windows the
first choice is %APPDATA%\\hho-wifi. AppData is already private to the
user, so the file is not passed through icacls. That call could strip the
user's own read access, and the next run then looked like nothing was saved.

If the Roaming file cannot be written, Local and ~/.config/hho-wifi are
tried. Load checks those same places, so a file left in the wrong folder
is still found.
"""

from __future__ import annotations

import getpass
import json
import os
import sys
from pathlib import Path

from .ui import err, ok


def config_dirs(
    platform: str | None = None,
    env: dict[str, str] | None = None,
    home: Path | None = None,
) -> list[Path]:
    platform = platform if platform is not None else sys.platform
    env = os.environ if env is None else env
    home = Path.home() if home is None else home
    if platform != "win32":
        return [home / ".config" / "hho-wifi"]

    found: list[Path] = []
    appdata = env.get("APPDATA")
    if appdata:
        found.append(Path(appdata) / "hho-wifi")
    else:
        found.append(home / "AppData" / "Roaming" / "hho-wifi")
    local = env.get("LOCALAPPDATA")
    if local:
        found.append(Path(local) / "hho-wifi")
    found.append(home / ".config" / "hho-wifi")

    out: list[Path] = []
    for path in found:
        if path not in out:
            out.append(path)
    return out


def wants_save(answer: str) -> bool:
    """Empty answer saves. Windows often delivers a leftover Enter here."""
    return answer.strip().lower() in ("", "y", "yes")


def _flush_console_input() -> None:
    """Drop the Enter that Windows getpass already used.

    getpass reads the console with msvcrt, and the same Enter is still
    sitting on stdin. The next input() then returns immediately, which
    used to skip the save question.
    """
    if sys.platform != "win32":
        return
    try:
        import ctypes

        handle = ctypes.windll.kernel32.GetStdHandle(-10)
        ctypes.windll.kernel32.FlushConsoleInputBuffer(handle)
    except Exception:
        pass
    try:
        import msvcrt

        while msvcrt.kbhit():
            msvcrt.getwch()
    except Exception:
        pass


def _read_pair(path: Path) -> tuple[str, str] | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, TypeError, UnicodeError):
        return None
    if not isinstance(data, dict):
        return None
    username = str(data.get("username", "")).strip()
    password = str(data.get("password", ""))
    if username and password:
        return username, password
    return None


class CredStore:
    def __init__(self, directory: Path | None = None, directories: list[Path] | None = None) -> None:
        if directories is not None:
            self.dirs = list(directories)
        elif directory is not None:
            self.dirs = [directory]
        else:
            self.dirs = config_dirs()
        if not self.dirs:
            raise ValueError("no credential directory")
        self.directory = self.dirs[0]
        self.path = self.directory / "credentials.json"

    def load(self) -> tuple[str, str] | None:
        for directory in self.dirs:
            found = _read_pair(directory / "credentials.json")
            if found:
                self.directory = directory
                self.path = directory / "credentials.json"
                return found
        return None

    def save(self, username: str, password: str) -> Path:
        errors: list[str] = []
        written: Path | None = None
        for directory in self.dirs:
            path = directory / "credentials.json"
            # Later folders are fallbacks. Don't create them unless the
            # preferred folder failed, or a file is already there.
            if written is not None and not path.exists():
                continue
            try:
                self._write(path, username, password)
            except OSError as exc:
                errors.append(f"{path}: {exc}")
                continue
            if _read_pair(path) != (username, password):
                errors.append(f"{path}: wrote the file but could not read it back")
                continue
            if written is None:
                written = path
                self.directory = directory
                self.path = path
        if written is None:
            detail = "; ".join(errors) or "no writable config directory"
            raise OSError(detail)
        return written

    def clear(self) -> bool:
        removed = False
        for directory in self.dirs:
            path = directory / "credentials.json"
            if not path.exists():
                continue
            path.unlink()
            removed = True
        return removed

    def prompt(self, offer_save: bool = True) -> tuple[str, str]:
        existing = self.load()
        default_user = existing[0] if existing else ""
        prompt_u = f"Username [{default_user}]: " if default_user else "Username: "
        try:
            username = input(prompt_u).strip() or default_user
        except EOFError:
            err("No terminal input. Open cmd or PowerShell and run wifi-login --configure.")
            raise SystemExit(1) from None
        if not username:
            err("Username cannot be empty.")
            raise SystemExit(1)
        password = getpass.getpass("Password: ")
        if not password:
            err("Password cannot be empty.")
            raise SystemExit(1)
        if offer_save:
            _flush_console_input()
            try:
                ans = input(f"Save credentials in {self.path}? [Y/n]: ")
            except EOFError:
                ans = ""
            if wants_save(ans):
                try:
                    saved = self.save(username, password)
                except OSError as exc:
                    err(f"Could not save credentials: {exc}")
                    raise SystemExit(1) from None
                ok(f"Saved credentials to {saved}.")
        return username, password

    def _write(self, path: Path, username: str, password: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(
            {"username": username, "password": password},
            indent=2,
            ensure_ascii=False,
        )
        tmp = path.with_suffix(".tmp")
        tmp.write_text(payload + "\n", encoding="utf-8")
        try:
            os.replace(tmp, path)
        except OSError:
            tmp.unlink(missing_ok=True)
            raise
        if os.name != "nt":
            try:
                os.chmod(path, 0o600)
            except OSError:
                pass
            try:
                os.chmod(path.parent, 0o700)
            except OSError:
                pass
