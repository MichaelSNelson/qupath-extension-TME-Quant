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


def _ucrt64_bin_dirs():
    """Candidate <MSYS2>\\ucrt64\\bin directories, most-reliable first.

    Derived from the running interpreter (the mingw python.exe lives IN
    ucrt64\\bin) and MSYS2_ROOT, so a non-default MSYS2 install location works;
    C:\\msys64 is only the last-resort fallback.
    """
    dirs = [os.path.dirname(sys.executable)]  # the actual ucrt64\bin of this python
    root = os.environ.get("MSYS2_ROOT")
    if root:
        dirs.append(os.path.join(root, "ucrt64", "bin"))
    dirs.append(r"C:\msys64\ucrt64\bin")
    return dirs


def _preload_mingw_runtime(bin_dirs):
    """Pin the correct ucrt64 GCC runtime DLLs into this process by FULL PATH.

    The spawned worker does not inherit the launcher's ucrt64-first PATH, so
    `fiber_backend`'s dependencies can otherwise resolve to a wrong-version
    libstdc++/libgomp already on the machine, giving "DLL load failed ... the
    specified procedure could not be found" (a symbol skew, not a missing file).
    Loading the ucrt64 copies first — by absolute path — means Windows reuses
    those handles when the .pyd asks for them by name, regardless of search order.
    Registering the DLL DIRECTORY is not enough; an incompatible copy elsewhere
    can still win. Ordered so each lib's dependency is already resident.
    """
    if os.name != "nt":
        return
    import ctypes
    # The real ucrt64\bin is the first candidate that actually holds the C++
    # runtime (a venv's Scripts dir does not), so probe for libstdc++-6.dll.
    chosen = next(
        (d for d in bin_dirs if d and os.path.isfile(os.path.join(d, "libstdc++-6.dll"))),
        None,
    )
    if not chosen:
        return
    for name in ("libwinpthread-1.dll", "libgcc_s_seh-1.dll", "libstdc++-6.dll",
                 "libgomp-1.dll", "libquadmath-0.dll", "libssp-0.dll"):
        p = os.path.join(chosen, name)
        if os.path.isfile(p):
            try:
                ctypes.WinDLL(p)
            except OSError:
                pass  # optional runtime lib; skip if this build doesn't need it


def _setup(repo_str):
    repo = Path(repo_str)
    for c in (repo / "src", repo / "src" / "tme_quant" / "src", repo / "src" / "ctfire_py"):
        if c.is_dir() and str(c) not in sys.path:
            sys.path.insert(0, str(c))
    os.environ.setdefault("MPLBACKEND", "Agg")
    if os.name == "nt":
        ucrt_dirs = _ucrt64_bin_dirs()
        seen = set()
        for d in (str(repo / "src" / "ctfire_py"), *ucrt_dirs):
            if d and d not in seen and os.path.isdir(d):
                seen.add(d)
                try:
                    os.add_dll_directory(d)
                except Exception:
                    pass
        # Front-load PATH with the first REAL ucrt64\bin (the one holding the C++
        # runtime), not merely the first existing dir -- in a venv the running
        # interpreter's own folder is Scripts\, which has none of the GCC DLLs.
        for d in ucrt_dirs:
            if os.path.isfile(os.path.join(d, "libstdc++-6.dll")):
                os.environ["PATH"] = d + os.pathsep + os.environ.get("PATH", "")
                break
        # Pin the correct GCC runtime BEFORE fiber_backend is imported.
        _preload_mingw_runtime(ucrt_dirs)


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
