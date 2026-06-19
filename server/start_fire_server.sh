#!/usr/bin/env bash
#
# start_fire_server.sh -- launch the fiber socket server (run in MSYS2 UCRT64).
#
#   cd /f/CTFireTest/fiber_socket_bridge
#   ./start_fire_server.sh [port]      # default port 5101
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="${TMEQUANT_REPO:-$(cd "$HERE/.." && pwd)/tme-quant}"
PORT="${1:-5101}"

if [[ "${MSYSTEM:-}" != "UCRT64" ]]; then
  echo "WARNING: not in the UCRT64 shell (MSYSTEM=${MSYSTEM:-unknown})."
  echo "The real backend may not load; you'll get the synthetic fallback."
fi
if [[ ! -d "$REPO/src/ctfire_py" ]]; then
  echo "ERROR: tme-quant repo not found at: $REPO  (set TMEQUANT_REPO to fix)"
  exit 1
fi

source "$HERE/venv_common.sh"
if ! ensure_venv; then
  echo "ERROR: venv missing. Run ./setup_fire_server.sh first."
  exit 1
fi

echo "Repo : $REPO"
echo "Venv : $VENV"
echo "Start: 127.0.0.1:$PORT   (Ctrl+C to stop, or send the SHUTDOWN command)"
exec env TMEQUANT_REPO="$REPO" MPLBACKEND=Agg \
  "$VENV_PY" "$HERE/fiber_socket_server.py" --repo "$REPO" --port "$PORT" --log INFO
