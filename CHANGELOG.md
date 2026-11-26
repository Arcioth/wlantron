# Changelog

## 0.2.4 — 2026-11-25

- Android explains a Wi-Fi that drops every connection. The 0.2.2 report from a tablet showed two raw "failed to connect … after 3000ms/4000ms" lines: it had an address and DNS, but neither the gateway nor any public site answered, so no login page ever appeared and no password was sent. The app now says that in plain words, lists the likely causes (the device's Wi-Fi MAC is not on the campus list, often because Android 10+ uses a random MAC per network; a phone or laptop hotspot instead of HHO_WiFi; the network closed right now), says where to read and change the MAC, and lists every URL it tried. It also logs Android's last verdict on the Wi-Fi (internet, sign-in page, or neither) as extra context, and keeps probing when Android saw a sign-in page. This does not make a device the network drops log in.
- A silent network fails in about 8 s instead of about 20 s: the probe stops after two public hosts give no answer at all. After a login POST, the re-check prints "Checking internet access (n of 5)."
- The password is only sent to a FortiGate auth page (an `/fgtauth?<hex>` page or a form with a `magic` token), posted to that page's own origin, and only once per tap. Before, any redirect from the gateway or a test site counted as the portal, so a router or FortiGate admin login on the gateway could have received it, and a second login form got a second POST. A portal that only redirects is no longer reported as signed in.
- The gateway is tried last and only counts if it names an `/fgtauth?` page. Firefox's normal `canonical.html` answer (a refresh to support.mozilla.org) is no longer mistaken for a portal.
- Android 7 to 10 no longer crash the login thread: the API 30 `LinkProperties.getDhcpServerAddress` call is gone.
- The Log in button no longer reads "LOG İN" on a Turkish phone. Buttons keep their own casing.

## 0.2.3 — 2026-11-03

- Android no longer treats the Firefox, Apple, or Google captive-portal URLs as proof of internet. Those names are often allowed before login, so 0.2.2 could say "signed in" while normal sites still failed. A sign-in now counts only if `example.com` loads from the Wi-Fi address. Android is then asked to recheck the network.

## 0.2.2 — 2026-11-03

- Android login no longer waits on `HttpURLConnection` to `detectportal.firefox.com`. That call ignored its timeout and did its DNS lookup off the Wi-Fi network, so Log in sat on "Looking for Wi-Fi" and Check reported the Firefox probe. Requests are sockets bound to the Wi-Fi network, and the Wi-Fi gateway is tried first.

## 0.2.1 — 2026-11-03

- Windows `build.ps1` no longer calls `Remove-Item`. A short user path such as `YT28B4~1` contains `~`, which PowerShell treats as a wildcard, so cleanup aborted the install. Deleting now goes through .NET. The script is shorter. The `.cmd` launchers are still UTF-8 with a BOM. Not retested on Windows.
- Windows install is one curl pipe. `install.cmd` is what starts PowerShell: `curl.exe -fsSL .../install.cmd | cmd`.

## 0.2.0 — 2026-11-02

- Android sideload app in `android/`. It signs in over the connected Wi-Fi with `Network.openConnection`, so mobile data or a VPN can stay the default route. Join `HHO_WiFi` in system settings first. The password is stored in the app's private preferences. Build with `cd android && nix-shell --run "gradle assembleDebug --no-daemon"`.
- Debug demo: https://github.com/Arcioth/wlantron/releases/tag/v0.2.0-debug (`wlantron-0.2.0-debug.apk`, built from `79b18ae`).

## 0.1.1 — 2026-11-02

- Windows saves the password when you press Enter. The old question defaulted to no, and the Enter from the password prompt answered it before you could type.
- Windows no longer runs `icacls` on the password file. That could remove your own read access, so the next launch looked like nothing was saved.
- If Roaming AppData cannot be written, Local AppData and `%USERPROFILE%\.config\hho-wifi` are tried. Load checks all three.
- `wifi-login --update` (or `wifi-login update`) pulls GitHub `main`. A git checkout uses `git pull --ff-only`. A curl install replaces the package from the tarball. The password file is not part of that update.

## 0.1.0 — 2026-11-02

- New repo at `~/Documents/wlantron`. The Linux captive-portal login that lived in `~/Documents/nixos/files/local-bin/wifi-login` is now this package.
- `wifi-login` and `wlantron` log in every connected adapter. `lan-login` is wired only. Same program, name-selected.
- Linux keeps `SO_BINDTODEVICE` and NetworkManager. Windows binds the adapter IPv4, sets `IP_UNICAST_IF`, and uses `netsh` plus `Get-NetAdapter`.
- Curl build: `build.sh` on Linux, `build.ps1` on Windows. Both byte-compile and install the three commands.
- Credentials stay in the old Linux path `~/.config/hho-wifi/credentials.json`.
