"""
fire_worker.py -- the isolated FIRE worker run in a subprocess by the server.

Why a separate module: the main server imports numpy at the top, so when a
worker is spawned and re-imports the main module, numpy's DLLs (a particular
libstdc++/libgomp) load BEFORE we can set up the DLL search -- and then the
compiled `fiber_backend` fails with "DLL load failed ... procedure could not be
found" (a version skew). This module imports NOTHING heavy at the top, so the
initializer can register the ucrt64 DLL directory and load `fiber_backend`
FIRST (claiming the correct runtime DLLs); numpy/scipy are imported afterwards
and reuse them.

A segfault in here only kills this worker; the parent server survives and
respawns a fresh worker.
"""

import os
import sys
from pathlib import Path

# Set lazily in worker_init, AFTER fiber_backend has loaded.
np = None
_FIRE = None
_BACKEND_OK = False
_BACKEND_ERR = None


def _setup(repo_str):
    repo = Path(repo_str)
    for c in (repo / "src", repo / "src" / "tme_quant" / "src", repo / "src" / "ctfire_py"):
        if c.is_dir() and str(c) not in sys.path:
            sys.path.insert(0, str(c))
    os.environ.setdefault("MPLBACKEND", "Agg")
    if os.name == "nt":
        ucrt = r"C:\msys64\ucrt64\bin"
        for d in (os.path.dirname(sys.executable), str(repo / "src" / "ctfire_py"), ucrt):
            if d and os.path.isdir(d):
                try:
                    os.add_dll_directory(d)
                except Exception:
                    pass
        if os.path.isdir(ucrt):
            os.environ["PATH"] = ucrt + os.pathsep + os.environ.get("PATH", "")


def worker_init(repo_str):
    """Initializer: load the compiled backend BEFORE numpy/scipy."""
    global _FIRE, np, _BACKEND_OK, _BACKEND_ERR
    _setup(repo_str)
    # CRITICAL ordering: fiber_backend first so it binds the correct GCC runtime
    # DLLs from ucrt64\bin; numpy/scipy (pulled in by fire_2d_angle) reuse them.
    try:
        import fiber_backend  # noqa: F401
        _BACKEND_OK = True
    except Exception as e:  # noqa: BLE001
        import traceback
        _BACKEND_ERR = f"{type(e).__name__}: {e}\n{traceback.format_exc()}"
    from ctfire_py.fire_2d_angle import fire_2d_angle  # imports scipy/numpy here
    import numpy as _np

    np = _np
    _FIRE = fire_2d_angle


def worker_probe():
    """Return (backend_loaded, real_error_or_None)."""
    return _BACKEND_OK, _BACKEND_ERR


# --- fiber reconstruction (mirror of the server's _fibers_from_fire) ---
def _angle_from_endpoints(p0, p1):
    drow = p1[0] - p0[0]
    dcol = p1[1] - p0[1]
    return float(np.degrees(np.arctan2(-drow, dcol)) % 180.0)


def _approx_width(fa_verts, Ra):
    try:
        idx = [v for v in fa_verts if 0 <= v < len(Ra)]
        if not idx:
            return float("nan")
        return float(2.0 * np.mean(np.asarray(Ra)[idx]))
    except Exception:  # noqa: BLE001
        return float("nan")


def _fibers_from_fire(data, ll1):
    Fai = data.get("Fai", [])
    Xai = np.asarray(data.get("Xai", np.empty((0, 3))))
    Fa = data.get("Fa", [])
    Ra = np.asarray(data.get("Ra", np.array([])))
    M = data.get("M", {})
    L = np.asarray(M.get("L", np.array([])))

    fibers = []
    out_id = 0
    for i in range(len(Fai)):
        if i >= len(L):
            continue
        arc_len = float(L[i])
        if arc_len <= ll1:
            continue
        verts = Fai[i].get("v", [])
        if len(verts) < 2:
            continue
        vidx = [v for v in verts if 0 <= v < len(Xai)]
        if len(vidx) < 2:
            continue
        path_rc = Xai[vidx, :2]
        points = [[float(p[1]), float(p[0])] for p in path_rc]
        p_start, p_end = path_rc[0], path_rc[-1]
        end_len = float(np.linalg.norm(p_end - p_start))
        straightness = (end_len / arc_len) if arc_len > 0 else float("nan")
        mid = path_rc[len(path_rc) // 2]
        center = [float(mid[1]), float(mid[0])]
        fa_verts = Fa[i].get("v", []) if i < len(Fa) else []
        width = _approx_width(fa_verts, Ra)
        fibers.append(
            {
                "id": out_id,
                "points": points,
                "center": center,
                "angle": _angle_from_endpoints(p_start, p_end),
                "length_arc": arc_len,
                "length_end": end_len,
                "width": width,
                "straightness": straightness,
                "tacs": None,
            }
        )
        out_id += 1
    return fibers


def worker_fire(fire_params_value, ll1, image):
    """Run FIRE + reconstruct polylines. `image` is the final 0-255 float image
    (normalisation handled by the server's analyze_real). May segfault (contained)."""
    im3 = np.ascontiguousarray(image, dtype=np.float32)[np.newaxis, :, :]
    data = _FIRE(p=fire_params_value, im=im3, plotflag=0)
    if not data:
        return []
    return _fibers_from_fire(data, ll1)
