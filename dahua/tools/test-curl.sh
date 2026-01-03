#!/usr/bin/env bash
set -euo pipefail

# === Config ===
: "${CAM_IP:?set CAM_IP (e.g. 192.168.80.35)}"
: "${CAM_USER:?set CAM_USER}"
: "${CAM_PASS:?set CAM_PASS}"

CAM_PORT="${CAM_PORT:-80}"
CHANNEL="${CHANNEL:-1}"     # Dahua coaxialControlIO channel parameter is usually 1-based
CAM_CHANNEL="${CAM_CHANNEL:-0}"  # Lighting_V2 uses 0 for single-channel cams typically
TIMEOUT="${TIMEOUT:-10}"

# Profiles to write for brightness (0=day,1=night,2=scene)
PROFILES="${PROFILES:-0 1 2}"

BASE="http://${CAM_IP}:${CAM_PORT}"

curl_d() {
  # -sS: quiet but show errors, -g: don't glob [], --digest: digest auth, --max-time: timeout
  curl -sS -g --digest -u "${CAM_USER}:${CAM_PASS}" --max-time "${TIMEOUT}" "${BASE}$1"
}

die() { echo "ERROR: $*" >&2; exit 1; }

get_status() {
  curl_d "/cgi-bin/coaxialControlIO.cgi?action=getStatus&channel=${CHANNEL}"
}

get_whitelight_state() {
  local out
  out="$(get_status)"
  # Expect: status.status.WhiteLight=On|Off
  echo "$out" | awk -F= '/status\.status\.WhiteLight=/{print $2}' | head -n1
}

on() {
  curl_d "/cgi-bin/coaxialControlIO.cgi?action=control&channel=${CHANNEL}&info[0].Type=1&info[0].IO=1"
}

off() {
  curl_d "/cgi-bin/coaxialControlIO.cgi?action=control&channel=${CHANNEL}&info[0].Type=1&info[0].IO=2"
}

get_lighting_v2() {
  curl_d "/cgi-bin/configManager.cgi?action=getConfig&name=Lighting_V2"
}

get_brightness() {
  # Reads one profile (0) by default; override with PROFILE env if needed
  local profile="${PROFILE:-0}"
  get_lighting_v2 | awk -F= -v p="$profile" '
    $1 ~ "table\\.Lighting_V2\\[" ENVIRON["CAM_CHANNEL"] "\\]\\[" p "\\]\\[1\\]\\.MiddleLight\\[0\\]\\.Light" {print $2; exit}
  '
}

set_brightness() {
  local level="${1:-}"
  [[ -n "$level" ]] || die "Usage: $0 set-brightness <0..100>"
  [[ "$level" =~ ^[0-9]+$ ]] || die "Brightness must be integer 0..100"
  (( level >= 0 && level <= 100 )) || die "Brightness must be 0..100"

  for p in ${PROFILES}; do
    curl_d "/cgi-bin/configManager.cgi?action=setConfig&Lighting_V2[${CAM_CHANNEL}][${p}][1].Mode=Manual&Lighting_V2[${CAM_CHANNEL}][${p}][1].State=On&Lighting_V2[${CAM_CHANNEL}][${p}][1].MiddleLight[0].Light=${level}" >/dev/null
  done
  echo "OK (set brightness=${level} for profiles: ${PROFILES})"
}

show_summary() {
  echo "=== Camera: ${CAM_IP}:${CAM_PORT}  channel(coax)=${CHANNEL}  channel(lighting)=${CAM_CHANNEL} ==="
  echo "--- coaxial status ---"
  get_status
  echo "--- parsed whitelight ---"
  echo "WhiteLight=$(get_whitelight_state || true)"
  echo "--- parsed brightness (PROFILE=${PROFILE:-0}) ---"
  echo "Brightness=$(get_brightness || true)"
}

usage() {
  cat <<EOF
Usage:
  CAM_IP=... CAM_USER=... CAM_PASS=... $0 <command> [args]

Commands:
  status                 Show raw coaxial status + parsed WhiteLight + brightness
  on                     Turn white light ON (coaxialControlIO)
  off                    Turn white light OFF (coaxialControlIO)
  set-brightness N       Set brightness 0..100 via Lighting_V2 for PROFILES (default: "0 1 2")
  get-brightness         Read brightness for PROFILE (default: 0)

Env overrides:
  CAM_PORT=80
  CHANNEL=1              (coaxialControlIO channel, usually 1)
  CAM_CHANNEL=0          (Lighting_V2 channel index, usually 0)
  PROFILES="0 1 2"       (which profiles to write for brightness)
  PROFILE=0              (which profile to read brightness from)
  TIMEOUT=10             (curl max seconds)
EOF
}

cmd="${1:-}"
shift || true

case "$cmd" in
  status) show_summary ;;
  on)     on; echo; show_summary ;;
  off)    off; echo; show_summary ;;
  set-brightness) set_brightness "${1:-}"; show_summary ;;
  get-brightness) echo "$(get_brightness || true)" ;;
  ""|-h|--help|help) usage ;;
  *) die "Unknown command: $cmd" ;;
esac