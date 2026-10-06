# TME Quant — QuPath Extension (Design Doc 01)

> The Java/JavaFX client. It owns the UI, reads region pixels, builds the parameter set, talks to
> the Python server over a socket, and writes fibers back into QuPath. Package
> `qupath.ext.tmequant`, targets QuPath 0.7 / Java 21. Line numbers drift as the file changes —
> method names are the stable reference.

---

## 1. Class inventory

| Class | Lines | Role |
|---|---|---|
| `TMEQuantExtension` | ~123 | Entry point. Adds the **Extensions ▸ TME Quant** menu (Analyze, Analyze+TACS, Ping, **Self-test**, Configure launcher) and registers preferences. |
| `FiberAnalysisDialog` | ~4740 | **The whole workbench.** Thresholding + detection + preview + tuning + commit + project-wide. Detailed in §2. |
| `FiberSocketClient` | ~337 | Stateless socket client. `ping/analyze/postprocess/gridSearch/shutdown`; Gson response models. Each call opens a fresh socket. |
| `FiberSocketPreferences` | ~279 | All persistent `PathPrefs` (host/port, launcher, auto-launch, every remembered dialog field incl. `EXTRA_PARAMS`). |
| `ChannelExtractor` | ~155 | One raster band → 8-bit gray for any color model (fluorescence-safe). `toGray(region, ch, lo, hi)` is the **global-reference** prescale used for every tile. |
| `FiberObjectBuilder` | ~126 | Server fiber polylines (region-local, downsampled) → QuPath PolylineROI objects at full-res, with per-fiber measurements + TACS class. |
| `MaskRasterizer` | ~59 | Boundary ROI → region-sized binary mask for TACS. |
| `ScopeSection` | ~208 | Project-run scope UI: image scope (Current/All/Specific) + region mode (whole-image / class-annotations). |
| `ProjectImageSelector` | ~261 | Reusable checkbox project-image picker (name + metadata filters). |
| `StepProgress` | ~113 | Thread-safe colored-bubble step list for batch/project progress. |
| `ServerLauncher` | ~220 | Brings the server up: reachability ping, auto-launch the batch, poll until ready, locate-launcher prompt. |
| `EngineSelfTest` | ~189 | **Diagnostic (recent).** Sends a built-in synthetic fiber image through `analyze` to prove the engine traces. See `04 §4`. |

## 2. `FiberAnalysisDialog` — the central class

Entry: `show(qupath, tacsDefault)` validates the image/selection, then calls
`ServerLauncher.ensureReachable(…, onReady=buildAndShow)` so the server is up before the UI appears.
`buildAndShow()` constructs the scene.

### 2a. UI sections (task-flow order, top to bottom)

- **① Thresholding** (`TitledPane`, collapsible) — "what FIRE sees". Objective preset, Collagen
  channel, Downsample, **Smoothing (sigma_im)**, **Background threshold** (native units) + live
  "% above" readout. Right: the **live red mask** canvas (no server call — pure client render).
  Every control here re-renders the mask instantly.
- **② Fiber detection** (`TitledPane`) — Seed spacing, Seed sensitivity, Cross-link box, Max bend,
  Angle-extend, Max link angle, Min fiber length, Fiber mode, TACS block. Right: the **fiber
  preview** canvas (synced scroll/zoom with the mask), tile label, and the **Preview action bar**
  (scope box "One tile (fast)" / "Whole region" + Preview + Export tile). Status label below.
- **Advanced FIRE settings** (`TitledPane`, collapsed, **full-width** below ②) — Distance
  smoothing, Link distance, Max fiber width, Nucleation, Direction decay, **Direction window
  (s_fiberdir)**, **FIRE min length (thresh_flen)**, **Interp. spacing (s_maxspace)**, Angle
  interval, Beam regularisation, **Dangling prune (thresh_dang_L)**, and the **Extra params (JSON)**
  box. (Bolded ones are the µm-conversion trap — see `03 §4`.)
