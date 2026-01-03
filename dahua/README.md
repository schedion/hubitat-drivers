# Dahua WhiteLight Hubitat Driver (HTTP Digest)

A Hubitat **native** Groovy driver to manually control the **white light / flood light** on Dahua cameras that expose the Dahua HTTP CGI API and require **HTTP Digest auth**.

This driver is designed for:
- Manual control via Hubitat UI (virtual switch-style)
- Optional polling to sync current state (On/Off) and brightness
- No Home Assistant, no RPC2 requirement

## Tested Camera
- `IPC-L46N-USA` (requires Digest; Basic auth returns `401` with `WWW-Authenticate: Digest ...`)
- White light status endpoint:
  - `GET /cgi-bin/coaxialControlIO.cgi?action=getStatus&channel=1`
  - Returns: `status.status.WhiteLight=On|Off`

## Core Endpoints Used

### 1) WhiteLight (On/Off)
- Status:
  - `/cgi-bin/coaxialControlIO.cgi?action=getStatus&channel=1`
  - Parse: `status.status.WhiteLight=On|Off`
- Control:
  - `/cgi-bin/coaxialControlIO.cgi?action=control&channel=1&info[0].Type=1&info[0].IO=1` (ON)
  - `/cgi-bin/coaxialControlIO.cgi?action=control&channel=1&info[0].Type=1&info[0].IO=2` (OFF)

Notes:
- `Type=1` is WhiteLight / Security light type used by this device
- IO values appear device-specific; on the L46N: `IO=1 => On`, `IO=2 => Off`

### 2) Brightness (Lighting_V2)
We set brightness by forcing `Lighting_V2` white light into manual state and setting `MiddleLight[0].Light`.

Example (profile modes 0/1/2):
- `/cgi-bin/configManager.cgi?action=setConfig&Lighting_V2[0][0][1].Mode=Manual&Lighting_V2[0][0][1].State=On&Lighting_V2[0][0][1].MiddleLight[0].Light=100`

Readback:
- `/cgi-bin/configManager.cgi?action=getConfig&name=Lighting_V2`
- Parse: `table.Lighting_V2[0][0][1].MiddleLight[0].Light=...`

## Why Digest Auth
Basic auth test:
```bash
curl -v -u "$CAM_USER:$CAM_PASS" \
"http://$CAM_IP/cgi-bin/coaxialControlIO.cgi?action=getStatus&channel=1"
```
Expected response:
```
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Digest ...
```
Camera responds `401` with `WWW-Authenticate: Digest ...`, so Digest is required.

## Hubitat Driver Features

### Capabilities
- Switch (on/off)
- SwitchLevel (brightness 0..100)
- Refresh
- Initialize

### Preferences
- Camera IP, Port, Username, Password
- Channel
- Polling interval (minutes, 0 disables)
- HTTP timeout seconds
- Max retries per request
- Debug logging

## Polling / State Sync

`refresh()` reads:
- WhiteLight on/off from `coaxialControlIO` status
- Brightness from `Lighting_V2`

## Digest Challenge Handling

The driver fetches the digest challenge (realm/nonce/qop/opaque) using a synchronous `httpGet` to capture the `WWW-Authenticate` header from the response.

## Quick Start
1. Copy the driver Groovy file into Hubitat:
   - Drivers Code → New Driver
2. Create a Virtual Device:
   - Type: the new driver
3. Set preferences:
   - camIP: IP only (no http://, no hostname)
   - camPort: 80
   - camUser / camPass
4. Click Save Preferences
5. Click Initialize or Refresh
6. Use On/Off and Level controls

## Troubleshooting

### Name or service not known
Hubitat cannot resolve or reach the host:
- Use raw IP only (e.g. 192.168.80.35)
- Ensure Hubitat can route to that subnet/VLAN

### Digest challenge missing
If the camera never returns `WWW-Authenticate: Digest ...`:
- Wrong endpoint/port
- Not actually Digest on that interface
- Firewall/connection issue

### Light doesn’t change but API returns OK
Some Dahua firmwares acknowledge config changes but won’t actuate until:
- FloodLightMode is set appropriately (often Mode=2 manual)
- The correct control endpoint is used (`coaxialControlIO` vs `Lighting_V2`)

## Security Notes
- Driver stores password in device preferences (Hubitat standard).
- Put cameras on an isolated VLAN and allow Hubitat access only.
- Consider creating a camera user with minimal permissions.
