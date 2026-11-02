# wlantron

FortiGate captive-portal login for the academy Wi-Fi (`HHO_WiFi`) and the wired school LAN. One program, three commands:

| Command | What it logs in |
|---|---|
| `wifi-login` | Every connected adapter. Brings `HHO_WiFi` up if Wi-Fi has no address. |
| `lan-login` | Wired Ethernet only. |
| `wlantron` | Same as `wifi-login`. |

The firewall authenticates each client IP, so Wi-Fi and LAN each need their own login. Saved browser `/fgtauth?` links go stale. This asks the network for a fresh one and posts your own username and password.

A VPN or phone tether can own the default route. Linux pins each socket to the adapter with `SO_BINDTODEVICE`. Windows binds the socket to that adapter's IPv4 address and sets `IP_UNICAST_IF`. Portal traffic stays on the school NIC.

## Build with curl

Linux:

```bash
curl -fsSL https://raw.githubusercontent.com/Arcioth/wlantron/main/build.sh | sh
```

Windows (cmd):

```bat
curl -fsSL -o %TEMP%\wlantron-build.ps1 https://raw.githubusercontent.com/Arcioth/wlantron/main/build.ps1 && powershell -NoProfile -ExecutionPolicy Bypass -File %TEMP%\wlantron-build.ps1
```

Windows (PowerShell):

```powershell
curl.exe -fsSL -o $env:TEMP\wlantron-build.ps1 https://raw.githubusercontent.com/Arcioth/wlantron/main/build.ps1; powershell -NoProfile -ExecutionPolicy Bypass -File $env:TEMP\wlantron-build.ps1
```

Both commands need Python 3.10 or newer. They byte-compile the package and install `wifi-login`, `lan-login`, and `wlantron`.

| | Linux | Windows |
|---|---|---|
| Install dir | `~/.local/bin` | `%LOCALAPPDATA%\wlantron\bin` (added to the user Path) |
| From a checkout | `./build.sh` | `powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1` |
| Prefix override | `WLANTRON_PREFIX` | `WLANTRON_PREFIX` |

A checkout install points the commands at that tree. The curl command copies the package under the prefix.

Update an install you already have (a git checkout or a curl copy). Passwords are left where they are:

```bash
wifi-login --update
```

`wifi-login update` is the same command. On a git checkout this is `git pull --ff-only`. On a curl install it downloads `main` and replaces the package files.

## Use

```bash
wifi-login --configure     # save username and password
wifi-login --update        # pull the latest main from GitHub
wifi-login                 # log in connected Wi-Fi and LAN
lan-login                  # wired only
wifi-login --status
wifi-login --reset         # drop the link, take a new lease, log in
wifi-login --browser       # open a fresh portal URL instead of posting
wifi-login --ask           # type the password this time
wifi-login --iface eno1    # one adapter (Windows names: "Wi-Fi", "Ethernet")
```

The Wi-Fi profile must already exist. Linux uses the NetworkManager connection `HHO_WiFi`. Windows uses a saved WLAN profile of that same name. `--ssid` selects a different profile.

Credentials are plaintext:

| OS | File |
|---|---|
| Linux | `~/.config/hho-wifi/credentials.json` (mode `0600`) |
| Windows | `%APPDATA%\hho-wifi\credentials.json` |

On Windows, Enter at the save question stores the password. `n` skips it. If Roaming AppData cannot be written, the file is tried under `%LOCALAPPDATA%\hho-wifi` and then `%USERPROFILE%\.config\hho-wifi`. A password already in one of those folders is still read.

`wifi-login --clear-saved` deletes the file. Nothing in this repo stores a password.

On Windows, joining Wi-Fi or resetting Ethernet can ask for an administrator. A login on an adapter that already has an address does not.

## Layout

```
build.sh              Linux curl build
build.ps1             Windows curl build
wlantron/             Python package (stdlib only)
contrib/wifi-login    NixOS launcher. Symlinks supply the other names.
```

Apache-2.0.
