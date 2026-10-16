#!/usr/bin/env python3
"""End-to-end regression checks for TME Quant, run inside a real QuPath through qupath-mcp.

The Python harness proves the fibre server; this proves the QuPath side: the dialog, the batch
dialog, the headless API, run logs and the objects that end up in the hierarchy. Each scenario
drives QuPath the way a person would (open the dialog, set controls by their labels, press the
button) and then reads the result back with Groovy as a fingerprint: object count, classes, and
sums of the measurements and ROI lengths.

    python3 regress.py                 # run everything, compare with expected.json
    python3 regress.py --record        # run everything, (re)write expected.json
    python3 regress.py flow_parallel   # run selected scenarios

Some scenarios compare two runs with each other (serial vs parallel tiles, dialog vs headless API)
and pass or fail on their own. The rest compare against the recorded fingerprints, so a change in
detection output shows up as a failure that names the scenario and the field that moved. Record
again after an intended change.

Needs: a QuPath 0.7 with this extension and the qupath-mcp extension installed, its MCP server on
MCP_PORT (default 51516, the Linux QuPath), and the fibre server on 5101. Paths come from the
environment (see CONFIG below).
"""
import argparse
import json
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG = {
    "port": os.environ.get("MCP_PORT", "51516"),
    "image": os.environ.get("REGRESS_IMAGE",
                            "/mnt/f/claude/liver_L002_1_20x_42841_11994_7.0_biref_003.ome.tif"),
    "workdir": os.environ.get("REGRESS_DIR", os.path.expanduser("~/tmequant-regress")),
    "qupath_log": os.environ.get("REGRESS_QUPATH_LOG",
                                 os.path.expanduser("~/tmequant-regress/qupath.out")),
    "expected": os.path.join(HERE, "expected.json"),
}
DIALOG = "TME Quant - Analyze region"
BATCH = "Run across project"


# ---- MCP client -----------------------------------------------------------------------------

