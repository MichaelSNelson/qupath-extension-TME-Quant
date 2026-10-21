#!/usr/bin/env python3
"""Score FIRE against hand-traced fibres, the way Gurrala et al. 2021 count oversegmentation.

Runs TMEQuant.detectFibersInSelected inside a real QuPath (through qupath-mcp, like regress.py) on
an area annotation that holds hand-traced LINE annotations, once per named parameter set, and
scores each run:

  follows   detections whose samples mostly lie along ONE traced fibre (within --tol px and
            --angle degrees of its local direction); the rest are spurious
  hit       traced fibres with at least one detection following them (recall)
  pieces    detections per hit fibre (1.0 = each fibre traced in one piece)
  coverage  share of the traced length that has a detection along it

The image is opened from its FILE, not from the project, and the annotations are copied into
that scratch image, so the project is only read, never saved.

    python3 gt_score.py --project /mnt/g/MH_FNA/project.qpproj --entry 258 \\
        --image /mnt/g/MH_FNA/SlideImages/<file>.ome.tif --thresh-im2 77

Each parameter set in SETS is a dict of preference values plus optional FIRE extras (JSON). The
threshold is found by bisection: the native value that maps to --thresh-im2 on --norm-region,
which is how the original run was normalised.
"""
import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import regress as R  # noqa: E402

# Old (before 0.2.4) values of the four FIRE internals, as the client sent them at downsample 1 on
# a 0.1725 um/px image: 6 um, 30 um, 10 um and 30 um converted to px.
PX_UM = 0.1725
OLD_EXTRAS = {"s_fiberdir": round(6 / PX_UM), "thresh_flen": 30 / PX_UM,
              "s_maxspace": 10 / PX_UM, "thresh_dang_L": 30 / PX_UM}
SETS = {
    "0.2.4 (FIRE units)": {"extra": {}},
    "0.2.3 (um-converted)": {"extra": OLD_EXTRAS},
}

GROOVY_SETUP = r'''
import qupath.lib.projects.ProjectIO
import qupath.lib.images.servers.ImageServers
import qupath.lib.images.ImageData
import qupath.lib.objects.PathObjects
def p = ProjectIO.loadProject(new File("%(project)s"), java.awt.image.BufferedImage)
def e = p.getImageList().find { it.getID() == "%(entry)s" }
def src = e.readHierarchy().getAnnotationObjects()
def box = src.findAll { it.getROI().isArea() }.max { r ->
    src.count { l -> l.getROI().isLine() && r.getROI().contains(l.getROI().getCentroidX(), l.getROI().getCentroidY()) }
        - r.getROI().getArea() / 1e12 }   // the area holding most lines; the smaller one on a tie
def lines = src.findAll { it.getROI().isLine() && box.getROI().contains(it.getROI().getCentroidX(), it.getROI().getCentroidY()) }
def srv = ImageServers.buildServer(new File("%(image)s").toURI())
def d = new ImageData(srv)
def boxObj = PathObjects.createAnnotationObject(box.getROI())
d.getHierarchy().addObject(boxObj)
fx { qupath.getViewer().setImageData(d) }
Thread.sleep(2000)
def r = box.getROI()
def gt = lines.collect { l -> l.getROI().getAllPoints().collect { pt -> [pt.getX(), pt.getY()] } }
return groovy.json.JsonOutput.toJson([box: [r.getBoundsX(), r.getBoundsY(), r.getBoundsWidth(), r.getBoundsHeight()],
    gt: gt, px_um: srv.getPixelCalibration().getAveragedPixelSizeMicrons()])
'''

