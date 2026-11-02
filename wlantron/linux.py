"""Linux backend: NetworkManager plus SO_BINDTODEVICE.

Binding the socket to the interface is what lets login work while a VPN or
a phone tether owns the default route. nmcli only brings the link up.
"""

from __future__ import annotations

import shutil
import socket
import subprocess
import time

from .const import USER_AGENT_LINUX
from .httpio import http_exchange
from .model import Iface
from .ui import info, notify_linux


class LinuxNet:
    def list_devices(self, kinds: tuple[str, ...]) -> list[str]:
        proc = subprocess.run(
            ["nmcli", "-t", "-f", "DEVICE,TYPE,STATE", "device"],
            capture_output=True,
            text=True,
        )
        out: list[str] = []
        for line in proc.stdout.splitlines():
            parts = line.split(":")
            if len(parts) < 3:
                continue
            dev, dtype, state = parts[0], parts[1], parts[2]
            if dtype in kinds and state != "unmanaged":
                out.append(dev)
        return out

    def get(self, name: str) -> Iface:
        proc = subprocess.run(
            [
                "nmcli",
                "-t",
                "-f",
                "GENERAL.TYPE,GENERAL.STATE,GENERAL.CONNECTION,IP4.ADDRESS,IP4.GATEWAY,IP4.DNS",
                "dev",
                "show",
                name,
            ],
            capture_output=True,
            text=True,
        )
        kind = "ethernet"
        state = ""
        connection = ""
        ips: list[str] = []
        gateway = ""
        dns: list[str] = []
        for line in proc.stdout.splitlines():
            if ":" not in line:
                continue
            key, value = line.split(":", 1)
            value = value.strip()
            if key == "GENERAL.TYPE" and value == "wifi":
                kind = "wifi"
            elif key == "GENERAL.STATE":
                state = value
            elif key == "GENERAL.CONNECTION":
                connection = "" if value == "--" else value
            elif key.startswith("IP4.ADDRESS") and value:
                ips.append(value.split("/")[0])
            elif key == "IP4.GATEWAY" and value and value != "--":
                gateway = value
            elif key.startswith("IP4.DNS") and value:
                dns.append(value)
        return Iface(name, kind, state, connection, ips, gateway, dns)

    def bring_up(self, name: str, con_name: str = "") -> Iface:
        if con_name:
            proc = subprocess.run(
                ["nmcli", "connection", "up", con_name, "ifname", name],
                capture_output=True,
                text=True,
            )
            if proc.returncode != 0:
                proc = subprocess.run(
                    ["nmcli", "connection", "up", con_name],
                    capture_output=True,
                    text=True,
                )
        else:
            proc = subprocess.run(
                ["nmcli", "device", "connect", name],
                capture_output=True,
                text=True,
            )
        if proc.returncode != 0:
            label = con_name or name
            detail = proc.stderr.strip() or proc.stdout.strip()
            raise RuntimeError(f"Could not activate '{label}': {detail}")
        return self.wait_for_lease(name)

    def bounce(self, name: str, con_name: str = "") -> Iface:
        st = self.get(name)
        con = st.connection or con_name
        kind = "Wi-Fi" if st.kind == "wifi" else "LAN"
        info(f"Resetting {kind} session '{con or name}' on {name}...")
        if st.kind == "wifi" and con:
            subprocess.run(
                ["nmcli", "connection", "down", con],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        else:
            subprocess.run(
                ["nmcli", "device", "disconnect", name],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        time.sleep(1.0)
        return self.bring_up(name, con if st.kind == "wifi" else "")

    def wait_for_lease(self, name: str, timeout: float = 15.0) -> Iface:
        deadline = time.time() + timeout
        while time.time() < deadline:
            st = self.get(name)
            if st.ips and st.gateway:
                return st
            time.sleep(0.5)
        raise RuntimeError(f"Timed out waiting for DHCP IPv4 lease on {name}")

    def exchange(self, iface: Iface, method: str, url: str, headers=None, body=None, fallback_ips=None, timeout=4.5):
        def prepare(sock: socket.socket) -> None:
            # SO_BINDTODEVICE pins this socket to the named NIC.
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, iface.name.encode() + b"\0")

        return http_exchange(
            prepare,
            method,
            url,
            iface.dns,
            headers,
            body,
            fallback_ips,
            timeout,
            USER_AGENT_LINUX,
        )

    def connectivity_check(self) -> None:
        if not shutil.which("nmcli"):
            return
        subprocess.run(
            ["nmcli", "networking", "connectivity", "check"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    def open_browser(self, url: str) -> str | None:
        for cand in ("zen-twilight", "zen-browser", "zen", "xdg-open"):
            exe = shutil.which(cand)
            if exe:
                subprocess.Popen(
                    [exe, url],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    start_new_session=True,
                )
                return cand
        return None

    def notify(self, summary: str, body: str = "") -> None:
        notify_linux(summary, body)
