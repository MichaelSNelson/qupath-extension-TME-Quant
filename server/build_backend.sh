#!/usr/bin/env bash
#
# build_backend.sh -- compile the FIRE C++ backend (.pyd) on THIS Windows
# machine so it matches the locally-installed MSYS2 UCRT64 libraries.
#
# Fixes: "ImportError: DLL load failed while importing fiber_backend:
#         The specified procedure could not be found."
# (that error means the *prebuilt* .pyd was built against different library
#  versions than the ones on this machine.)
#
# RUN IN THE MSYS2 UCRT64 SHELL:
#   cd /f/CTFireTest/fiber_socket_bridge
#   ./build_backend.sh
#
set -euo pipefail

echo "== Rebuild FIRE backend from source (MSYS2 UCRT64) =="

if [[ "${MSYSTEM:-}" != "UCRT64" ]]; then
  echo "ERROR: open the 'MSYS2 UCRT64' shell and run this there (MSYSTEM=${MSYSTEM:-unknown})."
  exit 1
fi

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="${TMEQUANT_REPO:-$(cd "$HERE/.." && pwd)/tme-quant}"
CPP="$REPO/src/ctfire_py/CPP"
DEST="$REPO/src/ctfire_py"
if [[ ! -d "$CPP" ]]; then
  echo "ERROR: C++ source not found at $CPP"
  echo "Make sure the tme-quant folder is at F:\\CTFireTest\\tme-quant"
  exit 1
fi

# 1. Build tools. gcc brings libgomp (OpenMP); make drives the compile.
echo "-- Installing compiler + pip + make (pacman) ..."
pacman -S --needed --noconfirm \
  mingw-w64-ucrt-x86_64-gcc \
  mingw-w64-ucrt-x86_64-python-pip \
  make

# Build inside the isolated venv so pip (pybind11) never touches system Python.
source "$HERE/venv_common.sh"
ensure_venv

# pybind11 (header-only, build-time only) via pip INTO THE VENV (no
# --break-system-packages needed -- the venv is not externally managed).
if ! "$VENV_PY" -m pybind11 --includes >/dev/null 2>&1; then
  echo "-- Installing pybind11 into the venv ..."
  "$VENV_PY" -m pip install pybind11 >/dev/null 2>&1 || true
fi
"$VENV_PY" -m pybind11 --includes >/dev/null 2>&1 || {
  echo "ERROR: pybind11 not available in the venv. Try:"
  echo "    $VENV_PY -m pip install pybind11"
  exit 1
}

# 2. Compile. The Makefile calls `python`, so put the venv's python first on PATH
#    (its EXT_SUFFIX matches the system interpreter, so the .pyd is named right).
echo "-- Compiling (4 C++ files; takes ~1 minute) ..."
cd "$CPP"
PATH="$VENV/bin:$PATH" make -f Makefile.ucrt64 clean >/dev/null 2>&1 || true
PATH="$VENV/bin:$PATH" make -f Makefile.ucrt64

NEW="$(ls -t fiber_backend*.pyd 2>/dev/null | head -1)"
if [[ -z "${NEW:-}" ]]; then
  echo "ERROR: build produced no .pyd. Scroll up for the compiler error."
  exit 1
fi
echo "-- Built: $NEW"

# 3. Put it next to fire_2d_angle.py (overwrites the incompatible prebuilt one).
cp -f "$NEW" "$DEST/"
echo "-- Installed to: $DEST/$NEW"

# 4. Validate it actually loads now (using the venv python).
echo "-- Validating ..."
cd "$DEST"
"$VENV_PY" -c "import sys; sys.path.insert(0, '.'); import fiber_backend; \
print('   SUCCESS:', fiber_backend.__file__); \
print('   exports:', [x for x in dir(fiber_backend) if not x.startswith('_')])"

echo
echo "== Backend rebuilt and loads. Now run:  ./setup_fire_server.sh  =="
