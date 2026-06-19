# QuPath &harr; TMEQuant FIRE — socket bridge

This connects **QuPath** to the **TMEQuant FIRE-only** fiber pipeline over a TCP
socket, following the same wire-protocol pattern that
`qupath-extension-qpsc` uses to talk to `microscope_command_server`.

QuPath sends the pixels of a `RegionRequest` to a small Python server; the server
runs FIRE fiber extraction and returns the fibers as polylines + per-fiber
properties; QuPath draws them as line/polyline **annotations** (or **detections**).

```
 QuPath (Java extension)                          Python server
 ───────────────────────                          ─────────────
 read RegionRequest pixels                         fiber_socket_server.py
   │  PNG + JSON header                                  │
   ▼  ── ANALYZE_  <len><meta> <len><png> ──────────▶    │ decode PNG -> numpy
                                                          │ curvealign_ctfire_mode_pipeline
                                                          │   (use_ct_reconstruction=False)
                                                          │ reconstruct polylines from Xai/Fai
   ◀──────────────  <len><json fibers> ──────────────────┘
 map region-local -> full-image coords
 build PolylineROI annotations/detections
```

## Components

| File | Role |
|------|------|
| `fiber_socket_server.py` | TCP server. Wraps the FIRE pipeline; synthetic fallback when the compiled backend is unavailable. |
| `fiber_socket_client_test.py` | Python stand-in for QuPath: sends a test region, prints fibers, can write an overlay PNG. |
| `JavaWireTest.java` | JDK-only client proving the Java framing is wire-compatible with the server. |
| `../qupath-extension-tme-quant/` | The QuPath extension (Java). |

## Wire protocol

8-byte ASCII command, then big-endian 4-byte length-prefixed payloads
(`struct.pack("!I", n)` on Python == `DataOutputStream.writeInt` in Java).

```
PING____                                            -> <len><json {ok, backend}>
ANALYZE_ <metaLen><metaJson> <pngLen><png> [maskBlk] -> <len><json {ok, backend, n_fibers, fibers[]}>
SHUTDOWN                                             -> (no response)
```
`[maskBlk]` = `<maskLen><maskPng>` is sent only when `meta.has_mask` is true
(a region-sized binary boundary mask, used for TACS).

`meta` JSON:
```json
{"region":{"x":489,"y":265,"width":400,"height":400,"downsample":1.0},
 "fiber_mode":2,"use_ct_reconstruction":false,
 "has_mask":false,"distance_threshold":100.0,"params":null}
```

Each returned fiber (TACS fields populated only when a mask was sent):
```json
{"id":0,"points":[[x,y],...],"center":[x,y],"angle":32.7,
 "length_arc":57.1,"length_end":46.3,"width":4.2,"straightness":0.81,
 "tacs":"TACS-3","angle_to_tangent":81.8,"distance_to_boundary":54.1}
```
`points` are **region-local** pixels in `(x=col, y=row)`; the extension adds the
region origin and multiplies by the downsample to get full-image coordinates.
The endpoint geometry of the centerlines comes straight from the FIRE output
(`Xai[Fai[i]['v']]`), so the drawn polylines trace the actual fibers.

---

## Windows setup

New to MSYS2 / running a server? Follow **`WINDOWS_SETUP_GUIDE.md`** (beginner
step-by-step). The helper scripts:

| Script | Run in | Does |
|--------|--------|------|
| `setup_fire_server.sh` | MSYS2 UCRT64 | one-time: installs packages, validates the backend, **auto-rebuilds it from source if the prebuilt `.pyd` doesn't match this machine** |
| `build_backend.sh` | MSYS2 UCRT64 | (re)compiles the FIRE engine `.pyd` from source; called automatically by setup, runnable on its own |
| `start_fire_server.sh` | MSYS2 UCRT64 | starts the server (port 5101) |
| `test_fire_server.sh` | MSYS2 UCRT64 | sends a test region, writes an overlay |
| `diagnose_backend.sh` | MSYS2 UCRT64 | prints why the engine `.pyd` won't load (suffixes, dependent DLLs, real traceback) |
| `Start-FireServer.ps1` | Windows PowerShell | convenience launcher (opens UCRT64 + starts server) |

Expected folder layout: `F:\CTFireTest\{fiber_socket_bridge, tme-quant}`.

**Tester flow (no WSL needed):** install MSYS2 + QuPath, copy the two folders, then
`./setup_fire_server.sh` → `./start_fire_server.sh`. The prebuilt engine is
fragile across MSYS2 versions, so setup rebuilds it locally as needed — a one-time
~1-minute compile that only requires MSYS2.

## Running the server

### On Windows (the real deployment — Python 3.14 / MSYS2 UCRT64)

**Use the scripts** (see the Windows setup section above and
`WINDOWS_SETUP_GUIDE.md`): `./setup_fire_server.sh` then `./start_fire_server.sh`
from the MSYS2 UCRT64 shell. Setup uses the MSYS2 UCRT64 system Python 3.14 with
`pacman`-installed packages, wires up `sys.path` via `TMEQUANT_REPO`, and
rebuilds the FIRE engine (`fiber_backend.cp314-mingw_x86_64_ucrt_gnu.pyd`) from
source if the prebuilt one doesn't match the machine.