GROOVY_THRESHOLD = r'''
import qupath.ext.tmequant.FiberEngine
import qupath.ext.tmequant.FiberParams
def srv = qupath.getImageData().getServer()
def target = %(target)d
def (rx, ry, rw, rh) = [%(nx)d, %(ny)d, %(nw)d, %(nh)d]
double lo = 0, hi = 65535
def im2 = { double t ->
    def n = FiberEngine.computeRegionNorm(srv, 0, rx, ry, rw, rh, %(down)s, t)
    FiberParams.serverThreshIm2(t, n[0], n[1], n[2]) }
40.times { double mid = (lo + hi) / 2; if (im2(mid) < target) lo = mid else hi = mid }
return groovy.json.JsonOutput.toJson([threshold: Math.round(hi), thresh_im2: im2(Math.round(hi))])
'''

GROOVY_RUN = r'''
import qupath.ext.tmequant.FiberSocketPreferences as P
import qupath.ext.tmequant.TMEQuant
def d = qupath.getImageData()
def h = d.getHierarchy()
h.removeObjects(h.getDetectionObjects(), true)
P.setThreshold(%(threshold)s)
P.downsampleProperty().set(1.0)
P.minLengthProperty().forUnit(true).set(%(minlen)s)
P.seedSpacingProperty().forUnit(true).set(%(seed)s)
P.extraParamsProperty().set('%(extra)s')
def box = h.getAnnotationObjects().find { it.getROI().isArea() }
h.getSelectionModel().setSelectedObject(box)
def n = TMEQuant.detectFibersInSelected(d)
def det = h.getDetectionObjects().findAll { it.getPathClass()?.toString() == "Fiber" }
return groovy.json.JsonOutput.toJson([n: n, fibres: det.collect { o ->
    o.getROI().getAllPoints().collect { pt -> [pt.getX(), pt.getY()] } }])
'''


