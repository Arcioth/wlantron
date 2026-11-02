"""Portal form parsing, launcher names, and credential file mode. No network."""

from __future__ import annotations

import argparse
import io
import os
import socket
import stat
import tarfile
import tempfile
import unittest
from pathlib import Path

from wlantron.cli import (
    NoDeviceError,
    choose_ifaces,
    invocation_name,
    is_lan_invocation,
    session_file,
)
from wlantron.const import DEFAULT_IFACE
from wlantron.creds import CredStore, config_dirs, wants_save
from wlantron.update import apply_tarball, package_rel, prepare_argv
from wlantron.model import Iface, ipv4_list
from wlantron.portal import (
    FortiFormParser,
    classify_form,
    extract_redirect_url,
    submit_fortigate_portal,
)
from wlantron.windows import _looks_like_wifi, _parse_json_blob


LOGIN_HTML = """<html><form action="/" method="post">
<input type="hidden" name="magic" value="tok">
<input type="text" name="username" value="">
<input type="password" name="password" value="">
<input type="submit" name="submit" value="Continue">
</form></html>"""

DISCLAIMER_HTML = """<html>Firewall Disclaimer<form method="post" action="/">
<input type="hidden" name="magic" value="abc">
</form></html>"""


class FakeNet:
    def __init__(self, rows: list[Iface]) -> None:
        self.rows = rows

    def list_devices(self, kinds):
        return [row.name for row in self.rows if row.kind in kinds]

    def get(self, name: str) -> Iface:
        for row in self.rows:
            if row.name == name:
                return row
        return Iface(name, "ethernet")


def args(**kwargs):
    base = dict(iface="", lan=False, wifi=False, all=False)
    base.update(kwargs)
    return argparse.Namespace(**base)


class ParseTests(unittest.TestCase):
    def test_redirect_header_and_body_token(self):
        loc = extract_redirect_url(
            "http://detectportal.firefox.com/canonical.html",
            302,
            {"location": "http://10.1.1.1:1000/fgtauth?abc"},
            b"",
        )
        self.assertEqual(loc, "http://10.1.1.1:1000/fgtauth?abc")
        body = extract_redirect_url(
            "http://example/",
            200,
            {},
            b'<a href="http://10.1.1.1/fgtauth?deadbeef">',
        )
        self.assertEqual(body, "http://10.1.1.1/fgtauth?deadbeef")

    def test_magic_copied_from_token_query(self):
        parser = FortiFormParser()
        parser.feed(
            '<form method="post"><input type="text" name="username">'
            '<input type="password" name="password"></form>'
        )
        fields, needs_user, needs_pass, user_field, pass_field = classify_form(
            parser, "http://10.0.0.1/fgtauth?abc123"
        )
        self.assertIn(("magic", "abc123"), fields)
        self.assertTrue(needs_user and needs_pass)
        self.assertEqual(user_field, "username")
        self.assertEqual(pass_field, "password")

    def test_login_then_disclaimer(self):
        posts: list[bytes] = []

        def request(method, url, headers=None, body=None, fallback_ips=None, timeout=4.5):
            if method == "POST":
                posts.append(body)
                if len(posts) == 1:
                    return 200, {}, [], DISCLAIMER_HTML.encode()
                return 200, {}, [], b"https://10.0.0.1/keepalive?abcdef ok"
            if "canonical.html" in url:
                return 200, {}, [], b"support.mozilla.org/kb/captive-portal"
            return 500, {}, [], b""

        ok = submit_fortigate_portal(
            "http://10.0.0.1/fgtauth?tok",
            LOGIN_HTML.encode(),
            request,
            iface_name="wlp4s0",
            ask=False,
            creds_get=lambda: ("cadet", "secret-test"),
            creds_prompt=lambda: (_ for _ in ()).throw(AssertionError("prompt")),
            session_file=None,
        )
        self.assertTrue(ok)
        self.assertEqual(len(posts), 2)
        first = posts[0].decode()
        self.assertIn("username=cadet", first)
        self.assertIn("password=secret-test", first)
        self.assertIn("magic=tok", first)
        self.assertIn("answer=1", posts[1].decode())

    def test_auth_failure_stops(self):
        def request(method, url, headers=None, body=None, fallback_ips=None, timeout=4.5):
            return 200, {}, [], b"Authentication Failed"

        ok = submit_fortigate_portal(
            "http://10.0.0.1/fgtauth?tok",
            LOGIN_HTML.encode(),
            request,
            iface_name="eno1",
            ask=False,
            creds_get=lambda: ("cadet", "secret-test"),
            creds_prompt=lambda: ("x", "y"),
            session_file=None,
        )
        self.assertFalse(ok)


