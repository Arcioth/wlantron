"""FortiGate captive-portal probe and login.

The firewall authenticates each client IP. Wi-Fi and wired LAN are separate
logins even when they share one username. A fresh /fgtauth? token is read
from the live redirect. Saved browser tokens go stale, so this never reuses
one.
"""

from __future__ import annotations

import json
import random
import re
import time
import urllib.parse
from collections.abc import Callable
from html.parser import HTMLParser
from pathlib import Path

from .ui import err, info, warn

Request = Callable[..., tuple[int, dict[str, str], list[str], bytes]]

PROBES = [
    {
        "url": "http://detectportal.firefox.com/canonical.html",
        "fallback_ips": ["151.101.129.91", "151.101.65.91", "151.101.1.91", "151.101.193.91"],
        "expect_status": 200,
        "expect_body": b"support.mozilla.org/kb/captive-portal",
    },
    {
        "url": "http://detectportal.firefox.com/success.txt",
        "fallback_ips": ["151.101.65.91", "151.101.129.91"],
        "expect_status": 200,
        "expect_exact": b"success",
    },
    {
        "url": "http://captive.apple.com/hotspot-detect.html",
        "fallback_ips": ["17.253.254.6", "17.253.57.201"],
        "expect_status": 200,
        "expect_body": b"Success",
    },
    {
        "url": "http://connectivitycheck.gstatic.com/generate_204",
        "fallback_ips": ["172.217.20.195", "172.217.21.195"],
        "expect_status": 204,
    },
]


def extract_redirect_url(base_url: str, status: int, headers: dict[str, str], body: bytes) -> str | None:
    if status in (301, 302, 303, 307, 308) and "location" in headers:
        return urllib.parse.urljoin(base_url, headers["location"].strip())

    text = body.decode("utf-8", "replace")
    match = re.search(r"https?://[^\s\"'<>]+/fgtauth\?[0-9a-fA-F]+", text)
    if match:
        return match.group(0)
    match = re.search(
        r'<meta[^>]+http-equiv=["\']?refresh["\']?[^>]+content=["\'][^"\']*url=([^"\'>\s]+)',
        text,
        re.I,
    )
    if match:
        return urllib.parse.urljoin(base_url, match.group(1).strip())
    match = re.search(r'(?:window\.)?location(?:\.href)?\s*=\s*["\']([^"\']+)["\']', text, re.I)
    if match:
        return urllib.parse.urljoin(base_url, match.group(1).strip())
    return None


class FortiFormParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.in_title = False
        self.title = ""
        self.in_form = False
        self.form_action = "/"
        self.form_method = "POST"
        self.inputs: list[dict[str, str]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        ad = {key.lower(): (value or "") for key, value in attrs}
        name = tag.lower()
        if name == "title":
            self.in_title = True
        elif name == "form" and not self.in_form:
            self.in_form = True
            self.form_action = ad.get("action") or "/"
            self.form_method = (ad.get("method") or "POST").upper()
        elif name == "input" and self.in_form:
            self.inputs.append(
                {
                    "name": ad.get("name", ""),
                    "type": ad.get("type", "text").lower(),
                    "value": ad.get("value", ""),
                }
            )

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "title":
            self.in_title = False

    def handle_data(self, data: str) -> None:
        if self.in_title:
            self.title += data


def classify_form(parser: FortiFormParser, current_url: str):
    """Split a FortiGate form into hidden fields plus username/password slots.

    The magic token is a query string with no '=' (for example /fgtauth?abcdef).
    If the form forgot the hidden input, it is copied from that query.
    """
    parsed = urllib.parse.urlsplit(current_url)
    fields: list[tuple[str, str]] = []
    field_names: set[str] = set()
    needs_user = False
    needs_pass = False
    user_field = "username"
    pass_field = "password"

    for inp in parser.inputs:
        name = inp["name"].strip()
        itype = inp["type"]
        if not name or itype in ("submit", "button", "image", "reset"):
            continue
        field_names.add(name)
        if itype == "password" or name.lower() in ("password", "passwd", "ft_pd"):
            needs_pass = True
            pass_field = name
        elif name.lower() in ("username", "user", "ft_un"):
            needs_user = True
            user_field = name
        else:
            fields.append((name, inp["value"]))

    if "magic" not in field_names and parsed.query and "=" not in parsed.query:
        fields.append(("magic", parsed.query))
    return fields, needs_user, needs_pass, user_field, pass_field


def probe_portal(request: Request) -> tuple[str, str | None, bytes | None]:
    """Return ("online"|"portal"|"unreachable", detail, inline html or None)."""
    errors: list[str] = []
    nonce = f"{int(time.time() * 1000)}{random.randint(100, 999)}"
    for probe in PROBES:
        sep = "&" if "?" in probe["url"] else "?"
        probe_url = f"{probe['url']}{sep}nocache={nonce}"
        try:
            status, hdrs, _, body = request(
                "GET",
                probe_url,
                fallback_ips=probe.get("fallback_ips", []),
                timeout=3.5,
            )
        except Exception as exc:
            errors.append(f"{probe['url']}: {exc}")
            continue

        if status == probe["expect_status"]:
            if "expect_exact" in probe and body.strip() == probe["expect_exact"]:
                return "online", None, None
            if "expect_body" in probe and probe["expect_body"] in body:
                return "online", None, None
            if "expect_exact" not in probe and "expect_body" not in probe:
                return "online", None, None

        redir = extract_redirect_url(probe_url, status, hdrs, body)
        if redir:
            return "portal", redir, None
        lowered = body.lower()
        if b"<form" in lowered and (b"magic" in lowered or b"fgtauth" in lowered):
            return "portal", probe_url, body

    return "unreachable", "; ".join(errors[:2]) or "all probes timed out", None


def _remember_session(session_file: Path | None, iface_name: str, url: str) -> None:
    if session_file is None or not url:
        return
    try:
        session_file.parent.mkdir(parents=True, exist_ok=True)
        payload = {"url": url, "time": int(time.time()), "iface": iface_name}
        session_file.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        info(f"Fortinet session URL ({iface_name}): {url}")
    except OSError as exc:
        warn(f"Could not save session URL: {exc}")


def submit_fortigate_portal(
    portal_url: str,
    inline_html: bytes | None,
    request: Request,
    *,
    iface_name: str,
    ask: bool,
    creds_get: Callable[[], tuple[str, str] | None],
    creds_prompt: Callable[[], tuple[str, str]],
    session_file: Path | None,
) -> bool:
    cookies: list[str] = []
    current_url = portal_url
    html_bytes = inline_html

    # Redirect, login form, then an optional disclaimer. Three steps is enough.
    for _step in range(3):
        if html_bytes is None:
            info(f"Fetching Fortinet portal form: {current_url}")
            hdrs_in = {"Cookie": "; ".join(cookies)} if cookies else None
            try:
                status, hdrs, new_cookies, html_bytes = request(
                    "GET", current_url, headers=hdrs_in, timeout=6.0
                )
            except Exception as exc:
                err(f"Could not open the portal form: {exc}")
                return False
            for cookie in new_cookies:
                if cookie not in cookies:
                    cookies.append(cookie)
            if status in (301, 302, 303, 307, 308) and "location" in hdrs:
                current_url = urllib.parse.urljoin(current_url, hdrs["location"].strip())
                html_bytes = None
                continue

        parser = FortiFormParser()
        parser.feed(html_bytes.decode("utf-8", "replace"))

        if not parser.in_form and not parser.inputs:
            redir = extract_redirect_url(current_url, 200, {}, html_bytes)
            if redir and redir != current_url:
                current_url = redir
                html_bytes = None
                continue
            err("Portal page did not contain an authentication form.")
            return False

        post_url = urllib.parse.urljoin(current_url, parser.form_action)
        parsed_cur = urllib.parse.urlsplit(current_url)
        origin = f"{parsed_cur.scheme}://{parsed_cur.netloc}"
        fields, needs_user, needs_pass, user_field, pass_field = classify_form(parser, current_url)

        if needs_user or needs_pass:
            creds = None if ask else creds_get()
            if creds:
                username, password = creds
                info(f"Authenticating as '{username}'...")
            else:
                if not sys_stdin_isatty():
                    err("Portal requires credentials; run `wifi-login --configure` in a terminal first.")
                    return False
                username, password = creds_prompt()
            fields.append((user_field, username))
            fields.append((pass_field, password))
        else:
            info("Accepting Fortinet Firewall Disclaimer...")
            if not any(key == "answer" for key, _ in fields):
                fields.append(("answer", "1"))

        encoded = urllib.parse.urlencode(fields).encode("utf-8")
        post_headers = {
            "Content-Type": "application/x-www-form-urlencoded",
            "Origin": origin,
            "Referer": current_url,
        }
        if cookies:
            post_headers["Cookie"] = "; ".join(cookies)

        try:
            p_status, p_hdrs, p_cookies, p_body = request(
                parser.form_method,
                post_url,
                headers=post_headers,
                body=encoded,
                timeout=7.0,
            )
            for cookie in p_cookies:
                if cookie not in cookies:
                    cookies.append(cookie)
        except Exception as exc:
            # FortiGate often resets the hijacked :80 socket the moment the
            # session is installed. The next probe decides if login worked.
            warn(f"Portal socket closed after POST ({exc}); checking if session opened...")
            p_status, p_hdrs, p_body = 200, {}, b""

        p_text = p_body.decode("utf-8", "replace")
        if re.search(r"Authentication Failed|Invalid username|Login failed", p_text, re.I):
            err("Fortinet reported: Authentication Failed (check username/password).")
            if not ask:
                warn("Update saved credentials with: wifi-login --configure  (or run: wifi-login --ask)")
            return False

        ka_match = re.search(r"https?://[^\s\"'<>]+/(?:keepalive|logout)\?[0-9a-fA-F]+", p_text)
        if not ka_match and "location" in p_hdrs and (
            "keepalive" in p_hdrs["location"] or "logout" in p_hdrs["location"]
        ):
            ka_url = urllib.parse.urljoin(post_url, p_hdrs["location"])
        elif ka_match:
            ka_url = ka_match.group(0)
        else:
            ka_url = ""
        if ka_url:
            _remember_session(session_file, iface_name, ka_url)

        next_redir = extract_redirect_url(post_url, p_status, p_hdrs, p_body)
        if next_redir and "/fgtauth?" in next_redir and next_redir != current_url:
            current_url = next_redir
            html_bytes = None
            continue
        if "Firewall Disclaimer" in p_text and "<form" in p_text:
            current_url = post_url
            html_bytes = p_body
            continue
        break

    for _ in range(5):
        state, _, _ = probe_portal(request)
        if state == "online":
            return True
        time.sleep(0.8)
    return False


def sys_stdin_isatty() -> bool:
    import sys

    return sys.stdin.isatty()