def resample(poly, step):
    """Points every `step` px along a polyline, each with its unit direction."""
    out = []
    for (x0, y0), (x1, y1) in zip(poly, poly[1:]):
        seg = math.hypot(x1 - x0, y1 - y0)
        if seg == 0:
            continue
        ux, uy = (x1 - x0) / seg, (y1 - y0) / seg
        k = max(1, int(seg // step))
        for i in range(k):
            t = i * seg / k
            out.append((x0 + ux * t, y0 + uy * t, ux, uy))
    return out


def nearest(pt, poly):
    """Distance from pt to a polyline, and the unit direction of the nearest segment."""
    best = (float("inf"), (1.0, 0.0))
    px, py = pt
    for (x0, y0), (x1, y1) in zip(poly, poly[1:]):
        dx, dy = x1 - x0, y1 - y0
        l2 = dx * dx + dy * dy
        if l2 == 0:
            continue
        t = max(0.0, min(1.0, ((px - x0) * dx + (py - y0) * dy) / l2))
        dist = math.hypot(px - (x0 + t * dx), py - (y0 + t * dy))
        if dist < best[0]:
            ln = math.sqrt(l2)
            best = (dist, (dx / ln, dy / ln))
    return best


def follows(sample, poly, tol_px, max_angle_deg):
    dist, (gx, gy) = nearest(sample[:2], poly)
    if dist > tol_px:
        return False
    cos = abs(sample[2] * gx + sample[3] * gy)   # orientation, so 0 and 180 deg agree
    return cos >= math.cos(math.radians(max_angle_deg))


def score(fibres, gt, tol_px, max_angle_deg, step=2.0):
    def bbox(poly, pad):
        xs = [p[0] for p in poly]
        ys = [p[1] for p in poly]
        return min(xs) - pad, min(ys) - pad, max(xs) + pad, max(ys) + pad

    gt_boxes = [bbox(g, tol_px) for g in gt]
    assigned = {}
    n_follow = 0
    for f in fibres:
        samples = resample(f, step)
        if not samples:
            continue
        votes = {}
        for s in samples:
            for gi, (a, b, c, d) in enumerate(gt_boxes):
                if a <= s[0] <= c and b <= s[1] <= d and follows(s, gt[gi], tol_px, max_angle_deg):
                    votes[gi] = votes.get(gi, 0) + 1
        if votes:
            gi, v = max(votes.items(), key=lambda kv: kv[1])
            if v > len(samples) / 2:
                n_follow += 1
                assigned.setdefault(gi, 0)
                assigned[gi] += 1
    # Coverage: traced-length samples with a detection running along them.
    f_boxes = [bbox(f, tol_px) for f in fibres if len(f) > 1]
    f_list = [f for f in fibres if len(f) > 1]
    covered = total = 0
    for g in gt:
        for s in resample(g, step):
            total += 1
            for fi, (a, b, c, d) in enumerate(f_boxes):
                if a <= s[0] <= c and b <= s[1] <= d and follows(s, f_list[fi], tol_px, max_angle_deg):
                    covered += 1
                    break
    hit = len(assigned)
    return {
        "detections": len(fibres),
        "follow_a_traced_fibre": n_follow,
        "traced_fibres": len(gt),
        "traced_fibres_hit": hit,
        "recall_pct": round(100.0 * hit / max(1, len(gt)), 1),
        "pieces_per_hit_fibre": round(n_follow / hit, 2) if hit else None,
        "traced_length_covered_pct": round(100.0 * covered / max(1, total), 1),
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--project", required=True)
    ap.add_argument("--entry", required=True, help="project entry ID holding the traced lines")
    ap.add_argument("--image", required=True, help="the entry's image FILE (opened directly)")
    ap.add_argument("--thresh-im2", type=int, default=77)
    ap.add_argument("--norm-region", default="2080,32,91616,73152",
                    help="x,y,w,h the original run was normalised on (full-res px)")
    ap.add_argument("--norm-down", type=float, default=1.0)
    ap.add_argument("--min-length-um", type=float, default=4.0)
    ap.add_argument("--seed-spacing-um", type=float, default=8.0)
    ap.add_argument("--tol", type=float, default=8.0, help="px a sample may lie from a traced fibre")
    ap.add_argument("--angle", type=float, default=30.0, help="max direction difference, degrees")
    ap.add_argument("--out", default="gt_score.json")
    a = ap.parse_args()

    q = R.Mcp(R.CONFIG["port"])
    q.connect()
    R.close_dialogs(q)
    setup = json.loads(q.groovy(GROOVY_SETUP % {"project": a.project, "entry": a.entry,
                                                "image": a.image}, timeout_s=1200)[0])
    print(f"box {setup['box']}  traced fibres {len(setup['gt'])}  pixel size {setup['px_um']} um")
    nx, ny, nw, nh = (int(v) for v in a.norm_region.split(","))
    thr = json.loads(q.groovy(GROOVY_THRESHOLD % {"target": a.thresh_im2, "nx": nx, "ny": ny,
                                                  "nw": nw, "nh": nh, "down": a.norm_down},
                              timeout_s=1200)[0])
    print(f"native threshold {thr['threshold']} -> thresh_im2 {thr['thresh_im2']} on the norm region")
    results = {"settings": vars(a), "threshold": thr, "box": setup["box"], "gt": setup["gt"], "sets": {}}
    for name, s in SETS.items():
        run = json.loads(q.groovy(GROOVY_RUN % {"threshold": thr["threshold"], "minlen": a.min_length_um,
                                                "seed": a.seed_spacing_um,
                                                "extra": json.dumps(s["extra"]) if s["extra"] else ""},
                                  timeout_s=1800)[0])
        sc = score(run["fibres"], setup["gt"], a.tol, a.angle)
        results["sets"][name] = {"extra": s["extra"], **sc, "fibres": run["fibres"]}
        print(f"{name:24s} " + "  ".join(f"{k}={v}" for k, v in sc.items()))
    q.groovy('import qupath.ext.tmequant.FiberSocketPreferences as P\nP.extraParamsProperty().set("")\n'
             'qupath.getImageData()?.setChanged(false)\nreturn 1')
    with open(a.out, "w") as f:
        json.dump(results, f, indent=2)
    print(f"wrote {a.out}")


if __name__ == "__main__":
    main()