class ChooseTests(unittest.TestCase):
    def setUp(self):
        self.net = FakeNet(
            [
                Iface("eno1", "ethernet", ips=["10.3.249.5"], gateway="10.3.248.1"),
                Iface(DEFAULT_IFACE, "wifi", ips=["10.50.1.8"], gateway="10.50.0.1"),
            ]
        )

    def test_names(self):
        self.assertFalse(is_lan_invocation("wifi-login"))
        self.assertFalse(is_lan_invocation("wlantron"))
        self.assertTrue(is_lan_invocation("lan-login"))
        both = [row.name for row in self.net.rows]
        self.assertEqual(choose_ifaces(self.net, args(), "wifi-login"), both)
        self.assertEqual(choose_ifaces(self.net, args(), "wlantron"), both)
        self.assertEqual(choose_ifaces(self.net, args(), "lan-login"), ["eno1"])
        self.assertEqual(choose_ifaces(self.net, args(wifi=True), "lan-login"), [DEFAULT_IFACE])

    def test_lan_missing(self):
        wifi_only = FakeNet([Iface("wlan0", "wifi")])
        with self.assertRaises(NoDeviceError):
            choose_ifaces(wifi_only, args(), "lan-login")

    def test_disconnected_linux_fallback(self):
        down = FakeNet(
            [
                Iface("eno1", "ethernet"),
                Iface(DEFAULT_IFACE, "wifi"),
            ]
        )
        self.assertEqual(choose_ifaces(down, args(), "wlantron"), [DEFAULT_IFACE])

    def test_dunder_main_is_wlantron(self):
        previous = os.environ.pop("WLANTRON_AS", None)
        try:
            self.assertEqual(invocation_name("__main__.py"), "wlantron")
            self.assertFalse(is_lan_invocation("wlantron"))
        finally:
            if previous is not None:
                os.environ["WLANTRON_AS"] = previous

    def test_invocation_env_overrides_argv(self):
        previous = os.environ.get("WLANTRON_AS")
        os.environ["WLANTRON_AS"] = "lan-login.cmd"
        try:
            self.assertEqual(invocation_name("wifi-login"), "lan-login")
            self.assertTrue(is_lan_invocation(invocation_name()))
        finally:
            if previous is None:
                os.environ.pop("WLANTRON_AS", None)
            else:
                os.environ["WLANTRON_AS"] = previous


