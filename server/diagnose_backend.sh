#!/usr/bin/env bash
#
# diagnose_backend.sh -- figure out WHY the compiled FIRE backend (.pyd) won't
# import. Run in the MSYS2 UCRT64 shell:
#
#   cd /f/CTFireTest/fiber_socket_bridge
#   ./diagnose_backend.sh
#
# Paste the whole output back if you want help interpreting it.
#
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="${TMEQUANT_REPO:-$(cd "$HERE/.." && pwd)/tme-quant}"

# Use the venv python if it exists (the server's actual interpreter), else system.
source "$HERE/venv_common.sh" 2>/dev/null || true
PY="python"
if [[ -x "${VENV_PY:-}" ]]; then
  PY="$VENV_PY"
fi

echo "================ environment ================"
echo "MSYSTEM = ${MSYSTEM:-unknown}   (must be UCRT64)"
echo -n "python  = "; "$PY" --version 2>&1
echo "which   = $PY"
echo "repo    = $REPO"
echo

echo "================ the .pyd file ================"
PYD_DIR="$REPO/src/ctfire_py"
if [[ -d "$PYD_DIR" ]]; then
  ls -la "$PYD_DIR"/*.pyd "$PYD_DIR"/*.so 2>/dev/null || echo "  (no .pyd/.so found in $PYD_DIR !)"
else
  echo "  ctfire_py dir not found: $PYD_DIR"
fi
PYD="$(ls "$PYD_DIR"/*.pyd 2>/dev/null | head -1)"
echo

echo "================ Python's accepted extension suffixes ================"
"$PY" -c "import importlib.machinery as m; [print('  ', s) for s in m.EXTENSION_SUFFIXES]"
echo "  (the .pyd's suffix must be one of these, else Python ignores the file)"
echo

if [[ -n "${PYD:-}" ]]; then
  echo "================ dependent DLLs (look for 'not found') ================"
  if command -v ldd >/dev/null 2>&1; then
    ldd "$PYD" 2>&1 | sed 's/^/  /'
  else
    echo "  'ldd' not available; install with: pacman -S mingw-w64-ucrt-x86_64-tools-git"
  fi
  echo
fi

echo "================ direct import (the real error) ================"
# Reproduce EXACTLY what ctfire_py/fire_2d_angle.py does (line 28-29):
#   sys.path.insert(0, <ctfire_py dir>); import fiber_backend
# but without the try/except that hides the real error.
REPO="$REPO" PYTHONPATH="$REPO/src:$REPO/src/tme_quant/src" "$PY" - <<'PYEOF'
import os, sys, traceback
repo = os.environ["REPO"]
sys.path.insert(0, os.path.join(repo, "src", "ctfire_py"))
try:
    import numpy
    print("  numpy:", numpy.__version__)
except Exception:
    traceback.print_exc()
try:
    import fiber_backend
    print("  SUCCESS:", fiber_backend.__file__)
    print("  exports:", [x for x in dir(fiber_backend) if not x.startswith('_')])
except Exception:
    print("  IMPORT FAILED -- real traceback:")
    traceback.print_exc()
PYEOF
echo
echo "================ done ================"
