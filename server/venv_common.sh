#!/usr/bin/env bash
#
# venv_common.sh -- sourced by the other scripts. Defines the virtual environment
# used for the fiber server and a helper to create it.
#
# Isolation policy:
#   * The C-extension stack (numpy/scipy/opencv/...) is installed with PACMAN into
#     the MSYS2 system Python -- that is the OS package manager and the only way to
#     get those on MSYS2/GCC. (Cleanly managed; `pacman -R` removes them.)
#   * Everything pip touches goes into THIS venv, never the system Python. The venv
#     is created with --system-site-packages so it REUSES the pacman packages
#     without copying or recompiling them.
#
# Override the location by exporting FIBER_VENV before running.

_vc_here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV="${FIBER_VENV:-$(cd "$_vc_here/.." && pwd)/venv_fire}"
VENV_PY="$VENV/bin/python"

ensure_venv() {
  if [[ ! -x "$VENV_PY" ]]; then
    echo "-- Creating virtual environment at:"
    echo "     $VENV"
    echo "   (--system-site-packages: reuses the pacman packages; pip stays isolated here)"
    python -m venv --system-site-packages "$VENV"
  fi
  if [[ ! -x "$VENV_PY" ]]; then
    echo "ERROR: failed to create venv at $VENV"
    return 1
  fi
}
