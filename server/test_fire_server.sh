#!/usr/bin/env bash
#
# test_fire_server.sh -- send a test region to a running server and print the
# fibers (no QuPath needed). Run in a SECOND MSYS2 UCRT64 shell while the server
# from start_fire_server.sh is running.
#
#   ./test_fire_server.sh [port] [image.tif]
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="${TMEQUANT_REPO:-$(cd "$HERE/.." && pwd)/tme-quant}"
PORT="${1:-5101}"
IMG="${2:-$REPO/tests/test_images/real1.tif}"

source "$HERE/venv_common.sh"
ensure_venv

echo "Image: $IMG"
exec env TMEQUANT_REPO="$REPO" MPLBACKEND=Agg "$VENV_PY" "$HERE/fiber_socket_client_test.py" \
  --port "$PORT" --image "$IMG" --x 0 --y 0 --w 400 --h 400 \
  --overlay "$HERE/roundtrip_overlay.png"
