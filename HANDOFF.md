# wlantron handoff

Started 2026-11-02.

GitHub: https://github.com/Arcioth/wlantron (public, Apache-2.0).

Source of truth: `~/Documents/wlantron`. Desktop commands are one Python 3 package. The Android app is `android/`.

## Install

Linux curl build:

```bash
curl -fsSL https://raw.githubusercontent.com/Arcioth/wlantron/main/build.sh | sh
```

Windows curl build:

```bat
curl.exe -fsSL https://raw.githubusercontent.com/Arcioth/wlantron/main/install.cmd | cmd
```

A local `./build.sh` points `~/.local/bin` at this tree. The piped curl copies a snapshot under the prefix.

```bash
wifi-login --update
```

Git checkout: `git pull --ff-only origin main`. Curl copy: replace the package from the `main` tarball. Passwords stay put.

## This machine

- NixOS launcher (not applied this session): `~/Documents/nixos/files/local-bin/wifi-login` matches `contrib/wifi-login`. It execs this repo. `configuration.nix` also symlinks `wlantron`. Do not run `apply-gen4.sh` unless asked. Until the next generation, `./build.sh` is what `~/.local/bin` runs.
- Saved passwords stay in `~/.config/hho-wifi/credentials.json`. Do not copy that file into git.
- Checked on this machine: unit tests, `--help`, and a local `./build.sh`. `wifi-login --status --iface wlp4s0` reported `HHO_WiFi` already authenticated. No portal POST. `eno1` was down, so `lan-login` was not run. Windows `build.ps1` was not run on Windows.
- 0.1.1: Windows credential save no longer uses `icacls`, and Enter at the save question stores the password. `wifi-login --update` pulls `main`. The Windows save path was not re-run on a Windows PC.
- 0.2.0: Android app in `android/`. Built 2026-11-03: `gradle test assembleDebug` succeeded. Debug demo release: https://github.com/Arcioth/wlantron/releases/tag/v0.2.0-debug (`wlantron-0.2.0-debug.apk`, prerelease, tag on `79b18ae`). No phone was attached. The phone must already be joined to `HHO_WiFi`.
- 0.2.1: Windows curl install failed for user `YİĞİT` because `Remove-Item` mishandles the 8.3 path `YT28B4~1`. `build.ps1` deletes with .NET instead, and the long Win32 helper was removed so the script stays short. Re-run the curl command. Not retested on Windows.
- 0.2.2 said signed in while normal sites still failed. 0.2.3 counts internet only when `example.com` loads from the Wi-Fi address, then asks Android to recheck. Debug demo: https://github.com/Arcioth/wlantron/releases/tag/v0.2.3-debug. Not tried on a phone in this session.
- 0.2.4 (2026-11-25): a Turkish-locale, Wi-Fi-only tablet on 0.2.2 got 192.168.137.76 (gateway 192.168.137.1) and DNS, but both TCP connects (gateway :80, detectportal's Fastly IP :80) timed out with no reset. No portal page, so no password was sent; 0.2.3 fails the same way. Likeliest cause: the tablet's current (randomized) MAC is not on the campus list. Also possible: a Windows Mobile Hotspot (192.168.137.1 is its default address) or a night cut at 23:10. No APK can log that device in. 0.2.4 explains it, stops after two silent hosts, sends the password only to an `/fgtauth` page or `magic` form on the same origin once per tap, tries the gateway last (fgtauth only), drops the API 30 DHCP call, and fixes "LOG İN". Checked in a cloud session: `PortalTest` on the JVM (22 tests) and a Kotlin type-check of every Android file against the API 35 framework classes. The APK was not built there: Google Maven (`dl.google.com`) was blocked. Build it here with the command in the README, using this machine's `~/.android/debug.keystore` so it installs over 0.2.3 (another key means uninstalling first and retyping the login).

## Next

- Build and sideload 0.2.4: `cd android && nix-shell --run "gradle test assembleDebug --no-daemon"`, then publish it like v0.2.3-debug.
- Settle the 0.2.4 ranking: run `wifi-login --status --iface wlp4s0` on HHO_WiFi and note the gateway. If it is not 192.168.137.x, the tablet was likely on a laptop hotspot. Ask which MAC was registered (factory or randomized) and what the tablet's network details show.

- On a Windows PC that already has the `HHO_WiFi` profile: run the curl build, then `wifi-login --status`, then `wifi-login`.
- Wired reset on Windows needs an elevated shell (`Restart-NetAdapter`). Login on an adapter that already has an address does not.

## Later

- If HHO_WiFi's gateway is not 192.168.137.1, add a hint shown only for that exact gateway: it is a Windows hotspot's default address.
- Android: a Wi-Fi settings button (`Settings.ACTION_WIFI_SETTINGS`, catch `ActivityNotFoundException`, keep it enabled while busy, `textAllCaps="false"`).
- Android: log address/prefix, DNS servers and `LinkProperties.getDomains()`; a live DNS check with `DnsResolver` and `FLAG_NO_CACHE_LOOKUP` (API 29+) to tell a cached answer from a working path.
- Android: a Turkish failure text in `AUTH_FAIL` once a real HHO failure page is captured; `values-tr` strings.
- Android: report a TLS handshake failure in plain words. Android 15 drops TLS 1.0/1.1 for targetSdk 35, and the desktop lowers OpenSSL to SECLEVEL=1 for this portal. Unverified for HHO.
- Android: `readHttp` edge cases (bare `\n\n` header end, 1xx replies, a reset inside the body). No effect on the 0.2.4 report.
- Android: run `lintDebug` with NewApi as a gate (`abortOnError=false` hid `getDhcpServerAddress`), and a signing config kept outside the repo so every build installs over the last.
- Android: rotation (overlapping runs, `freezesText` on the log).
- Desktop parity: `portal.py` still POSTs credentials to any form with credential fields and can POST up to 3 times; port the fgtauth/magic, same-origin and once-per-run rules. It also keeps only the first two errors and says "all probes timed out" when nothing errored.

Signed-off-by: Grok 4.7 (xAI) & Arcioth
