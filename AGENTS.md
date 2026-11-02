# wlantron

School FortiGate captive-portal login. Linux and Windows. The user already runs this as `wifi-login` / `lan-login`.

## Do

- Keep the portal POST limited to the signed-in user's own username and password.
- Keep Linux on `SO_BINDTODEVICE` plus NetworkManager. Keep Windows on a bind to that adapter's IPv4 plus `IP_UNICAST_IF` (31) plus `netsh` / `Get-NetAdapter`.
- `lan-login` is wired-only. `wifi-login` and `wlantron` are the smart default (every connected adapter). The string `wlantron` contains `lan` and must not be treated as the wired alias. The check is an exact name match in `cli.py`.
- Credentials stay in `~/.config/hho-wifi/credentials.json` on Linux so the old file still works. Windows uses `%APPDATA%\hho-wifi\credentials.json`. Never commit that file. Never log the password or the POST body.
- Stdlib only. Python 3.10+.
- The curl build is `build.sh` (Linux) and `build.ps1` (Windows). A checkout install points launchers at the tree. A piped install copies into the prefix.
- Tests: `python3 -m unittest discover -s tests -v` from the repo root. They must not touch the real credential file and must not hit the network.

## Do not

- Add a bypass, a shared-password grabber, or anything aimed at other clients on the portal.
- Hardcode a username or password.
- Rebuild NixOS (`apply-gen4.sh`) unless the user asks. The generation launcher is `contrib/wifi-login`, copied to `~/Documents/nixos/files/local-bin/wifi-login`. It execs this repo. `configuration.nix` symlinks `lan-login` and `wlantron`.
- Reformat unrelated Documents trees.

## Names

Default Wi-Fi profile: `HHO_WiFi`. This machine's Wi-Fi is `wlp4s0`, wired is `eno1`. Windows profile and adapter names differ (`Wi-Fi`, `Ethernet`).