class Mcp:
    """Minimal streamable-HTTP JSON-RPC client for the qupath-mcp server."""

    def __init__(self, port):
        self.url = f"http://127.0.0.1:{port}/mcp"
        self.session = None
        self.n = 0

    def _post(self, body):
        if "method" in body and not body["method"].startswith("notifications/"):
            self.n += 1
            body = dict(body, id=self.n)
        body["jsonrpc"] = "2.0"
        req = urllib.request.Request(self.url, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json",
                                              "Accept": "application/json, text/event-stream"})
        if self.session:
            req.add_header("Mcp-Session-Id", self.session)
        with urllib.request.urlopen(req, timeout=3600) as r:
            self.session = r.headers.get("Mcp-Session-Id") or self.session
            raw, ctype = r.read().decode(), r.headers.get("Content-Type", "")
        if not raw.strip():
            return None
        if "text/event-stream" in ctype:
            msgs = [json.loads(l[5:]) for l in raw.splitlines() if l.startswith("data:")]
            return next((m for m in msgs if "result" in m or "error" in m), msgs[-1])
        return json.loads(raw)

    def connect(self):
        self._post({"method": "initialize", "params": {
            "protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "tmequant-regress", "title": "TME Quant regression", "version": "1"}}})
        self._post({"method": "notifications/initialized"})

    def call(self, tool, **args):
        r = self._post({"method": "tools/call", "params": {"name": tool, "arguments": args}})
        if "error" in r:
            raise RuntimeError(f"{tool}: {r['error']}")
        res = r["result"]
        text = "\n".join(c.get("text", "") for c in res.get("content", []) if c.get("type") == "text")
        if res.get("isError"):
            raise RuntimeError(f"{tool}{args}: {text}")
        return text

    def groovy(self, code, timeout_s=3000):
        out = self.call("qupath_run_groovy", code=code, timeout_s=timeout_s)
        # "output:\n...\n\nresult: X" -> X, with printed lines available too
        return out.rsplit("result: ", 1)[-1].strip(), out


# ---- QuPath helpers -------------------------------------------------------------------------

def log_mark():
    try:
        with open(CONFIG["qupath_log"], errors="replace") as f:
            return sum(1 for _ in f)
    except FileNotFoundError:
        return 0


def wait_log(mark, needle, timeout=1800, also_fail=("Exception in thread",)):
    """Wait for `needle` in QuPath's log after line `mark`; return the new lines."""
    t0 = time.monotonic()
    while time.monotonic() - t0 < timeout:
        with open(CONFIG["qupath_log"], errors="replace") as f:
            lines = f.readlines()[mark:]
        if any(needle in l for l in lines):
            return lines
        time.sleep(2)
    raise TimeoutError(f"'{needle}' not in QuPath's log after {timeout}s")


FINGERPRINT = r'''
def h = qupath.getImageData().getHierarchy()
def objs = (h.getDetectionObjects() + h.getAnnotationObjects()).findAll {
    def c = it.getPathClass()?.toString(); c == "Fiber" || c?.startsWith("TACS") }
def keys = ["Length (um)", "Angle (deg)", "Width mean (um)", "Width median (um)", "Straightness",
            "Curvature (deg/um)"]
def sums = keys.collectEntries { k -> [k, objs.sum { o -> def v = o.getMeasurementList().get(k);
            (v == null || Double.isNaN(v)) ? 0d : v } ?: 0d] }
def out = [n: objs.size(),
           classes: objs.countBy { it.getPathClass().toString() }.sort(),
           roi_length: objs.sum { it.getROI().getLength() } ?: 0d,
           run_ids: objs.collect { it.getMeasurementList().get("Run ID") }.unique().size(),
           sums: sums]
return groovy.json.JsonOutput.toJson(out)
'''


def fingerprint(q):
    fp = json.loads(q.groovy(FINGERPRINT)[0])
    fp["roi_length"] = round(fp["roi_length"], 2)
    fp["sums"] = {k: round(v, 2) for k, v in fp["sums"].items()}
    return fp


def open_image(q, path=None):
    q.groovy('qupath.getImageData()?.setChanged(false)\nreturn "ok"')
    q.call("qupath_open", path_or_uri=path or CONFIG["image"])
    q.call("qupath_wait", text=os.path.basename(path or CONFIG["image"]), timeout_s=60)


def reset_hierarchy(q, rects=(), named=None, shapes=()):
    """Clear the image and add rectangle (x, y, w, h) and ellipse annotations; select the first."""
    code = '''
import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane
def h = qupath.getImageData().getHierarchy()
def p = ImagePlane.getDefaultPlane()
def made = []
''' + "".join(
        f'made << PathObjects.createAnnotationObject(ROIs.createRectangleROI({x},{y},{w},{h_}, p))\n'
        for x, y, w, h_ in rects) + "".join(
        f'made << PathObjects.createAnnotationObject(ROIs.createEllipseROI({x},{y},{w},{h_}, p))\n'
        for x, y, w, h_ in shapes) + f'''
{"".join(f'made[{i}].setName("{n}"){chr(10)}' for i, n in enumerate(named or []))}
fx {{ h.clearAll(); h.addObjects(made); if (made) h.getSelectionModel().setSelectedObject(made[0]) }}
return made.size()
'''
    return q.groovy(code)[0]


def select_named(q, name):
    q.groovy(f'''def h = qupath.getImageData().getHierarchy()
def a = h.getAnnotationObjects().find {{ it.getName() == "{name}" }}
fx {{ h.getSelectionModel().setSelectedObject(a) }}
return a != null''')


def remove_fibres(q):
    q.groovy('''def h = qupath.getImageData().getHierarchy()
def f = (h.getDetectionObjects() + h.getAnnotationObjects()).findAll {
    def c = it.getPathClass()?.toString(); c == "Fiber" || c?.startsWith("TACS") }
fx { h.removeObjects(f, true) }
return f.size()''')


def close_dialogs(q):
    for title in (BATCH, DIALOG):
        try:
            q.call("qupath_close", window=title)
        except RuntimeError:
            pass


def open_dialog(q):
    close_dialogs(q)
    q.call("qupath_menu", path="Extensions>TME Quant>Analyze fibers in selected region...")
    q.call("qupath_wait", text="Apply to chosen area", window=DIALOG, timeout_s=60)
    q.groovy(f'''def w = window("{DIALOG}")
fx {{ w.scene.root.lookupAll(".titled-pane").each {{ it.setExpanded(true) }} }}
return "ok"''')


def set_controls(q, settings):
    """settings: list of (kind, control-label, value); kind is 'set' or 'select'."""
    for kind, control, value in settings:
        if kind == "set":
            q.call("qupath_set", value=value, control=control, window=DIALOG)
        else:
            q.call("qupath_select", text=value, control=control, window=DIALOG)


def apply_dialog(q, timeout=1800):
    mark = log_mark()
    q.call("qupath_click", text="Apply to chosen area", window=DIALOG)
    lines = wait_log(mark, "committing", timeout)
    for l in lines:
        if "Post-process:" in l or "Tracing" in l:
            print("     " + l.split(" - ", 1)[-1].strip())
    time.sleep(2)  # objects are added on the FX thread after the log line
    return lines


# Every scenario starts from these, because the dialog persists its settings: without them a
# scenario inherits whatever the previous one left (a recorded baseline once ran entirely at the
# large scenario's threshold of 3500). Threshold 6682 is this image's default.
BASE = [("set", "Downsample", 1), ("set", "Parallel tiles", 1),
        ("set", "Background threshold", 6682), ("set", "Min fiber length (um)", 15),
        ("set", "Two-pass (second threshold)", "false"),
        ("set", "Use whole image (ignore selection)", "false"),
        ("set", "Restrict to selected object (mask + clip)", "false"),
        ("set", "Classify TACS relative to a boundary", "false"),
        ("set", "Create detections (not annotations)", "true"),
        ("set", "Remove existing Fiber/TACS objects in the region first", "true")]
FIRE = [("select", "Tracer", "FIRE (seed & extend)")]
FLOW = [("select", "Tracer", "Flow (orientation field)")]
CENTERLINE = [("select", "Tracer", "Centerline (medial axis)")]


# ---- scenarios ------------------------------------------------------------------------------
# Each returns (fingerprint-or-None, list of failure messages). A fingerprint is compared with
# expected.json; the messages are checks the scenario makes on its own.

def s_flow_parallel(q):
    """Flow tracer, 2400 px region: 4 parallel tiles must give exactly the serial result."""
    reset_hierarchy(q, rects=[(4000, 2500, 2400, 2400)])
    open_dialog(q)
    set_controls(q, BASE + FLOW)
    apply_dialog(q)
    serial = fingerprint(q)
    set_controls(q, [("set", "Parallel tiles", 4)])
    apply_dialog(q)
    par = fingerprint(q)
    fails = [] if strip_ids(serial) == strip_ids(par) else [f"parallel differs: {diff(serial, par)}"]
    return serial, fails


def s_centerline_parallel(q):
    """Centerline uses the same worker pool as Flow; parallel must equal serial."""
    reset_hierarchy(q, rects=[(4000, 2500, 2400, 2400)])
    open_dialog(q)
    set_controls(q, BASE + CENTERLINE)
    apply_dialog(q)
    serial = fingerprint(q)
    set_controls(q, [("set", "Parallel tiles", 4)])
    apply_dialog(q)
    par = fingerprint(q)
    fails = [] if strip_ids(serial) == strip_ids(par) else [f"parallel differs: {diff(serial, par)}"]
    return serial, fails


def s_fire_parallel_requested(q):
    """FIRE ignores Parallel tiles on the server; asking for 4 must neither time out nor change it."""
    reset_hierarchy(q, rects=[(4000, 2500, 1600, 1600)])
    open_dialog(q)
    set_controls(q, BASE + FIRE)
    apply_dialog(q)
    serial = fingerprint(q)
    set_controls(q, [("set", "Parallel tiles", 4)])
    lines = apply_dialog(q)
    par = fingerprint(q)
    fails = []
    if strip_ids(serial) != strip_ids(par):
        fails.append(f"parallel request changed FIRE output: {diff(serial, par)}")
    skipped = [l for l in lines if "skipped" in l or "timed out" in l.lower()]
    if skipped:
        fails.append(f"{len(skipped)} tile(s) skipped/timed out with parallel requested")
    return serial, fails


def s_headless_vs_dialog(q):
    """TMEQuant.detectFibers on an annotation vs the dialog on the same region, FIRE defaults."""
    reset_hierarchy(q, rects=[(4000, 2500, 1600, 1600)], named=["region"])
    # Open at downsample 2 and switch to 1 in the open dialog: the dialog used to keep the stats of
    # the downsample it opened at, so this is the path that diverged from the headless API.
    open_dialog(q)
    set_controls(q, [("set", "Downsample", 2)])
    close_dialogs(q)
    open_dialog(q)
    set_controls(q, BASE + FIRE)
    apply_dialog(q)   # also persists the tuned settings the headless API reads
    dialog = fingerprint(q)
    close_dialogs(q)
    remove_fibres(q)
    n, _ = q.groovy('''import qupath.ext.tmequant.TMEQuant
return TMEQuant.detectFibersInAnnotations(qupath.getImageData())''')
    time.sleep(1)
    headless = fingerprint(q)
    fails = []
    if strip_ids(dialog) != strip_ids(headless):
        fails.append(f"headless != dialog: {diff(dialog, headless)}")
    return {"dialog": dialog, "headless": headless}, fails


ELLIPSE = (4200, 2700, 2000, 1600)


def s_restrict_to_object(q):
    """Restrict-to-object on an ellipse: fibres well inside must come out unchanged."""
    reset_hierarchy(q, shapes=[ELLIPSE])
    open_dialog(q)
    set_controls(q, BASE + FLOW)
    apply_dialog(q)
    free = json.loads(q.groovy(ANGLES)[0])
    set_controls(q, [("set", "Restrict to selected object (mask + clip)", "true")])
    apply_dialog(q)
    fp = fingerprint(q)
    clipped = json.loads(q.groovy(ANGLES)[0])
    set_controls(q, [("set", "Restrict to selected object (mask + clip)", "false")])
    import math
    x, y, w, h = ELLIPSE
    cx, cy, ax, ay = x + w / 2, y + h / 2, w / 2, h / 2

    def r(px, py):  # 1.0 on the ellipse
        return math.sqrt(((px - cx) / ax) ** 2 + ((py - cy) / ay) ** 2)
    # A fibre with both ends well inside (r < 0.85, >= ~120 px from the edge) is not clipped, and the
    # mask is too far away to change its trace, so it must reappear with the same angle.
    inner = [f for f in free if r(f[3], f[4]) < 0.85 and r(f[5], f[6]) < 0.85]
    worst, missing = 0.0, 0
    for f in inner:
        c = min(clipped, key=lambda g: (g[0] - f[0]) ** 2 + (g[1] - f[1]) ** 2, default=None)
        if c is None or math.hypot(c[0] - f[0], c[1] - f[1]) > 2.0:
            missing += 1
            continue
        d = abs(c[2] - f[2]) % 180
        worst = max(worst, min(d, 180 - d))
    fails = []
    if len(inner) < 5:
        fails.append(f"only {len(inner)} fibres well inside the ellipse; nothing to compare")
    if missing:
        fails.append(f"{missing}/{len(inner)} fibres well inside the ellipse vanished under restrict-to-object")
    if worst > 1.0:
        fails.append(f"angle of a fibre well inside moved by {worst:.1f} deg under restrict-to-object")
    fp["restrict_inner"] = len(inner)
    return fp, fails


ANGLES = r'''
def h = qupath.getImageData().getHierarchy()
def f = h.getDetectionObjects().findAll { it.getPathClass()?.toString() == "Fiber" }
return groovy.json.JsonOutput.toJson(f.collect { def r = it.getROI(); def p = r.getAllPoints()
    [r.getCentroidX(), r.getCentroidY(), it.getMeasurementList().get("Angle (deg)"), p[0].x, p[0].y, p[-1].x, p[-1].y] })
'''


def s_tacs(q):
    """TACS against an ellipse boundary inside a larger region: classes must be assigned and stable."""
    reset_hierarchy(q, rects=[(3600, 2200, 3000, 3000)], shapes=[(4400, 3000, 1400, 1400)],
                    named=["region", "tumour"])
    select_named(q, "region")
    open_dialog(q)
    set_controls(q, BASE + FLOW + [("set", "Classify TACS relative to a boundary", "true"),
                                   ("select", "Boundary annotation", "tumour")])
    apply_dialog(q)
    fp = fingerprint(q)
    set_controls(q, [("set", "Classify TACS relative to a boundary", "false")])
    fails = []
    tacs = {k: v for k, v in fp["classes"].items() if k.startswith("TACS")}
    if len(tacs) < 2:
        fails.append(f"expected several TACS classes, got {fp['classes']}")
    return fp, fails


def s_batch_two_images(q):
    """Batch over two project images (inside annotations): both get fibres, one global scale."""
    proj = os.path.join(CONFIG["workdir"], "proj2")
    q.groovy(f'''import qupath.lib.projects.Projects
import qupath.lib.images.servers.ImageServers
import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane
def dir = new File("{proj}")
if (dir.exists()) dir.deleteDir()
dir.mkdirs()
def project = Projects.createProject(dir, java.awt.image.BufferedImage)
def uri = new File("{CONFIG['image']}").toURI()
[["a", 4000, 2500], ["b", 1500, 3800]].each {{ n, x, y ->
    def e = project.addImage(ImageServers.buildServer(uri).getBuilder())
    e.setImageName("liver_" + n)
    def d = e.readImageData()
    d.getHierarchy().addObject(PathObjects.createAnnotationObject(
        ROIs.createRectangleROI(x, y, 1600, 1600, ImagePlane.getDefaultPlane())))
    e.saveImageData(d)
}}
project.syncChanges()
qupath.getImageData()?.setChanged(false)   // the scratch image is never saved; no "Save changes?" prompt
fx {{ qupath.setProject(project); qupath.openImageEntry(project.getImageList()[0]) }}
return project.getImageList().size()''')
    time.sleep(3)
    select_first_annotation(q)
    open_dialog(q)
    set_controls(q, BASE + FLOW)
    close_dialogs(q)
    open_dialog(q)
    q.call("qupath_click", text="Run across project…", window=DIALOG)
    q.call("qupath_wait", text="Run analysis", window=BATCH, timeout_s=30)
    q.call("qupath_click", text="All project images (2)", window=BATCH)
    q.call("qupath_click", text="Inside every area annotation", window=BATCH)
    mark = log_mark()
    q.call("qupath_click", text="Run analysis", window=BATCH)
    q.call("qupath_wait", text="Batch targets", timeout_s=60)
    q.call("qupath_key", keys="Enter", window="Batch targets")
    q.call("qupath_wait", text="Finished.", window=BATCH, timeout_s=1800)
    summary = [l for l in q.call("qupath_describe", window=BATCH, limit=80).splitlines() if "Finished." in l]
    counts, _ = q.groovy('''def p = qupath.getProject()
return groovy.json.JsonOutput.toJson(p.getImageList().collectEntries { e ->
    def d = e.readImageData()
    [e.getImageName(), d.getHierarchy().getDetectionObjects().count { it.getPathClass()?.toString() == "Fiber" }] })''')
    counts = json.loads(counts)
    lines = wait_log(mark, "Image data written")
    norms = [l.split("norm=", 1)[1].split()[0:6] for l in log_lines_since(mark) if ": norm=" in l]
    fails = []
    if any(v == 0 for v in counts.values()) or len(counts) != 2:
        fails.append(f"per-image fibre counts {counts}")
    if not summary:
        fails.append("no 'Finished.' summary in the batch dialog")
    close_dialogs(q)
    q.groovy('fx { qupath.setProject(null) }\nreturn "ok"')
    open_image(q)
    # The summary ends with the run's timestamped log file name; keep only the counts.
    text = summary[0].split("Finished.", 1)[1].split("Log:", 1)[0].strip(' ."') if summary else None
    return {"counts": counts, "summary": text}, fails


def snap_failure(q, name, run_dir):
    """Save every open QuPath window (dialogs and the viewer) as they are when a scenario fails.

    Uses qupath-mcp's snap() helper, which writes full-size PNGs to its own folder
    (-Dqupath.driver.out, else the JVM temp dir); they are moved into this run's folder.
    """
    import shutil
    try:
        paths = json.loads(q.groovy(f'''def files = snap("{name}")
return groovy.json.JsonOutput.toJson(files.collect {{ it.getAbsolutePath() }})''')[0])
    except Exception as ex:  # noqa: BLE001 - evidence is best effort; never mask the failure
        return [f"(could not snap: {ex})"]
    os.makedirs(run_dir, exist_ok=True)
    kept = []
    # The screenshots show only the part of a scrolling dialog that is in view; the text outline has
    # every control and its value, for a few KB.
    for title in (DIALOG, BATCH):
        try:
            text = q.call("qupath_describe", window=title, limit=500)
        except RuntimeError:
            continue
        dest = os.path.join(run_dir, f"{name}-{title.replace(' ', '_')}.txt")
        with open(dest, "w") as f:
            f.write(text)
        kept.append(dest)
    for p in paths:
        dest = os.path.join(run_dir, os.path.basename(p))
        try:
            shutil.move(p, dest)
            kept.append(dest)
        except OSError:
            kept.append(p)
    return kept


def log_lines_since(mark):
    with open(CONFIG["qupath_log"], errors="replace") as f:
        return f.readlines()[mark:]


def select_first_annotation(q):
    q.groovy('''def h = qupath.getImageData().getHierarchy()
fx { h.getSelectionModel().setSelectedObject(h.getAnnotationObjects()[0]) }
return "ok"''')


def s_large_postprocess(q):
    """Enough fibres to take the blocked post-process path (> 20,000) and check it completes."""
    reset_hierarchy(q, rects=[(0, 0, 10320, 7720)])
    open_dialog(q)
    set_controls(q, BASE + CENTERLINE + [("set", "Parallel tiles", 6),
                                         ("set", "Use whole image (ignore selection)", "true"),
                                         ("set", "Background threshold", 3500),
                                         ("set", "Min fiber length (um)", 3)])
    lines = apply_dialog(q, timeout=5400)
    fp = fingerprint(q)
    set_controls(q, [("set", "Use whole image (ignore selection)", "false"), ("set", "Parallel tiles", 1)])
    fails = []
    blocked = [l.split(" - ", 1)[-1].strip() for l in lines if "Post-process in blocks:" in l]
    passes = [l.split(" - ", 1)[-1].strip() for l in lines if "Post-process pass" in l]
    broken = [l for l in lines if "post-process failed" in l]
    fp["blocked"] = blocked[-1] if blocked else None
    fp["passes"] = passes[-2:]
    if not blocked:
        raw = [l for l in lines if "Post-process:" in l]
        n_raw = int(raw[-1].split("Post-process:", 1)[1].split()[0]) if raw else 0
        fails.append(f"blocked path not taken ({n_raw} raw fibres; it needs > 20000 -- lower the threshold)")
    elif len(passes) < 2:
        fails.append(f"blocked post-process logged {len(passes)} of its 2 passes")
    if broken:
        fails.append(f"{len(broken)} block(s) failed and were kept unstitched")
    return fp, fails


SCENARIOS = {
    "flow_parallel": s_flow_parallel,
    "centerline_parallel": s_centerline_parallel,
    "fire_parallel_requested": s_fire_parallel_requested,
    "headless_vs_dialog": s_headless_vs_dialog,
    "restrict_to_object": s_restrict_to_object,
    "tacs": s_tacs,
    "batch_two_images": s_batch_two_images,
    "large_postprocess": s_large_postprocess,
}


# ---- comparison -----------------------------------------------------------------------------

def strip_ids(fp):
    return {k: v for k, v in fp.items() if k != "run_ids"}


def diff(a, b, path=""):
    out = []
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            out += diff(a.get(k), b.get(k), f"{path}.{k}" if path else k)
    elif a != b:
        out.append(f"{path}: {a} -> {b}")
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scenarios", nargs="*", help=f"subset of: {', '.join(SCENARIOS)}")
    ap.add_argument("--record", action="store_true", help="write the fingerprints to expected.json")
    a = ap.parse_args()
    names = a.scenarios or list(SCENARIOS)
    unknown = [n for n in names if n not in SCENARIOS]
    if unknown:
        sys.exit(f"unknown scenario(s): {unknown}")

    q = Mcp(CONFIG["port"])
    q.connect()
    os_name = q.groovy('return System.getProperty("os.name")')[0]
    print(f"QuPath on port {CONFIG['port']}: {os_name}")
    expected = {}
    if os.path.exists(CONFIG["expected"]):
        with open(CONFIG["expected"]) as f:
            expected = json.load(f)

    open_image(q)
    results, failed = {}, []
    run_dir = os.path.join(CONFIG["workdir"], "failures", time.strftime("%Y%m%d-%H%M%S"))
    for name in names:
        t0 = time.monotonic()
        print(f"--- {name}: {SCENARIOS[name].__doc__.strip()}")
        try:
            fp, fails = SCENARIOS[name](q)
        except Exception as ex:  # noqa: BLE001 - report and carry on with the next scenario
            fp, fails = None, [f"error: {ex}"]
            for path in snap_failure(q, name, run_dir):
                fails.append(f"saved: {path}")
            close_dialogs(q)
            try:
                open_image(q)
            except Exception:  # noqa: BLE001
                pass
        if fp is not None:
            results[name] = fp
            if not a.record and name in expected and expected[name] != fp:
                fails.append("fingerprint changed: " + "; ".join(diff(expected[name], fp)))
            elif not a.record and name not in expected:
                print("     (no recorded fingerprint; run with --record to add it)")
        if fails and not any(m.startswith("saved: ") for m in fails):
            for path in snap_failure(q, name, run_dir):
                fails.append(f"saved: {path}")
        status = "FAIL" if fails else "ok"
        print(f"     {status} in {time.monotonic() - t0:.0f}s")
        for m in fails:
            print(f"     - {m}")
        if fails:
            failed.append(name)

    if a.record:
        expected.update(results)
        with open(CONFIG["expected"], "w") as f:
            json.dump(expected, f, indent=2, sort_keys=True)
        print(f"recorded {len(results)} fingerprint(s) in {CONFIG['expected']}")
    print(f"\n{len(names) - len(failed)}/{len(names)} passed" + (f"; failed: {failed}" if failed else ""))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
