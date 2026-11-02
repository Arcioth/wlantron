"""Windows backend: PowerShell for adapter state, netsh to join Wi-Fi.

Windows has no SO_BINDTODEVICE. Each socket is bound to that adapter's own
IPv4 address, and IP_UNICAST_IF (31) selects the same interface index, so a
VPN default route does not carry the portal login.
"""

from __future__ import annotations

import json
import os
import re
import socket
import struct
import subprocess
import sys
import time

from .const import DEFAULT_CON, USER_AGENT_WINDOWS
from .httpio import http_exchange
from .model import Iface, ipv4_list, ipv4_one
from .ui import info

# Winsock option: outgoing IPv4 interface, index in network byte order.
IP_UNICAST_IF = 31
CREATE_NO_WINDOW = 0x08000000

_LIST_PS = r"""
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$adapters = @(Get-NetAdapter -ErrorAction Stop | Where-Object {
  $_.HardwareInterface -eq $true -and ([string]($_.Status)) -ne 'Not Present'
})
$rows = @()
foreach ($a in $adapters) {
  $ips = @()
  try {
    $found = @(Get-NetIPAddress -InterfaceIndex $a.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue)
    foreach ($item in $found) {
      $addr = [string]$item.IPAddress
      if ($addr -and $addr -notlike '169.254.*') { $ips += $addr }
    }
  } catch {}
  $gw = ''
  try {
    $route = Get-NetRoute -InterfaceIndex $a.ifIndex -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
      Sort-Object RouteMetric | Select-Object -First 1
    if ($route -and $route.NextHop -and [string]$route.NextHop -ne '0.0.0.0') {
      $gw = [string]$route.NextHop
    }
  } catch {}
  $dns = @()
  try {
    $dnsObjs = @(Get-DnsClientServerAddress -InterfaceIndex $a.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue)
    foreach ($dnsObj in $dnsObjs) {
      foreach ($server in @($dnsObj.ServerAddresses)) {
        $text = [string]$server
        if ($text -and $text -ne '0.0.0.0') { $dns += $text }
      }
    }
  } catch {}
  $medium = [string]$a.NdisPhysicalMedium
  $physical = [string]$a.PhysicalMediaType
  $kind = 'ethernet'
  if ($medium -eq '9' -or $medium -match '802' -or $physical -match '802\.11|Wireless') {
    $kind = 'wifi'
  }
  $rows += [pscustomobject]@{
    name = [string]$a.Name
    kind = $kind
    state = [string]$a.Status
    connection = [string]$a.Name
    ips = @($ips)
    gateway = $gw
    dns = @($dns)
    index = [int]$a.ifIndex
  }
}
if ($rows.Count -eq 0) { '[]' } else { $rows | ConvertTo-Json -Compress -Depth 5 }
"""


def _run(args: list[str], timeout: float = 30) -> subprocess.CompletedProcess[str]:
    kwargs = {}
    if sys.platform == "win32":
        kwargs["creationflags"] = CREATE_NO_WINDOW
    return subprocess.run(
        args,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        **kwargs,
    )


