# Agent Instructions (Codex / VS Code)

This file explains the current state of the work and how to continue it in an IDE-driven workflow.

## Goal
Maintain a Hubitat Groovy driver that:
1. Controls Dahua white light (On/Off) reliably via HTTP Digest auth.
2. Controls brightness (0–100) via Lighting_V2.
3. Syncs state (polling + refresh).
4. Has robust retry/timeout logic.
5. Has clear diagnostics for network vs auth vs parsing failures.

## Current Known-Working Device Behavior (IPC-L46N-USA)

### Status
`GET /cgi-bin/coaxialControlIO.cgi?action=getStatus&channel=1`

Returns:
- `status.status.WhiteLight=On|Off`
- `status.status.Speaker=Off` (ignored)

### Control
`GET /cgi-bin/coaxialControlIO.cgi?action=control&channel=1&info[0].Type=1&info[0].IO=1`  -> turns white light ON  
`GET /cgi-bin/coaxialControlIO.cgi?action=control&channel=1&info[0].Type=1&info[0].IO=2`  -> turns white light OFF

### Brightness / Lighting_V2
Read:
`GET /cgi-bin/configManager.cgi?action=getConfig&name=Lighting_V2`

Write (for profile modes 0,1,2):
`GET /cgi-bin/configManager.cgi?action=setConfig&Lighting_V2[0][P][1].Mode=Manual&Lighting_V2[0][P][1].State=On&Lighting_V2[0][P][1].MiddleLight[0].Light=XX`

Where:
- `P` in {0,1,2}
- `[1]` is WhiteLight (as observed on the device)
- `XX` is 0..100

Important: controlling On/Off should be done via `coaxialControlIO` because writing Lighting_V2 alone may not actuate on some firmwares.

## Digest Auth Requirements

Basic auth returns:
- `401 Unauthorized`
- `WWW-Authenticate: Digest realm="...", qop="auth", nonce="...", opaque="...", algorithm="MD5"`

Therefore driver must:
- Fetch digest challenge
- Generate Authorization header per RFC 7616 style MD5 digest (qop=auth)
- Track nonce-count (nc) and cnonce

## Hubitat Implementation Constraints
- Hubitat Groovy environment is not a full JVM app:
  - Use `@Field` for constants (NOT `private static final` top-level)
  - Cron expression parsing can be finicky; `schedule("0 */${m} * ? * *", ...)` works, but ensure `${m}` interpolation is valid Groovy string.
- `httpGet` does not reliably expose response headers for 401 via exception handling.
- Use `asynchttpGet` to capture headers (WWW-Authenticate) reliably.

## Driver Architecture Summary

### Capabilities
- Switch, SwitchLevel, Refresh, Initialize

### Key Flows

#### `initialize()`
- setup polling
- refresh once

#### `on()`
- setLevel(currentLevel or 100)
- coaxialControl(true)
- refresh shortly after

#### `off()`
- coaxialControl(false)
- refresh shortly after

#### `setLevel(lvl)`
- clamp 0..100
- if 0 -> off
- else:
  - write Lighting_V2 for profile modes 0,1,2
  - update Hubitat level event
  - ensure switch is "on" logically

#### `refresh()`
- Read coaxial status; update switch state
- Read Lighting_V2; update level state

### Digest Challenge
- Fetch via `asynchttpGet` to any endpoint that returns 401 + WWW-Authenticate (systemInfo is fine).
- Parse header into map (realm/nonce/qop/opaque/algorithm)
- Cache in `state.digestChallenge`
- Maintain `state.ncInt`

### Retry / Timeout
- Preferences:
  - `httpTimeoutSecs`
  - `maxRetries`
- On likely 401 -> clear cached challenge and re-fetch
- On timeout -> retry
- On DNS errors (`Name or service not known`) -> throw explicit network error

## Development Workflow in VS Code

### Repo layout suggestion
