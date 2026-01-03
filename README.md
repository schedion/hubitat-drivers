# Hubitat Drivers

Collection of Hubitat Groovy drivers for local-first integrations.

## Drivers

### Dahua WhiteLight (HTTP Digest)
- Folder: `dahua/`
- Driver: `dahua/DahuaWhiteLightDigest.groovy`
- README: `dahua/README.md`
- Controls Dahua camera white light (on/off + brightness) via HTTP Digest auth.
- Tested on IPC-L46N-USA floodlight camera.

### Valetudo REST Robot
- Folder: `valetudo/`
- Driver: `valetudo/valetudo.groovy`
- Targets Valetudo REST API v2 for robot state, fan presets, and commands.
- Required preference: `robotIP` (IP only).

## Quick Start
1. Open the driver file you want and copy it into Hubitat:
   - Hubitat → Drivers Code → New Driver
2. Create a Virtual Device using the new driver.
3. Set required preferences and click Save Preferences.
4. Use Initialize/Refresh to sync state.

## Repository Layout
- `dahua/` Dahua white light driver + docs + tools.
- `valetudo/` Valetudo robot driver.
- `AGENTS.md` Agent workflow notes for this repo.

## License
See `LICENSE`.
