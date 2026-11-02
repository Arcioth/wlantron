"""HTTP and DNS on one chosen adapter.

`prepare(sock)` binds the socket before connect or send. Linux binds the
device. Windows binds that adapter's IPv4 address. The rest of the request
is the same, so a VPN default route cannot steal the portal traffic.
"""

from __future__ import annotations

import http.client
import ipaddress
import random
import socket
import ssl
import struct
import urllib.parse
from collections.abc import Callable

Prepare = Callable[[socket.socket], None]


def is_ip_literal(host: str) -> bool:
    try:
        ipaddress.ip_address(host)
        return True
    except ValueError:
        return False


def dns_query_a(hostname: str, dns_server: str, prepare: Prepare, timeout: float = 2.0) -> list[str]:
    txid = random.randint(0, 0xFFFF)
    qname = b"".join(
        bytes([len(label)]) + label.encode("ascii") for label in hostname.strip(".").split(".")
    ) + b"\x00"
    packet = struct.pack("!HHHHHH", txid, 0x0100, 1, 0, 0, 0) + qname + struct.pack("!HH", 1, 1)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        prepare(sock)
        sock.settimeout(timeout)
        sock.sendto(packet, (dns_server, 53))
        resp, _ = sock.recvfrom(1024)
    finally:
        sock.close()

    if len(resp) < 12:
        return []
    r_txid, flags, qdcount, ancount, _, _ = struct.unpack("!HHHHHH", resp[:12])
    if r_txid != txid or (flags & 0x000F) != 0:
        return []

    def skip_name(buf: bytes, off: int) -> int:
        while off < len(buf):
            ln = buf[off]
            if ln == 0:
                return off + 1
            if (ln & 0xC0) == 0xC0:
                return off + 2
            off += 1 + ln
        return off

    off = 12
    for _ in range(qdcount):
        off = skip_name(resp, off) + 4
    ips: list[str] = []
    for _ in range(ancount):
        if off >= len(resp):
            break
        off = skip_name(resp, off)
        if off + 10 > len(resp):
            break
        rtype, rclass, _, rdlen = struct.unpack("!HHIH", resp[off : off + 10])
        off += 10
        if rtype == 1 and rclass == 1 and rdlen == 4 and off + 4 <= len(resp):
            ips.append(".".join(str(b) for b in resp[off : off + 4]))
        off += rdlen
    return ips


def resolve_host(
    hostname: str,
    dns_servers: list[str],
    prepare: Prepare,
    fallback_ips: list[str] | None = None,
) -> list[str]:
    if is_ip_literal(hostname):
        return [hostname]
    out: list[str] = []
    for server in dns_servers:
        try:
            for ip in dns_query_a(hostname, server, prepare):
                if ip not in out:
                    out.append(ip)
            if out:
                break
        except OSError:
            continue
    for ip in fallback_ips or []:
        if ip not in out:
            out.append(ip)
    return out


def http_exchange(
    prepare: Prepare,
    method: str,
    url: str,
    dns_servers: list[str],
    headers: dict[str, str] | None,
    body: bytes | None,
    fallback_ips: list[str] | None,
    timeout: float,
    user_agent: str,
) -> tuple[int, dict[str, str], list[str], bytes]:
    parsed = urllib.parse.urlsplit(url)
    scheme = parsed.scheme.lower()
    host = parsed.hostname or ""
    if not host:
        raise ValueError(f"Invalid URL: {url}")
    default_port = 443 if scheme == "https" else 80
    port = parsed.port or default_port
    path = parsed.path or "/"
    if parsed.query:
        path = f"{path}?{parsed.query}"

    ips = resolve_host(host, dns_servers, prepare, fallback_ips)
    if not ips:
        raise OSError(f"Could not resolve '{host}' via interface DNS ({dns_servers})")

    host_hdr = host if port == default_port else f"{host}:{port}"
    req_headers = {
        "Host": host_hdr,
        "User-Agent": user_agent,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Cache-Control": "no-cache, no-store",
        "Pragma": "no-cache",
        "Connection": "close",
    }
    if headers:
        req_headers.update(headers)
    if body is not None:
        req_headers["Content-Length"] = str(len(body))

    last_err: Exception | None = None
    for target_ip in ips[:3]:
        raw_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock: socket.socket = raw_sock
        try:
            prepare(raw_sock)
            raw_sock.settimeout(timeout)
            raw_sock.connect((target_ip, port))
            if scheme == "https":
                ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
                ctx.check_hostname = False
                ctx.verify_mode = ssl.CERT_NONE
                try:
                    ctx.set_ciphers("DEFAULT:@SECLEVEL=1")
                except ssl.SSLError:
                    pass
                # IP literals have no SNI. That matches Firefox on https://<ip>/.
                sni = None if is_ip_literal(host) else host
                sock = ctx.wrap_socket(raw_sock, server_hostname=sni)
                sock.settimeout(timeout)

            lines = [f"{method} {path} HTTP/1.1"]
            for key, value in req_headers.items():
                lines.append(f"{key}: {value}")
            raw_req = ("\r\n".join(lines) + "\r\n\r\n").encode("latin-1")
            if body:
                raw_req += body
            sock.sendall(raw_req)

            resp = http.client.HTTPResponse(sock)
            resp.begin()
            data = resp.read(262144)
            hdr_map = {key.lower(): value for key, value in resp.getheaders()}
            cookies = [value.split(";", 1)[0] for key, value in resp.getheaders() if key.lower() == "set-cookie"]
            return resp.status, hdr_map, cookies, data
        except Exception as exc:
            last_err = exc
        finally:
            try:
                sock.close()
            except Exception:
                pass
    assert last_err is not None
    raise last_err