- **Parameter tuning / Suggest** (`TitledPane`, collapsed) — Count tolerance, Refinement rounds,
  Disjoint pieces; buttons **Calibrate from traced lines**, **Suggest parameters…**, **Tune across
  project…**; a local result echo.
- **Output options** (bordered group) — create-detections, clear-existing, whole-image; grouped
  with the commit `ButtonBar` (Pin-on-top, **Add to image**, Close).
- **Docs bar** — README/settings links + **Save settings… / Load settings…** (full config to JSON).
- **Project-wide row** — **Run across project…** and **Reload data**.

### 2b. Threading model

- **JavaFX Application Thread:** all UI construction, listeners, mask/preview redraws,
  `setBusy()`, `fail()`.
- **Named background threads** (one-shot): `tmequant-preview` (runPreview), `tmequant-gridsearch`
  (Suggest), `tmequant-project-tuning`, `tmequant-batch` (runBatchAnalysis), `tmequant-bg`
  (refreshBackground region read), `tmequant-commit`, plus `tmequant-ping/-selftest/-server-check/
  -server-poll` in other classes.
- **Marshaling:** a worker does socket I/O + image reads, then `Platform.runLater()` to set the
  `lastBg*` fields and redraw. Mid-run progress goes through a `Consumer<String> status` or
  `StepProgress`. A `bgGen` generation counter discards superseded background reads.

### 2c. Tiling

- `TILE_CORE=768`, `TILE_OVERLAP=96`, `MAX_TILES=64` (analysed px). `computeTiles(rwA,rhA,d)` grows
  the core size rather than exceeding `MAX_TILES`; each tile reads core ± overlap.
- `detectTile()` reads the tile, prescales via `toGray(region, ch, normLo, serverNormHi())`, calls
  `client.analyze`, offsets fibers to region coords, tags `tile`. Returns `ok=false` (not throw) on
  a too-dense tile so the run skips just that tile.
- **Seam handling:** a fiber is kept only if its centre is in that tile's *core* (`inCore`);
  seam-crossing pieces are rejoined by the server's `POSTPROC` stitch.
- Preview tile: `previewTileIdx` (-1 = centre), `pickPreviewTile()` (double-click), `drawTileGrid()`
  (yellow selected core). Picking a tile also recenters the mask view on it.

### 2d. The four end-to-end paths

- **Preview** — `runPreview()` → thread → `analyzeFull()` (whole region) or `analyzeOneTile()`
  (one tile). Draws fibers on the preview canvas, shows a "funnel" diagnostic (traced → length →
  width → kept).
- **Commit** — `commit()` reuses the last whole-region result iff region + `settingsSignature()`
  unchanged, else re-runs `analyzeFull`; builds objects via `FiberObjectBuilder`, optionally clears
  existing, `hierarchy.addObjects()`.
- **Suggest** — `runSuggest()` retargets the region, collects selected area ROIs + traced GT lines,
  pre-calibrates min-length/seed from the lines, then `iterativeSuggest()` → `aggregatedSearch()`
  (per-piece `client.gridSearch`, two-stage count-then-geometry, aggregated by combo) → ranked
  results window. Selecting a row calls `applyOverrides()` (converts server analysed-px back to
  spinner units).
- **Project-wide** — `runBatchAnalysis()` computes a **global norm** across all selected images,
  then for each image **context-swaps** the dialog's per-image fields
  (`server/normLo/normHi/calibrated/pixelSizeMicrons/rx…rh`), calls `analyzeFull`, writes objects,
  `entry.saveImageData`, and restores context in `finally`. Project-tuning pools GT pieces across
  images under one global norm.

### 2e. Follow-active-image

`imageChangeListener` on `qupath.imageDataProperty()` → `rebindToImage()`: detach old listeners,
rebind `imageData/server/hierarchy`, recompute calibration, adopt the new selection, reset stale
preview state (`bgGen++`), refresh channels/boundary/stats, reattach, redraw. Deferred via
`pendingImage` if a run is in progress. Selection and hierarchy changes are followed too
(`onSelectionChanged` recomputes stats per region; `onHierarchyChanged`).