def _powershell(script: str, timeout: float = 30) -> subprocess.CompletedProcess[str]:
    return _run(
        ["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
        timeout=timeout,
    )


def _ps_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _parse_json_blob(text: str):
    raw = text.strip().lstrip("\ufeff")
    start = None
    for i, ch in enumerate(raw):
        if ch in "[{":
            start = i
            break
    if start is None:
        raise ValueError(raw[:240] or "empty adapter list")
    return json.loads(raw[start:])


def _as_rows(payload) -> list[dict]:
    if payload is None:
        return []
    if isinstance(payload, dict):
        return [payload]
    if isinstance(payload, list):
        return [row for row in payload if isinstance(row, dict)]
    return []


def _looks_like_wifi(name: str, kind: str) -> str:
    if kind == "wifi":
        return "wifi"
    if re.search(r"wi-?fi|wlan|wireless", name, re.I):
        return "wifi"
    return "ethernet"


class WindowsNet:
    def __init__(self) -> None:
        self._cache: list[Iface] | None = None
        self._cache_at = 0.0

    def _all(self, fresh: bool = False) -> list[Iface]:
        now = time.time()
        if not fresh and self._cache is not None and now - self._cache_at < 2.0:
            return self._cache
        rows = self._query()
        self._cache = rows
        self._cache_at = time.time()
        return rows

    def _query(self) -> list[Iface]:
        proc = _powershell(_LIST_PS, timeout=40)
        if proc.returncode != 0:
            detail = (proc.stderr or proc.stdout or "Get-NetAdapter failed").strip()
            raise RuntimeError(detail)
        try:
            payload = _parse_json_blob(proc.stdout)
        except (ValueError, json.JSONDecodeError) as exc:
            raise RuntimeError(f"Could not read Windows adapters: {exc}") from exc
        rows: list[Iface] = []
        for row in _as_rows(payload):
            name = str(row.get("name") or "")
            if not name or re.search(r"vEthernet|Loopback|Teredo|isatap", name, re.I):
                continue
            kind = _looks_like_wifi(name, str(row.get("kind") or "ethernet"))
            try:
                index = int(row.get("index") or 0)
            except (TypeError, ValueError):
                index = 0
            rows.append(
                Iface(
                    name=name,
                    kind=kind,
                    state=str(row.get("state") or ""),
                    connection=str(row.get("connection") or name),
                    ips=ipv4_list(row.get("ips")),
                    gateway=ipv4_one(str(row.get("gateway") or "")),
                    dns=ipv4_list(row.get("dns")),
                    index=index,
                )
            )
        return rows

    def list_devices(self, kinds: tuple[str, ...]) -> list[str]:
        return [row.name for row in self._all() if row.kind in kinds]

    def get(self, name: str, fresh: bool = False) -> Iface:
        for row in self._all(fresh=fresh):
            if row.name == name:
                return row
        return Iface(name=name, kind=_looks_like_wifi(name, "ethernet"))

    def bring_up(self, name: str, con_name: str = "") -> Iface:
        st = self.get(name)
        if st.kind == "wifi":
            ssid = con_name or DEFAULT_CON
            proc = _run(["netsh", "wlan", "connect", f"name={ssid}", f"interface={name}"])
            if proc.returncode != 0:
                detail = (proc.stderr or proc.stdout or "netsh wlan connect failed").strip()
                raise RuntimeError(f"Could not join '{ssid}' on {name}: {detail}")
        else:
            proc = _powershell(
                f"Enable-NetAdapter -Name {_ps_quote(name)} -Confirm:$false"
            )
            if proc.returncode != 0:
                detail = (proc.stderr or proc.stdout or "Enable-NetAdapter failed").strip()
                raise RuntimeError(f"Could not enable '{name}': {detail}")
        return self.wait_for_lease(name, timeout=20.0)

    def bounce(self, name: str, con_name: str = "") -> Iface:
        st = self.get(name)
        kind = "Wi-Fi" if st.kind == "wifi" else "LAN"
        info(f"Resetting {kind} session on {name}...")
        if st.kind == "wifi":
            _run(["netsh", "wlan", "disconnect", f"interface={name}"])
            time.sleep(1.0)
            return self.bring_up(name, con_name or DEFAULT_CON)
        proc = _powershell(f"Restart-NetAdapter -Name {_ps_quote(name)} -Confirm:$false")
        if proc.returncode != 0:
            detail = (proc.stderr or proc.stdout or "Restart-NetAdapter failed").strip()
            raise RuntimeError(
                f"Could not reset '{name}': {detail}. Ethernet reset needs an elevated PowerShell."
            )
        time.sleep(1.0)
        return self.wait_for_lease(name, timeout=20.0)

    def wait_for_lease(self, name: str, timeout: float = 20.0) -> Iface:
        deadline = time.time() + timeout
        while time.time() < deadline:
            st = self.get(name, fresh=True)
            if st.ips and st.gateway:
                return st
            time.sleep(0.7)
        raise RuntimeError(f"Timed out waiting for DHCP IPv4 lease on {name}")

    def exchange(self, iface: Iface, method: str, url: str, headers=None, body=None, fallback_ips=None, timeout=4.5):
        if not iface.ips:
            raise OSError(f"{iface.name} has no IPv4 address to bind")
        local_ip = iface.ips[0]
        if_index = iface.index

        def prepare(sock: socket.socket) -> None:
            if if_index:
                try:
                    sock.setsockopt(socket.IPPROTO_IP, IP_UNICAST_IF, struct.pack("!I", if_index))
                except OSError:
                    pass
            sock.bind((local_ip, 0))

        return http_exchange(
            prepare,
            method,
            url,
            iface.dns,
            headers,
            body,
            fallback_ips,
            timeout,
            USER_AGENT_WINDOWS,
        )

    def connectivity_check(self) -> None:
        return None

    def open_browser(self, url: str) -> str | None:
        try:
            os.startfile(url)  # type: ignore[attr-defined]
            return "default browser"
        except OSError:
            return None

    def notify(self, summary: str, body: str = "") -> None:
        return None
