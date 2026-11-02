# Changelog

## 0.1.0 — 2026-11-02

- New repo at `~/Documents/wlantron`. The Linux captive-portal login that lived in `~/Documents/nixos/files/local-bin/wifi-login` is now this package.
- `wifi-login` and `wlantron` log in every connected adapter. `lan-login` is wired only. Same program, name-selected.
- Linux keeps `SO_BINDTODEVICE` and NetworkManager. Windows binds the adapter IPv4, sets `IP_UNICAST_IF`, and uses `netsh` plus `Get-NetAdapter`.
- Curl build: `build.sh` on Linux, `build.ps1` on Windows. Both byte-compile and install the three commands.
- Credentials stay in the old Linux path `~/.config/hho-wifi/credentials.json`.
