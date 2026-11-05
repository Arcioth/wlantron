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
- 0.2.2: Android Check failed on detectportal.firefox.com and Log in stayed on "Looking for Wi-Fi". HTTP is now a socket bound to the Wi-Fi network, with the gateway probed first. Debug demo: https://github.com/Arcioth/wlantron/releases/tag/v0.2.2-debug. Not tried on a phone in this session.

## Next

- On a Windows PC that already has the `HHO_WiFi` profile: run the curl build, then `wifi-login --status`, then `wifi-login`.
- Wired reset on Windows needs an elevated shell (`Restart-NetAdapter`). Login on an adapter that already has an address does not.

Signed-off-by: Grok 4.7 (xAI) & Arcioth