### 2f. Normalization & unit helpers

See `03` for the full treatment. Key members: `normLo/normHi/statMin/statMax/statSample`;
`computeStats()`; `threshIm2()` (mask scale); `serverNormHi()/serverThreshIm2()` (floored, sent
scale); `toAnalysedPx()/fromAnalysedPx()` + per-param wrappers; `buildMeta()` (assembles the
override set + Extra-JSON overlay + `prescaled:true`).

### 2g. Tooltip / help-text conventions

- **Every input's tooltip must also be on its row LABEL.** After a grid (`t1`/`t2`/`adv`/`tuneGrid`)
  is populated, call `mirrorTooltipsToLabels(grid)`: it copies each column‑1 control's tooltip onto
  its column‑0 `Label` (only where the label has none), so hovering the *descriptive text* shows the
  same help as hovering the field. It stays in sync automatically — don't hand‑add label tooltips.
  Set the control tooltips before the layout/mirror call.
- **Tooltips must WRAP.** Build them with the `ttip(String)` helper (one per UI class:
  `FiberAnalysisDialog`, `ScopeSection`, `StepProgress`, `ProjectImageSelector`) — it sets
  `wrapText(true)` + `maxWidth(420)`. Never use a bare `new Tooltip(...)`; long text runs off the
  screen edge instead of wrapping.
- **State the DIRECTION of effect.** Parameter tooltips (esp. Advanced) say what HIGHER vs LOWER does
  (e.g. `lam_dirdecay`: *higher* keeps the running heading / *lower* reacts to sharp bends). The
  source of truth for semantics is `docs/PARAMETERS.md`.
- **Flag inactive params.** `Angle‑extend` (`thresh_dang_aextend`) and `Dangling prune`
  (`thresh_dang_L`) are currently NO‑OPS — `fire_2d_angle.py` hard‑sets `faithful_danglers=True`
  (MATLAB‑faithful = no removal). Their tooltips say "currently INACTIVE"; keep that note until
  dangler removal is re‑enabled.

## 3. Fragile / recent areas (read before editing)

- **`serverNormHi` floor vs mask `normHi`** — two parallel intensity scales. The **sent** image is
  floored (`MAX_SERVER_IM2=155`) so bright collagen keeps its gradient; the **mask** uses the plain
  99.5th-percentile `normHi`. They must stay consistent per-use; confusing the two reintroduces the
  saturation bug. (`03 §3`.)
- **µm↔px advanced params** — converted at *run time* using the current downsample, so changing
  Downsample silently changes the analysed-px value sent. Several structural params are really
  CT-FIRE **pixel** quantities mis-typed as µm and inflate on fine-pixel images. (`03 §4`.)
- **Extra-params JSON** — persisted (`EXTRA_PARAMS` + `rememberText`), parsed at run time (bad JSON
  fails only on Preview/Commit), overlaid **last** so it overrides every control.
- **`settingsSignature()`** governs preview→commit reuse but omits some advanced-only knobs — a
  change there may not invalidate a reusable commit. Verify when adding a new control.
- **Batch context-swap** mutates the dialog's own per-image fields from a background thread and
  restores in `finally`. `setBusy(true)` guards it, but any concurrent FX read of those fields
  during a batch would see swapped state.
- **`EngineSelfTest` / param logging** — recent diagnostics; the self-test deliberately sends only
  9 params so it isolates the engine from the extension's conversions (`04 §4`).
- Every path funnels through `FiberSocketClient` over the socket. Per call the server gets:
  `ANALYZE_` = one prescaled 0-255 gray tile PNG + `buildMeta` JSON (+ mask); `POSTPROC` = fiber
  JSON + mask; `GRIDSRCH` = masked gray piece + grid meta.
