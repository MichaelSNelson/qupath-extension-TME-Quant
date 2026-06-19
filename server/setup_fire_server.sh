#!/usr/bin/env bash
#
# setup_fire_server.sh -- one-time Windows setup for the fiber socket server.
#
# RUN THIS IN THE "MSYS2 UCRT64" SHELL (Start menu -> "MSYS2 UCRT64").
# The compiled FIRE backend (fiber_backend.cp314-mingw_x86_64_ucrt_gnu.pyd) only
# loads under the UCRT64 Python 3.14 toolchain, so this must NOT be run from
# PowerShell, cmd, or the plain MSYS/MINGW64 shells.
#
#   cd /f/CTFireTest/fiber_socket_bridge
#   ./setup_fire_server.sh
#
set -euo pipefail

echo "== Fiber socket server setup (MSYS2 UCRT64) =="

# 1. Must be the UCRT64 environment.
if [[ "${MSYSTEM:-}" != "UCRT64" ]]; then
  echo "ERROR: this is the '${MSYSTEM:-unknown}' shell, not UCRT64."
  echo "Open 'MSYS2 UCRT64' from the Start menu and run this again."
  exit 1
fi

# 2. Install the C-extension stack the pipeline needs (pacman, not pip -- avoids
#    GCC build failures; same approach as fire_only_windows_setup.md).
echo "-- Installing UCRT64 Python packages via pacman ..."
pacman -S --needed --noconfirm \
  mingw-w64-ucrt-x86_64-python \
  mingw-w64-ucrt-x86_64-python-pip \
  mingw-w64-ucrt-x86_64-python-numpy \
  mingw-w64-ucrt-x86_64-python-scipy \
  mingw-w64-ucrt-x86_64-python-scikit-image \
  mingw-w64-ucrt-x86_64-python-scikit-learn \
  mingw-w64-ucrt-x86_64-python-opencv \
  mingw-w64-ucrt-x86_64-python-pandas \
  mingw-w64-ucrt-x86_64-python-matplotlib \
  mingw-w64-ucrt-x86_64-python-shapely \
  mingw-w64-ucrt-x86_64-python-tifffile \
  mingw-w64-ucrt-x86_64-python-openpyxl \
  mingw-w64-ucrt-x86_64-python-pillow \
  mingw-w64-ucrt-x86_64-python-imageio \
  mingw-w64-ucrt-x86_64-python-networkx

echo -n "-- System Python: "; python --version

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 2b. Create the isolated virtual environment (reuses the pacman packages; all
#     pip installs from here on go into the venv, never the system Python).
source "$HERE/venv_common.sh"
ensure_venv
echo -n "-- venv Python: "; "$VENV_PY" --version

# 3. Locate the tme-quant repo (default: F:\CTFireTest\tme-quant == sibling of
#    this folder). Override by exporting TMEQUANT_REPO before running.
REPO="${TMEQUANT_REPO:-$(cd "$HERE/.." && pwd)/tme-quant}"
if [[ ! -d "$REPO/src/ctfire_py" ]]; then
  echo "ERROR: tme-quant repo not found at: $REPO"
  echo "Copy the 'tme-quant' folder to F:\\CTFireTest\\tme-quant (so it sits next"
  echo "to fiber_socket_bridge), or export TMEQUANT_REPO=/f/path/to/tme-quant."
  exit 1
fi
echo "-- Using repo: $REPO"

# 4. Validate every import the server touches, including the real C++ backend.
#    If the (prebuilt) backend fails to load -- which happens when it was
#    compiled against different library versions than this machine has -- rebuild
#    it from source automatically, then re-validate. Testers run ONE command.
validate_backend() {
  PYTHONPATH="$REPO/src:$REPO/src/tme_quant/src" MPLBACKEND=Agg "$VENV_PY" - <<'PYEOF'
import importlib, sys
import numpy, PIL, cv2                       # server + synthetic fallback
try:
    fb = importlib.import_module("ctfire_py.fiber_backend")
except Exception as e:
    print("   backend load failed:", repr(e))
    sys.exit(2)
import tme_quant
from tme_quant.tme_analysis.pipelines.curvealign_ctfireMode_pipeline import (
    curvealign_ctfire_mode_pipeline,
)
print("   fiber_backend:", fb.__file__)
print("   ALL IMPORTS OK -- real FIRE backend loaded")
PYEOF
}

echo "-- Validating imports and the FIRE backend ..."
if ! validate_backend; then
  echo
  echo "-- The prebuilt FIRE engine did not load on this machine."
  echo "-- Rebuilding it from source (one-time; takes ~1 minute) ..."
  echo
  if [[ -x "$HERE/build_backend.sh" ]]; then
    "$HERE/build_backend.sh"
  else
    bash "$HERE/build_backend.sh"
  fi
  echo
  echo "-- Re-validating after rebuild ..."
  if ! validate_backend; then
    echo
    echo "ERROR: backend still not loading after rebuild."
    echo "Run ./diagnose_backend.sh and share the output."
    exit 2
  fi
fi

echo
echo "== Setup complete. =="
echo "Start the server with:   ./start_fire_server.sh"
echo "Smoke-test it with:      ./test_fire_server.sh   (in a second UCRT64 shell)"
