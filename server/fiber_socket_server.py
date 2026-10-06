#!/usr/bin/env python3
"""
fiber_socket_server.py
======================

A small TCP socket server that exposes the TMEQuant FIRE-only fiber-extraction
pipeline to QuPath (or any client), modelled on the wire protocol used by
`microscope_command_server` <-> `qupath-extension-qpsc`:

  * 8-byte fixed-length ASCII command headers (padded with '_')
  * big-endian 4-byte length prefixes for variable-length payloads

QuPath sends the pixels of a RegionRequest (as a PNG) plus a small JSON header;
the server runs the FIRE pipeline and returns fibers as line geometry + per-fiber
properties that QuPath can turn into Line/Polyline annotations or detection
objects.

----------------------------------------------------------------------------
WIRE PROTOCOL
----------------------------------------------------------------------------
Every request starts with one 8-byte command:

    PING____      -> health check.        Response: <len><json>
    ANALYZE_      -> run fiber analysis.   Request body follows (see below)
                                           Response: <len><json>
    SHUTDOWN      -> stop the server.      No response.

For ANALYZE_, the client then writes:

    <4-byte BE uint32  meta_len> <meta_len bytes UTF-8 JSON>
    <4-byte BE uint32  png_len>  <png_len bytes PNG image>

The JSON header looks like:

    {
      "region":   {"x": 489, "y": 265, "width": 400, "height": 400,
                   "downsample": 1.0},        # echoed back; offsets applied client-side
      "fiber_mode": 2,                          # 1=segments, 2/3=merged fibers
      "use_ct_reconstruction": false,           # false = FIRE-only (no curvelops)
      "params": null                            # optional ctfire params override
    }

The server replies with one length-prefixed JSON object:

    {
      "ok": true,
      "backend": "real" | "synthetic",
      "n_fibers": 96,
      "image": {"width": 400, "height": 400},
      "fibers": [
        {
          "id": 0,
          "points": [[x0,y0],[x1,y1]],   # region-LOCAL pixels, (x=col, y=row)
          "center": [cx, cy],            # region-local (x=col, y=row)
          "angle": 170.4,                # degrees [0,180)
          "length_arc": 57.1,            # arc length (px)
          "length_end": 46.3,            # straight end-to-end length (px)
          "width": 4.2,
          "straightness": 0.81,
          "tacs": null                   # populated only with a boundary mask
        }, ...
      ]
    }

Coordinates are region-local; the QuPath client adds the region origin
(x,y) and multiplies by the downsample to map back to full-image coordinates.

----------------------------------------------------------------------------
The endpoint geometry replicates draw_utils.draw_curvs exactly so the lines
drawn in QuPath match the orientation of the pipeline's own overlay PNG:

    xc, yc = center_col, center_row ; a = deg2rad(angle) ; L = length/2
    p1 = (xc - L*cos a,  yc + L*sin a)
    p2 = (xc + L*cos a,  yc - L*sin a)
"""

from __future__ import annotations

import argparse
import concurrent.futures
import contextlib
import io
import json
import logging
import math
import multiprocessing
import os
import socket
import struct
import sys
import threading
import traceback
from pathlib import Path

# numpy / PIL are imported lazily (in main(), after the backend is pre-loaded) so
# that a spawned worker -- which re-imports THIS module during bootstrap -- does
# NOT load numpy's DLLs before fire_worker.worker_init loads the compiled backend.
# That ordering is what avoids the MSYS2 "DLL load failed ... procedure could not
# be found" version skew inside the worker. Both are set as module globals below.
np = None
Image = None

# ---------------------------------------------------------------------------
# Protocol constants
# ---------------------------------------------------------------------------
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 5101  # microscope server uses 5000; pick a neighbour

CMD_LEN = 8
CMD_PING = b"PING____"
CMD_ANALYZE = b"ANALYZE_"
CMD_POSTPROC = b"POSTPROC"  # stitch + dedup + global TACS on an assembled fiber set
CMD_GRIDSEARCH = b"GRIDSRCH"  # try a grid of FIRE params, score, return the best
CMD_SHUTDOWN = b"SHUTDOWN"

logger = logging.getLogger("fiber_socket_server")

# ---------------------------------------------------------------------------
# Optional per-run log file (written into the QuPath project by request)
# ---------------------------------------------------------------------------
# When QuPath sends "log_dir"/"log_file" in the request meta, tee this request's
# logger output AND FIRE's raw stdout/stderr prints (the "Starting FIRE 2D
# extraction...", density-guard, retry lines the console shows) into that file so
# the full server transcript is preserved in the project even after the console
# scrolls away. Format matches the console handler (basicConfig, in main()).
_LOG_FMT = "%(asctime)s %(levelname)-7s %(name)s: %(message)s"
# FIRE runs in-process and effectively serially; this guards the process-global
# stdout/stderr swap so overlapping requests can't corrupt each other's streams.
_run_log_lock = threading.Lock()


class _Tee:
    """A minimal write-through stream: forwards to several underlying streams
    (the real console + the run-log file). Never raises on a bad stream."""

    def __init__(self, *streams):
        self._streams = streams

    def write(self, s):
        for st in self._streams:
            try:
                st.write(s)
            except Exception:  # noqa: BLE001
                pass
        return len(s)

    def flush(self):
        for st in self._streams:
            try:
                st.flush()
            except Exception:  # noqa: BLE001
                pass


# Shared state for the run log. One handler and one stdout/stderr tee are installed for the
# FIRST request that asks for a given file and removed by the LAST one to finish, counted here.
_run_log_state = {"key": None, "fh": None, "handler": None, "refs": 0, "out": None, "err": None}


@contextlib.contextmanager
def _run_file_logging(log_dir, log_file):
    """Append this request's full log (logger lines + FIRE prints) to
    ``<log_dir>/<log_file>``. No-op when the client didn't request logging or the
    file can't be opened (analysis still proceeds normally).

    REFERENCE COUNTED, and the lock is held only to install or remove the handler -- never
    across the analysis itself. The previous version wrapped its ``yield`` in the lock, so a
    second concurrent request blocked until the first had finished tracing: with logging on
    (the default) the server was effectively single-threaded no matter how many tiles a client
    sent at once. Tiles of one run share a log file, so they share one handler and one tee.

    A request naming a DIFFERENT file while one is already open (two runs at once) logs to the
    console only, rather than fighting over sys.stdout.
    """
    if not log_dir or not log_file:
        yield
        return
    key = os.path.join(log_dir, log_file)
    joined = False
    with _run_log_lock:
        st = _run_log_state
        if st["refs"] > 0:
            if st["key"] == key:
                st["refs"] += 1
                joined = True
        else:
            try:
                os.makedirs(log_dir, exist_ok=True)
                fh = open(key, "a", encoding="utf-8", buffering=1)
            except Exception as exc:  # noqa: BLE001
                logger.warning("Could not open server run log in %r: %s", log_dir, exc)
                fh = None
            if fh is not None:
                handler = logging.StreamHandler(fh)
                handler.setFormatter(logging.Formatter(_LOG_FMT))
                logging.getLogger().addHandler(handler)
                # Console handlers captured the ORIGINAL stderr at construction, so they keep
                # printing to the console; the tee adds the file copy for bare prints.
                st.update(key=key, fh=fh, handler=handler, refs=1,
                          out=sys.stdout, err=sys.stderr)
                sys.stdout = _Tee(st["out"], fh)
                sys.stderr = _Tee(st["err"], fh)
                joined = True
    if not joined:
        yield
        return
    try:
        yield
    finally:
        with _run_log_lock:
            st = _run_log_state
            st["refs"] -= 1
            if st["refs"] <= 0:
                sys.stdout, sys.stderr = st["out"], st["err"]
                logging.getLogger().removeHandler(st["handler"])
                try:
                    st["fh"].flush()
                    st["fh"].close()
                except Exception:  # noqa: BLE001
                    pass
                st.update(key=None, fh=None, handler=None, refs=0, out=None, err=None)


# ---------------------------------------------------------------------------
# Pipeline import (with graceful synthetic fallback)
# ---------------------------------------------------------------------------
# The FIRE C++ backend is platform/Python-version specific. When it cannot be
# imported (e.g. wrong Python version, no compiled .pyd/.so), the server falls
# back to a synthetic line detector so the socket round-trip and the QuPath
# rendering path can still be exercised end-to-end.

_PIPELINE = None          # curvealign_ctfire_mode_pipeline (DataFrame path)
_FIRE = None              # fire_2d_angle (low-level, gives full polylines)
_DEFAULT_PARAMS = None    # DEFAULT_CTFIRE_PARAMS
_BOUNDARY_COORDS = None   # extract_boundary_coords_from_mask
_BOUNDARY_ASSOC = None    # extract_tif_boundary
_CLASSIFY_TACS = None     # classify_fiber_tacs
_BACKEND = "synthetic"    # "real" once the pipeline imports
_IMPORT_ERROR = None      # why the real backend is unavailable (shown to client)
_TACS_OK = False          # True when the optional TACS functions imported
_REPO_ROOT = None         # repo path (for spawning the isolated FIRE worker)

# The FIRE C++ backend can SEGFAULT (or std::terminate) on pathological inputs --
# a very dense tile makes the tracer build a huge junction structure and it dies
# with NO catchable Python exception. Run in-process that kills the whole server.
# So FIRE runs in a spawned worker subprocess by default: a crash there raises a
# contained BrokenProcessPool, the tile is auto-retried with coarser params, and
# the server survives. At startup main() PROBES the worker (fire_worker.worker_probe):
# if the freshly-spawned interpreter cannot load the compiled backend's DLLs, it
# logs the real error and falls back to in-process automatically -- so isolation is
# always safe to leave on. Force in-process with TMEQUANT_FIRE_WORKER=0.
_EXECUTOR = None
_USE_WORKER = os.environ.get("TMEQUANT_FIRE_WORKER", "1") != "0"
_W_FIRE = None            # fire_2d_angle in the worker process
_W_BACKEND_ERR = None     # real fiber_backend import error inside the worker
# Per-request FIRE timeout, set from the ANALYZE meta (Advanced server settings) so it can be tuned
# from the dialog; falls back to the env/default in _run_fire when None.
_FIRE_TIMEOUT_OVERRIDE = None


def _cfg(meta, key, env_var, default):
    """One knob's value: the ANALYZE meta wins (Advanced server settings in the dialog), else the
    environment variable, else the built-in default. Everything is returned as float."""
    if meta is not None:
        v = meta.get(key)
        if v is not None:
            try:
                return float(v)
            except (TypeError, ValueError):
                pass
    return float(os.environ.get(env_var, default) or default)


def _default_repo_root() -> Path:
    """Best-effort guess at the tme-quant repo root.

    Override with the TMEQUANT_REPO environment variable.
    """
    env = os.environ.get("TMEQUANT_REPO")
    if env:
        return Path(env)
    # default: sibling extracted folder next to this script
    here = Path(__file__).resolve().parent
    guess = (
        here.parent
        / "TMEQuant_fire_only_extracted"
        / "TMEQuant_fire_only"
        / "tme-quant"
    )
    return guess


def _try_import_pipeline(repo_root: Path) -> bool:
    """Wire up sys.path and import curvealign_ctfire_mode_pipeline.

    Returns True on success (sets module globals), False otherwise.
    """
    global _PIPELINE, _FIRE, _DEFAULT_PARAMS, _BACKEND, _IMPORT_ERROR, _TACS_OK, _REPO_ROOT
    global _BOUNDARY_COORDS, _BOUNDARY_ASSOC, _CLASSIFY_TACS
    _REPO_ROOT = repo_root
    # repo-level src/ (ctfire_py, pycurvelets) + package src/
    candidates = [
        repo_root / "src",
        repo_root / "src" / "tme_quant" / "src",
    ]
    for c in candidates:
        if c.is_dir() and str(c) not in sys.path:
            sys.path.insert(0, str(c))
    os.environ.setdefault("MPLBACKEND", "Agg")  # headless

    # Windows: Python 3.8+ does NOT search PATH for an extension module's DLL
    # dependencies. The compiled fiber_backend.pyd needs the GCC runtime DLLs
    # (libstdc++, libgomp, libpython, ...) which live next to python.exe in
    # ucrt64\bin. Register those directories so the .pyd loads regardless of how
    # the server was launched (this is the usual cause of a silent fall back to
    # the synthetic detector even though a standalone `import fiber_backend` worked).
    if os.name == "nt":
        # ucrt64\bin comes from the running interpreter and MSYS2_ROOT so a
        # non-default MSYS2 install location works (C:\msys64 is only a fallback).
        _msys_root = os.environ.get("MSYS2_ROOT")
        dll_dirs = [
            os.path.dirname(sys.executable),          # .../ucrt64/bin (mingw python)
            str(repo_root / "src" / "ctfire_py"),     # the .pyd's own folder
        ]
        if _msys_root:
            dll_dirs.append(os.path.join(_msys_root, "ucrt64", "bin"))
        dll_dirs.append(r"C:\msys64\ucrt64\bin")      # common MSYS2 location (fallback)
        for d in dll_dirs:
            if d and os.path.isdir(d):
                try:
                    os.add_dll_directory(d)
                except Exception:  # noqa: BLE001
                    pass

    # Pre-load the compiled backend BEFORE the heavy scientific stack (scipy,
    # scikit-image, opencv, ...) is imported by the pipeline. If the failure is a
    # DLL conflict caused by one of those loading an incompatible shared runtime
    # first, importing fiber_backend now (it gets cached in sys.modules, so
    # fire_2d_angle's later `import fiber_backend` reuses it) can avoid it.
    try:
        cp = str(repo_root / "src" / "ctfire_py")
        if cp not in sys.path:
            sys.path.insert(0, cp)
        import fiber_backend as _fb_early  # noqa: F401
        logger.info("Pre-loaded fiber_backend before the scientific stack.")
    except Exception as exc:  # noqa: BLE001
        logger.debug("Early fiber_backend pre-load failed (%s); will diagnose below.", exc)

    # --- core (required for real fiber analysis) ---
    try:
        from tme_quant.tme_analysis.pipelines.curvealign_ctfireMode_pipeline import (  # noqa: E501
            curvealign_ctfire_mode_pipeline,
        )
        from ctfire_py.fire_2d_angle import fire_2d_angle
        from pycurvelets.get_fire import DEFAULT_CTFIRE_PARAMS

        # fire_2d_angle imports the compiled backend with a try/except that
        # silently sets it to None. Verify it actually loaded -- otherwise the
        # server would claim "real" and then fail every request. NOTE: the
        # package attribute `ctfire_py.fire_2d_angle` is shadowed by the function
        # of the same name (ctfire_py/__init__ does `from .fire_2d_angle import
        # ...`), so fetch the real MODULE object from sys.modules.
        _f2a_mod = sys.modules.get("ctfire_py.fire_2d_angle")
        if _f2a_mod is None or getattr(_f2a_mod, "fiber_backend", None) is None:
            # Re-attempt the import directly to capture the REAL underlying error
            # (fire_2d_angle swallows it behind a generic warning).
            real_err = "unknown"
            try:
                import importlib
                cp = str(repo_root / "src" / "ctfire_py")
                if cp not in sys.path:
                    sys.path.insert(0, cp)
                if "fiber_backend" in sys.modules:
                    del sys.modules["fiber_backend"]
                importlib.import_module("fiber_backend")
                real_err = "(re-import succeeded; likely an import-order/DLL conflict "
                "with numpy/scipy/opencv loaded first)"
            except Exception as e:  # noqa: BLE001
                real_err = f"{type(e).__name__}: {e}"
            raise ImportError(
                "compiled C++ fiber_backend did not load. Real error: " + real_err
            )

        _PIPELINE = curvealign_ctfire_mode_pipeline
        _FIRE = fire_2d_angle
        _DEFAULT_PARAMS = DEFAULT_CTFIRE_PARAMS
        _BACKEND = "real"
        _IMPORT_ERROR = None
        logger.info("Loaded real FIRE pipeline from %s", repo_root)
    except Exception as exc:  # noqa: BLE001 - want any import failure
        _IMPORT_ERROR = f"{type(exc).__name__}: {exc}"
        logger.error("REAL BACKEND UNAVAILABLE -> synthetic fallback: %s", _IMPORT_ERROR)
        logger.debug("%s", traceback.format_exc())
        return False

    # --- TACS (optional; failure must NOT disable real fiber analysis) ---
    try:
        from tme_quant.fiber_analysis.utils.boundary_tif_utils import (
            extract_boundary_coords_from_mask,
            extract_tif_boundary,
        )
        from tme_quant.fiber_analysis.tacs import classify_fiber_tacs

        _BOUNDARY_COORDS = extract_boundary_coords_from_mask
        _BOUNDARY_ASSOC = extract_tif_boundary
        _CLASSIFY_TACS = classify_fiber_tacs
        _TACS_OK = True
    except Exception as exc:  # noqa: BLE001
        _TACS_OK = False
        logger.warning(
            "TACS classification unavailable (%s: %s) -- fiber detection still works.",
            type(exc).__name__,
            exc,
        )
    return True


# ---------------------------------------------------------------------------
# Geometry helpers
# ---------------------------------------------------------------------------
def _angle_from_endpoints(p0, p1) -> float:
    """Fiber angle in degrees [0,180) in the pipeline's y-down convention.

    p0, p1 are (row, col).  Matches draw_curvs: positive angle tilts the line
    up-to-the-right because screen y grows downward.
    """
    drow = p1[0] - p0[0]
    dcol = p1[1] - p0[1]
    return float(np.degrees(np.arctan2(-drow, dcol)) % 180.0)


def _approx_width(fa_verts, Ra) -> float:
    """Approximate fiber width = 2 * mean radius over the fiber's vertices."""
    try:
        idx = [v for v in fa_verts if 0 <= v < len(Ra)]
        if not idx:
            return float("nan")
        return float(2.0 * np.mean(np.asarray(Ra)[idx]))
    except Exception:  # noqa: BLE001
        return float("nan")


def _width_stats(fa_verts, Ra):
    """Fibre width stats over the fibre's vertices (each width = 2*distance-map radius), analysed px.

    Returns (mean, max, std). A fibre's thickness varies along its length, so a single mean hides
    real variation — callers expose all three. NaNs when no valid vertices.
    """
    try:
        idx = [v for v in fa_verts if 0 <= v < len(Ra)]
        if not idx:
            return float("nan"), float("nan"), float("nan")
        w = 2.0 * np.asarray(Ra)[idx].astype(float)
        return float(np.mean(w)), float(np.max(w)), float(np.std(w))
    except Exception:  # noqa: BLE001
        return float("nan"), float("nan"), float("nan")


def _valley_cut_edges(pts, smooth, thr, valley_frac, win_px=12.0, step=1.0):
    """Edges where a fibre's path crosses a darker seam between two bright stretches.

    FIRE steps are only a few px, so a hop between neighbouring bundles is a WALK of short edges,
    invisible to per-edge tests. Resample the path every `step` px, read the lightly smoothed
    intensity, and flag a dip whose value is <= valley_frac x the dimmer of the brightest intensity
    within `win_px` on each side (both sides on collagen, i.e. > thr). Each contiguous dip is cut once,
    at the edge containing its minimum.
    """
    p = np.asarray(pts, dtype=float)
    if len(p) < 2:
        return []
    seg = np.diff(p, axis=0)
    sl = np.hypot(seg[:, 0], seg[:, 1])
    cum = np.concatenate([[0.0], np.cumsum(sl)])
    tot = cum[-1]
    if tot < 2 * win_px:
        return []
    t = np.arange(0.0, tot + 1e-9, step)
    xs = np.interp(t, cum, p[:, 0])
    ys = np.interp(t, cum, p[:, 1])
    h, w = smooth.shape[:2]
    xi = np.clip(np.round(xs).astype(int), 0, w - 1)
    yi = np.clip(np.round(ys).astype(int), 0, h - 1)
    prof = smooth[yi, xi].astype(float)
    W = max(1, int(win_px / step))
    m = len(prof)
    flag = np.zeros(m, bool)
    for i in range(W, m - W):
        left = prof[i - W:i].max()
        right = prof[i + 1:i + 1 + W].max()
        ref = min(left, right)
        if ref > thr and prof[i] <= valley_frac * ref:
            flag[i] = True
    edges = []
    i = 0
    while i < m:
        if flag[i]:
            j = i
            while j + 1 < m and flag[j + 1]:
                j += 1
            k = i + int(np.argmin(prof[i:j + 1]))
            e = int(np.searchsorted(cum, t[k], side="right") - 1)
            edges.append(min(max(e, 0), len(p) - 2))
            i = j + 1
        else:
            i += 1
    return edges


