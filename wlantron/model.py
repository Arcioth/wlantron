"""One network adapter, the same shape on Linux and Windows."""

from __future__ import annotations

import ipaddress
from dataclasses import dataclass, field


@dataclass
class Iface:
    name: str
    kind: str
    state: str = ""
    connection: str = ""
    ips: list[str] = field(default_factory=list)
    gateway: str = ""
    dns: list[str] = field(default_factory=list)
    index: int = 0

    @property
    def label(self) -> str:
        return self.connection or self.name


def ipv4_list(values) -> list[str]:
    """Keep IPv4 strings. Windows DNS lists sometimes include junk or IPv6."""
    if values is None:
        return []
    if isinstance(values, str):
        values = [values]
    out: list[str] = []
    for raw in values:
        text = str(raw).strip()
        if not text:
            continue
        try:
            addr = ipaddress.ip_address(text)
        except ValueError:
            continue
        if addr.version == 4 and text not in out:
            out.append(text)
    return out


def ipv4_one(value: str) -> str:
    found = ipv4_list([value] if value else [])
    return found[0] if found else ""
