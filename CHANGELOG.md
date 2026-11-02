# Changelog

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
