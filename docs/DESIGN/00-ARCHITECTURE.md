# TME Quant — System Architecture (Design Doc 00)

> Design-doc set covering how the TME Quant collagen-fiber analysis system is *supposed* to
> work and how the parts fit together. Written 2026-08. Read this file first; the numbered
> companions go deeper on each part.
>
> - `00-ARCHITECTURE.md` — this file: the parts, the wire, the end-to-end flows.
> - `01-QUPATH-EXTENSION.md` — the Java/JavaFX client (menus, dialog, follow-active-image).
> - `02-SOCKET-SERVER.md` — the Python server (protocol, analyze pipeline, grid search).
> - `03-PARAMETERS-AND-NORMALIZATION.md` — **the critical part**: how UI settings become the
>   numbers FIRE sees. This is where every "0 fibers" bug has lived.
> - `04-INSTALL-LAUNCH-DIAGNOSTICS.md` — MSYS2, the batch files, auto-launch, self-test, rebuild.

---

## 1. What the system does

TME Quant lets a QuPath user select a region of a collagen image, run the **CT-FIRE**
fiber-extraction algorithm on it, preview the traced fibers, and commit them back into QuPath as
objects — optionally classified by **TACS** (Tumour-Associated Collagen Signatures) relative to a
tumour boundary. It can also **tune** detection parameters against hand-traced ground-truth fibers
and run **across a whole project**.

The fiber math (CT-FIRE, compiled C++) is heavy and Windows/MSYS2-specific, so it is **not** in the
QuPath extension. Instead the extension is a thin **socket client** and the math runs in a separate
**Python server** process. They talk over a local TCP socket.

## 2. The parts

```
┌─────────────────────────────┐        TCP 127.0.0.1:5101        ┌──────────────────────────────┐
│ QuPath + TME Quant extension │  ── 8-byte cmd + framed bytes ──▶│ Python FIRE socket server     │
│ (Java / JavaFX)              │                                  │ fiber_socket_server.py        │
│                              │◀── length-prefixed JSON reply ───│                               │
│  • menus, dialog, preview    │                                  │  • protocol dispatch          │
│  • reads region pixels       │   region PNG (0-255 gray) +      │  • prescale/threshold handling│
│  • builds the parameter set  │   JSON meta (params, region)     │  • calls CT-FIRE, scores grids│
│  • draws fibers, commits     │                                  │  • isolates FIRE in a worker  │
└─────────────────────────────┘                                  └───────────────┬──────────────┘
                                                                                 │ in-process import
                                                                                 ▼
                                                                  ┌──────────────────────────────┐
                                                                  │ tme-quant pipeline + CT-FIRE  │
                                                                  │ compiled fiber_backend (.pyd) │
                                                                  │ (external, not redistributed) │
                                                                  └──────────────────────────────┘
```

| Part | Language | Repo / location | Redistributed? |
|---|---|---|---|
| **QuPath extension** | Java 21 (QuPath 0.7) | `qupath-extension-tme-quant/` | Yes (Apache-2.0) |
| **Socket server + scripts** | Python 3.14 (Windows/MSYS2) | `fiber_socket_bridge/` | Yes |
| **FIRE pipeline (`tme-quant`)** | Python + compiled C++ | obtained separately, sits next to `fiber_socket_bridge` as `../tme-quant` | **No** (own license) |
| **MSYS2 UCRT64** | — | `C:\msys64` (or `MSYS2_ROOT`) | external install |

The deploy layout on the analysis PC:
```
<root>\                          e.g. D:\python_microscope_server\tmequant\
  fiber_socket_bridge\           server scripts + fiber_socket_server.py
  tme-quant\                     the FIRE pipeline (sibling; note the hyphen)
  venv_fire\                     Python venv created by setup (system-site-packages)
```

## 3. The wire protocol (summary — details in `02`)

Mirrors the QPSC microscope-server convention: an **8-byte ASCII command**, then one or more
**4-byte big-endian length-prefixed** payloads, then a length-prefixed **JSON** reply.

| Command (8 bytes) | Purpose | Client sends | Server returns |
|---|---|---|---|
| `PING____` | health check | — | `{ok, backend, tacs, reason}` |
| `ANALYZE_` | trace one region/tile | meta JSON + region PNG (+ optional mask PNG) | `{ok, backend, nFibers, nTraced, fibers[…], …}` |
| `GRIDSRCH` | tune: score a parameter grid | meta JSON (grid + GT lines) + region PNG | ranked combos `{n_combos, results[…]}` |
| `POSTPROC` | dedup/stitch/global TACS on assembled fibers | meta JSON (+ optional mask) | `{ok, fibers[…]}` |
| `SHUTDOWN` | stop the server | — | — |