def _snap_to_centerline(fibers, image, thr, search_px=6, penalty=0.15, iters=3, step_px=2.0):
    """Move each fibre onto the centre line of the collagen it is tracing.

    FIRE's vertices are sparse and its steps chord across the bends of crimped collagen, so a trace can
    sit on a bundle's edge or cut over dark tissue inside a bend (which also triggers false seam cuts).
    Here each fibre is resampled every `step_px`, and every sample is moved along the local normal
    (within +/-`search_px`) to the maximum of the distance-to-background map of the thresholded image
    -- i.e. onto the medial axis -- with a small per-pixel `penalty` so it can't hop to a neighbouring
    bundle; the result is lightly smoothed and the step repeats `iters` times. Topology is unchanged
    (same fibres, same order); only geometry is corrected. Width is re-measured along the new centreline.
    Scored against hand-drawn centrelines (tile 812): within-4px recall 45% -> 61%, precision 40% -> 55%.
    """
    if not fibers:
        return fibers
    from scipy import ndimage as _nd
    from scipy.ndimage import gaussian_filter as _gf
    img = np.asarray(image, dtype=np.float32)
    dmap = _nd.distance_transform_edt(_gf(img, 1.5) > thr)
    H, W = dmap.shape
    offs = np.arange(-int(search_px), int(search_px) + 1)

    def _resample(p, step):
        sl = np.hypot(*np.diff(p, axis=0).T)
        cum = np.concatenate([[0.0], np.cumsum(sl)])
        if cum[-1] <= 0:
            return p[:1]
        t = np.arange(0.0, cum[-1] + 1e-9, step)
        if t[-1] < cum[-1]:
            t = np.append(t, cum[-1])  # keep the true endpoint
        return np.column_stack([np.interp(t, cum, p[:, 0]), np.interp(t, cum, p[:, 1])])

    out = []
    for f in fibers:
        try:
            p0 = np.asarray(f.get("points") or [], dtype=float)
            if len(p0) < 2:
                out.append(f)
                continue
            P = _resample(p0, step_px)
            if len(P) < 5:
                out.append(f)
                continue
            for _ in range(int(iters)):
                T = np.gradient(P, axis=0)
                T /= (np.linalg.norm(T, axis=1, keepdims=True) + 1e-9)
                N = np.column_stack([-T[:, 1], T[:, 0]])
                cand = P[:, None, :] + offs[None, :, None] * N[:, None, :]
                cx = np.clip(np.round(cand[..., 0]).astype(int), 0, W - 1)
                cy = np.clip(np.round(cand[..., 1]).astype(int), 0, H - 1)
                best = offs[np.argmax(dmap[cy, cx] - penalty * np.abs(offs)[None, :], axis=1)]
                Q = P + best[:, None] * N
                Qs = Q.copy()
                ker = np.ones(5) / 5.0
                Qs[2:-2, 0] = np.convolve(Q[:, 0], ker, "valid")
                Qs[2:-2, 1] = np.convolve(Q[:, 1], ker, "valid")
                # keep samples inside the image: a centre line hugging the tile edge must not step
                # past it, or the pad/bounds filter downstream drops the WHOLE fibre.
                Qs[:, 0] = np.clip(Qs[:, 0], 0.0, W - 1.0)
                Qs[:, 1] = np.clip(Qs[:, 1], 0.0, H - 1.0)
                P = _resample(Qs, step_px)
                if len(P) < 5:
                    break
            if len(P) < 2:
                out.append(f)
                continue
            pts = [[float(x), float(y)] for x, y in P]
            nf = dict(f)
            nf["points"] = pts
            arc, end, ang, straight = _poly_metrics(pts)
            mid = pts[len(pts) // 2]
            nf["center"] = [mid[0], mid[1]]
            nf["angle"] = ang
            nf["length_arc"] = arc
            nf["length_end"] = end
            nf["straightness"] = straight
            wv = 2.0 * dmap[np.clip(np.round(P[:, 1]).astype(int), 0, H - 1),
                            np.clip(np.round(P[:, 0]).astype(int), 0, W - 1)]
            if wv.size and float(wv.max()) > 0:
                nf["width"] = float(np.mean(wv))
                nf["width_max"] = float(np.max(wv))
                nf["width_std"] = float(np.std(wv))
            out.append(nf)
        except Exception as exc:  # noqa: BLE001 - never let refinement fail a tile
            logger.debug("centreline snap skipped a fibre (%s)", exc)
            out.append(f)
    return out


def _split_bridging_fibers(fibers, image, thr, min_edge_px=8.0, gap_frac=0.4,
                           jump_mult=4.0, min_jump_px=20.0, valley_frac=0.55):
    """Cut spurious long "jump" edges out of a fibre, splitting it into separate fibres.

    FIRE's extension search radius scales with the local fibre width (distance-map value), so a vertex
    inside a thick bright bundle can LINK to a distant seed — a long straight leap. Two independent
    tests cut such an edge (either one triggers):

    1. CROSSES BACKGROUND: an edge >= `min_edge_px` whose sampled pixels are >= `gap_frac` below `thr`
       (a jump over a dark gap; the `line_clear_above_thresh` guard uses the smoothed distance map at a
       low seed-sensitivity threshold, which a faint/bled path slips past).
    2. GEOMETRY: an edge dramatically longer than this fibre's TYPICAL step (>= `jump_mult`x the median
       edge AND >= `min_jump_px`). Real tracing steps are small and regular, so a lone huge step is a
       link/jump — this catches jumps that run over faint-but-ABOVE-threshold pixels, which test 1
       cannot see. Needs >= 3 edges for a meaningful median.
    3. VALLEY ("crossing streams"): an edge whose two ends sit on bright collagen but whose middle
       dips to <= `valley_frac` of the dimmer end (10th percentile of a lightly smoothed profile). That
       is a hop from one bundle to a NEIGHBOURING bundle through the darker seam between them -- the
       seam can stay above threshold where bundles touch, so test 1 misses it. valley_frac<=0 disables.

    Well-behaved fibres are untouched. Runs on OUR side so tme-quant stays vanilla.
    """
    if not fibers:
        return fibers
    h, w = image.shape[:2]
    smooth = None
    if valley_frac and valley_frac > 0:
        try:
            from scipy.ndimage import gaussian_filter
            smooth = gaussian_filter(np.asarray(image, dtype=np.float32), 1.5)
        except Exception:  # noqa: BLE001
            smooth = None
    out = []
    for f in fibers:
      try:
        pts = f.get("points")
        if not pts or len(pts) < 2:
            out.append(f)
            continue
        seglens = [((pts[i + 1][0] - pts[i][0]) ** 2 + (pts[i + 1][1] - pts[i][1]) ** 2) ** 0.5
                   for i in range(len(pts) - 1)]
        # geometry-jump threshold: only when there are enough steps to define "typical".
        jump_len = float("inf")
        if len(seglens) >= 3:
            med = float(np.median(seglens))
            if med > 0:
                jump_len = max(jump_mult * med, min_jump_px)
        cuts = []
        for i in range(len(pts) - 1):
            seglen = seglens[i]
            if seglen >= jump_len:      # test 2: geometry — a lone huge step among small ones
                cuts.append(i)
                continue
            if seglen < min_edge_px:
                continue
            x0, y0 = pts[i]
            x1, y1 = pts[i + 1]
            n = max(1, int(seglen))
            below = 0
            total = 0
            for k in range(n + 1):
                t = k / n
                xc = int(x0 + (x1 - x0) * t)
                yc = int(y0 + (y1 - y0) * t)
                if 0 <= yc < h and 0 <= xc < w:
                    total += 1
                    if image[yc, xc] < thr:
                        below += 1
            if total and below / total >= gap_frac:
                cuts.append(i)  # drop the edge between vertex i and i+1
                continue
            pass  # (valley crossings are detected along the whole path below)
        if smooth is not None:
            cuts = sorted(set(cuts) | set(_valley_cut_edges(pts, smooth, thr, valley_frac)))
        if not cuts:
            out.append(f)
            continue
        pieces = []
        start = 0
        for c in cuts:
            pieces.append(pts[start:c + 1])
            start = c + 1
        pieces.append(pts[start:])
        for pc in pieces:
            if len(pc) < 2:
                continue
            nf = dict(f)
            nf["points"] = pc
            arc, end, ang, straight = _poly_metrics(pc)
            mid = pc[len(pc) // 2]
            nf["center"] = [mid[0], mid[1]]
            nf["angle"] = ang
            nf["length_arc"] = arc
            nf["length_end"] = end
            nf["straightness"] = straight
            out.append(nf)  # width_* inherited from the parent (pieces share ~its thickness)
      except Exception as exc:  # noqa: BLE001 - never let a split edge-case fail the whole tile
        logger.debug("bridge-split skipped a fibre (%s)", exc)
        out.append(f)
    for j, f in enumerate(out):
        f["id"] = j
    return out


def _exclusion_coverage(pts, excl, step_px=1.0):
    """Fraction of a polyline's ARC LENGTH that lies inside `excl`, plus the per-sample mask.

    Sampled by arc length (not per vertex) so a long straight segment crossing a small object
    is weighted by how much of it is really inside, not by its vertex count.
    """
    p = np.asarray(pts, dtype=float)
    if len(p) < 2:
        return 0.0, np.zeros(0, dtype=bool), np.zeros(0)
    seg = np.diff(p, axis=0)
    L = np.hypot(seg[:, 0], seg[:, 1])
    total = float(L.sum())
    if total <= 0:
        return 0.0, np.zeros(0, dtype=bool), np.zeros(0)
    n = max(2, int(total / max(0.25, step_px)))
    s = np.linspace(0.0, total, n)
    cum = np.concatenate([[0.0], np.cumsum(L)])
    xs = np.interp(s, cum, p[:, 0])
    ys = np.interp(s, cum, p[:, 1])
    h, w = excl.shape
    xi = np.clip(np.rint(xs).astype(int), 0, w - 1)
    yi = np.clip(np.rint(ys).astype(int), 0, h - 1)
    inside = excl[yi, xi].astype(bool)
    return float(inside.mean()), inside, s


def _resample_between(pts, s0, s1, step_px=1.0):
    """Rebuild a polyline from the arc-length range [s0, s1] of `pts`."""
    p = np.asarray(pts, dtype=float)
    seg = np.diff(p, axis=0)
    L = np.hypot(seg[:, 0], seg[:, 1])
    cum = np.concatenate([[0.0], np.cumsum(L)])
    n = max(2, int((s1 - s0) / max(0.25, step_px)))
    s = np.linspace(s0, s1, n)
    return [[float(np.interp(t, cum, p[:, 0])), float(np.interp(t, cum, p[:, 1]))] for t in s]


def _apply_exclusion(fibers, excl, max_frac=0.5, trim=True, min_len=0.0, step_px=1.0):
    """Drop or trim fibres over EXCLUDED regions (nuclei, blobs, any user "don't trace here" mask).

    A mask like brightfield nuclei must NOT simply delete its pixels: measured on a nucleus-rich
    field, 6 of the 7 longest fibres pass THROUGH a nucleus while sitting only ~6% inside one, so a
    hard erase would chop the best fibres to remove the junk. Instead the mask is applied to the
    traced geometry in two graded steps:

      * coverage - a fibre with >= `max_frac` of its ARC LENGTH inside the mask IS the excluded
                   object (a nucleus traced as a short arc), so it is dropped whole.
      * trim     - end runs that lie inside the mask are peeled off (spokes radiating out of a
                   nucleus), keeping the stretch that sits on real collagen. Same idea as
                   _trim_edge_tails, with the mask playing the part of the tile edge.

    A fibre that merely CROSSES a masked object keeps its full length: interior mask runs are left
    alone. `max_frac` <= 0 disables the coverage rule; fibres left shorter than `min_len` are dropped.
    """
    out = []
    n_drop = n_trim = 0
    for f in fibers:
        pts = f.get("points") or []
        if len(pts) < 2:
            out.append(f)
            continue
        try:
            frac, inside, s = _exclusion_coverage(pts, excl, step_px)
        except Exception as exc:  # noqa: BLE001 - never let a mask edge-case fail the tile
            logger.debug("exclusion skipped a fibre (%s)", exc)
            out.append(f)
            continue
        if len(inside) == 0:
            out.append(f)
            continue
        if max_frac > 0 and frac >= max_frac:
            n_drop += 1
            continue
        if not trim or not inside.any() or not (inside[0] or inside[-1]):
            out.append(f)
            continue
        # Peel the leading/trailing INSIDE runs; keep everything between them.
        i0 = 0
        while i0 < len(inside) and inside[i0]:
            i0 += 1
        i1 = len(inside) - 1
        while i1 > i0 and inside[i1]:
            i1 -= 1
        if i1 - i0 < 1:
            n_drop += 1
            continue
        new_pts = _resample_between(pts, s[i0], s[i1], step_px)
        arc, end, ang, straight = _poly_metrics(new_pts)
        if arc <= 0 or (min_len > 0 and arc < min_len):
            n_drop += 1
            continue
        nf = dict(f)
        nf["points"] = new_pts
        mid = new_pts[len(new_pts) // 2]
        nf["center"] = [mid[0], mid[1]]
        nf["angle"] = ang
        nf["length_arc"] = arc
        nf["length_end"] = end
        nf["straightness"] = straight
        out.append(nf)  # width_* inherited (trimming the ends does not change thickness)
        n_trim += 1
    for j, f in enumerate(out):
        f["id"] = j
    return out, n_drop, n_trim


def _anti_flow_scores(fibers, image, scales=(3.0, 7.0), coh_min=0.25, step_px=2.0):
    """Per-fibre angle (deg) between the trace and an INDEPENDENT estimate of the local flow.

    Checking a tracer against the field it followed only confirms it -- that is exactly how the
    zero-padding edge artifact survived, since the corrupted field agreed with the fibres it had
    produced. So this scores against the STRUCTURE TENSOR (first derivatives, from gradients),
    which is independent of the Hessian ridge field the flow tracer follows: two different
    estimators of the same quantity, from different information.

    ~0 deg = the fibre runs along the collagen; toward 90 = across it. NaN where the tensor has no
    confident opinion (coherence below `coh_min`), so an unlit region yields no verdict rather than
    a false accusation. Validated on a KNOWN failure: with the tracer deliberately broken, 12
    near-edge fibres scored >40 deg; with it fixed, 1.
    """
    if not fibers:
        return []
    th, coh = _ridge_orientation(image, scales=scales)
    h, w = image.shape
    out = []
    for f in fibers:
        p = np.asarray(f.get("points") or [], dtype=float)
        if len(p) < 2:
            out.append(float("nan"))
            continue
        seg = np.diff(p, axis=0)
        ln = np.hypot(seg[:, 0], seg[:, 1])
        tot = ln.sum()
        if tot <= 0:
            out.append(float("nan"))
            continue
        n = max(2, int(tot / step_px))
        s_ = np.linspace(0.0, tot, n)
        cum = np.concatenate([[0.0], np.cumsum(ln)])
        xs = np.interp(s_, cum, p[:, 0])
        ys = np.interp(s_, cum, p[:, 1])
        ang = np.arctan2(np.gradient(ys), np.gradient(xs))
        xi = np.clip(xs.astype(int), 0, w - 1)
        yi = np.clip(ys.astype(int), 0, h - 1)
        d = np.abs(((ang - th[yi, xi] + np.pi / 2) % np.pi) - np.pi / 2)
        k = coh[yi, xi] >= coh_min
        out.append(float(np.degrees(np.median(d[k]))) if k.sum() >= 3 else float("nan"))
    return out


def _trim_edge_tails(fibers, w, h, margin=3.0):
    """Peel boundary-hugging segments off the ENDS of each fibre (tile-edge artifact).

    Where collagen is cut by the crop, FIRE traces the boundary's medial axis, appending a segment
    that runs ALONG the edge (a cap perpendicular to the real fibre). A segment whose BOTH endpoints
    sit within `margin` px of an edge is such a cap -- pop it from either end until an interior vertex
    is reached. A fibre that merely REACHES the edge (only its last vertex near it) is untouched; a
    fibre that was entirely edge is dropped (left with <2 points). Recomputes geometry for trimmed
    fibres; width_* are inherited (thickness is unaffected by trimming the ends).
    """
    def near(p):
        return p[1] <= margin or p[1] >= h - 1 - margin or p[0] <= margin or p[0] >= w - 1 - margin

    out = []
    for f in fibers:
        pts = [list(p) for p in (f.get("points") or [])]
        if len(pts) < 2:
            out.append(f)
            continue
        changed = False
        while len(pts) >= 2 and near(pts[-1]) and near(pts[-2]):
            pts.pop()
            changed = True
        while len(pts) >= 2 and near(pts[0]) and near(pts[1]):
            pts.pop(0)
            changed = True
        if len(pts) < 2:
            continue  # was entirely edge -> drop
        if not changed:
            out.append(f)
            continue
        nf = dict(f)
        nf["points"] = pts
        arc, end, ang, straight = _poly_metrics(pts)
        mid = pts[len(pts) // 2]
        nf["center"] = [mid[0], mid[1]]
        nf["angle"] = ang
        nf["length_arc"] = arc
        nf["length_end"] = end
        nf["straightness"] = straight
        out.append(nf)
    return out


def _ridge_orientation(image, scales=(3.0, 7.0)):
    """Per-pixel local ridge orientation (radians) + coherence [0..1] from the structure tensor.

    coherence 0 = isotropic (round blob) .. 1 = strongly oriented (a clear fibre/ridge). Used to tell
    a fibre running ALONG a bright ridge (keep) from one cutting ACROSS it (a false crossbar).

    MULTI-SCALE: a thin fibre is oriented at a small scale, but the INTERIOR of a THICK bright band is
    uniform -- no texture -- so a small-scale tensor reads it as incoherent and the flow filter leaves
    its crossing spokes alone (the 'stars on thick bands'). A larger scale captures the band's overall
    elongation. So we evaluate several scales and, per pixel, keep the orientation of whichever scale is
    MOST coherent -- fine detail for thin fibres, the band's macro-direction inside thick bands.
    """
    from skimage.feature import structure_tensor, structure_tensor_eigenvalues
    from skimage.filters import gaussian
    g = gaussian(image, sigma=1.5, preserve_range=True)
    best_theta = None
    best_coh = None
    for s in scales:
        # mode="reflect" is NOT optional: skimage defaults to mode="constant", cval=0, which pads
        # with zeros, so the image border becomes a step edge and the tensor reports "the collagen
        # runs ALONG this border" -- confidently (measured 73 deg wrong at confidence 0.53 on
        # synthetic stripes). Near a tile edge that inverts this filter: a genuine fibre meeting the
        # edge at an angle looks like a cross-flow fibre and is REMOVED, while an edge-parallel
        # artifact looks correct and is KEPT.
        Arr, Arc, Acc = structure_tensor(g, sigma=float(s), order="rc",
                                         mode="reflect")  # Jyy=Arr, Jxy=Arc, Jxx=Acc
        theta = 0.5 * np.arctan2(2 * Arc, Acc - Arr) + np.pi / 2.0       # along the ridge
        l1, l2 = structure_tensor_eigenvalues([Arr, Arc, Acc])
        coh = (l1 - l2) / (l1 + l2 + 1e-6)
        if best_coh is None:
            best_theta, best_coh = theta, coh
        else:
            take = coh > best_coh
            best_theta = np.where(take, theta, best_theta)
            best_coh = np.maximum(best_coh, coh)
    return best_theta, best_coh


def _remove_flow_crossers(fibers, image, coh_strong=0.55, off_deg=52.0, frac=0.6, min_oriented=3):
    """Remove fibres that run ACROSS the local collagen flow (length-independent).

    Generalises the crossbar filter: wherever the tissue is strongly oriented (structure-tensor
    coherence >= coh_strong), a real fibre runs ALONG the flow. A fibre that is misaligned (>= off_deg
    from the local ridge) over most (>= frac) of its strongly-oriented length is a crosser -- a wide-
    bundle cross, an asterisk spoke, or a perpendicular bar -- and is dropped, regardless of length.
    A WAVY fibre stays aligned to its LOCAL (curving) ridge, so it is KEPT. The coh_strong gate is the
    safety dial: raise it so mixed-orientation tissue (no dominant flow) is left alone; lower it to
    clean aligned bundles more aggressively. min_oriented guards against acting on too little evidence.
    off_deg<=0 or coh_strong>=1 disables.
    """
    if off_deg <= 0 or coh_strong >= 1.0 or not fibers:
        return fibers
    H, W = image.shape
    try:
        theta_ridge, coh = _ridge_orientation(image)
    except Exception as exc:  # noqa: BLE001
        logger.warning("Flow filter skipped (structure tensor unavailable: %s).", exc)
        return fibers

    def _s(a, x, y):
        return a[min(H - 1, max(0, int(round(y)))), min(W - 1, max(0, int(round(x))))]

    keep = []
    for f in fibers:
        pts = f.get("points") or []
        if len(pts) < 2:
            keep.append(f); continue
        P = np.asarray(pts, dtype=float)
        nb = mis = 0
        for i in range(len(P) - 1):
            dx, dy = P[i + 1][0] - P[i][0], P[i + 1][1] - P[i][1]
            if dx == 0 and dy == 0:
                continue
            mx, my = (P[i][0] + P[i + 1][0]) / 2, (P[i][1] + P[i + 1][1]) / 2
            if _s(coh, mx, my) < coh_strong:
                continue
            nb += 1
            d = abs(math.atan2(dy, dx) - _s(theta_ridge, mx, my)) % math.pi
            if d > math.pi / 2:
                d = math.pi - d
            if math.degrees(d) >= off_deg:
                mis += 1
        if nb >= min_oriented and mis / max(1, nb) >= frac:
            continue  # crosses the local flow -> drop
        keep.append(f)
    return keep


def _debug_export_payload(gray: np.ndarray, thr: float, fibers: list) -> dict:
    """Base64 PNGs of exactly what FIRE traced on, for a single-tile debug export.

    - gray_png    : the prescaled/ridge image FIRE received (what it "sees")
    - mask_png    : its threshold foreground (gray > thr) — missed fibres show here first
    - dist_png    : cityblock distance-to-background of that mask — the map the tracer WALKS and
                    sizes its search radius from (large values => long jumps)
    - overlay_png : the traced fibres drawn on the grey
    Coordinates are in the grey's own frame. Touches only our bridge (no fire_2d_angle change).
    """
    import base64
    from PIL import Image as _PIL, ImageDraw as _Draw

    def _png_b64(arr, mode="L"):
        buf = io.BytesIO()
        _PIL.fromarray(arr, mode=mode).save(buf, format="PNG")
        return base64.b64encode(buf.getvalue()).decode("ascii")

    g = np.clip(gray, 0, 255).astype(np.uint8)
    fg = (gray > thr)
    mask = (fg.astype(np.uint8) * 255)
    try:
        from scipy import ndimage
        dist = ndimage.distance_transform_cdt(fg.astype(np.uint8), metric="taxicab").astype(np.float32)
    except Exception:  # noqa: BLE001
        dist = np.zeros(gray.shape, dtype=np.float32)
    dmax = float(dist.max()) or 1.0
    dist8 = (dist / dmax * 255.0).astype(np.uint8)

    rgb = np.stack([g, g, g], axis=-1)
    ov = _PIL.fromarray(rgb, mode="RGB")
    dr = _Draw.Draw(ov)
    for f in fibers:
        pts = f.get("points")
        if pts and len(pts) >= 2:
            dr.line([(float(p[0]), float(p[1])) for p in pts], fill=(0, 255, 0), width=2)
    obuf = io.BytesIO()
    ov.save(obuf, format="PNG")

    return {
        "gray_png": _png_b64(g),
        "mask_png": _png_b64(mask),
        "dist_png": _png_b64(dist8),
        "overlay_png": base64.b64encode(obuf.getvalue()).decode("ascii"),
        "thresh": float(thr),
        "dist_max": dmax,
        "foreground_frac": round(float(fg.mean()), 4),
        "width": int(g.shape[1]),
        "height": int(g.shape[0]),
        "n_fibers": len(fibers),
    }


def _fibers_from_fire(data: dict, ll1: float) -> list:
    """Reconstruct one polyline per fiber from a fire_2d_angle output dict.

    Each fiber i is the ordered vertex path ``Xai[Fai[i]['v']]`` (row, col).
    Mirrors pycurvelets.get_fire._build_fiber_dataframe's source data but keeps
    the *whole* centerline instead of flattening to per-vertex rows.
    """
    Fai = data.get("Fai", [])
    Xai = np.asarray(data.get("Xai", np.empty((0, 3))))
    Fa = data.get("Fa", [])
    Xa = np.asarray(data.get("Xa", np.empty((0, 3))))
    Ra = np.asarray(data.get("Ra", np.array([])))
    M = data.get("M", {})
    L = np.asarray(M.get("L", np.array([])))

    # NOTE: the LL1 (min-length) filter is applied by analyze_real, not here, so it
    # can report how many fibers FIRE traced vs how many survived the length cut
    # (a too-high Min length is a common reason for "no fibers"). ll1 is unused.
    fibers = []
    out_id = 0
    for i in range(len(Fai)):
        if i >= len(L):
            continue
        arc_len = float(L[i])
        verts = Fai[i].get("v", [])
        if len(verts) < 2:
            continue
        vidx = [v for v in verts if 0 <= v < len(Xai)]
        if len(vidx) < 2:
            continue
        path_rc = Xai[vidx, :2]  # (row, col), ordered along the fiber

        # points for QuPath: (x=col, y=row)
        points = [[float(p[1]), float(p[0])] for p in path_rc]
        p_start, p_end = path_rc[0], path_rc[-1]
        end_len = float(np.linalg.norm(p_end - p_start))
        straightness = (end_len / arc_len) if arc_len > 0 else float("nan")
        mid = path_rc[len(path_rc) // 2]
        center = [float(mid[1]), float(mid[0])]  # (x, y)
        fa_verts = Fa[i].get("v", []) if i < len(Fa) else []
        width, width_max, width_std = _width_stats(fa_verts, Ra)

        fibers.append(
            {
                "id": out_id,
                "points": points,            # full centerline polyline
                "center": center,
                "angle": _angle_from_endpoints(p_start, p_end),
                "length_arc": arc_len,
                "length_end": end_len,
                "width": width,              # MEAN width (also drives the widMAX filter)
                "width_max": width_max,
                "width_std": width_std,
                "straightness": straightness,
                "tacs": None,
            }
        )
        out_id += 1
    return fibers


# ---------------------------------------------------------------------------
# TACS classification (only when a boundary mask is supplied)
# ---------------------------------------------------------------------------
def _attach_tacs(fibers: list, mask: np.ndarray, dist_thresh: float) -> str:
    """Classify each fiber TACS-1/2/3 relative to the boundary in `mask`.

    Mutates each fiber dict in place, adding ``tacs``, ``angle_to_tangent`` and
    ``distance_to_boundary``. Uses the validated pipeline functions
    ``extract_tif_boundary`` (boundary-relative angle) and
    ``classify_fiber_tacs``. Returns a short status string for logging.
    """
    if _BOUNDARY_ASSOC is None or _CLASSIFY_TACS is None:
        return "tacs-unavailable"
    if not fibers:
        return "no-fibers"

    import pandas as pd

    # Per-fiber DataFrame (one row per polyline) the boundary code expects.
    rows = []
    for f in fibers:
        cx, cy = f["center"]  # (x=col, y=row)
        rows.append(
            {
                "center_row": float(cy),
                "center_col": float(cx),
                "angle": float(f["angle"]),
                "total_length": float(f["length_arc"]),
                "end_length": float(f["length_end"]),
                "curvature": float(f["straightness"]),
                "width": float(f["width"]) if np.isfinite(f["width"]) else 3.0,
            }
        )
    df = pd.DataFrame(rows)

    coords = _BOUNDARY_COORDS(mask)
    if not coords:
        return "no-boundary-found"

    _, _, _, res = _BOUNDARY_ASSOC(
        coordinates=coords, img=mask, fiber_df=df, dist_thresh=dist_thresh, min_dist=[]
    )
    counts = {"TACS-1": 0, "TACS-2": 0, "TACS-3": 0, "None": 0}
    for i, f in enumerate(fibers):
        ang = float(res["nearest_boundary_angle"].iloc[i])
        dist = float(res["nearest_boundary_distance"].iloc[i])
        tacs = _CLASSIFY_TACS(ang, f["straightness"], dist, tacs_zone_width=dist_thresh)
        f["tacs"] = tacs
        f["angle_to_tangent"] = ang
        f["distance_to_boundary"] = dist
        counts[tacs if tacs in counts else "None"] += 1
    return "TACS " + " ".join(f"{k}={v}" for k, v in counts.items())


# ---------------------------------------------------------------------------
# Post-processing: dedup + seam-stitch + GLOBAL TACS on an assembled fiber set
# ---------------------------------------------------------------------------
def _kdtree(pts):
    try:
        from scipy.spatial import cKDTree
        return cKDTree(pts) if len(pts) else None
    except Exception:  # noqa: BLE001
        return None


def _query_ball(tree, pts, q, r):
    if tree is not None:
        return tree.query_ball_point(q, r)
    d = np.hypot(pts[:, 0] - q[0], pts[:, 1] - q[1])
    return list(np.nonzero(d <= r)[0])


def _acute(a, b):
    d = abs(a - b) % 180.0
    return min(d, 180.0 - d)


def _waviness_metrics(points, kink_deg=30.0, smooth_win=9):
    """Crimp / waviness descriptors for one fibre polyline (coords in ANALYSED px).

    All are computed from the traced centreline, so a tracer that smooths the crimp away will report
    a small amplitude and few kinks -- which is exactly what makes the tracers comparable.
      kink_max_deg  sharpest single direction change (deg)      -- the "sharp kink" the user cares about
      kink_count    number of turns >= kink_deg
      curvature     total turning per unit length (deg / px)     -- client converts to deg/um
      crimp_amp     RMS perpendicular deviation from the fibre's own smoothed centreline (px)
      crimp_period  mean wavelength between turn-direction reversals (px)
    """
    zero = {"kink_max_deg": 0.0, "kink_count": 0, "curvature": 0.0,
            "crimp_amp": 0.0, "crimp_period": 0.0}
    p = np.asarray(points, dtype=float)
    if len(p) < 3:
        return zero
    seg = np.diff(p, axis=0)
    seglen = np.hypot(seg[:, 0], seg[:, 1])
    arc = float(seglen.sum())
    if arc <= 0:
        return zero
    ang = np.degrees(np.arctan2(seg[:, 1], seg[:, 0]))
    turn = (np.diff(ang) + 180.0) % 360.0 - 180.0      # signed turn at each interior vertex
    at = np.abs(turn)
    out = {
        "kink_max_deg": float(at.max()) if at.size else 0.0,
        "kink_count": int((at >= kink_deg).sum()),
        "curvature": float(at.sum() / arc),
        "crimp_amp": 0.0,
        "crimp_period": 0.0,
    }
    # amplitude: deviation of the polyline from a smoothed version of itself
    if len(p) >= 5:
        k = int(min(smooth_win, len(p) - (1 - len(p) % 2)))
        if k >= 3:
            ker = np.ones(k) / k
            sx = np.convolve(p[:, 0], ker, mode="same")
            sy = np.convolve(p[:, 1], ker, mode="same")
            h = k // 2
            if h:  # 'same' convolution biases the ends -- keep the originals there
                sx[:h], sx[-h:] = p[:h, 0], p[-h:, 0]
                sy[:h], sy[-h:] = p[:h, 1], p[-h:, 1]
            dev = np.hypot(p[:, 0] - sx, p[:, 1] - sy)
            out["crimp_amp"] = float(np.sqrt(np.mean(dev ** 2)))
    # period: a full wave spans two successive reversals of turn direction
    s = np.sign(turn)
    s = s[s != 0]
    if s.size > 1:
        rev = int(np.count_nonzero(s[1:] != s[:-1]))
        if rev > 0:
            out["crimp_period"] = float(2.0 * arc / rev)
    return out


def _poly_metrics(points):
    """(arc_len, end_len, angle_deg, straightness) for a polyline of [x,y] points."""
    p = np.asarray(points, dtype=float)
    if len(p) < 2:
        return 0.0, 0.0, 0.0, 0.0
    seg = np.diff(p, axis=0)
    arc = float(np.sum(np.hypot(seg[:, 0], seg[:, 1])))
    end = float(np.hypot(p[-1, 0] - p[0, 0], p[-1, 1] - p[0, 1]))
    # x = col, y = row (y grows downward) -> same convention as detection.
    ang = float(np.degrees(np.arctan2(-(p[-1, 1] - p[0, 1]), (p[-1, 0] - p[0, 0]))) % 180.0)
    straight = end / arc if arc > 0 else 0.0
    return arc, end, ang, straight


def _outward_dir(poly, end_idx):
    """Unit vector pointing away from the fiber body at the given end (0 or 1)."""
    p = np.asarray(poly, dtype=float)
    if len(p) < 2:
        return np.array([0.0, 0.0])
    if end_idx == 1:
        v = p[-1] - p[-2]
    else:
        v = p[0] - p[1]
    n = float(np.hypot(v[0], v[1]))
    return v / n if n > 0 else v


def _join_ok(polyA, ea, polyB, eb, angle_tol_deg):
    """True if the two fiber ends form a smooth (near-collinear) continuation."""
    da = _outward_dir(polyA, ea)
    db = _outward_dir(polyB, eb)
    # The two outward directions should point roughly opposite (face each other).
    dot = float(da[0] * db[0] + da[1] * db[1])
    return dot <= -np.cos(np.deg2rad(angle_tol_deg))


def _concat(polyA, ea, polyB, eb):
    a = [list(p) for p in polyA]
    b = [list(p) for p in polyB]
    if ea == 0:
        a.reverse()          # join end of A must be LAST
    if eb == 1:
        b.reverse()          # join end of B must be FIRST
    # drop B's first point if it coincides with A's last (avoid a zero-length seg)
    if a and b and abs(a[-1][0] - b[0][0]) < 1e-6 and abs(a[-1][1] - b[0][1]) < 1e-6:
        b = b[1:]
    return a + b


def _dedup_fibers(fibers, dedup_tol):
    """Drop near-duplicate fibers that two overlapping tiles both traced."""
    n = len(fibers)
    if n < 2:
        return fibers
    cen = np.array([f.get("center", [0, 0]) for f in fibers], float)
    ang = np.array([f.get("angle", 0.0) for f in fibers], float)
    ln = np.array([f.get("length_arc", 0.0) for f in fibers], float)
    tile = np.array([f.get("tile", -1) for f in fibers])
    keep = np.ones(n, bool)
    tree = _kdtree(cen)
    for i in range(n):
        if not keep[i]:
            continue
        for j in _query_ball(tree, cen, cen[i], dedup_tol):
            if j <= i or not keep[j] or tile[i] == tile[j]:
                continue
            if _acute(ang[i], ang[j]) > 20.0:
                continue
            lo, hi = sorted((ln[i], ln[j]))
            if hi <= 0 or lo / hi < 0.5:
                continue
            if ln[i] >= ln[j]:
                keep[j] = False
            else:
                keep[i] = False
                break
    return [f for f, k in zip(fibers, keep) if k]


def _refit_fiber(f, pts):
    """A copy of `f` on a new (shorter) polyline, with its geometry measurements redone.

    Width statistics are carried over unchanged: they are a median/max/std along the PARENT
    path and the distance transform that produced them is not available here. For the case
    this is used for -- the two near-coincident arms of a retrace -- that is the same number
    either way.
    """
    pl = [[float(x), float(y)] for x, y in pts]
    arc, end, ang, straight = _poly_metrics(pl)
    g = dict(f)
    g["points"] = pl
    g["length_arc"] = arc
    g["length_end"] = end
    g["angle"] = ang
    g["straightness"] = straight
    g["center"] = list(pl[len(pl) // 2])
    if "curvature" in f:                      # only recompute what the fibre already carried
        g.update(_waviness_metrics(pl))
    return g


def _split_self_retrace(fibers, min_sep=8.0, lag_px=30.0):
    """Cut a fibre that DOUBLES BACK on itself, keeping only its longer arm.

    A traced path can turn around and come back along (nearly) its own route. The orientation
    field is undirected, so a flow walk that loses the thread -- at a low-coherence patch, or
    where two bundles cross -- can flip sign and retrace; the walk's turn limit never objects,
    because it caps the turn per 1.5 px STEP, not the total. The result is a single object of
    roughly twice the real length whose two ends sit side by side, which inflates every length
    statistic it enters and reads as one improbably long fibre.

    The signature is two points FAR apart along the path but CLOSE in space: a pair within
    `min_sep` px whose separation along the arc exceeds `lag_px`. On a retrace, every such
    pair straddles the turn and so shares (about) the same arc midpoint, so the turn is
    located as the MEDIAN midpoint over all qualifying pairs -- robust to a stray pair. The
    fibre is cut there and only the longer arm kept: the two arms of a retrace are duplicates
    of each other, so keeping both would trade one bad object for two.

    A genuine wide U-bend (around a vessel, say) is NOT cut -- its arms stay more than
    `min_sep` apart, so no pair qualifies. Only a near-coincident doubling-back triggers.
    Returns (fibers, n_cut). min_sep <= 0 disables.
    """
    if min_sep <= 0 or not fibers:
        return fibers, 0
    out = []
    n_cut = 0
    for f in fibers:
        p = np.asarray(f.get("points") or [], dtype=float)
        if len(p) < 4:
            out.append(f)
            continue
        seg = np.diff(p, axis=0)
        s = np.concatenate([[0.0], np.cumsum(np.hypot(seg[:, 0], seg[:, 1]))])
        if s[-1] <= lag_px:
            out.append(f)          # too short to double back by this definition
            continue
        tree = _kdtree(p)
        if tree is None:
            out.append(f)          # no scipy: skip rather than go quadratic per fibre
            continue
        try:
            pairs = tree.query_pairs(min_sep, output_type="ndarray")
        except TypeError:          # older scipy: set of tuples
            pairs = np.array(sorted(tree.query_pairs(min_sep)), dtype=int).reshape(-1, 2)
        if len(pairs) == 0:
            out.append(f)
            continue
        i, j = pairs[:, 0], pairs[:, 1]        # query_pairs guarantees i < j
        far = (s[j] - s[i]) > lag_px
        if not far.any():
            out.append(f)
            continue
        cut_s = float(np.median(0.5 * (s[i[far]] + s[j[far]])))
        k = int(np.clip(np.searchsorted(s, cut_s), 1, len(s) - 2))
        arm = p[:k + 1] if s[k] >= s[-1] - s[k] else p[k:]
        if len(arm) < 2:
            out.append(f)
            continue
        out.append(_refit_fiber(f, arm))
        n_cut += 1
    return out, n_cut


def _dedup_overlapping_paths(fibers, tol_px=6.0, overlap_frac=0.7, step_px=2.0):
    """Drop fibres that retrace (most of) a longer fibre's path -- the 'duplicate along the same
    bundle' artifact, including pairs from the SAME tile (which the cross-tile dedup skips).

    Every fibre is resampled every `step_px`; a fibre is a duplicate when >= `overlap_frac` of its
    samples lie within `tol_px` of an already-KEPT, longer fibre. Fibres are decided longest-first, so
    the longest trace of a bundle survives. A genuine crossing overlaps only a short stretch (low
    fraction) and is kept; overlap_frac>=1 or tol_px<=0 disables.
    """
    n = len(fibers)
    if n < 2 or tol_px <= 0 or overlap_frac >= 1.0:
        return fibers
    samples, owner = [], []
    per = []
    for idx, f in enumerate(fibers):
        p = np.asarray(f.get("points") or [], dtype=float)
        if len(p) < 2:
            per.append(np.empty((0, 2)))
            continue
        seg = np.diff(p, axis=0)
        sl = np.hypot(seg[:, 0], seg[:, 1])
        cum = np.concatenate([[0.0], np.cumsum(sl)])
        tot = cum[-1]
        if tot <= 0:
            per.append(p[:1])
        else:
            t = np.arange(0.0, tot + 1e-9, step_px)
            per.append(np.column_stack([np.interp(t, cum, p[:, 0]), np.interp(t, cum, p[:, 1])]))
        samples.append(per[-1])
        owner.append(np.full(len(per[-1]), idx))
    if not samples:
        return fibers
    allp = np.vstack(samples)
    allo = np.concatenate(owner)
    tree = _kdtree(allp)
    lengths = [float(f.get("length_arc", 0.0)) for f in fibers]
    order = sorted(range(n), key=lambda i: -lengths[i])
    kept = np.zeros(n, bool)
    for i in order:
        pts = per[i]
        if len(pts) == 0:
            kept[i] = True
            continue
        covered = 0
        for q in pts:
            for j in _query_ball(tree, allp, q, tol_px):
                o = int(allo[j])
                if o != i and kept[o]:
                    covered += 1
                    break
        if covered / len(pts) < overlap_frac:
            kept[i] = True
    return [f for f, k in zip(fibers, kept) if k]


def _endpoint_dir(pl, end):
    """Outward unit direction at a polyline endpoint (end 0=start, 1=finish).

    Same value as ``_outward_dir`` but returns a plain (dx, dy) tuple and avoids the
    per-call ``np.asarray`` — it is evaluated once per endpoint per stitch pass.
    """
    if len(pl) < 2:
        return (0.0, 0.0)
    if end == 1:
        vx = pl[-1][0] - pl[-2][0]
        vy = pl[-1][1] - pl[-2][1]
    else:
        vx = pl[0][0] - pl[1][0]
        vy = pl[0][1] - pl[1][1]
    nrm = (vx * vx + vy * vy) ** 0.5
    if nrm <= 0.0:
        return (0.0, 0.0)
    return (vx / nrm, vy / nrm)


def _stitch_fibers(fibers, tol, angle_tol):
    """Greedily join fiber pieces split across tile seams (cross-tile only).

    Endpoint matching uses a KDTree, so one full sweep is ~O(N log N). We run it as
    repeated PASSES: each pass builds ONE tree over all current endpoints and performs a
    set of disjoint joins (each fibre is absorbed or extended at most once per pass), then
    compacts the list and repeats until nothing joins. This avoids the old behaviour of
    restarting the whole sweep — and rebuilding the tree — after EVERY single join, which
    was ~O(joins * N log N) and made large runs (100k+ fibres) take many minutes. The
    number of passes is bounded by the longest chain of seam-split pieces (small).

    Each endpoint's outward direction is precomputed once per pass so the collinearity
    test in the inner loop is a plain dot product (no per-comparison numpy).
    """
    polys = [[list(p) for p in f["points"]] for f in fibers]
    tiles = [f.get("tile", -1) for f in fibers]
    extra = [{k: v for k, v in f.items() if k not in ("points", "center", "id")} for f in fibers]
    merge_id = -2
    cos_thr = float(np.cos(np.deg2rad(angle_tol)))  # join if outward dirs oppose within angle_tol
    changed = True
    guard = 0
    while changed and guard < 1000:
        changed = False
        guard += 1
        n = len(polys)
        if n < 2:
            break
        # Endpoints: fibre i owns ep indices 2*i (start) and 2*i+1 (end).
        ep = []  # (poly_idx, end, x, y)
        for i, pl in enumerate(polys):
            ep.append((i, 0, pl[0][0], pl[0][1]))
            ep.append((i, 1, pl[-1][0], pl[-1][1]))
        pts = np.array([[e[2], e[3]] for e in ep], float)
        tree = _kdtree(pts)
        dirs = [_endpoint_dir(polys[e[0]], e[1]) for e in ep]
        dead = [False] * n     # absorbed into another fibre this pass
        touched = [False] * n  # geometry changed this pass -> leave alone until next pass
        for a in range(len(ep)):
            ia, ea, xa, ya = ep[a]
            if dead[ia] or touched[ia]:
                continue
            dax, day = dirs[a]
            for b in _query_ball(tree, pts, [xa, ya], tol):
                if b == a:
                    continue
                ib, eb, xb, yb = ep[b]
                if ib == ia or dead[ib] or touched[ib] or tiles[ia] == tiles[ib]:
                    continue
                dbx, dby = dirs[b]
                # Ends face each other (outward dirs roughly opposite) -> smooth continuation.
                if dax * dbx + day * dby <= -cos_thr:
                    polys[ia] = _concat(polys[ia], ea, polys[ib], eb)
                    tiles[ia] = merge_id
                    merge_id -= 1
                    dead[ib] = True     # ib folded into ia
                    touched[ia] = True  # defer ia's next join to the next pass
                    changed = True
                    break
        if changed:
            polys = [p for p, dc in zip(polys, dead) if not dc]
            tiles = [t for t, dc in zip(tiles, dead) if not dc]
            extra = [e for e, dc in zip(extra, dead) if not dc]
    out = []
    for i, pl in enumerate(polys):
        arc, end, ang, straight = _poly_metrics(pl)
        mid = pl[len(pl) // 2]
        f = dict(extra[i])
        f["points"] = pl
        f["center"] = [mid[0], mid[1]]
        f["angle"] = ang
        f["length_arc"] = arc
        f["length_end"] = end
        f["straightness"] = straight
        f["tacs"] = None
        f["id"] = i
        out.append(f)
    return out


def postprocess_fibers(fibers, mask, dist_thresh, do_stitch, dedup_tol, stitch_tol, angle_tol):
    """Dedup + stitch (cross-tile) then classify TACS against the full boundary."""
    if do_stitch and len(fibers) > 1:
        before = len(fibers)
        fibers = _dedup_fibers(fibers, dedup_tol)
        fibers = _stitch_fibers(fibers, stitch_tol, angle_tol)
        # _dedup_fibers compares CENTROIDS and skips same-tile pairs by design, so it only ever
        # removes cross-tile duplicates. Anything that produced two copies within ONE tile after
        # tracing -- two-pass detection being the obvious case, where both passes trace the same
        # bundle -- slipped through: the per-tile path dedup had already run before the merge, and
        # this one declines to look. Run the path-overlap dedup on the assembled set too.
        after_stitch = len(fibers)
        # A join can itself double a fibre back: two pieces whose ends meet but whose bodies lie
        # alongside each other are collinear at the seam and pass the stitch's angle test.
        fibers, n_retrace = _split_self_retrace(fibers)
        if n_retrace:
            logger.info("postproc: cut %d stitched fibre(s) that doubled back on themselves.",
                        n_retrace)
        fibers = _dedup_overlapping_paths(fibers)
        if len(fibers) != after_stitch:
            logger.info("postproc: path-dedup removed %d near-duplicate fibre(s) that the "
                        "centroid dedup skips (same-tile pairs, e.g. from two-pass).",
                        after_stitch - len(fibers))
        logger.info("postproc: %d -> %d fibers (dedup+stitch)", before, len(fibers))
    else:
        for i, f in enumerate(fibers):
            f["id"] = i
    if mask is not None and len(fibers) > 0:
        status = _attach_tacs(fibers, mask, float(dist_thresh))
        logger.info("postproc global %s", status)
    return fibers


# ---------------------------------------------------------------------------
# Parameter grid search (suggest good FIRE settings for a small region)
# ---------------------------------------------------------------------------
def _angle_diff_deg(a: float, b: float) -> float:
    """Smallest difference between two undirected angles in [0,180)."""
    d = abs((a - b) % 180.0)
    return d if d <= 90.0 else 180.0 - d


def _line_angle_xy(p0, p1) -> float:
    """Angle [0,180) of a segment given (x,y) endpoints, matching fiber angles."""
    dx = p1[0] - p0[0]
    dy = p1[1] - p0[1]
    return float(np.degrees(np.arctan2(-dy, dx)) % 180.0)


def _gt_segments(gt_lines):
    """Each ground-truth polyline -> (midpoint xy, angle, length)."""
    segs = []
    for line in gt_lines or []:
        pts = [p for p in line if p and len(p) >= 2]
        if len(pts) < 2:
            continue
        p0, p1 = pts[0], pts[-1]
        mid = ((p0[0] + p1[0]) / 2.0, (p0[1] + p1[1]) / 2.0)
        length = float(np.hypot(p1[0] - p0[0], p1[1] - p0[1]))
        segs.append({"mid": mid, "ang": _line_angle_xy(p0, p1), "len": length})
    return segs


def _score_supervised(fibers, gt_segs, match_dist, match_angle, score_mode="match",
                      count_tol=0.15, count_sigma=0.35, recall_floor=0.5, len_tol=0.25):
    """Greedy-match detected fibers to ground-truth lines and score the combo.

    Two modes (the greedy match + precision/recall/orientation are computed the same
    way in both; only the final aggregation differs):

    - ``"match"`` (default): F-measure of PRECISION and RECALL x orientation. Using
      precision is essential -- a very low threshold floods the region with fibres,
      trivially matching every GT line (recall -> 1.0); precision (matched / detected)
      collapses for such a flood, so the search doesn't just pick the lowest threshold.

    - ``"count"``: PRIMARY objective is total detected count ~= ground-truth count
      (the user traces every fibre in the region). ``count_score`` is a flat-topped
      tolerance band around n_det/n_gt == 1 (don't overfit imperfect annotations),
      multiplied by RECALL and a precision term so the right count can't be hit by the
      WRONG fibres (a DIFFERENT set that merely totals right), and lightly shaped by
      length + orientation agreement.
    """
    used = set()
    matched = 0
    matched_gt = []  # indices (into gt_segs) of the GT lines this combo actually detected
    errs = []
    for gi, g in enumerate(gt_segs):
        best_j, best_d = None, match_dist
        for j, f in enumerate(fibers):
            if j in used:
                continue
            c = f.get("center")
            if not c:
                continue
            d = float(np.hypot(c[0] - g["mid"][0], c[1] - g["mid"][1]))
            if d <= best_d and _angle_diff_deg(f.get("angle", 0.0), g["ang"]) <= match_angle:
                best_j, best_d = j, d
        if best_j is not None:
            used.add(best_j)
            matched += 1
            matched_gt.append(gi)
            errs.append(_angle_diff_deg(fibers[best_j].get("angle", 0.0), g["ang"]))
    n_gt = max(1, len(gt_segs))
    n = len(fibers)
    recall = matched / n_gt
    precision = (matched / n) if n > 0 else 0.0
    orient_err = float(np.mean(errs)) if errs else 90.0
    orient_factor = 1.0 - 0.5 * (orient_err / 90.0)

    # --- count closeness (primary in count mode; reported in both) ---
    count_ratio = n / n_gt
    dev = abs(count_ratio - 1.0)
    if dev <= count_tol:
        count_score = 1.0
    else:
        excess = dev - count_tol
        count_score = float(np.exp(-(excess * excess) / (2.0 * count_sigma * count_sigma)))

    # --- median fibre-length agreement (secondary) ---
    gt_lens = [g["len"] for g in gt_segs if g.get("len")]
    det_lens = [f.get("length_arc", 0.0) for f in fibers if f.get("length_arc")]
    if gt_lens and det_lens:
        gt_med = float(np.median(gt_lens))
        det_med = float(np.median(det_lens))
        len_err = abs(det_med - gt_med) / gt_med if gt_med > 0 else 1.0
        len_factor = 1.0 if len_err <= len_tol else max(0.0, 1.0 - (len_err - len_tol))
    else:
        len_factor = 0.0

    if score_mode == "count":
        # Reward the RIGHT fibers, not just the right COUNT: recall (fraction of annotated
        # fibers actually matched) multiplies directly, and precision penalises spurious
        # detections that merely hit the target count. Otherwise "17 detected / target 17"
        # can rank top while detecting a DIFFERENT 17 than were annotated. (recall_floor kept
        # as an accepted arg for callers, but no longer gates the score.)
        score = (count_score * recall * (0.5 + 0.5 * precision)
                 * (0.7 + 0.2 * len_factor + 0.1 * orient_factor))
        fbeta = 0.0
    else:
        # F-beta=1.5 leans slightly toward recall; precision still dominates a flood.
        beta2 = 1.5 * 1.5
        denom = beta2 * precision + recall
        fbeta = ((1.0 + beta2) * precision * recall / denom) if denom > 0 else 0.0
        score = fbeta * orient_factor
    return {
        "score": round(score, 4), "recall": round(recall, 3),
        "precision": round(precision, 3), "fbeta": round(fbeta, 3),
        "orient_err": round(orient_err, 1), "matched": matched, "n_gt": len(gt_segs),
        "matched_gt": matched_gt,
        "n_fibers": n, "n_detected": n,
        "count_score": round(count_score, 4), "count_ratio": round(count_ratio, 3),
        "len_factor": round(len_factor, 3),
    }


def _rasterize_fibers(fibers, w, h, width):
    """Binary mask of the fibre polylines, thickened by `width` px (for coverage)."""
    from PIL import ImageDraw
    im = Image.new("L", (w, h), 0)
    dr = ImageDraw.Draw(im)
    lw = max(1, int(round(width)))
    for f in fibers:
        pts = [(float(p[0]), float(p[1])) for p in f.get("points", []) if p and len(p) >= 2]
        if len(pts) >= 2:
            dr.line(pts, fill=255, width=lw)
    return np.asarray(im) > 0


def _score_unsupervised(fibers, prescaled_img, thresh):
    """No ground truth: reward coverage of the thresholded foreground by fibres and
    median straightness; penalise degenerate (single-orientation) and runaway sets."""
    h, w = prescaled_img.shape[:2]
    fg = prescaled_img > thresh
    fg_n = int(fg.sum())
    if fg_n == 0:
        return {"score": -1.0, "coverage": 0.0, "straightness": 0.0,
                "degenerate": True, "n_fibers": len(fibers)}
    fmask = _rasterize_fibers(fibers, w, h, width=5)
    coverage = float((fg & fmask).sum()) / fg_n
    straight = [f.get("straightness") for f in fibers
                if isinstance(f.get("straightness"), (int, float)) and np.isfinite(f.get("straightness"))]
    med_straight = float(np.median(straight)) if straight else 0.0
    angs = np.array([f.get("angle", 0.0) for f in fibers])
    ang_std = float(np.std(angs % 180.0)) if angs.size else 0.0
    degenerate = ang_std < 8.0 and len(fibers) > 3
    n = len(fibers)
    cap = max(20, (w * h) // 2000)  # soft area-based cap
    runaway = min(1.0, max(0.0, n / cap - 1.0))
    score = 1.0 * coverage + 0.4 * med_straight - (0.5 if degenerate else 0.0) - 0.3 * runaway
    return {
        "score": round(score, 4), "coverage": round(coverage, 3),
        "straightness": round(med_straight, 3), "angle_std": round(ang_std, 1),
        "degenerate": degenerate, "runaway": round(runaway, 3), "n_fibers": n,
    }


def _grid_combos(grid: dict, max_combos: int):
    """Cartesian product of {key: [values]} -> list of {key: value} dicts (capped)."""
    import itertools
    keys = list(grid.keys())
    value_lists = [grid[k] if isinstance(grid[k], list) else [grid[k]] for k in keys]
    combos = [dict(zip(keys, vals)) for vals in itertools.product(*value_lists)]
    return combos[:max_combos]


def gridsearch(image: np.ndarray, meta: dict) -> dict:
    """Run a grid of FIRE parameter combos on one (small) region and rank them.

    meta keys: grid {param: [values]}, base_overrides {}, gt_lines [[ [x,y], ... ]],
    prescaled, match_dist, match_angle, max_combos, top_k.
    """
    if _BACKEND != "real":
        return {"ok": False, "error": "real backend not loaded; cannot grid-search"}

    img = image
    if img.ndim == 3:
        img = img[..., 0]
    imn = np.ascontiguousarray(img).astype(np.float32)
    prescaled = bool(meta.get("prescaled"))
    if not prescaled and imn.max() > 0:
        imn = imn / imn.max() * 255.0
    elif prescaled and imn.max() > 255.0:
        # Defensive: a 'prescaled' tile must be 0-255 (see analyze_real). Normalise a stray 16-bit
        # array rather than thresholding a 0-65535 image against a 0-255 thresh_im2.
        imn = imn / imn.max() * 255.0

    grid = meta.get("grid") or {}
    base = meta.get("base_overrides") or {}
    combos = _grid_combos(grid, int(meta.get("max_combos", 60)))
    gt_segs = _gt_segments(meta.get("gt_lines"))
    supervised = len(gt_segs) > 0
    match_dist = float(meta.get("match_dist", 12.0))
    match_angle = float(meta.get("match_angle", 20.0))
    top_k = int(meta.get("top_k", 3))
    # Count-primary scoring config (default "match" keeps the old F-measure behaviour).
    score_mode = str(meta.get("score_mode", "match"))
    count_tol = float(meta.get("count_tol", 0.15))
    count_sigma = float(meta.get("count_sigma", 0.35))
    recall_floor = float(meta.get("recall_floor", 0.5))
    len_tol = float(meta.get("len_tol", 0.25))

    logger.info("GRIDSEARCH %d combos on %s, %s (%d GT lines)",
                len(combos), imn.shape,
                "SUPERVISED" if supervised else "UNSUPERVISED", len(gt_segs))

    results = []
    for i, combo in enumerate(combos):
        overrides = dict(base)
        overrides.update(combo)
        try:
            fibers = analyze_real(imn, {"prescaled": True, "overrides": overrides})
        except Exception as exc:  # noqa: BLE001
            logger.warning("  combo %d/%d failed (%s): %s", i + 1, len(combos), combo, exc)
            continue
        if supervised:
            sc = _score_supervised(fibers, gt_segs, match_dist, match_angle,
                                   score_mode=score_mode, count_tol=count_tol,
                                   count_sigma=count_sigma, recall_floor=recall_floor,
                                   len_tol=len_tol)
        else:
            thr = float(overrides.get("thresh_im2", 5) or 5)
            sc = _score_unsupervised(fibers, imn, thr)
        sc["overrides"] = overrides
        sc["_fibers"] = fibers  # kept only for the top_k, stripped below
        results.append(sc)
        # Per-combo trace so a run can be reconstructed / troubleshot from the server log.
        logger.info(
            "  combo %d/%d: seed=%s sens=%s ext=%.3f linka=%.3f -> n_fibers=%d score=%.4f%s",
            i + 1, len(combos),
            combo.get("thresh_LMPdist"), combo.get("thresh_LMP"),
            float(combo.get("thresh_ext", 0.0)), float(combo.get("thresh_linka", 0.0)),
            sc.get("n_fibers", sc.get("n_detected", 0)), sc.get("score", 0.0),
            (" matched=%d/%d" % (sc.get("matched", 0), sc.get("n_gt", 0))) if supervised else "")

    results.sort(key=lambda r: r["score"], reverse=True)
    # Tag the "good band" (within 5% of the best score) and surface it separately, so
    # callers can present a small range of acceptable settings rather than one brittle
    # point (human annotation is imperfect -- don't overfit to a single combo).
    best_score = results[0]["score"] if results else 0.0
    band_cut = best_score * 0.95 if best_score > 0 else 0.0
    best_band = []
    for rank, r in enumerate(results):
        r["in_band"] = bool(r["score"] >= band_cut and r["score"] > 0)
        if r["in_band"]:
            best_band.append(r["overrides"])
        if rank < top_k:
            r["fibers"] = r.pop("_fibers")
        else:
            r.pop("_fibers", None)
    logger.info("GRIDSEARCH best score=%s overrides=%s",
                results[0]["score"] if results else None,
                results[0]["overrides"] if results else None)
    return {"ok": True, "supervised": supervised, "n_combos": len(combos),
            "results": results, "best_band": best_band, "score_mode": score_mode}


# ---------------------------------------------------------------------------
# Isolated FIRE worker (so a C++ segfault can't take down the server)
# ---------------------------------------------------------------------------
# The worker code lives in fire_worker.py -- a deliberately lightweight module
# (no numpy at import time) so the worker can load the compiled backend BEFORE
# numpy/scipy and avoid the "DLL load failed ... procedure could not be found"
# version skew seen on MSYS2 spawn.
import fire_worker  # noqa: E402


def _inprocess_fire(fire_params_value, ll1, image):
    """FIRE + reconstruction in THIS process (no isolation). `image` is already
    the final 0-255 float image (normalisation handled by analyze_real)."""
    im3 = np.ascontiguousarray(image, dtype=np.float32)[np.newaxis, :, :]
    data = _FIRE(p=fire_params_value, im=im3, plotflag=0)
    if not data:
        return []
    return _fibers_from_fire(data, ll1)


def _get_executor():
    global _EXECUTOR
    if _EXECUTOR is None:
        ctx = multiprocessing.get_context("spawn")
        _EXECUTOR = concurrent.futures.ProcessPoolExecutor(
            max_workers=1, mp_context=ctx,
            initializer=fire_worker.worker_init, initargs=(str(_REPO_ROOT),),
        )
    return _EXECUTOR


_TRACE_EXECUTOR = None
_TRACE_WORKERS = int(os.environ.get("TMEQUANT_TRACE_WORKERS", "0") or 0)
_trace_lock = threading.Lock()


def _trace_worker_count(meta):
    """How many worker PROCESSES to trace with. 0/1 keeps everything in this process.

    Off unless asked for, because it changes where every tile is executed. The client sets
    it per request; the env var is the fallback for a server driven by something else.
    """
    try:
        n = int(float(meta.get("trace_workers", _TRACE_WORKERS) or 0))
    except (TypeError, ValueError):
        n = _TRACE_WORKERS
    return max(0, min(n, 32))


def _get_trace_executor(n):
    """Lazily build the tracer pool. Workers get the resolved FIRE defaults so they never
    have to load the pipeline (the flow and centerline tracers do not use it)."""
    global _TRACE_EXECUTOR
    with _trace_lock:
        if _TRACE_EXECUTOR is None:
            import trace_worker
            ctx = multiprocessing.get_context("spawn")
            _TRACE_EXECUTOR = concurrent.futures.ProcessPoolExecutor(
                max_workers=n, mp_context=ctx,
                initializer=trace_worker.init, initargs=(_DEFAULT_PARAMS,),
            )
            logger.info("Tracer pool: %d worker process(es). The flow/centerline tracers are "
                        "GIL-bound, so threads do not overlap them; processes do.", n)
        return _TRACE_EXECUTOR


def _analyze_in_worker(image, meta, mask, exclude, n):
    """Run one tile in the pool and replay its log lines here, so the run transcript still
    has them even though they were produced in another process."""
    import trace_worker
    fut = _get_trace_executor(n).submit(trace_worker.analyze, image, meta, mask, exclude)
    fibers, stats, text = fut.result()
    for line in text.splitlines():
        if line.strip():
            logger.info("[worker] %s", line)
    return fibers, stats


_SEED_HINT = (
    "This tile produced too many fibre fragments for FIRE's linking step to hold (it grows with "
    "the fibre COUNT and how many fibres cross at shared points, not overall brightness). Try, in "
    "order: raise 'Seed spacing' (fewer fibres), raise 'Downsample' (merges crossings and shrinks "
    "the tile), or enable 'Ridge enhancement' (thins solid bundles). Raising the threshold helps "
    "least. In a full run this single tile is skipped and every other tile still processes normally."
)


_WEB_HINT = (
    "The collagen in this tile is one CONNECTED WEB rather than separate fibres, so FIRE's "
    "extension step can walk the whole tile from any seed and its linking step has to hold all of "
    "it at once. Raising 'Seed spacing' does NOT help (the walk, not the seed count, is the cost) "
    "and raising 'Downsample' makes it WORSE (it merges the gaps that still separate the strands). "
    "What does help, in order: raise the 'Background threshold' until the web breaks apart, switch "
    "the Tracer to 'Centerline (medial axis)' (it has no linking step, so a web costs it nothing), "
    "or check the image's upper normalisation bound -- a web like this is usually a SATURATED tile "
    "whose blown-out collagen edges have fused into one network."
)


def _largest_component_skeleton(fg) -> int:
    """Skeleton length (px) of the LARGEST connected foreground component.

    This is what FIRE's extension + linking step actually has to hold: how far a single seed can
    walk, and how many junctions share that walk. Measured across real tiles it is the only cheap
    statistic that separates a tile tracing in seconds from one that never finishes -- foreground
    DENSITY does not (a tile with 30.8% foreground traced in 7 s while a 16.2% tile timed out),
    and neither do tile size, component count or seed spacing.

    Raw PIXEL count is not a substitute either: a thick solid bundle can be ~1M px yet have a
    short skeleton and trace fine (tile 918), while a thin filigree web has fewer pixels and a far
    longer skeleton. Costs ~0.5 s on a 2200px tile.
    """
    from skimage.morphology import skeletonize
    from scipy import ndimage as _nd
    lab, n = _nd.label(fg)
    if n == 0:
        return 0
    sk = skeletonize(fg)
    return int(_nd.sum(sk, lab, index=np.arange(1, n + 1)).max())


class FireWorkerCrash(RuntimeError):
    """The isolated FIRE worker died (segfault / std::terminate / OS kill).

    Contained by the subprocess boundary: the parent server survives. Raised
    with the SAME density root cause as a catchable bad_alloc, so the analysis
    retry loop treats it identically -- coarsen the seeding and try again.
    """


def _reset_executor():
    global _EXECUTOR
    try:
        if _EXECUTOR is not None:
            _EXECUTOR.shutdown(wait=False, cancel_futures=True)
    except Exception:  # noqa: BLE001
        pass
    _EXECUTOR = None


def _run_fire(fire_params_value, ll1, image):
    """Run FIRE, isolated in a worker subprocess when possible.

    A hard crash (segfault) or hang is contained: the worker dies, the server
    survives, and a clear error is raised. Normal analysis exceptions (e.g. a
    catchable MemoryError / bad_alloc) propagate to the caller unchanged.
    """
    global _USE_WORKER
    if not (_USE_WORKER and _REPO_ROOT is not None):
        return _inprocess_fire(fire_params_value, ll1, image)

    def _submit():
        return _get_executor().submit(fire_worker.worker_fire, fire_params_value, ll1, image)

    # The startup probe already confirmed the worker CAN load the backend on this host (else
    # _USE_WORKER was set False at startup and we returned in-process above). So a worker that is
    # gone between tiles is TRANSIENT (a previous tile crashed it): respawn and retry ONCE rather than
    # permanently dropping to in-process. In-process is exactly where a later dense tile would segfault
    # and take the WHOLE server down -- the failure mode this isolation exists to prevent. A one-off
    # respawn reloads the backend (~30 s) but only after an actual crash, never per tile.
    try:
        fut = _submit()
    except concurrent.futures.process.BrokenProcessPool:
        _reset_executor()
        raise FireWorkerCrash("FIRE crashed (segfault) - too dense a tile. " + _SEED_HINT)
    except Exception as exc:  # noqa: BLE001 - worker died between tiles; respawn + retry once
        logger.warning("FIRE worker submit failed (%s); respawning the worker and retrying.", exc)
        _reset_executor()
        try:
            fut = _submit()
        except Exception as exc2:  # noqa: BLE001
            _reset_executor()
            raise FireWorkerCrash(
                "FIRE worker could not be respawned (%s) - this tile is skipped; the server stays up. "
                "%s" % (exc2, _SEED_HINT))

    # A normal tile traces in a few seconds; one that takes minutes is pathologically dense and won't
    # give usable fibres anyway, so fail it FAST and let the full run skip it (the old 10-min cap made
    # a dense-heavy run take hours — every bad tile burned 10 minutes). Configurable via env.
    fire_timeout = _FIRE_TIMEOUT_OVERRIDE if _FIRE_TIMEOUT_OVERRIDE else float(
        os.environ.get("TMEQUANT_FIRE_TIMEOUT", "180") or 180)
    try:
        return fut.result(timeout=fire_timeout)
    except concurrent.futures.process.BrokenProcessPool:
        _reset_executor()
        raise FireWorkerCrash("FIRE crashed (segfault) - too dense a tile. " + _SEED_HINT)
    except concurrent.futures.TimeoutError:
        _reset_executor()
        raise RuntimeError("FIRE timed out (%.0f s) - too dense. %s" % (fire_timeout, _SEED_HINT))
    # Any other exception (MemoryError, etc.) propagates so run_analysis can
    # report it (e.g. its bad_alloc hint).


# ---------------------------------------------------------------------------
# Analysis entry points
# ---------------------------------------------------------------------------
def _is_oom_error(exc: Exception) -> bool:
    """True for a C++ bad_alloc / Python OOM that a coarser seeding could avoid."""
    if isinstance(exc, MemoryError):
        return True
    msg = str(exc).lower()
    return "bad_alloc" in msg or "bad alloc" in msg or "out of memory" in msg


def _is_recoverable_dense(exc: Exception) -> bool:
    """True for the failure modes a denser-tile workaround (raise threshold /
    seed spacing) can fix: a catchable bad_alloc/OOM, OR a contained worker crash
    (segfault / std::terminate) surfaced as FireWorkerCrash. Both stem from a tile
    that is too dense, so both are retried with progressively coarser seeding."""
    return isinstance(exc, FireWorkerCrash) or _is_oom_error(exc)


def _ridge_enhance(imn: np.ndarray, meta: dict):
    """Optional grayscale ridge / vesselness pre-filter (experimental).

    FIRE thresholds raw intensity and traces the distance-map of the resulting binary
    blob, so a solid bright bundle's medial axis runs ACROSS its width and internal
    striations are lost. A multiscale Hessian ridge filter (Frangi/Sato) instead scores
    how line-like each pixel is at a few scales, lighting up parallel ridges inside a
    bundle and going near-zero between fibres. Returning that 0-255 "fiberness" map in
    place of the intensity image makes FIRE's threshold + distance map operate on
    structure, not brightness: bundles trace ALONG their axis, faint fibres survive, and
    cross-gap jumps are rejected (the gap is ~0 in the fiberness map).

    No-op unless meta['ridge_enable'] is truthy. Lives here (our bridge) so tme-quant
    stays vanilla. Runs on the already-normalised 0-255 tile; the response replaces it,
    so downstream frac_above / density-guard / thresh_im2 all see the fiberness map
    (the Background threshold must be RE-TUNED for ridge mode -- its scale differs).

    meta keys (all optional):
      ridge_enable  bool   turn it on
      ridge_method  str    "frangi" (default) or "sato"
      ridge_scales  [px]   Hessian sigmas in ANALYSED px (~fibre half-widths);
                           default [1,2,3,4]
      ridge_beta    float  Frangi blob-suppression (default 0.5)
      ridge_gamma   float  Frangi contrast; omit for skimage's auto (half max Hessian)
    """
    # Keys may arrive at the top level of meta (dedicated UI) OR inside meta['overrides']
    # (so the free-form "Extra params (JSON)" box can toggle it without a client rebuild).
    _ov = meta.get("overrides") or {}
    def _rg(key, default=None):
        v = meta.get(key)
        return _ov.get(key, default) if v is None else v

    if not _rg("ridge_enable"):
        return imn, False
    try:
        from skimage.filters import frangi, sato
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "Ridge filter requested but scikit-image is unavailable (%s); tracing raw "
            "intensity instead.", exc)
        return imn, False

    scales = _rg("ridge_scales") or [1.0, 2.0, 3.0, 4.0]
    try:
        sigmas = [float(s) for s in scales if float(s) > 0]
    except (TypeError, ValueError):
        sigmas = []
    if not sigmas:
        sigmas = [1.0, 2.0, 3.0, 4.0]

    src = np.ascontiguousarray(imn, dtype=np.float32)
    method = str(_rg("ridge_method", "frangi")).lower()
    try:
        if method == "sato":
            resp = sato(src, sigmas=sigmas, black_ridges=False)
        else:
            kw = {"sigmas": sigmas, "black_ridges": False,
                  "beta": float(_rg("ridge_beta", 0.5) or 0.5)}
            if _rg("ridge_gamma") is not None:
                kw["gamma"] = float(_rg("ridge_gamma"))
            resp = frangi(src, **kw)
    except Exception as exc:  # noqa: BLE001
        logger.warning("Ridge filter failed (%s); tracing raw intensity instead.", exc)
        return imn, False

    resp = np.nan_to_num(resp, nan=0.0, posinf=0.0, neginf=0.0)
    mx = float(resp.max())
    if mx <= 0.0:
        logger.warning("Ridge filter produced an empty response; tracing raw intensity.")
        return imn, False
    out = np.ascontiguousarray(resp * (255.0 / mx), dtype=np.float32)
    logger.info(
        "Ridge filter ON (%s, sigmas=%s): replaced intensity with a fiberness map "
        "(max response %.4g). NOTE: re-tune the Background threshold for ridge mode.",
        method, sigmas, mx)
    return out, True


def _ridge_threshold(fiberness: np.ndarray) -> int:
    """Pick a FIRE ``thresh_im2`` for a 0-255 fibre-ness map (ridge mode).

    A PERCENTILE of the positive response, not Otsu. Otsu assumes the data is bimodal and splits
    two peaks; a fibre-ness map is not bimodal -- it is a long tail of weak responses with no second
    mode -- so Otsu lands far too high and keeps only the strongest ridges. Measured on a thick
    band: Otsu chose 74 and traced 32 fibres, while the 60th percentile chose 19 and traced 98 over
    four times the length. (That percentile was already this function's fallback; it was simply
    never reached.) Computed on the positive response only, since the map is mostly exact zeros.

    Clamped to a sane band so a degenerate map can't send it to 0 (everything passes -> density
    blow-up) or 255 (nothing passes -> 0 fibres). The proactive density guard still runs afterwards,
    so this only needs to be in the right ballpark.
    """
    pos = fiberness[fiberness > 0]
    if pos.size < 16:
        return 8
    t = float(np.percentile(pos, _cfg({}, "ridge_pct", "TMEQUANT_RIDGE_PCT", 60.0)))
    return int(min(200, max(4, round(t))))



# --- Flow (orientation-field) tracer ------------------------------------------------------------
# Follows the local collagen DIRECTION rather than the shape of the threshold mask. Inside a thick
# bundle the mask is one solid lump, so a medial axis describes its outline (a honeycomb mesh) and
# FIRE's distance transform is equally blind -- but the fibre direction is still there in the
# intensity striations. Scored against hand-drawn ground truth this beat the centreline tracer on
# BOTH recall (61.1% vs 53.7%) and precision (54.1% vs 50.7%).

def _flow_orientation(image, sigmas=(2.0, 4.0, 8.0)):
    """Per-pixel fibre direction + confidence, from the HESSIAN (a ridge detector).

    The structure tensor (first derivatives) peaks on the intensity BOUNDARY and runs along it, so
    streamlines seeded from it trace bundle outlines. The Hessian (second derivatives) peaks on the
    ridge CREST -- the fibre centre. Measured: it cut edge-hugging traces from 44% to 23% of length
    inside thick structures, below the 36% you would get by chance.

    mode="reflect" is NOT optional: skimage defaults to zero padding, which turns the image border
    into a step edge and makes the field report "along the border", confidently (73 deg wrong at
    0.53 confidence on synthetic stripes) -- streamlines then run perpendicular to the collagen at
    every tile edge.
    """
    from skimage.feature import hessian_matrix
    best_r = best_t = None
    for sg in sigmas:
        Hrr, Hrc, Hcc = hessian_matrix(image, sigma=sg, order="rc",
                                       use_gaussian_derivatives=False, mode="reflect")
        a, b, c = Hcc, Hrc, Hrr
        tr = (a + c) / 2.0
        dd = np.sqrt(((a - c) / 2.0) ** 2 + b * b)
        l1 = tr + dd
        l2 = tr - dd                      # most negative = across a bright ridge
        ridgeness = np.maximum(-l2, 0) * (sg ** 2)   # gamma-normalised so scales compare
        ex, ey = b, (l1 - a)                         # eigenvector of l1 = ALONG the fibre
        n = np.hypot(ex, ey)
        n[n < 1e-12] = 1.0
        theta = np.arctan2(ey / n, ex / n)
        if best_r is None:
            best_r, best_t = ridgeness, theta
        else:
            m = ridgeness > best_r
            best_r = np.where(m, ridgeness, best_r)
            best_t = np.where(m, theta, best_t)
    hi = np.percentile(best_r, 99.5) or 1.0
    return best_t, np.clip(best_r / hi, 0, 1)


def _flow_sample(a, x, y):
    """Bilinear sample of `a`, vectorised over N points."""
    return _flow_gather(a.reshape(-1), _flow_weights(a.shape, x, y))


def _flow_weights(shape, x, y):
    """Bilinear gather indices + weights for points (x, y), as FLAT offsets.

    Split out from the sampling because the walk reads THREE fields -- coherence and the two
    direction components -- at the same positions on every step, and used to redo the clip,
    the int conversion, the fractions and the four corner indices once per field. Profiling a
    single tile put 66% of the whole trace in this arithmetic. Flat offsets also let the
    gather use take() rather than 2-D fancy indexing, which is appreciably faster.
    """
    h, w = shape
    xc = np.clip(x, 0, w - 1.001)
    yc = np.clip(y, 0, h - 1.001)
    x0 = xc.astype(np.int32)
    y0 = yc.astype(np.int32)
    fx = xc - x0
    fy = yc - y0
    x1 = np.minimum(x0 + 1, w - 1)
    y1 = np.minimum(y0 + 1, h - 1)
    r0 = y0 * w
    r1 = y1 * w
    gx = 1.0 - fx
    gy = 1.0 - fy
    return (r0 + x0, r0 + x1, r1 + x0, r1 + x1, gx * gy, fx * gy, gx * fy, fx * fy)


def _flow_gather(flat, wts):
    """Bilinear value of one FLATTENED field at the positions `wts` was built for."""
    i00, i01, i10, i11, w00, w01, w10, w11 = wts
    return (flat.take(i00) * w00 + flat.take(i01) * w01
            + flat.take(i10) * w10 + flat.take(i11) * w11)


def _flow_walk(ux, uy, coh, mask, sx, sy, sign, step, max_steps, coh_min, maxturn):
    """March every seed one way at once. Returns (steps, N, 2) positions and per-seed step counts."""
    n = len(sx)
    uxf, uyf, cohf = ux.reshape(-1), uy.reshape(-1), coh.reshape(-1)
    x = sx.astype(np.float32).copy()
    y = sy.astype(np.float32).copy()
    w0 = _flow_weights(ux.shape, x, y)
    dx = _flow_gather(uxf, w0) * sign
    dy = _flow_gather(uyf, w0) * sign
    # (max_steps, n, 2) float32. At the old batch of 20000 seeds this was a 128 MB buffer,
    # allocated and NaN-filled twice per batch, and the walk then strode through it every
    # step -- the tracer was memory-bound, not compute-bound. Batching at 2000 keeps it to
    # ~13 MB and measured 2.8x faster on one tile, with bit-identical output (seeds are
    # walked independently, so batching changes grouping only).
    traj = np.full((max_steps, n, 2), np.nan, dtype=np.float32)
    alive = np.ones(n, bool)
    nsteps = np.zeros(n, np.int32)
    h, w = mask.shape
    cosmax = np.cos(maxturn)
    for k in range(max_steps):
        if not alive.any():
            break
        nx = x + dx * step
        ny = y + dy * step
        ok = alive & (nx >= 0) & (nx < w) & (ny >= 0) & (ny < h)
        xi = np.clip(nx, 0, w - 1).astype(np.int32)
        yi = np.clip(ny, 0, h - 1).astype(np.int32)
        ok &= mask[yi, xi]
        wts = _flow_weights(ux.shape, nx, ny)   # once, then read all three fields with it
        ok &= _flow_gather(cohf, wts) >= coh_min
        vx = _flow_gather(uxf, wts)
        vy = _flow_gather(uyf, wts)
        flip = (vx * dx + vy * dy) < 0          # the field is undirected; keep going the same way
        vx = np.where(flip, -vx, vx)
        vy = np.where(flip, -vy, vy)
        ok &= (vx * dx + vy * dy) >= cosmax     # a real fibre does not kink that hard
        alive &= ok
        if not alive.any():
            break
        x = np.where(alive, nx, x)
        y = np.where(alive, ny, y)
        dx = np.where(alive, vx, dx)
        dy = np.where(alive, vy, dy)
        traj[k][alive] = np.stack([x[alive], y[alive]], axis=1)
        nsteps += alive
    return traj, nsteps


def trace_flow(image, mask, parallel_px=17.0, coh_min=0.05, step=1.5, max_len=1200,
               max_turn_deg=35.0, min_len_px=23.0, batch=2000):
    """Trace fibres by following the orientation field; keep the LONGEST, spaced `parallel_px` apart.

    `parallel_px` is the "parallel fibre distance": the minimum lateral spacing between kept fibres
    in one bundle. A thick bundle really does contain many fibres, so tracing dozens through it is
    not an error -- it is the truth, and it would also sink QuPath. This makes the sampling
    explicit. CONSEQUENCE: fibre COUNT and DENSITY scale with it, so they sample the population
    rather than measure it; per-fibre geometry (length, angle, waviness) is unaffected.

    Candidates are accepted LONGEST-first, each claiming a corridor. Selecting by confidence
    instead hands each bundle to its most confidently-oriented fibre, which is usually a short one.
    """
    from scipy import ndimage as _nd
    th, coh = _flow_orientation(image)
    ux = np.cos(th).astype(np.float32)
    uy = np.sin(th).astype(np.float32)
    coh = coh.astype(np.float32)
    h, w = image.shape
    seed_spacing = max(2, int(parallel_px / 3))
    ys, xs = np.mgrid[0:h:seed_spacing, 0:w:seed_spacing]
    ys = ys.ravel()
    xs = xs.ravel()
    keep = mask[ys, xs] & (coh[ys, xs] >= coh_min)
    ys = ys[keep]
    xs = xs[keep]
    if len(xs) == 0:
        return []
    max_steps = int(max_len / step)
    maxturn = np.radians(max_turn_deg)
    cands = []
    for b0 in range(0, len(xs), batch):
        bx = xs[b0:b0 + batch].astype(np.float32)
        by = ys[b0:b0 + batch].astype(np.float32)
        fwd, nf = _flow_walk(ux, uy, coh, mask, bx, by, 1.0, step, max_steps, coh_min, maxturn)
        bwd, nb = _flow_walk(ux, uy, coh, mask, bx, by, -1.0, step, max_steps, coh_min, maxturn)
        for i in range(len(bx)):
            pts = np.concatenate([bwd[:nb[i], i][::-1],
                                  np.array([[bx[i], by[i]]], np.float32),
                                  fwd[:nf[i], i]])
            if len(pts) < 3:
                continue
            ln = float(np.hypot(*np.diff(pts, axis=0).T).sum())
            if ln >= min_len_px:
                cands.append((ln, pts))
    cands.sort(key=lambda t: -t[0])
    visited = np.zeros((h, w), bool)
    r = max(1, int(round(parallel_px / 2.0)))
    dist = _nd.distance_transform_edt(mask)
    out = []
    for ln, pts in cands:
        pi = pts.astype(np.int32)
        pi[:, 0] = np.clip(pi[:, 0], 0, w - 1)
        pi[:, 1] = np.clip(pi[:, 1], 0, h - 1)
        mid = pi[len(pi) // 2]
        if visited[mid[1], mid[0]]:
            continue
        s = pi[::3]
        if visited[s[:, 1], s[:, 0]].mean() > 0.5:
            continue       # mostly retraces a fibre already kept
        for px, py in pi[::2]:
            visited[max(0, py - r):py + r + 1, max(0, px - r):px + r + 1] = True
        pl = [[float(a), float(b)] for a, b in pts]
        arc, end, ang, straight = _poly_metrics(pl)
        wid = dist[pi[:, 1], pi[:, 0]] * 2.0     # same width measure the centreline tracer uses
        out.append({
            "points": pl, "length_arc": arc, "length_end": end, "angle": ang,
            "straightness": straight,
            "center": [float(pts[len(pts) // 2, 0]), float(pts[len(pts) // 2, 1])],
            "width": float(np.median(wid)), "width_max": float(wid.max()),
            "width_std": float(wid.std()),
        })
    for i, f in enumerate(out):
        f["id"] = i
    return out


# --- Centerline (medial-axis) tracer -----------------------------------------------------------
# An alternative to FIRE's seed-and-extend: threshold -> mild smooth -> skeletonize -> split at
# junctions -> re-link smooth continuations -> order each chain into a polyline. Because it follows
# the bundle's MEDIAL AXIS it keeps crimp/waviness (including sharp kinks), which the seeded tracer
# tends to smooth or fragment. Emits the SAME fibre dicts, so every downstream measurement, the CSV,
# stitching and TACS work unchanged. Width comes from the distance transform along the centreline.

def _cl_branches(sk):
    """Split a skeleton into branch pixel-sets by removing junction pixels."""
    from scipy import ndimage as _nd
    nb8 = np.ones((3, 3), dtype=int)  # built lazily: np is bound after backend load
    deg = _nd.convolve(sk.astype(int), nb8, mode="constant") - sk.astype(int)
    junc = sk & (deg >= 3)
    lab, n = _nd.label(sk & ~junc, structure=np.ones((3, 3)))
    return lab, n, junc


def _cl_order(coords):
    """Order a branch's pixels into a path: start at an endpoint, walk neighbour to neighbour."""
    pts = [tuple(c) for c in coords]
    if len(pts) <= 2:
        return pts
    s = set(pts)

    def nbrs(p):
        r, c = p
        return [(r + dr, c + dc) for dr in (-1, 0, 1) for dc in (-1, 0, 1)
                if (dr or dc) and (r + dr, c + dc) in s]
    ends = [p for p in pts if len(nbrs(p)) <= 1]
    cur = ends[0] if ends else pts[0]
    path, seen = [cur], {cur}
    while True:
        nxt = [q for q in nbrs(cur) if q not in seen]
        if not nxt:
            break
        cur = min(nxt, key=lambda q: abs(q[0] - cur[0]) + abs(q[1] - cur[1]))
        path.append(cur)
        seen.add(cur)
    return path


def _cl_link(paths, junc, max_turn_deg=60.0, tip=5):
    """Chain branches that continue SMOOTHLY through a junction (side-branches stay separate)."""
    from scipy import ndimage as _nd
    ends = []
    for bi, p in enumerate(paths):
        a = np.asarray(p, dtype=float)
        k = min(tip, len(a) - 1)
        d0 = a[0] - a[k]; d0 /= (np.linalg.norm(d0) + 1e-9)
        d1 = a[-1] - a[-1 - k]; d1 /= (np.linalg.norm(d1) + 1e-9)
        ends.append((bi, 0, p[0], d0))
        ends.append((bi, 1, p[-1], d1))
    jl, _ = _nd.label(junc, structure=np.ones((3, 3)))
    H, W = jl.shape
    at_j = {}
    for idx, (bi, we, (r, c), dv) in enumerate(ends):
        for dr in (-1, 0, 1):
            for dc in (-1, 0, 1):
                rr, cc = r + dr, c + dc
                if 0 <= rr < H and 0 <= cc < W and jl[rr, cc] > 0:
                    at_j.setdefault(int(jl[rr, cc]), set()).add(idx)
    partner = {}
    for _j, idxs in at_j.items():
        idxs = sorted(idxs)
        cand = []
        for ai in range(len(idxs)):
            for bi2 in range(ai + 1, len(idxs)):
                ia, ib = idxs[ai], idxs[bi2]
                if ends[ia][0] == ends[ib][0]:
                    continue
                dot = float(np.dot(ends[ia][3], ends[ib][3]))
                turn = 180.0 - math.degrees(math.acos(max(-1.0, min(1.0, dot))))
                cand.append((turn, ia, ib))
        cand.sort()
        for turn, ia, ib in cand:
            if turn > max_turn_deg:
                break
            if ia in partner or ib in partner:
                continue
            partner[ia] = ib
            partner[ib] = ia
    n = len(paths)
    used, chains = set(), []
    for se in [e for e in range(2 * n) if e not in partner]:
        if (se // 2) in used:
            continue
        chain, cur = [], se
        while True:
            b, w = cur // 2, cur % 2
            if b in used:
                break
            used.add(b)
            chain.append((b, w))
            nxt = partner.get(b * 2 + (1 - w))
            if nxt is None:
                break
            cur = nxt
        if chain:
            chains.append(chain)
    for b in range(n):
        if b not in used:
            used.add(b)
            chains.append([(b, 0)])
    return chains


def trace_centerlines(gray, thr, smooth_sigma=2.0, min_len_px=20.0, min_obj=60,
                      simplify_tol=1.2, link_turn_deg=60.0):
    """Trace fibre centrelines as the medial axis of the thresholded collagen."""
    from scipy import ndimage as _nd
    from skimage.filters import gaussian as _gauss
    from skimage.morphology import skeletonize as _skel, remove_small_objects as _rso
    from skimage.measure import approximate_polygon as _approx
    gs = _gauss(gray, sigma=smooth_sigma, preserve_range=True) if smooth_sigma > 0 else gray
    mask = _rso(gs > thr, int(min_obj))
    sk = _skel(mask)
    dist = _nd.distance_transform_edt(mask)
    lab, n, junc = _cl_branches(sk)
    objs = _nd.find_objects(lab)
    paths = []
    for i in range(1, n + 1):
        sl = objs[i - 1]
        if sl is None:
            continue
        coords = np.argwhere(lab[sl] == i)
        if len(coords) < 3:
            continue
        coords[:, 0] += sl[0].start
        coords[:, 1] += sl[1].start
        p = _cl_order(coords)
        if len(p) >= 3:
            paths.append(p)
    if not paths:
        return []
    fibers = []
    for chain in _cl_link(paths, junc, max_turn_deg=link_turn_deg):
        rc_list = []
        for b, w in chain:
            seg = paths[b] if w == 0 else paths[b][::-1]
            if rc_list and rc_list[-1] == seg[0]:
                seg = seg[1:]
            rc_list.extend(seg)
        if len(rc_list) < 3:
            continue
        rc = np.asarray(rc_list, dtype=float)
        rc_s = _approx(rc, tolerance=simplify_tol)   # tight tolerance so the crimp survives
        if len(rc_s) < 2:
            continue
        pts_xy = [[float(c), float(r)] for r, c in rc_s]
        arc, end, ang, straight = _poly_metrics(pts_xy)
        if arc < min_len_px:
            continue
        wv = 2.0 * dist[rc[:, 0].astype(int), rc[:, 1].astype(int)]
        mid = pts_xy[len(pts_xy) // 2]
        fibers.append({
            "id": len(fibers), "points": pts_xy, "center": [mid[0], mid[1]], "angle": ang,
            "length_arc": arc, "length_end": end, "straightness": straight,
            "width": float(np.mean(wv)), "width_max": float(np.max(wv)),
            "width_std": float(np.std(wv)), "tacs": None,
        })
    return fibers


def analyze_real(image: np.ndarray, meta: dict, mask=None, stats_out=None, exclude=None) -> list:
    """Run FIRE directly and return one polyline per fiber.

    Replicates _run_fire_only_on_image's normalisation, then reconstructs the
    full centerlines from the fire_2d_angle output (richer than the flattened
    per-vertex fiber_structure DataFrame). When `mask` is provided, each fiber
    is also TACS-classified relative to that boundary.

    `exclude` is an optional region-sized "don't trace here" mask (nuclei imported from a paired
    brightfield image, blob detections, or any user regions). See _apply_exclusion.
    """
    params = meta.get("params")
    import copy as _copy
    resolved = _copy.deepcopy(params if params is not None else _DEFAULT_PARAMS)

    # Apply lightweight per-request overrides from the QuPath dialog. Most keys
    # map into the FIRE "value" dict; a few live at the top level of the params.
    _TOP_LEVEL = {
        "LL1", "widMAX", "coefficient_percentile", "num_scales", "fiber_threshold",
    }
    overrides = meta.get("overrides") or {}
    for k, v in overrides.items():
        if v is None:
            continue
        if k in _TOP_LEVEL:
            resolved[k] = v
        else:
            resolved.setdefault("value", {})[k] = v

    fire_p = resolved["value"].copy()
    ll1 = float(resolved.get("LL1", 30) or 30)

    # RAW mode: run FIRE / tme-quant exactly as a plain Python run would, with NONE of the
    # bridge's own guards, retries, or cleanup. Skips the density + seed guards, the dense-tile
    # param-easing retries, ridge pre-filter, bridge-split, edge-trim and cross-bar removal.
    # KEEPS only what is either tme-quant's own extraction (LL1 min-length, widMAX max-width) or
    # transport infrastructure (16-bit prescale, square-padding for the FIRE stride bug + the
    # pad-margin filter). If a tile crashes/OOMs/times out, it simply yields no fibres (no retry,
    # no param change). Meta key: raw_mode (bool).
    raw_mode = bool(meta.get("raw_mode"))
    if raw_mode:
        logger.info("RAW mode ON: bypassing all bridge guards/retries/cleanup "
                    "(density guard, seed guard, dense-retry, ridge, bridge-split, edge-trim, "
                    "cross-bar). Output = plain FIRE + tme-quant LL1/widMAX only.")

    # Some FIRE params are used as Python ints (e.g. ang_interval feeds np.arange/range
    # in the angle step, s_xlinkbox/s_fiberdir are C++ int args). A GUI sends them as JSON
    # numbers that decode to float (5.0), which raises "'float' object cannot be
    # interpreted as an integer". Coerce the known integer params so any caller is safe.
    _INT_FIRE_PARAMS = (
        "ang_interval", "s_xlinkbox", "s_fiberdir", "s_minstep", "s_maxstep",
        "num_scales", "thresh_LMPdist",
    )
    for _k in _INT_FIRE_PARAMS:
        if fire_p.get(_k) is not None:
            try:
                fire_p[_k] = int(round(float(fire_p[_k])))
            except (TypeError, ValueError):
                pass

    img = image
    if img.ndim == 3:
        img = img[..., 0]
    imn = np.ascontiguousarray(img).astype(np.float32)
    # When the client has already scaled the tile to a consistent 0-255 range
    # (meta.prescaled -- using a GLOBAL reference so every tile and the threshold
    # share one scale), do NOT re-normalise per tile. That per-tile max scaling is
    # what amplified noise in near-black border tiles into spurious fibers.
    if not bool(meta.get("prescaled")) and imn.max() > 0:
        imn = imn / imn.max() * 255.0
    elif bool(meta.get("prescaled")) and imn.max() > 255.0:
        # DEFENSIVE: a 'prescaled' tile must already be 0-255. If a caller ever sends a 16-bit array
        # (PIL 'I;16' -> uint16, 0-65535), the 0-255 thresh_im2 below would pass ~every pixel ->
        # 100% foreground -> density-guard exhaustion / crash. Normalise instead of melting down.
        logger.warning("prescaled tile arrived with values up to %.0f (expected 0-255) -- the client "
                       "should send 8-bit; normalising defensively.", float(imn.max()))
        imn = imn / imn.max() * 255.0
    # Optional grayscale ridge/vesselness pre-filter (experimental). When enabled it
    # replaces the intensity image with a 0-255 "fiberness" map BEFORE thresholding, so
    # everything below (frac_above, density guard, FIRE threshold + distance map) traces
    # line-like structure instead of brightness. No-op unless meta['ridge_enable'].
    imn, ridge_applied = (imn, False) if raw_mode else _ridge_enhance(imn, meta)
    if ridge_applied:
        # The client's thresh_im2 is tuned for RAW intensity and is floored high (~155) so bright
        # collagen keeps its gradient. That floor is meaningless on a fibre-ness map (its energy
        # sits low), so at 155 nothing survives -> 0 fibres. Pick a data-driven threshold from the
        # map itself (Otsu, separating ridges from background), overriding whatever the client sent.
        old_thr = fire_p.get("thresh_im2")
        new_thr = _ridge_threshold(imn)
        fire_p = dict(fire_p)
        fire_p["thresh_im2"] = new_thr
        logger.info("Ridge mode: overrode thresh_im2 %s -> %d (Otsu on the fibre-ness map).",
                    old_thr, new_thr)
    # EXCLUSION MASK ("don't trace here"). Validate the shape once, here, so every use below can
    # trust it. RAW mode is a promise of an unmanipulated plain-Python run, so the mask is ignored
    # there (loudly) rather than silently changing what "raw" means.
    excl = None
    if exclude is not None:
        if raw_mode:
            logger.warning("RAW mode is on -- the exclusion mask was IGNORED (raw mode runs FIRE "
                           "with no manipulation by the bridge). Turn raw mode off to use it.")
        elif exclude.shape != imn.shape:
            logger.warning("Exclusion mask %s does not match the tile %s -- ignored.",
                           exclude.shape, imn.shape)
        else:
            excl = exclude.astype(bool)
            logger.info("Exclusion mask: %.1f%% of the tile is excluded from fibre tracing.",
                        100.0 * excl.mean())

    # Optional HARD ERASE: remove the excluded pixels from the image BEFORE thresholding, so they
    # cannot be traced at all AND stop inflating the density/seed guards below (on a nucleus-rich
    # field ~34% of the thresholded "collagen" was nuclei, which drove the guard to raise the
    # threshold and under-detect real collagen). The cost is that a fibre CROSSING an excluded
    # object is cut in two, so this is off by default -- the graded post-trace filter is preferred.
    if excl is not None and _cfg(meta, "exclude_erase", "TMEQUANT_EXCLUDE_ERASE", 0.0) > 0:
        imn = imn.copy()
        imn[excl] = 0.0
        logger.info("Exclusion mask: ERASED from the image before tracing (hard mode) -- fibres "
                    "crossing an excluded object will be cut.")

    thr = fire_p.get("thresh_im2", 5) or 5
    frac_above = float(np.count_nonzero(imn > thr)) / max(1, imn.size)
    logger.info(
        "FIRE params: thresh_im2=%s LL1=%s thresh_LMPdist=%s prescaled=%s | %.1f%% of pixels > threshold",
        fire_p.get("thresh_im2"), ll1, fire_p.get("thresh_LMPdist"),
        bool(meta.get("prescaled")), frac_above * 100.0,
    )
    # --- Pad NON-SQUARE images to a square before tracing (FIRE stride bug) ---
    # The compiled backend's extend_xlink / find_local_max take the image HEIGHT as
    # the row stride instead of the WIDTH: CPP/extend_xlink_native.cpp does
    # `engine(sizey, sizez)` (height, width) but the engine uses its first arg as the
    # stride (`image[r*stride + c]`). When height != width this reinterprets the
    # row-major buffer as its (wrong) reshape: horizontal pixel adjacency is preserved
    # but vertical adjacency is scrambled, so the tracer collapses EVERY fiber to a
    # spurious horizontal line (angles pinned to 0/180, fibers in empty space). Square
    # images are unaffected (stride == height == width), which is why near-square tiles
    # looked fine. Padding to S x S (real image kept at the top-left, zero-padded
    # margin = background, so no fibers are traced there and coordinates are unchanged)
    # makes the stride correct. Proper fix is upstream: swap to `engine(sizez, sizey)`.
    orig_h, orig_w = imn.shape
    padded_to_square = orig_h != orig_w
    if padded_to_square:
        side = max(orig_h, orig_w)
        square = np.zeros((side, side), dtype=imn.dtype)
        square[:orig_h, :orig_w] = imn
        imn = np.ascontiguousarray(square)
        if excl is not None:
            esq = np.zeros((side, side), dtype=bool)
            esq[:orig_h, :orig_w] = excl
            excl = esq  # keep the mask in the same frame as the fibre coordinates
        logger.info("Padded non-square %dx%d tile to %dx%d (FIRE stride workaround).",
                    orig_w, orig_h, side, side)

    # Run FIRE, gracefully degrading a too-dense tile instead of crashing. A
    # std::bad_alloc here is NOT real RAM exhaustion -- it is the C++ backend making
    # one oversized allocation when the foreground is pathologically dense (e.g. 40%+
    # of pixels above threshold => a huge seed/link structure). Coarsening the seed
    # spacing slashes the seed count, so we retry with progressively larger spacing
    # rather than failing the tile outright.
    seed_used = float(fire_p.get("thresh_LMPdist", 2) or 2)
    thr_used = float(fire_p.get("thresh_im2", 5) or 5)
    seed_bumped = False  # "params eased" flag (kept name for stats compatibility)

    # TRACER SELECTION. "centerline" replaces FIRE's seed-and-extend with the medial axis of the
    # thresholded collagen (keeps crimp/kinks, no nucleation spokes). It needs none of the dense-tile
    # guards or the bad_alloc retry (there is no seed/link explosion), so those are skipped; every
    # downstream step (pad filter, edge trim, flow filter, LL1, widMAX, stats) runs unchanged.
    tracer = str(meta.get("tracer", "fire") or "fire").lower()
    use_centerline = (tracer == "centerline")
    use_flow = (tracer == "flow")
    cl_fibers = None
    if use_centerline:
        cl_fibers = trace_centerlines(
            imn, thr_used,
            smooth_sigma=_cfg(meta, "cl_smooth", "TMEQUANT_CL_SMOOTH", 2.0),
            min_len_px=max(1.0, ll1),
            link_turn_deg=_cfg(meta, "cl_link_turn", "TMEQUANT_CL_LINK_TURN", 60.0))
        logger.info("Centerline tracer: %d fibre(s) from the medial axis (threshold %.0f).",
                    len(cl_fibers), thr_used)
    elif use_flow:
        cl_fibers = trace_flow(
            imn, imn > thr_used,
            parallel_px=_cfg(meta, "flow_parallel_px", "TMEQUANT_FLOW_PARALLEL", 17.0),
            min_len_px=max(1.0, ll1))
        logger.info("Flow tracer: %d fibre(s) from the orientation field (threshold %.0f, "
                    "parallel spacing %.0f px).", len(cl_fibers), thr_used,
                    _cfg(meta, "flow_parallel_px", "TMEQUANT_FLOW_PARALLEL", 17.0))

    # PROACTIVE density guard. The reactive retry below only helps for a CATCHABLE
    # failure (bad_alloc / an isolated-worker crash). When FIRE runs in-process
    # (worker isolation unavailable on this host), a pathologically dense tile
    # instead SEGFAULTS with no catchable exception and kills the whole server.
    # The only defence there is to never hand the tracer that density: if the
    # foreground fraction is above a safe cap, raise the threshold up-front (the
    # same lever the retry uses) until it drops under the cap. Tiles below the cap
    # are untouched, so normal images are unaffected.
    DENSITY_CAP = _cfg(meta, "density_cap", "TMEQUANT_DENSITY_CAP", 0.35)
    guard_frac = frac_above
    guarded = False
    while (not raw_mode) and (not use_centerline) and (not use_flow) and guard_frac > DENSITY_CAP and thr_used < 254.0:
        thr_used = min(254.0, thr_used * 1.4 + 5.0)
        guard_frac = float(np.count_nonzero(imn > thr_used)) / max(1, imn.size)
        guarded = True
    if guarded:
        fire_p = dict(fire_p)
        fire_p["thresh_im2"] = thr_used
        seed_bumped = True
        logger.warning(
            "Proactive density guard: %.1f%% of pixels were above threshold (cap %.0f%%) on a "
            "%dx%d tile -- raised threshold to %.0f (now %.1f%% fg) BEFORE tracing to avoid an "
            "in-process crash. Raise the Background threshold for cleaner, intended results.",
            frac_above * 100.0, DENSITY_CAP * 100.0, imn.shape[1], imn.shape[0],
            thr_used, guard_frac * 100.0,
        )

    # PROACTIVE "giant web" guard -- the one that catches the timeouts the density guard misses.
    #
    # A tile hangs in "Extending nucleation points" when its foreground forms ONE enormous
    # connected component: every seed can then walk the whole network and the linking step must
    # hold all of it. Density does NOT predict this (a 30.8% tile traced in 7 s while a 16.2% tile
    # timed out at 180 s), which is why the cap above let these through. Measured on real tiles:
    #
    #     largest-component skeleton   result
    #        7,600 -  19,000 px        traces in seconds   (tiles 290/337/484/916)
    #          105,556 px              traces fine         (tile 918, thick solid bundles)
    #          143,586 px              7.8 s, 29 fibres
    #          338,287 px              TIMED OUT
    #          575,689 px              TIMED OUT
    #
    # So the cap sits between the largest known-good (143,586) and the smallest known-bad
    # (338,287). Raising the THRESHOLD is the lever that breaks the web apart; seed spacing does
    # nothing and downsampling makes it worse (it merges the gaps). The centerline tracer has no
    # linking step, so it is exempt. Env: TMEQUANT_WEB_CAP (0 disables).
    WEB_CAP = _cfg(meta, "web_cap", "TMEQUANT_WEB_CAP", 200000.0)
    if (not raw_mode) and (not use_centerline) and (not use_flow) and WEB_CAP > 0:
        try:
            web_px = _largest_component_skeleton(imn > thr_used)
        except Exception as exc:  # noqa: BLE001 - never let the guard itself fail a tile
            logger.debug("giant-web guard skipped (%s)", exc)
            web_px = 0
        if web_px > WEB_CAP:
            web_before, thr_before = web_px, thr_used
            for _ in range(6):  # each probe costs ~0.5 s; bound the cost
                if web_px <= WEB_CAP or thr_used >= 254.0:
                    break
                thr_used = min(254.0, thr_used * 1.1 + 3.0)
                web_px = _largest_component_skeleton(imn > thr_used)
            if web_px <= WEB_CAP:
                fire_p = dict(fire_p)
                fire_p["thresh_im2"] = thr_used
                seed_bumped = True
                logger.warning(
                    "Giant-web guard: the collagen formed ONE connected network of %d skeleton px "
                    "(cap %d) -- raised threshold %.0f -> %.0f to break it into %d px BEFORE "
                    "tracing, which would otherwise have run until the %.0fs timeout for no "
                    "fibres. %s",
                    web_before, int(WEB_CAP), thr_before, thr_used, web_px,
                    _cfg(meta, "fire_timeout", "TMEQUANT_FIRE_TIMEOUT", 180), _WEB_HINT)
            else:
                # Still one web at the threshold ceiling: tracing WILL burn the full timeout and
                # return nothing, so fail now and let the run move on to the next tile.
                raise FireWorkerCrash(
                    "FIRE cannot process this tile: its collagen is one connected network of %d "
                    "skeleton px (cap %d) even at threshold %.0f. Failing fast rather than tracing "
                    "for the full timeout and returning nothing. %s" % (
                        web_px, int(WEB_CAP), thr_used, _WEB_HINT))

    # PROACTIVE seed-count guard. The extension step ("Extending nucleation points") and fiberproc
    # scale with the NUMBER of seeds; a fine seed spacing on a large/dense tile produces tens of
    # thousands of seeds -> the extension hangs (and, in-process, can segfault). Estimate the seed
    # count ~ foreground_px / seed_spacing^2 and raise the seed spacing until it is under a safe cap,
    # BEFORE tracing. Coarser seeding loses some fine curvature, but that beats a hung/killed tile;
    # sparse tiles are far under the cap and untouched. Env: TMEQUANT_SEED_CAP.
    SEED_CAP = _cfg(meta, "seed_cap", "TMEQUANT_SEED_CAP", 15000)
    fg_px = float(np.count_nonzero(imn > thr_used))
    est_seeds = fg_px / max(1.0, seed_used * seed_used)
    if (not raw_mode) and (not use_centerline) and (not use_flow) and est_seeds > SEED_CAP:
        new_seed = seed_used * (est_seeds / SEED_CAP) ** 0.5
        fire_p = dict(fire_p)
        fire_p["thresh_LMPdist"] = int(round(new_seed))
        old_seed, seed_used = seed_used, float(fire_p["thresh_LMPdist"])
        seed_bumped = True
        logger.warning(
            "Proactive seed guard: ~%.0f seeds estimated at spacing %.0f (cap %.0f) -> raised seed "
            "spacing to %.0f px BEFORE tracing so the extension step stays tractable. Raise the "
            "Background threshold or use a higher Downsample for finer seeding without this.",
            est_seeds, old_seed, SEED_CAP, seed_used)

    # Per-tile FIRE timeout from the meta (Advanced server settings), read by _run_fire.
    global _FIRE_TIMEOUT_OVERRIDE
    _FIRE_TIMEOUT_OVERRIDE = _cfg(meta, "fire_timeout", "TMEQUANT_FIRE_TIMEOUT", 180)
    fibers = cl_fibers  # centerline tracer already produced them; skip the FIRE attempt loop
    max_attempts = 4
    for attempt in (() if (use_centerline or use_flow) else range(max_attempts)):
        try:
            fibers = _run_fire(fire_p, ll1, imn)
            break
        except Exception as exc:  # noqa: BLE001
            if raw_mode:
                # RAW mode: no param easing, no retry. A crash/OOM/timeout just yields an empty
                # tile (as the user requested), logged (with traceback) so the cause is visible.
                logger.warning("RAW mode: FIRE failed on this tile -- returning no fibres (%s).", exc)
                logger.debug("RAW-mode tile failure traceback:\n%s", traceback.format_exc())
                fibers = []
                break
            if _is_recoverable_dense(exc) and attempt < max_attempts - 1:
                # Both a bad_alloc and a hard worker crash are driven by junction
                # DENSITY (an O(sum D^2) C++ structure on a few very high-degree
                # vertices), not the raw seed count -- so coarsening seed spacing
                # alone barely helps. RAISING THE THRESHOLD removes foreground pixels
                # => sparser ridges => lower vertex degree, which is the effective
                # lever. We raise both and retry rather than failing the tile.
                cause = "crashed (segfault)" if isinstance(exc, FireWorkerCrash) else "hit bad_alloc/OOM"
                seed_used = float(int(max(2.0, seed_used) * 2.0))
                thr_used = min(254.0, thr_used * 1.4 + 5.0)
                fire_p = dict(fire_p)
                fire_p["thresh_LMPdist"] = int(seed_used)  # C++ expects int seed spacing
                fire_p["thresh_im2"] = thr_used
                seed_bumped = True
                new_frac = float(np.count_nonzero(imn > thr_used)) / max(1, imn.size)
                logger.warning(
                    "FIRE %s on a dense tile (%.1f%% foreground) -- retry %d/%d "
                    "with threshold %.0f (%.1f%% fg) + seed spacing %.0f.",
                    cause, frac_above * 100.0, attempt + 2, max_attempts, thr_used,
                    new_frac * 100.0, seed_used,
                )
                continue
            if _is_recoverable_dense(exc):
                # Exhausted all attempts -- still too dense even at the coarsest
                # seeding. Raise a clear, actionable error (the tile is reported as
                # failed; the server stays up thanks to worker isolation).
                raise FireWorkerCrash(
                    "FIRE could not process this tile: it stayed too dense "
                    "(%.1f%% foreground) through %d attempts up to threshold %.0f / "
                    "seed spacing %.0f. %s" % (
                        frac_above * 100.0, max_attempts, thr_used, seed_used, _SEED_HINT)
                ) from exc
            raise
    if seed_bumped:
        logger.info("Dense tile recovered by auto-raising threshold to %.0f / seed spacing to %.0f "
                    "-- raise the Background threshold for cleaner, intended results.",
                    thr_used, seed_used)

    # Cut spurious straight "bridges" where FIRE linked across a dark gap (search radius ~ fibre
    # width let a thick-bundle vertex jump to a distant seed). Uses the same image + threshold FIRE
    # traced on (imn is padded; fibre coords are in that frame).
    # Snap FIRE traces onto the collagen centre line (geometry only) BEFORE the seam-cut test, so a
    # trace that merely chorded across a crimp bend isn't mistaken for a jump between bundles.
    # The centerline tracer's output already IS the medial axis, so it is skipped. snap_centerline<=0 off.
    if fibers and not raw_mode and not use_centerline and not use_flow \
            and _cfg(meta, "snap_centerline", "TMEQUANT_SNAP_CENTERLINE", 1.0) > 0:
        fibers = _snap_to_centerline(
            fibers, imn, float(fire_p.get("thresh_im2", thr_used) or thr_used),
            search_px=_cfg(meta, "snap_search_px", "TMEQUANT_SNAP_SEARCH", 6.0))
        logger.info("Centreline snap: refined %d FIRE trace(s) onto the collagen medial axis.", len(fibers))

    if fibers and not raw_mode:
        before_split = len(fibers)
        fibers = _split_bridging_fibers(
            fibers, imn, float(fire_p.get("thresh_im2", thr_used) or thr_used),
            gap_frac=_cfg(meta, "split_gap_frac", "TMEQUANT_SPLIT_GAP_FRAC", 0.4),
            jump_mult=_cfg(meta, "split_jump_mult", "TMEQUANT_SPLIT_JUMP_MULT", 4.0),
            valley_frac=_cfg(meta, "split_valley_frac", "TMEQUANT_SPLIT_VALLEY_FRAC", 0.55))
        if len(fibers) != before_split:
            logger.info("Bridge split: %d -> %d fibres (cut cross-gap links).",
                        before_split, len(fibers))

    # Coordinates are already in the real-image frame (the real tile sat at the
    # top-left of the padded square), so no offset correction is needed. Defensively
    # drop any fiber that reaches into the zero-padded margin. The pad is background, so FIRE should
    # not trace there -- but a long extension jump can land a fibre's ENDPOINT in the pad, drawing
    # PAST the real tile edge ("jump outside the tile"). Checking only the centre missed those, so
    # reject a fibre if ANY vertex is outside the real (orig_w x orig_h) bounds. Only the pad is
    # affected: the tile's read-overlap is inside these bounds, so genuine edge fibres are kept.
    # (The bridge-split usually cuts such a jump first, leaving the in-bounds piece, which passes.)
    if padded_to_square and fibers:
        def _all_in_bounds(f):
            pts = f.get("points") or []
            if len(pts) < 2:
                return False
            return all(0 <= p[0] < orig_w and 0 <= p[1] < orig_h for p in pts)
        before_pad = len(fibers)
        fibers = [f for f in fibers if _all_in_bounds(f)]
        if len(fibers) != before_pad:
            logger.info("Pad filter: dropped %d fibre(s) reaching into the square-pad margin.",
                        before_pad - len(fibers))

    # Trim tile-EDGE artifacts. Where collagen is truncated by the tile boundary, FIRE's distance
    # transform treats the edge as background and traces a medial axis ALONG the edge -- a segment
    # perpendicular to the real fibre, hugging the boundary (a "cap"). Rather than DROP the whole fibre
    # (which loses the real interior part), peel off only the end segments that run along the edge.
    if fibers and not raw_mode:
        before_edge = len(fibers)
        fibers = _trim_edge_tails(fibers, orig_w, orig_h,
                                  margin=_cfg(meta, "edge_trim_margin", "TMEQUANT_EDGE_MARGIN", 3.0))
        if len(fibers) != before_edge:
            logger.info("Edge filter: dropped %d fibre(s) left too short after trimming edge caps.",
                        before_edge - len(fibers))

    # Apply the exclusion mask to the traced geometry (drop fibres that ARE the excluded object,
    # peel spokes radiating out of one, keep fibres that merely cross it). Runs before the flow and
    # duplicate filters so those see the cleaned set.
    if fibers and excl is not None:
        max_frac = _cfg(meta, "exclude_max_frac", "TMEQUANT_EXCLUDE_MAX_FRAC", 0.5)
        fibers, n_ex_drop, n_ex_trim = _apply_exclusion(
            fibers, excl,
            max_frac=max_frac,
            trim=_cfg(meta, "exclude_trim", "TMEQUANT_EXCLUDE_TRIM", 1.0) > 0,
            min_len=ll1)
        if n_ex_drop or n_ex_trim:
            logger.info("Exclusion mask: dropped %d fibre(s) >=%.0f%% inside an excluded region, "
                        "trimmed %d more back to real collagen.",
                        n_ex_drop, max_frac * 100.0, n_ex_trim)

    # Remove fibres that run ACROSS the local collagen flow (directional structure-tensor filter).
    # This one filter subsumes the earlier ad-hoc cross-bar / asterisk / perpendicular-crossbar passes:
    # wide-bundle crossers, asterisk spokes and perpendicular crossbars are ALL fibres that cut across
    # the local flow. Length-independent; a WAVY along-flow fibre is kept (it follows its local curving
    # ridge). flow_off_deg<=0 disables. The coherence gate (flow_coh) is the safety dial -- higher
    # leaves mixed-orientation tissue alone.
    if fibers and not raw_mode:
        flow_off = _cfg(meta, "flow_off_deg", "TMEQUANT_FLOW_OFF_DEG", 52.0)
        if flow_off > 0:
            before_flow = len(fibers)
            fibers = _remove_flow_crossers(
                fibers, imn,
                coh_strong=_cfg(meta, "flow_coh", "TMEQUANT_FLOW_COH", 0.55),
                off_deg=flow_off)
            if len(fibers) != before_flow:
                logger.info("Flow filter: removed %d fibre(s) running across the local flow.",
                            before_flow - len(fibers))

    # OPTIONAL anti-flow check. Reports (and only removes if asked) fibres that run against an
    # INDEPENDENTLY estimated local flow -- a self-audit the tracer cannot perform on itself.
    # anti_flow_check>0 attaches the score; anti_flow_max_deg>0 also drops fibres above it.
    if fibers and not raw_mode and _cfg(meta, "anti_flow_check", "TMEQUANT_ANTI_FLOW", 0.0) > 0:
        try:
            scores = _anti_flow_scores(fibers, imn)
            for f, sc in zip(fibers, scores):
                f["anti_flow_deg"] = sc
            bad = sum(1 for sc in scores if sc == sc and sc > 40.0)
            logger.info("Anti-flow check: %d of %d fibre(s) run >40 deg against the independent "
                        "flow estimate (scored, not removed unless a limit is set).",
                        bad, len(fibers))
            lim = _cfg(meta, "anti_flow_max_deg", "TMEQUANT_ANTI_FLOW_MAX", 0.0)
            if lim > 0:
                before = len(fibers)
                fibers = [f for f in fibers
                          if not (f.get("anti_flow_deg") == f.get("anti_flow_deg")
                                  and f.get("anti_flow_deg", 0.0) > lim)]
                if len(fibers) != before:
                    logger.info("Anti-flow check: removed %d fibre(s) over %.0f deg.",
                                before - len(fibers), lim)
        except Exception as exc:  # noqa: BLE001 - a self-check must never fail the tile
            logger.warning("Anti-flow check skipped: %s", exc)

    # Cut fibres that doubled back on themselves before anything measures them: a retrace is
    # one object carrying twice its real length, and the arm we drop is a duplicate of the arm
    # we keep, so this also stops the duplicate-path filter below seeing a pair it cannot act on.
    if fibers and not raw_mode:
        fibers, n_retrace = _split_self_retrace(
            fibers,
            min_sep=_cfg(meta, "retrace_sep", "TMEQUANT_RETRACE_SEP", 8.0),
            lag_px=_cfg(meta, "retrace_lag", "TMEQUANT_RETRACE_LAG", 30.0))
        if n_retrace:
            logger.info("Doubling-back filter: cut %d fibre(s) that retraced their own path "
                        "(kept the longer arm).", n_retrace)

    # Remove duplicate traces that follow the same bundle (same tile or not): a fibre whose path lies
    # mostly on top of a longer kept fibre. dedup_overlap>=1 disables.
    if fibers and not raw_mode:
        before_dup = len(fibers)
        fibers = _dedup_overlapping_paths(
            fibers,
            tol_px=_cfg(meta, "dedup_path_tol", "TMEQUANT_DEDUP_PATH_TOL", 6.0),
            overlap_frac=_cfg(meta, "dedup_overlap", "TMEQUANT_DEDUP_OVERLAP", 0.7))
        if len(fibers) != before_dup:
            logger.info("Duplicate-path filter: removed %d fibre(s) retracing a longer fibre.",
                        before_dup - len(fibers))

    n_traced = len(fibers)

    # Min-length (LL1) filter -- applied here so we can REPORT how many fibers FIRE
    # traced vs how many survived (a too-high Min length is a common "no fibers" cause).
    n_after_length = n_traced
    if ll1 and ll1 > 0 and fibers:
        fibers = [f for f in fibers if float(f.get("length_arc", 0.0)) > ll1]
        n_after_length = len(fibers)
        if n_after_length < n_traced:
            logger.info("Min-length filter (LL1=%.0f px): kept %d of %d traced fibers.",
                        ll1, n_after_length, n_traced)

    # Max-width filter (widMAX). The compiled FIRE-only backend does NOT read
    # widMAX, so we apply it here as tme-quant's extraction step does: drop fibers
    # whose estimated width exceeds the cap (used to reject thick blob/vessel
    # detections that aren't true fibers). widMAX is in analysed px; 0/None = off.
    wid_max = resolved.get("widMAX")
    try:
        wid_max = float(wid_max) if wid_max is not None else 0.0
    except (TypeError, ValueError):
        wid_max = 0.0
    n_width_dropped = 0
    if wid_max > 0 and fibers:
        before = len(fibers)
        fibers = [
            f for f in fibers
            if not (np.isfinite(f.get("width", float("nan"))) and f["width"] > wid_max)
        ]
        n_width_dropped = before - len(fibers)
        if n_width_dropped:
            logger.info("widMAX filter: dropped %d fiber(s) wider than %.1f px",
                        n_width_dropped, wid_max)

    # Waviness / crimp metrics on the FINAL fibre set, for BOTH tracers so they are directly
    # comparable (a tracer that smooths the crimp will show small amplitude / few kinks).
    for f in fibers:
        f.update(_waviness_metrics(f.get("points") or []))

    for i, f in enumerate(fibers):
        f["id"] = i

    if stats_out is not None:
        stats_out.update({
            "n_traced": n_traced,
            "n_after_length": n_after_length,
            "n_length_dropped": n_traced - n_after_length,
            "min_length_px": ll1,
            "n_width_dropped": n_width_dropped,
            "seed_spacing_used": seed_used,
            "seed_auto_raised": seed_bumped,
            "frac_above": round(frac_above, 4),
        })
        if meta.get("debug_export"):
            try:
                crop = imn[:orig_h, :orig_w]  # drop the square-pad margin
                stats_out["debug"] = _debug_export_payload(
                    crop, float(fire_p.get("thresh_im2", thr_used) or thr_used), fibers)
            except Exception as exc:  # noqa: BLE001
                logger.warning("debug export failed: %s", exc)
                stats_out["debug"] = {"error": str(exc)}

    if mask is not None and len(fibers) > 0:
        dist_thresh = float(meta.get("distance_threshold") or 100.0)
        try:
            status = _attach_tacs(fibers, mask, dist_thresh)
            logger.info("%s", status)
        except Exception as exc:  # noqa: BLE001
            logger.error("TACS classification failed: %s", exc)
            logger.debug("%s", traceback.format_exc())
    return fibers


def analyze_synthetic(image: np.ndarray, meta: dict) -> list:
    """Fallback fiber detector using OpenCV edge + probabilistic Hough lines.

    Not a substitute for FIRE; only exists so the protocol and the QuPath
    rendering can be tested when the compiled backend is unavailable.
    """
    import cv2

    img = image
    if img.ndim == 3:
        img = img[..., 0]
    img8 = img.astype(np.float32)
    if img8.max() > 0:
        img8 = img8 / img8.max() * 255.0
    img8 = img8.astype(np.uint8)

    edges = cv2.Canny(img8, 40, 120)
    lines = cv2.HoughLinesP(
        edges, 1, np.pi / 180, threshold=30, minLineLength=15, maxLineGap=5
    )
    fibers = []
    if lines is not None:
        for i, ln in enumerate(lines[:500]):
            x1, y1, x2, y2 = (float(v) for v in ln[0])
            dx, dy = x2 - x1, y2 - y1
            length = float(np.hypot(dx, dy))
            # angle in the same convention as the pipeline (y grows downward):
            ang = float((np.degrees(np.arctan2(-dy, dx))) % 180.0)
            fibers.append(
                {
                    "id": i,
                    "points": [[x1, y1], [x2, y2]],
                    "center": [(x1 + x2) / 2.0, (y1 + y2) / 2.0],
                    "angle": ang,
                    "length_arc": length,
                    "length_end": length,
                    "width": float("nan"),
                    "straightness": 1.0,
                    "tacs": None,
                }
            )
    return fibers


def run_analysis(image: np.ndarray, meta: dict, mask=None, exclude=None) -> dict:
    """Dispatch to real or synthetic analysis and build the response dict."""
    h, w = image.shape[:2]
    stats = {}
    if _BACKEND == "real":
        try:
            # FIRE keeps its own isolated-worker path (it needs the compiled backend and its
            # crash/retry semantics); only the pure-numpy tracers go to the pool.
            tracer = str(meta.get("tracer", "fire") or "fire").lower()
            workers = _trace_worker_count(meta) if tracer in ("flow", "centerline") else 0
            if workers > 1:
                fibers, wstats = _analyze_in_worker(image, meta, mask, exclude, workers)
                stats.update(wstats or {})
            else:
                fibers = analyze_real(image, meta, mask, stats_out=stats, exclude=exclude)
        except Exception as exc:  # noqa: BLE001
            msg = str(exc)
            logger.error("Real analysis failed: %s", msg)
            logger.debug("%s", traceback.format_exc())
            # A real-backend failure should NOT masquerade as synthetic fibers --
            # report it so the user can act (most commonly: too many FIRE seeds).
            low = msg.lower()
            if isinstance(exc, FireWorkerCrash):
                # Already a user-facing, actionable message (dense-tile crash or
                # exhausted retries). The server survived because FIRE is isolated.
                hint = msg
            elif isinstance(exc, MemoryError) or "bad_alloc" in low:
                hint = (
                    "FIRE ran out of memory: too many fiber seeds for this region. "
                    "Increase 'Seed spacing', raise 'Background threshold (%)', or use a "
                    "higher 'Downsample', then Preview again."
                )
            elif "segfault" in low or "crashed" in low or "seeds" in low or "timed out" in low:
                hint = msg  # already a user-facing message from _run_fire
            else:
                hint = "FIRE analysis error: " + msg
            return {
                "ok": False,
                "backend": "real",
                "error": hint,
                "n_fibers": 0,
                "image": {"width": int(w), "height": int(h)},
                "region": meta.get("region"),
                "fibers": [],
            }
        backend = "real"
    else:
        fibers = analyze_synthetic(image, meta)
        backend = "synthetic"

    return {
        "ok": True,
        "backend": backend,
        "reason": _IMPORT_ERROR if backend == "synthetic" else None,
        "n_fibers": len(fibers),
        # Diagnostics so the client can explain a low/zero count:
        "n_traced": stats.get("n_traced"),
        "n_length_dropped": stats.get("n_length_dropped"),
        "min_length_px": stats.get("min_length_px"),
        "n_width_dropped": stats.get("n_width_dropped"),
        "seed_spacing_used": stats.get("seed_spacing_used"),
        "seed_auto_raised": stats.get("seed_auto_raised"),
        "frac_above": stats.get("frac_above"),
        "debug": stats.get("debug"),  # present only when meta.debug_export (single-tile test export)
        "image": {"width": int(w), "height": int(h)},
        "region": meta.get("region"),
        "fibers": fibers,
    }


# ---------------------------------------------------------------------------
# Socket framing helpers
# ---------------------------------------------------------------------------
def _recv_exact(conn: socket.socket, n: int) -> bytes:
    """Read exactly n bytes or raise ConnectionError."""
    buf = bytearray()
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise ConnectionError(f"socket closed after {len(buf)}/{n} bytes")
        buf.extend(chunk)
    return bytes(buf)


def _read_uint32(conn: socket.socket) -> int:
    return struct.unpack("!I", _recv_exact(conn, 4))[0]


def _send_json(conn: socket.socket, obj: dict) -> None:
    payload = json.dumps(obj).encode("utf-8")
    conn.sendall(struct.pack("!I", len(payload)))
    conn.sendall(payload)


# ---------------------------------------------------------------------------
# Per-client handler
# ---------------------------------------------------------------------------
def handle_client(conn: socket.socket, addr, shutdown_event: threading.Event) -> None:
    logger.info("Client connected: %s", addr)
    try:
        while True:
            try:
                cmd = _recv_exact(conn, CMD_LEN)
            except ConnectionError:
                break  # client hung up

            if cmd == CMD_PING:
                _send_json(conn, {
                    "ok": True,
                    "backend": _BACKEND,
                    "tacs": _TACS_OK,
                    "reason": _IMPORT_ERROR,
                })

            elif cmd == CMD_SHUTDOWN:
                logger.info("SHUTDOWN received from %s", addr)
                shutdown_event.set()
                break

            elif cmd == CMD_ANALYZE:
                meta_len = _read_uint32(conn)
                meta_raw = _recv_exact(conn, meta_len) if meta_len else b"{}"
                meta = json.loads(meta_raw.decode("utf-8"))
                png_len = _read_uint32(conn)
                png_bytes = _recv_exact(conn, png_len)
                image = np.asarray(Image.open(io.BytesIO(png_bytes)))

                # Optional boundary mask (for TACS). meta["has_mask"] gates an
                # extra length-prefixed PNG block after the image.
                mask = None
                if meta.get("has_mask"):
                    mask_len = _read_uint32(conn)
                    mask_bytes = _recv_exact(conn, mask_len)
                    mask_img = np.asarray(Image.open(io.BytesIO(mask_bytes)))
                    if mask_img.ndim == 3:
                        mask_img = mask_img[..., 0]
                    mask = (mask_img > 127).astype(np.uint8)

                # Optional EXCLUSION mask ("don't trace here"): a further length-prefixed PNG block,
                # gated by meta["has_exclude_mask"] and always sent AFTER the boundary mask, so a
                # client that sends neither -- or only the boundary -- stays wire-compatible.
                exclude = None
                if meta.get("has_exclude_mask"):
                    ex_len = _read_uint32(conn)
                    ex_bytes = _recv_exact(conn, ex_len)
                    ex_img = np.asarray(Image.open(io.BytesIO(ex_bytes)))
                    if ex_img.ndim == 3:
                        ex_img = ex_img[..., 0]
                    exclude = ex_img > 127

                with _run_file_logging(meta.get("log_dir"), meta.get("log_file")):
                    logger.info(
                        "ANALYZE_ region=%s image=%s mask=%s exclude=%s",
                        meta.get("region"),
                        image.shape,
                        None if mask is None else mask.shape,
                        None if exclude is None else exclude.shape,
                    )
                    try:
                        resp = run_analysis(image, meta, mask, exclude=exclude)
                    except Exception as exc:  # noqa: BLE001
                        logger.error("analysis error: %s", exc)
                        logger.debug("%s", traceback.format_exc())
                        resp = {"ok": False, "error": str(exc), "fibers": []}
                    _send_json(conn, resp)
                    logger.info("-> %d fibers (%s)", len(resp.get("fibers", [])), resp.get("backend"))

            elif cmd == CMD_POSTPROC:
                meta_len = _read_uint32(conn)
                meta = json.loads(_recv_exact(conn, meta_len).decode("utf-8"))
                mask = None
                if meta.get("has_mask"):
                    mlen = _read_uint32(conn)
                    mbytes = _recv_exact(conn, mlen)
                    mimg = np.asarray(Image.open(io.BytesIO(mbytes)))
                    if mimg.ndim == 3:
                        mimg = mimg[..., 0]
                    mask = (mimg > 127).astype(np.uint8)
                fibers_in = meta.get("fibers", []) or []
                with _run_file_logging(meta.get("log_dir"), meta.get("log_file")):
                    logger.info(
                        "POSTPROC %d fibers, stitch=%s, mask=%s",
                        len(fibers_in), meta.get("stitch", True),
                        None if mask is None else mask.shape,
                    )
                    try:
                        out = postprocess_fibers(
                            fibers_in,
                            mask,
                            float(meta.get("distance_threshold") or 100.0),
                            bool(meta.get("stitch", True)),
                            float(meta.get("dedup_tol", 4.0)),
                            float(meta.get("stitch_tol", 6.0)),
                            float(meta.get("angle_tol", 45.0)),
                        )
                        resp = {"ok": True, "n_fibers": len(out), "fibers": out}
                    except Exception as exc:  # noqa: BLE001
                        logger.error("POSTPROC error: %s", exc)
                        logger.debug("%s", traceback.format_exc())
                        resp = {"ok": False, "error": str(exc), "fibers": fibers_in}
                    _send_json(conn, resp)
                    logger.info("-> POSTPROC %d fibers", len(resp.get("fibers", [])))

            elif cmd == CMD_GRIDSEARCH:
                meta_len = _read_uint32(conn)
                meta_raw = _recv_exact(conn, meta_len) if meta_len else b"{}"
                meta = json.loads(meta_raw.decode("utf-8"))
                png_len = _read_uint32(conn)
                png_bytes = _recv_exact(conn, png_len)
                image = np.asarray(Image.open(io.BytesIO(png_bytes)))
                try:
                    resp = gridsearch(image, meta)
                except Exception as exc:  # noqa: BLE001
                    logger.error("GRIDSEARCH error: %s", exc)
                    logger.debug("%s", traceback.format_exc())
                    resp = {"ok": False, "error": str(exc), "results": []}
                _send_json(conn, resp)
                logger.info("-> GRIDSEARCH %d results", len(resp.get("results", [])))

            else:
                logger.warning("Unknown command from %s: %r", addr, cmd)
                _send_json(conn, {"ok": False, "error": f"unknown command {cmd!r}"})
    except Exception as exc:  # noqa: BLE001
        logger.error("Client handler error %s: %s", addr, exc)
        logger.debug("%s", traceback.format_exc())
    finally:
        conn.close()
        logger.info("Client disconnected: %s", addr)


# ---------------------------------------------------------------------------
# Server main loop
# ---------------------------------------------------------------------------
def serve(host: str, port: int) -> None:
    shutdown_event = threading.Event()
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind((host, port))
        except OSError as exc:
            # Most common cause: another server is already listening on this port.
            # Don't dump a traceback at the user -- explain it and what to do.
            logger.error(
                "Could not bind %s:%d (%s).\n"
                "  * A TME Quant server is probably ALREADY running on this port --\n"
                "    look for another console window; if so, just use that one.\n"
                "  * Otherwise the port is taken by another program. Start this server on a\n"
                "    different port, e.g.  ./start_fire_server.sh 5102  (and set the same\n"
                "    port in QuPath Preferences > TME Quant), or stop whatever holds %d.",
                host, port, exc, port)
            raise SystemExit(3)
        s.listen()
        s.settimeout(1.0)  # so we can poll shutdown_event
        logger.info("Fiber socket server listening on %s:%d (backend=%s)", host, port, _BACKEND)
        while not shutdown_event.is_set():
            try:
                conn, addr = s.accept()
            except socket.timeout:
                continue
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            t = threading.Thread(
                target=handle_client, args=(conn, addr, shutdown_event), daemon=True
            )
            t.start()
    logger.info("Server stopped.")


def main(argv=None) -> int:
    multiprocessing.freeze_support()  # safe no-op except for frozen Windows builds
    ap = argparse.ArgumentParser(description="TMEQuant FIRE fiber socket server")
    ap.add_argument("--host", default=DEFAULT_HOST)
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument(
        "--repo",
        default=str(_default_repo_root()),
        help="Path to the tme-quant repo root (or set TMEQUANT_REPO).",
    )
    ap.add_argument(
        "--synthetic",
        action="store_true",
        help="Force the synthetic fallback even if the real backend is available.",
    )
    ap.add_argument("--log", default="INFO")
    args = ap.parse_args(argv)

    logging.basicConfig(
        level=getattr(logging, args.log.upper(), logging.INFO),
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )

    global np, Image, _USE_WORKER

    if not args.synthetic:
        # The scientific-stack + FIRE import below takes ~30 s or longer, depending on hardware. Make it loud so users
        # don't fire analyses at a server that is still loading (it isn't listening yet).
        logger.info("=" * 68)
        logger.info("PLEASE WAIT — loading the FIRE pipeline (~30 s or longer on first start, depending on hardware).")
        logger.info("The server is NOT ready and will REJECT analyses until you see the")
        logger.info("'Fiber socket server listening on ...' line below.")
        logger.info("=" * 68)
        _try_import_pipeline(Path(args.repo))  # pre-loads the backend before numpy
    else:
        logger.info("Synthetic mode forced via --synthetic.")

    # Now bind numpy/PIL for the server's own use (already loaded by the pipeline
    # import above in the main process; loaded here for the synthetic path).
    import numpy
    from PIL import Image as _PILImage

    np = numpy
    Image = _PILImage

    # If crash-isolation is requested, probe the worker now: confirm it can load
    # the compiled backend, log the REAL error if not, and fall back to in-process
    # so analysis still works either way.
    if _USE_WORKER and _BACKEND == "real":
        try:
            ok, err = _get_executor().submit(fire_worker.worker_probe).result(timeout=180)
            if ok:
                logger.info("FIRE crash-isolation ON (worker subprocess loaded the backend).")
            else:
                logger.error(
                    "FIRE worker could NOT load the backend -> isolation OFF (in-process). "
                    "Real worker error:\n%s", err or "(no error captured)")
                _USE_WORKER = False
                _reset_executor()
        except Exception as exc:  # noqa: BLE001
            logger.error("FIRE worker probe failed (%s) -> isolation OFF (in-process).", exc)
            logger.debug("%s", traceback.format_exc())
            _USE_WORKER = False
            _reset_executor()

    try:
        serve(args.host, args.port)
    except KeyboardInterrupt:
        logger.info("Interrupted; shutting down.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