**Environment isolation:** the scientific stack (numpy/scipy/opencv/…) is
installed with `pacman` into the MSYS2 base Python (the only way to get those on
MSYS2/GCC). `setup_fire_server.sh` then creates a **virtual environment**
(`venv_fire`, with `--system-site-packages` so it reuses those pacman packages
without copying them) and runs everything from it. Anything `pip` installs
(pybind11, build-time only) goes into the venv — **never** the system Python. So
the base interpreter only ever gets cleanly-managed pacman packages.

Equivalent manual invocation (using the venv the scripts create):
```bash
# from the MSYS2 UCRT64 shell
export TMEQUANT_REPO=/f/CTFireTest/tme-quant           # your repo root
/f/CTFireTest/venv_fire/bin/python \
  /f/CTFireTest/fiber_socket_bridge/fiber_socket_server.py --repo "$TMEQUANT_REPO" --port 5101
```
The server adds the repo's `src/` paths itself (no install / `.pth` needed).

### On WSL/Linux (what was used to develop & test this)

The repo also ships a Linux backend `fiber_backend.cpython-310-x86_64-linux-gnu.so`
(Python **3.10**). A 3.10 venv loads it and runs the **real** pipeline on Linux:

```bash
cd .../TMEQuant_fire_only/tme-quant
uv venv --python 3.10 .venv-fire-linux
uv pip install --python .venv-fire-linux/bin/python \
    numpy scipy scikit-image scikit-learn networkx opencv-python-headless \
    pandas shapely tifffile openpyxl pillow imageio matplotlib

TMEQUANT_REPO=$PWD MPLBACKEND=Agg \
  .venv-fire-linux/bin/python /path/to/fiber_socket_bridge/fiber_socket_server.py --port 5101
```

### Synthetic fallback (any Python, no backend)

If the compiled backend cannot load, the server automatically falls back to an
OpenCV Hough-line "fiber" detector so the socket + QuPath rendering path can
still be exercised. Force it with `--synthetic`.

---

## Testing without QuPath

```bash
# 1) Python round-trip (writes an overlay you can eyeball)
python fiber_socket_client_test.py --port 5101 --x 0 --y 0 --w 400 --h 400 \
    --overlay roundtrip_overlay.png

# 2) Java wire-compatibility (uses the same framing as the extension)
javac JavaWireTest.java
java JavaWireTest region_0_0_400_400.png 127.0.0.1 5101
```

Both were run during development against the real Linux backend: 46 fibers
returned, geometry within region bounds, overlay traces the collagen fibers.

---

## Using the QuPath extension

1. Build it: `cd ../qupath-extension-tme-quant && ./gradlew shadowJar`
   (produces `build/libs/qupath-extension-tme-quant-0.1.0-all.jar`).
2. Drag the jar onto QuPath (or drop it in the extensions dir).
3. Start the Python server (above).
4. In QuPath: open an image, draw + select a region, then **Extensions ▸ TME Quant ▸ Analyze fibers in selected region…**. This opens an **interactive
   dialog**: choose the collagen **channel** (multi-channel images), tune
   **downsample / intensity threshold / min length / seed spacing / fiber mode**,
   click **Preview** to see fibers drawn over the region, then **Add to image**.
   - **Analyze fibers + TACS (boundary = selection)…** — same dialog with TACS
     on; fibers are coloured by TACS relative to the selected boundary
     (red = TACS-3 / perpendicular, green = TACS-2 / parallel, blue = TACS-1),
     with angle-to-boundary and distance stored as measurements.
   - **Ping fiber server** checks connectivity.
   - Persistent preferences: `tmequant.host`, `tmequant.port`,
     `tmequant.collagenChannel`, `tmequant.createDetections`,
     `tmequant.fiberMode`.

   The dialog reads **one chosen channel** via raw raster samples, so it handles
   multi-channel / fluorescence / non-RGB images (which previously crashed PNG
   encoding). See `CHANGELOG.md` for the full history.

### Status / what's tested

- ✅ Python server + **real** FIRE backend (Linux 3.10) — round-trip verified.
- ✅ Synthetic fallback — verified.
- ✅ Java↔Python socket framing — verified with `JavaWireTest`.
- ✅ **TACS** path (boundary mask → per-fiber TACS-1/2/3) — verified on both the
  Python and Java clients (`extract_tif_boundary` + `classify_fiber_tacs`).
- ✅ Extension compiles and builds a shadow jar (QuPath 0.7.0, Java 21 target).
- ⏳ Live in-GUI run (draw rectangle → menu → see fibers) — needs a QuPath
  desktop session; not automatable headlessly. The object-building code uses
  the standard QuPath APIs (`RegionRequest`, `server.readRegion`,
  `ROIs.createPolylineROI`, `PathObjects.create*Object`) exactly as the
  reference `qupath-extension-fiber-analysis` does.

### Possible follow-ups

- Offer Area annotations (fiber width buffered to a polygon) or a non-object
  `PathOverlay` rendering instead of individual objects.
- Persistent/keep-alive connection + cancellation for very large regions.
- Per-TACS summary counts written back as a parent annotation measurement.
