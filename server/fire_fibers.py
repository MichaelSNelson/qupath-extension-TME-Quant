"""fire_fibers.py -- turn a fire_2d_angle output dict into the fibers the server sends.

Shared by the server (in-process FIRE) and fire_worker.py (isolated FIRE), so both paths return
identical fibers. numpy is imported inside each function, not at module level: the worker must
load the compiled FIRE backend before numpy (see fire_worker.py), and importing this module must
not get in the way of that.
"""


def angle_from_endpoints(p0, p1) -> float:
    """Fiber angle in degrees [0,180) in the pipeline's y-down convention.

    p0, p1 are (row, col). Matches draw_curvs: positive angle tilts the line
    up-to-the-right because screen y grows downward.
    """
    import numpy as np
    drow = p1[0] - p0[0]
    dcol = p1[1] - p0[1]
    return float(np.degrees(np.arctan2(-drow, dcol)) % 180.0)


def width_fields(w):
    """The width measurements every tracer reports, from the per-vertex widths `w` (analyzed px).

    Mean AND median: the samples are skewed by junctions and blobs the trace passes through
    (a junction doubles the local distance-map radius), so the mean overstates a clean fiber
    while the median tracks its body; the ratio of the two flags a contaminated trace. `width`
    stays the MEAN on every tracer -- it also drives the widMAX filter, which must mean the same
    thing whichever tracer ran. NaNs when there are no samples.
    """
    import numpy as np
    w = np.asarray(w, dtype=float).ravel()
    if w.size == 0:
        nan = float("nan")
        return {"width": nan, "width_median": nan, "width_max": nan, "width_std": nan}
    return {"width": float(np.mean(w)), "width_median": float(np.median(w)),
            "width_max": float(np.max(w)), "width_std": float(np.std(w))}


def width_stats(fa_verts, Ra):
    """Width fields (see `width_fields`) over a FIRE fiber's vertices (width = 2*radius)."""
    import numpy as np
    try:
        idx = [v for v in fa_verts if 0 <= v < len(Ra)]
        return width_fields(2.0 * np.asarray(Ra)[idx].astype(float) if idx else [])
    except Exception:  # noqa: BLE001
        return width_fields([])


def fibers_from_fire(data: dict, ll1: float) -> list:
    """Reconstruct one polyline per fiber from a fire_2d_angle output dict.

    Each fiber i is the ordered vertex path ``Xai[Fai[i]['v']]`` (row, col).
    Mirrors pycurvelets.get_fire._build_fiber_dataframe's source data but keeps
    the *whole* centerline instead of flattening to per-vertex rows.

    The LL1 (min-length) filter is NOT applied here: analyze_real applies it after
    post-processing, so it can report how many fibers FIRE traced vs how many survived
    the length cut (a too-high Min length is a common reason for "no fibers"). `ll1` is
    accepted for the callers' signature and unused.
    """
    import numpy as np
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
        fibers.append(
            {
                "id": out_id,
                "points": points,            # full centerline polyline
                "center": center,
                "angle": angle_from_endpoints(p_start, p_end),
                "length_arc": arc_len,
                "length_end": end_len,
                **width_stats(fa_verts, Ra),  # width (MEAN, drives widMAX), median, max, std
                "straightness": straightness,
                "tacs": None,
            }
        )
        out_id += 1
    return fibers
