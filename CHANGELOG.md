# Changelog

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