class CredTests(unittest.TestCase):
    def test_roundtrip_mode_600(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            store = CredStore(Path(tmp))
            store.save("cadet", "secret-test")
            mode = stat.S_IMODE(store.path.stat().st_mode)
            self.assertEqual(mode, 0o600)
            self.assertEqual(store.load(), ("cadet", "secret-test"))
            self.assertTrue(store.clear())
            self.assertIsNone(store.load())

    def test_empty_answer_saves_and_no_does_not(self):
        self.assertTrue(wants_save(""))
        self.assertTrue(wants_save("Y"))
        self.assertFalse(wants_save("n"))

    def test_windows_dirs_and_legacy_load(self):
        home = Path("C:/Users/cadet")
        dirs = config_dirs(
            "win32",
            {
                "APPDATA": str(home / "AppData" / "Roaming"),
                "LOCALAPPDATA": str(home / "AppData" / "Local"),
            },
            home,
        )
        self.assertEqual(dirs[0], home / "AppData" / "Roaming" / "hho-wifi")
        self.assertIn(home / ".config" / "hho-wifi", dirs)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            roaming = root / "roaming"
            legacy = root / "legacy"
            legacy.mkdir()
            (legacy / "credentials.json").write_text(
                '{"username": "cadet", "password": "secret-test"}\n',
                encoding="utf-8",
            )
            store = CredStore(directories=[roaming, legacy])
            self.assertEqual(store.load(), ("cadet", "secret-test"))
            store.save("cadet", "secret-test")
            self.assertTrue((roaming / "credentials.json").is_file())
            self.assertIn("secret-test", (legacy / "credentials.json").read_text(encoding="utf-8"))


class UpdateTests(unittest.TestCase):
    def test_update_word_becomes_flag(self):
        self.assertEqual(prepare_argv(["update"]), ["--update"])
        self.assertEqual(prepare_argv(["--status"]), ["--status"])

    def test_rejects_path_escape(self):
        self.assertIsNone(package_rel("wlantron-main/../../etc/passwd"))
        self.assertIsNone(package_rel("/etc/passwd"))
        self.assertEqual(package_rel("wlantron-main/wlantron/cli.py"), Path("wlantron/cli.py"))

    def test_apply_tarball_replaces_package_only(self):
        blob = io.BytesIO()
        with tarfile.open(fileobj=blob, mode="w:gz") as tar:
            payload = b"__version__ = '9.9.9'\n"
            info = tarfile.TarInfo("wlantron-main/wlantron/__init__.py")
            info.size = len(payload)
            tar.addfile(info, io.BytesIO(payload))
            main = b"print('new')\n"
            main_info = tarfile.TarInfo("wlantron-main/wlantron/__main__.py")
            main_info.size = len(main)
            tar.addfile(main_info, io.BytesIO(main))
            sneak = b"nope\n"
            bad = tarfile.TarInfo("wlantron-main/../../outside.txt")
            bad.size = len(sneak)
            tar.addfile(bad, io.BytesIO(sneak))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            package = root / "wlantron"
            package.mkdir()
            (package / "__init__.py").write_text("__version__ = 'old'\n", encoding="utf-8")
            (package / "__main__.py").write_text("print('old')\n", encoding="utf-8")
            count = apply_tarball(root, blob.getvalue())
            self.assertEqual(count, 2)
            self.assertIn("9.9.9", (package / "__init__.py").read_text(encoding="utf-8"))
            self.assertIn("new", (package / "__main__.py").read_text(encoding="utf-8"))
            self.assertFalse((root / "outside.txt").exists())


class WindowsShapeTests(unittest.TestCase):
    def test_json_blob_and_wifi_name(self):
        payload = _parse_json_blob('noise\n{"name": "Wi-Fi", "ips": "10.0.0.8"}\n')
        self.assertEqual(payload["name"], "Wi-Fi")
        self.assertEqual(ipv4_list(payload["ips"]), ["10.0.0.8"])
        self.assertEqual(ipv4_list(["10.0.0.1", "fe80::1", "nope"]), ["10.0.0.1"])
        self.assertEqual(_looks_like_wifi("Wi-Fi", "ethernet"), "wifi")
        self.assertEqual(_looks_like_wifi("Ethernet", "ethernet"), "ethernet")

    def test_session_name_is_a_filename(self):
        path = session_file("Ethernet 2")
        self.assertEqual(path.name, "hho-session-Ethernet_2.json")

    def test_bind_to_device_exists_on_linux(self):
        if os.name != "posix":
            self.skipTest("linux socket option")
        self.assertTrue(hasattr(socket, "SO_BINDTODEVICE"))


if __name__ == "__main__":
    unittest.main()
