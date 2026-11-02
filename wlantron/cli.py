"""Command line for wifi-login, lan-login, and wlantron.

The three names are one program. `lan-login` limits itself to wired
adapters. `wifi-login` and `wlantron` check every connected adapter unless
you pass --wifi or --lan. The name `wlantron` contains the letters "lan"
and must not be treated as the wired-only alias.
"""

from __future__ import annotations

import argparse
import getpass
import os
import re
import sys
from pathlib import Path

from . import __version__
from .const import DEFAULT_CON, DEFAULT_IFACE
from .creds import CredStore
from .portal import probe_portal, submit_fortigate_portal
from .ui import dim, enable_color, err, info, ok, warn

_LAN_NAMES = {"lan-login", "lanlogin"}


class NoDeviceError(RuntimeError):
    pass


def invocation_name(argv0: str | None = None) -> str:
    raw = os.environ.get("WLANTRON_AS") or argv0 or Path(sys.argv[0]).name
    name = Path(raw).name
    for ext in (".cmd", ".exe", ".py", ".bat"):
        if name.lower().endswith(ext):
            name = name[: -len(ext)]
            break
    # python3 wlantron/__main__.py has no launcher name. Treat it as wlantron,
    # not as the wired-only alias (the letters "lan" sit inside the project name).
    if name == "__main__":
        return "wlantron"
    return name


def is_lan_invocation(name: str) -> bool:
    return name.lower() in _LAN_NAMES


def session_file(iface_name: str) -> Path:
    safe = re.sub(r"[^A-Za-z0-9._-]+", "_", iface_name).strip("._") or "iface"
    if sys.platform == "win32":
        base = os.environ.get("LOCALAPPDATA")
        root = Path(base) / "wlantron" if base else Path.home() / "AppData" / "Local" / "wlantron"
    else:
        root = Path.home() / ".cache"
    return root / f"hho-session-{safe}.json"


def get_net():
    if sys.platform == "win32":
        from .windows import WindowsNet

        return WindowsNet()
    from .linux import LinuxNet

    return LinuxNet()


def _fallback_list(net, wifi: list[str], eth: list[str]) -> list[str]:
    if sys.platform != "win32" and DEFAULT_IFACE in wifi:
        return [DEFAULT_IFACE]
    if wifi:
        return wifi
    if eth:
        return eth
    return ["Wi-Fi" if sys.platform == "win32" else DEFAULT_IFACE]


def choose_ifaces(net, args, invoked: str) -> list[str]:
    eth = net.list_devices(("ethernet",))
    wifi = net.list_devices(("wifi",))
    if args.iface:
        return [args.iface]
    if args.lan or (is_lan_invocation(invoked) and not args.wifi):
        if not eth:
            raise NoDeviceError("No wired Ethernet / LAN interface found.")
        return eth
    if args.wifi:
        return wifi or (["Wi-Fi"] if sys.platform == "win32" else [DEFAULT_IFACE])
    if args.all:
        return eth + wifi
    connected: list[str] = []
    for name in eth + wifi:
        st = net.get(name)
        if st.ips and st.gateway:
            connected.append(name)
    if connected:
        return connected
    return _fallback_list(net, wifi, eth)


def ensure_connected(net, name: str, wifi_con: str, force_reset: bool):
    st = net.get(name)
    if force_reset:
        return net.bounce(name, wifi_con if st.kind == "wifi" else "")
    if not st.ips or not st.gateway:
        if st.kind == "wifi":
            info(f"{name} is not connected; bringing up '{wifi_con}'...")
            return net.bring_up(name, wifi_con)
        info(f"{name} is not connected; activating LAN...")
        return net.bring_up(name, "")
    return st


def _request_for(net, iface):
    def request(method, url, headers=None, body=None, fallback_ips=None, timeout=4.5):
        return net.exchange(
            iface,
            method,
            url,
            headers=headers,
            body=body,
            fallback_ips=fallback_ips,
            timeout=timeout,
        )

    return request


def check_interface_status(net, name: str) -> int:
    st = net.get(name)
    if not st.ips:
        warn(f"{name}: disconnected")
        return 1
    info(
        f"{name} ({st.kind or 'net'}): Connection='{st.connection}' IP={st.ips[0]} "
        f"GW={st.gateway} DNS={','.join(st.dns) or 'none'}"
    )
    state, detail, _ = probe_portal(_request_for(net, st))
    if state == "online":
        ok(f"{st.label} is AUTHENTICATED and has full internet access.")
        return 0
    if state == "portal":
        warn(f"{st.label} is behind Fortinet captive portal:")
        dim(detail or "")
        return 2
    err(f"{st.label} probe failed: {detail}")
    return 3


