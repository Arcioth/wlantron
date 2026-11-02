# wlantron handoff

Started 2026-11-02.

GitHub: https://github.com/Arcioth/wlantron (public, Apache-2.0).

Source of truth: `~/Documents/wlantron`. `wifi-login`, `lan-login`, and `wlantron` are one Python 3 package. Stdlib only.

## Install

Linux curl build:

```bash
curl -fsSL https://raw.githubusercontent.com/Arcioth/wlantron/main/build.sh | sh
```

Windows curl build:

```bat
curl -fsSL -o %TEMP%\wlantron-build.ps1 https://raw.githubusercontent.com/Arcioth/wlantron/main/build.ps1 && powershell -NoProfile -ExecutionPolicy Bypass -File %TEMP%\wlantron-build.ps1
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

## Next

- On a Windows PC that already has the `HHO_WiFi` profile: run the curl build, then `wifi-login --status`, then `wifi-login`.
- Wired reset on Windows needs an elevated shell (`Restart-NetAdapter`). Login on an adapter that already has an address does not.

Signed-off-by: Grok 4.7 (xAI) & Arcioth
