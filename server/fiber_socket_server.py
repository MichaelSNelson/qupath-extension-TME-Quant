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
import io
import json
import logging
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

# The FIRE C++ backend can SEGFAULT on pathological inputs (too many seeds), which
# would kill the whole server. Running it in a spawned worker subprocess would
# contain that, BUT on MSYS2/venv the freshly-spawned interpreter cannot load the
# compiled backend's DLLs (it loads fine in the main process). So isolation is
# OPT-IN via TMEQUANT_FIRE_WORKER=1 until that is resolved; by default FIRE runs
# in-process (works, but a segfault on a pathological tile ends the server -- the
# fix for that is to raise the threshold / seed spacing / downsample).
_EXECUTOR = None
_USE_WORKER = os.environ.get("TMEQUANT_FIRE_WORKER", "") == "1"
_W_FIRE = None            # fire_2d_angle in the worker process
_W_BACKEND_ERR = None     # real fiber_backend import error inside the worker


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
        dll_dirs = [
            os.path.dirname(sys.executable),          # .../ucrt64/bin (mingw python)
            str(repo_root / "src" / "ctfire_py"),     # the .pyd's own folder
            r"C:\msys64\ucrt64\bin",                  # common MSYS2 location
        ]
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
        width = _approx_width(fa_verts, Ra)

        fibers.append(
            {
                "id": out_id,
                "points": points,            # full centerline polyline
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


def _stitch_fibers(fibers, tol, angle_tol):
    """Greedily join fiber pieces split across tile seams (cross-tile only)."""
    polys = [[list(p) for p in f["points"]] for f in fibers]
    tiles = [f.get("tile", -1) for f in fibers]
    extra = [{k: v for k, v in f.items() if k not in ("points", "center", "id")} for f in fibers]
    merge_id = -2
    changed = True
    guard = 0
    while changed and guard < 100000:
        changed = False
        guard += 1
        if len(polys) < 2:
            break
        ep = []  # (poly_idx, end, x, y)
        for i, pl in enumerate(polys):
            ep.append((i, 0, pl[0][0], pl[0][1]))
            ep.append((i, 1, pl[-1][0], pl[-1][1]))
        pts = np.array([[e[2], e[3]] for e in ep], float)
        tree = _kdtree(pts)
        for a in range(len(ep)):
            ia, ea, xa, ya = ep[a]
            joined = False
            for b in _query_ball(tree, pts, [xa, ya], tol):
                if b == a:
                    continue
                ib, eb, xb, yb = ep[b]
                if ib == ia or tiles[ia] == tiles[ib]:
                    continue
                if _join_ok(polys[ia], ea, polys[ib], eb, angle_tol):
                    polys[ia] = _concat(polys[ia], ea, polys[ib], eb)
                    tiles[ia] = merge_id
                    merge_id -= 1
                    del polys[ib]
                    del tiles[ib]
                    del extra[ib]
                    joined = True
                    break
            if joined:
                changed = True
                break
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
      multiplied by an overlap GUARD (recall/recall_floor) so the right count can't be
      hit by the WRONG fibres, and lightly shaped by length + orientation agreement.
    """
    used = set()
    matched = 0
    errs = []
    for g in gt_segs:
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
        guard = min(1.0, recall / recall_floor) if recall_floor > 0 else 1.0
        score = count_score * guard * (0.6 + 0.25 * len_factor + 0.15 * orient_factor)
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


_SEED_HINT = (
    "Raise 'Background threshold (%)' or 'Seed spacing', or use a higher "
    "'Downsample', then Preview again."
)


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

    # A broken pool (worker already crashed) -> reset + report; isolation stays on.
    try:
        fut = _get_executor().submit(fire_worker.worker_fire, fire_params_value, ll1, image)
    except concurrent.futures.process.BrokenProcessPool:
        _reset_executor()
        raise RuntimeError("FIRE crashed (segfault) - too many fiber seeds. " + _SEED_HINT)
    except Exception as exc:  # noqa: BLE001 - genuine infra (can't spawn/pickle)
        logger.warning("FIRE worker unavailable (%s); running in-process.", exc)
        _USE_WORKER = False
        return _inprocess_fire(fire_params_value, ll1, image)

    try:
        return fut.result(timeout=600)
    except concurrent.futures.process.BrokenProcessPool:
        _reset_executor()
        raise RuntimeError("FIRE crashed (segfault) - too many fiber seeds. " + _SEED_HINT)
    except concurrent.futures.TimeoutError:
        _reset_executor()
        raise RuntimeError("FIRE timed out (10 min) - too dense. " + _SEED_HINT)
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


def analyze_real(image: np.ndarray, meta: dict, mask=None, stats_out=None) -> list:
    """Run FIRE directly and return one polyline per fiber.

    Replicates _run_fire_only_on_image's normalisation, then reconstructs the
    full centerlines from the fire_2d_angle output (richer than the flattened
    per-vertex fiber_structure DataFrame). When `mask` is provided, each fiber
    is also TACS-classified relative to that boundary.
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
    fibers = None
    max_attempts = 4
    for attempt in range(max_attempts):
        try:
            fibers = _run_fire(fire_p, ll1, imn)
            break
        except Exception as exc:  # noqa: BLE001
            if _is_oom_error(exc) and attempt < max_attempts - 1:
                # The bad_alloc is driven by junction DENSITY (an O(sum D^2) C++ structure
                # on a few very high-degree vertices), not by the raw seed count -- so
                # coarsening seed spacing alone barely helps. RAISING THE THRESHOLD removes
                # foreground pixels => sparser ridges => lower vertex degree, which is the
                # effective lever. We raise both and retry rather than failing the tile.
                seed_used = float(int(max(2.0, seed_used) * 2.0))
                thr_used = min(254.0, thr_used * 1.4 + 5.0)
                fire_p = dict(fire_p)
                fire_p["thresh_LMPdist"] = int(seed_used)  # C++ expects int seed spacing
                fire_p["thresh_im2"] = thr_used
                seed_bumped = True
                new_frac = float(np.count_nonzero(imn > thr_used)) / max(1, imn.size)
                logger.warning(
                    "FIRE OOM/bad_alloc on a dense tile (%.1f%% foreground) -- retry %d/%d "
                    "with threshold %.0f (%.1f%% fg) + seed spacing %.0f.",
                    frac_above * 100.0, attempt + 2, max_attempts, thr_used,
                    new_frac * 100.0, seed_used,
                )
                continue
            raise
    if seed_bumped:
        logger.info("Dense tile recovered by auto-raising threshold to %.0f / seed spacing to %.0f "
                    "-- raise the Background threshold for cleaner, intended results.",
                    thr_used, seed_used)

    # Coordinates are already in the real-image frame (the real tile sat at the
    # top-left of the padded square), so no offset correction is needed. Defensively
    # drop any fiber whose centre fell in the zero-padded margin (the pad is
    # background, so FIRE should not trace there in the first place).
    if padded_to_square and fibers:
        fibers = [
            f for f in fibers
            if f.get("center")
            and 0 <= f["center"][0] < orig_w and 0 <= f["center"][1] < orig_h
        ]

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


def run_analysis(image: np.ndarray, meta: dict, mask=None) -> dict:
    """Dispatch to real or synthetic analysis and build the response dict."""
    h, w = image.shape[:2]
    stats = {}
    if _BACKEND == "real":
        try:
            fibers = analyze_real(image, meta, mask, stats_out=stats)
        except Exception as exc:  # noqa: BLE001
            msg = str(exc)
            logger.error("Real analysis failed: %s", msg)
            logger.debug("%s", traceback.format_exc())
            # A real-backend failure should NOT masquerade as synthetic fibers --
            # report it so the user can act (most commonly: too many FIRE seeds).
            low = msg.lower()
            if isinstance(exc, MemoryError) or "bad_alloc" in low:
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

                logger.info(
                    "ANALYZE_ region=%s image=%s mask=%s",
                    meta.get("region"),
                    image.shape,
                    None if mask is None else mask.shape,
                )
                try:
                    resp = run_analysis(image, meta, mask)
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
        s.bind((host, port))
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
        # The scientific-stack + FIRE import below takes ~30-40 s. Make it loud so users
        # don't fire analyses at a server that is still loading (it isn't listening yet).
        logger.info("=" * 68)
        logger.info("PLEASE WAIT — loading the FIRE pipeline (~30-40 s on first start).")
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