`backend` is `"real"` (compiled CT-FIRE loaded) or `"synthetic"` (OpenCV fallback — FIRE params
have no effect). **Key subtlety:** `backend=real` only means the backend *imported*; it does not
guarantee it *traces*. That gap is what the **self-test** (`04`) exists to close.

## 4. End-to-end flows

### 4a. Preview one tile ("Preview fibers", One tile fast)
1. User selects an annotation → dialog reads its bounding box as the region.
2. `computeStats()` samples the region → `normLo/normHi` (intensity white point).
3. User sets **Background threshold** by eye against the live red mask (stage ①).
4. Preview: extension picks one tile, reads its pixels at the analysis downsample,
   **prescales to 0-255** using `normLo / serverNormHi()`, and sends `ANALYZE_` with a meta
   carrying `thresh_im2` + all FIRE params (see `03`).
5. Server runs CT-FIRE on that tile, returns fibers; extension draws them over the preview canvas.

### 4b. Commit ("Add to image")
Same as preview but **all** tiles (`analyzeFull`) → stitch/dedup/TACS via `POSTPROC` →
`FiberObjectBuilder` writes `Fiber` / `TACS-2/3` objects into the image hierarchy.

### 4c. Tune ("Suggest parameters…")
1. User traces a handful of real fibers as **line** annotations inside the region (ground truth).
2. Extension builds a **two-stage** parameter grid (stage A = count axes: seed spacing, seed
   sensitivity, link distance, nucleation; stage B = geometry: bend, link angle, cross-link box)
   and sends `GRIDSRCH` with the GT lines.
3. Server runs CT-FIRE for every combo, scores each against the GT (count + recall + precision +
   length/orientation), returns a ranked list; extension shows a diversity-aware results window.

### 4d. Project-wide (Tune across project… / Run across project…)
Loops the above over many project images under **one global intensity scale**
(`computeGlobalNorm`), writing results into each image's data file. See `01`.

## 5. Cross-cutting concepts (each expanded in `03`)

- **Prescale / normalization.** Native pixel intensities (e.g. 16-bit) are mapped to 0-255 via
  `[normLo, normHi]` *before* being sent, using a **global** reference so every tile and the
  threshold agree. `normHi` too low ⇒ bright collagen clips to a flat 255 ⇒ FIRE finds no ridges
  ⇒ **0 fibers**. The `serverNormHi()` floor exists to prevent this.
- **Two thresholds that must stay consistent.** The user's native **Background threshold** →
  `thresh_im2` (0-255) via the same `normLo/normHi`. If they diverge, the red mask lies about what
  FIRE sees.
- **Units: µm vs px.** Distances shown in the UI are µm on a calibrated image, px otherwise, and
  are converted to *analysed pixels* (`toAnalysedPx`, dividing by downsample) before sending.
  Several **advanced FIRE structural params are algorithmic pixel values**, not physical microns —
  treating them as µm inflates them on high-resolution images (a known source of 0-fiber bugs).
- **Downsample-independence.** Physical params are converted at send time so changing Downsample
  doesn't change their real-world size.
- **Tiling.** Large regions are split into overlapping `TILE_CORE`-sized tiles; each traced
  independently, then stitched. Non-square tiles are padded to square server-side (a FIRE stride
  workaround).

## 6. Where the bugs have lived (history that informs the design)

Every "0 fibers on a real image" report traced back to **stage ① / normalization / unit
conversion**, never to the search or the engine:
1. Coarse stats sampling made `normHi` too low → `thresh_im2≈240` (saturation) → fixed by sampling
   near the mask resolution and the `serverNormHi()` floor.
2. On the **whole image** (mostly dark), the 99.5th-percentile `normHi` collapses → same
   saturation → the `serverNormHi()` floor bounds `thresh_im2 ≤ ~155` regardless.
3. Advanced FIRE params (`s_fiberdir`, `s_maxspace`, `thresh_flen`, …) converted µm→px inflate on
   fine-pixel images (0.17 µm/px) and are **held fixed across the whole Suggest search**, so a bad
   value makes *every* combo return 0 identically.

The **self-test** proved the engine + a synthetic image + default params trace fine (63 segments),
which is what localised the remaining failures to the parameter set. See `03` and `04`.

## 7. Reading order for a newcomer

1. This file. 2. `03-PARAMETERS-AND-NORMALIZATION.md` (the crux). 3. `01` and `02` for the two
code bases. 4. `04` for getting a working server on a new machine.