def login_interface(net, store: CredStore, runtime: dict, name: str, args) -> bool:
    try:
        st = ensure_connected(net, name, args.ssid, args.reset)
    except RuntimeError as exc:
        err(str(exc))
        return False

    info(f"Checking '{st.label}' on {st.name} ({st.ips[0]} via {st.gateway})...")
    request = _request_for(net, st)
    state, portal_url, inline_html = probe_portal(request)

    if state == "unreachable" and not args.reset:
        warn(f"Initial probe failed on {st.name} ({portal_url}); bouncing connection once...")
        try:
            st = net.bounce(st.name, args.ssid if st.kind == "wifi" else "")
        except RuntimeError as exc:
            err(str(exc))
            return False
        request = _request_for(net, st)
        state, portal_url, inline_html = probe_portal(request)

    if state == "online":
        net.connectivity_check()
        ok(f"'{st.label}' ({st.name}) is already authenticated and online.")
        net.notify(f"{st.name} Online", f"{st.label} is already authenticated.")
        return True

    if state != "portal" or not portal_url:
        err(f"Could not reach captive portal on {st.name}: {portal_url}")
        return False

    info(f"Detected fresh Fortinet portal session on {st.name}: {portal_url}")
    if args.browser:
        opener = net.open_browser(portal_url)
        if opener:
            info(f"Opening fresh portal session in {opener}: {portal_url}")
            return True
        err(f"No browser launcher found. Open this URL manually:\n  {portal_url}")
        return False

    def creds_get():
        if runtime.get("creds"):
            return runtime["creds"]
        return store.load()

    def creds_prompt():
        pair = store.prompt(offer_save=True)
        runtime["creds"] = pair
        return pair

    # --ask skips saved credentials for the first prompt. After the user types
    # them once, later adapters in this run reuse that pair.
    ask_now = bool(args.ask) and not runtime.get("creds")
    if submit_fortigate_portal(
        portal_url,
        inline_html,
        request,
        iface_name=st.name,
        ask=ask_now,
        creds_get=creds_get,
        creds_prompt=creds_prompt,
        session_file=session_file(st.name),
    ):
        net.connectivity_check()
        ok(f"Authenticated '{st.label}' ({st.name}) — full internet access verified!")
        net.notify(f"{st.name} Authenticated", f"Signed in to {st.label} ({st.ips[0]}).")
        return True

    prog = invocation_name()
    err(f"Captive portal authentication did not complete on {st.name}. Try `{prog} --reset` or `--browser`.")
    return False


def build_parser(invoked: str) -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        prog=invoked,
        description="HHO_WiFi and school LAN Fortinet captive-portal login for Linux and Windows.",
    )
    ap.add_argument("--version", action="version", version=f"wlantron {__version__}")
    ap.add_argument("--update", action="store_true", help="Update this install from GitHub main. Passwords are kept.")
    ap.add_argument("--status", action="store_true", help="Show live portal / connectivity status per interface")
    ap.add_argument("--configure", "--save", action="store_true", help="Save or update portal username and password")
    ap.add_argument("--clear-saved", action="store_true", help="Delete saved portal credentials")
    ap.add_argument("--ask", action="store_true", help="Prompt for username/password instead of using the saved pair")
    ap.add_argument("--browser", "-b", action="store_true", help="Open a fresh /fgtauth? token in the browser")
    ap.add_argument("--reset", "-r", action="store_true", help="Bounce the connection, then log in")
    ap.add_argument("--iface", default="", help="One interface (Linux: wlp4s0, eno1. Windows: Wi-Fi, Ethernet)")
    ap.add_argument("--lan", "-l", action="store_true", help="Wired Ethernet only (same as the lan-login command)")
    ap.add_argument("--wifi", "-w", action="store_true", help="Wi-Fi only")
    ap.add_argument("--all", "-a", action="store_true", help="Every Wi-Fi and wired adapter, including disconnected ones")
    ap.add_argument("--ssid", default=DEFAULT_CON, help=f"Wi-Fi profile to bring up (default: {DEFAULT_CON})")
    return ap


def main(argv: list[str] | None = None) -> int:
    enable_color()
    invoked = invocation_name()
    if argv is None:
        argv = sys.argv[1:]
    from .update import prepare_argv

    argv = prepare_argv(list(argv))
    args = build_parser(invoked).parse_args(argv)

    if args.update:
        from .update import update_install

        return update_install()

    store = CredStore()

    if args.clear_saved:
        if store.clear():
            ok(f"Removed saved credentials ({store.path}).")
        else:
            info(f"No saved credentials at {store.path}.")
        return 0

    if args.configure:
        info(f"Configuring saved portal credentials ({store.path})")
        return _configure(store)

    try:
        net = get_net()
        targets = choose_ifaces(net, args, invoked)
    except NoDeviceError as exc:
        err(str(exc))
        return 1
    except RuntimeError as exc:
        err(str(exc))
        return 1

    if not targets:
        err("No network interface found.")
        return 1

    if args.status:
        ret = 0
        for name in targets:
            rc = check_interface_status(net, name)
            if rc != 0 and ret == 0:
                ret = rc
        return ret

    runtime: dict = {}
    success = 0
    failed = 0
    for name in targets:
        if login_interface(net, store, runtime, name, args):
            success += 1
        else:
            failed += 1
    if failed:
        return 1
    return 0 if success else 1


def _configure(store: CredStore) -> int:
    """Ask once and always save. Used by --configure."""
    existing = store.load()
    default_user = existing[0] if existing else ""
    prompt_u = f"Username [{default_user}]: " if default_user else "Username: "
    try:
        username = input(prompt_u).strip() or default_user
    except EOFError:
        err("No terminal input. Open cmd or PowerShell and run wifi-login --configure.")
        return 1
    if not username:
        err("Username cannot be empty.")
        return 1
    try:
        password = getpass.getpass("Password: ")
    except EOFError:
        err("No terminal input. Open cmd or PowerShell and run wifi-login --configure.")
        return 1
    if not password:
        err("Password cannot be empty.")
        return 1
    try:
        saved = store.save(username, password)
    except OSError as exc:
        err(f"Could not save credentials: {exc}")
        return 1
    ok(f"Credentials saved to {saved}. Run `wifi-login` or `lan-login` to authenticate.")
    return 0
