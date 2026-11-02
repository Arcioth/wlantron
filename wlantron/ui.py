"""Terminal lines. The ice colors match nmtui-crt and vpn-tui-crt."""

from __future__ import annotations

import shutil
import subprocess
import sys

C_ICE = "\033[38;2;154;214;238m"
C_OK = "\033[38;2;140;230;180m"
C_WARN = "\033[38;2;232;240;176m"
C_ERR = "\033[38;2;255;138;150m"
C_DIM = "\033[38;2;110;150;170m"
C_RST = "\033[0m"


def enable_color() -> None:
    """Windows consoles ignore ANSI colors until virtual-terminal mode is on."""
    if sys.platform != "win32":
        return
    try:
        import ctypes

        handle = ctypes.windll.kernel32.GetStdHandle(-11)
        mode = ctypes.c_uint()
        if ctypes.windll.kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
            ctypes.windll.kernel32.SetConsoleMode(handle, mode.value | 0x0004)
    except Exception:
        return


def info(msg: str) -> None:
    print(f"{C_ICE}[wifi-login]{C_RST} {msg}")


def ok(msg: str) -> None:
    print(f"{C_OK}[OK]{C_RST} {msg}")


def warn(msg: str) -> None:
    print(f"{C_WARN}[WARN]{C_RST} {msg}")


def err(msg: str) -> None:
    print(f"{C_ERR}[ERR]{C_RST} {msg}", file=sys.stderr)


def dim(msg: str) -> None:
    print(f"  {C_DIM}{msg}{C_RST}")


def notify_linux(summary: str, body: str = "") -> None:
    import os

    if shutil.which("notify-send") and os.environ.get("WAYLAND_DISPLAY"):
        cmd = ["notify-send", "-a", "wifi-login", summary]
        if body:
            cmd.append(body)
        subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
