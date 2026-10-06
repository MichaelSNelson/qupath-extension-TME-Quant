"""trace_worker.py -- worker process for the NON-FIRE tracers (flow, centerline).

Why processes and not threads: measured on a 16-core machine, running the flow tracer in
4 threads was SLOWER than running it serially (0.86x), while its individual building blocks
(scipy gaussian_filter, distance_transform_edt, skimage hessian_matrix) each scale 2.2-2.8x
under the same test. The tracer is dominated by Python-level loops -- `_flow_walk` steps up
to 800 times over small arrays, and candidate selection is a plain loop over thousands of
polylines -- so it holds the GIL and threads cannot overlap it.

Why a separate module from fire_worker: that one must import the compiled `fiber_backend`
BEFORE numpy, to pin the right GCC runtime DLLs on Windows. The flow and centerline tracers
never touch `fiber_backend` -- they are numpy/scipy/skimage only -- so this worker skips that
dance entirely and never loads the FIRE pipeline. The parent hands it the resolved default
parameters instead, so `analyze_real` has everything it needs.

Log records made inside the worker are captured and returned, so the server's per-run
transcript still contains each tile's lines even though they were produced in another process.
"""

import io
import logging
import os

_m = None            # the server module, imported here rather than at the top


def init(params):
    """Import the server module in this worker and bind what its main() normally sets."""
    global _m
    import importlib.util
    import sys

    here = os.path.dirname(os.path.abspath(__file__))
    if here not in sys.path:
        sys.path.insert(0, here)
    spec = importlib.util.spec_from_file_location(
        "fss_trace_worker", os.path.join(here, "fiber_socket_server.py"))
    _m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(_m)

    import numpy
    from PIL import Image as _PILImage

    _m.np = numpy
    _m.Image = _PILImage
    # The tracers do not need the FIRE pipeline; passing the resolved defaults avoids
    # loading it (and its DLLs) in every worker.
    _m._DEFAULT_PARAMS = params
    _m._BACKEND = "real"
    logging.getLogger().setLevel(logging.INFO)


def analyze(image, meta, mask=None, exclude=None):
    """Run one tile. Returns (fibers, stats, captured_log_text).

    Exceptions are allowed to propagate: the parent turns them into the same user-facing
    error it would have produced running in-process.
    """
    buf = io.StringIO()
    handler = logging.StreamHandler(buf)
    handler.setFormatter(logging.Formatter("%(levelname)-7s %(name)s: %(message)s"))
    root = logging.getLogger()
    root.addHandler(handler)
    stats = {}
    try:
        fibers = _m.analyze_real(image, meta, mask, stats_out=stats, exclude=exclude)
    finally:
        root.removeHandler(handler)
        handler.flush()
    return fibers, stats, buf.getvalue()
