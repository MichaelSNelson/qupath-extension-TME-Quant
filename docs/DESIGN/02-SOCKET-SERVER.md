# TME Quant — Python FIRE Socket Server (Design Doc 02)

> The server that owns all the fiber math. The QuPath extension is a thin client; everything about
> *how fibers are computed* lives here. Files:
> `fiber_socket_bridge/fiber_socket_server.py` (~1500 lines) and `fire_worker.py` (~140 lines).
> FIRE defaults come from `tme-quant/src/tme_quant/src/pycurvelets/get_fire.py`
> (`DEFAULT_CTFIRE_PARAMS`). Line numbers below are current as of 2026-08.

---

## 1. Responsibilities

1. Listen on `127.0.0.1:5101`, speak the 8-byte-command wire protocol.
2. Load the compiled CT-FIRE backend (or fall back to a synthetic detector).
3. Turn a region image + parameter set into traced fibers (`analyze_real`).
4. Score parameter grids for tuning (`gridsearch`).
5. Post-process assembled fibers (dedup / seam-stitch / global TACS).
6. Contain FIRE crashes so one bad tile doesn't kill the server.

## 2. Wire protocol

**Framing** (`fiber_socket_server.py`): 8-byte ASCII command (`_`-padded), then variable parts each
as a big-endian `uint32` length + raw bytes, then a length-prefixed UTF-8 JSON reply. Helpers:
`_recv_exact :1250`, `_read_uint32 :1261`, `_send_json :1265`. Dispatch loop `handle_client :1274`
(a connection may pipeline several commands).

| Command | Handler | Request payload | Response |
|---|---|---|---|
| `PING____` | `:1283` | none | `{ok, backend:"real"|"synthetic", tacs, reason}` |
| `ANALYZE_` | `:1296` | meta JSON + region PNG (+ mask PNG if `has_mask`) | analyze result (below) |
| `POSTPROC` | `:1330` | meta JSON (+ mask PNG) | `{ok, n_fibers, fibers[]}` |
| `GRIDSRCH` | `:1365` | meta JSON + region PNG | `{ok, supervised, n_combos, results[], best_band, score_mode}` |
| `SHUTDOWN` | `:1291` | none | none (sets shutdown_event) |
| unknown | `:1383` | — | `{ok:false, error:"unknown command …"}` |

### ANALYZE_ meta (client → server)
`region {x,y,width,height,downsample}` (echoed only), `fiber_mode`, `use_ct_reconstruction`,
`params` (full ctfire dict or null → server defaults), `overrides` (per-key dialog values),
`prescaled` (bool), `has_mask`, `distance_threshold` (TACS zone, default 100).

### ANALYZE_ response
`ok, backend, reason, n_fibers`, diagnostics `n_traced, n_length_dropped, min_length_px,
n_width_dropped, seed_spacing_used, seed_auto_raised, frac_above`, `image {w,h}`, `region`,
`fibers[]`. Per-fiber (`_fibers_from_fire :312`): `id, points[[x,y]…]` (region-local, x=col/y=row),
`center, angle` [0,180), `length_arc, length_end, width, straightness, tacs`, plus
`angle_to_tangent`/`distance_to_boundary` when TACS ran. A real-backend error returns
`{ok:false, backend:"real", error, n_fibers:0, …}` (not a synthetic masquerade).

## 3. Backend loading (real vs synthetic)  — the source of "backend=real but 0 fibers" nuance

- `_BACKEND` starts `"synthetic"` (`:130`), becomes `"real"` only after a *verified* import
  (`:256`). `_IMPORT_ERROR` is surfaced via PING `reason` and synthetic responses.
- `_try_import_pipeline(repo_root) :167`:
  - Adds `repo/src` and `repo/src/tme_quant/src` to `sys.path`; `MPLBACKEND=Agg`.
  - **Windows DLL fix** (`:191-202`): `os.add_dll_directory` for the Python dir, the `.pyd` dir
    (`src/ctfire_py`), and `C:\msys64\ucrt64\bin` — Python 3.8+ won't search PATH for
    extension-module DLL deps.
  - **Pre-load trick** (`:204-216`): imports `fiber_backend` *before* the scientific stack
    (scipy/skimage/opencv), so it's cached in `sys.modules` and `fire_2d_angle`'s later import
    reuses the correctly-linked module — avoiding the DLL/runtime skew that causes
    `DLL load failed … procedure could not be found`.
  - **Load verification** (`:232-251`): `fire_2d_angle` silently sets its `fiber_backend` attr to
    None on failure; the code fetches the real module from `sys.modules`, and if the backend is
    None re-imports to capture the *real* error and raises `ImportError`. This is what prevents the
    server from claiming `real` yet failing every request — **but it only checks the module
    imports, not that it traces**, which is why a subtly-incompatible build can still return 0. The
    QuPath **self-test** closes that last gap (see `04`).
- Any import failure → `_IMPORT_ERROR` set, logs `REAL BACKEND UNAVAILABLE -> synthetic fallback`,
  stays synthetic. TACS import is separate and non-fatal (`_TACS_OK`). `--synthetic` forces
  fallback. The ~30 s "PLEASE WAIT loading" banner prints before the socket listens.

## 4. `analyze_real` pipeline (`:951`)  — stage by stage

1. **Param resolution** (`:959-993`): `resolved = deepcopy(meta.params or DEFAULT_CTFIRE_PARAMS)`.
   Override keys in `_TOP_LEVEL {LL1, widMAX, coefficient_percentile, num_scales, fiber_threshold}`
   go to `resolved[k]`; everything else into `resolved["value"][k]`. `_INT_FIRE_PARAMS`
   (`ang_interval, s_xlinkbox, s_fiberdir, s_minstep, s_maxstep, num_scales, thresh_LMPdist`) are
   `int(round(...))` — JSON numbers arrive as floats and the C++/`np.arange` sites reject floats.
2. **Prescale handling** (`:995-1004`): squeeze to 2D, float32. If **not** `prescaled` and max>0,
   normalize `imn = imn/imn.max()*255` (per-tile). When `prescaled=true` (client already scaled to
   a **global** 0-255 reference), skip — per-tile max scaling was what amplified black-border tile
   noise into spurious fibers.
3. **Log** (`:1005-1011`): `FIRE params: thresh_im2=… LL1=… thresh_LMPdist=… prescaled=… |
   X% of pixels > threshold` (`frac_above`).
4. **Non-square padding** (`:1012-1032`): if `h≠w`, pad to `S×S` (S=max), real image top-left, zero
   margin. **Why:** the compiled backend uses image *height* as the row stride
   (`engine(sizey, sizez)`), so a non-square buffer scrambles vertical adjacency and collapses
   every fiber to a spurious horizontal line. Square ⇒ stride==h==w ⇒ correct. Coordinates
   unchanged; proper fix is upstream (`engine(sizez, sizey)`).
5. **FIRE + OOM retry** (`:1040-1074`): up to 4 attempts. On `bad_alloc`/`MemoryError`
   (`_is_oom_error :943`), **raise the threshold** (`thr*1.4+5`) and double seed spacing, then
   retry — bad_alloc is driven by junction *density* (O(Σ D²)), so thinning ridges via threshold is
   the real lever. Logs the auto-raise advisory if it recovers.
6. **Filters:** drop fibers whose center fell in the zero pad; `n_traced = len`; **min-length
   (LL1)** filter `length_arc > ll1` (applied here so it can report kept-vs-traced, `:1089`);
   **max-width (widMAX)** filter in Python because the FIRE-only backend ignores widMAX (`:1099`).
7. **TACS** (`:1135-1143`): if a mask was sent, `_attach_tacs` classifies each fiber
   (TACS-1/2/3) by boundary-relative angle within `distance_threshold`; non-fatal on error.

Note: the `"Fiber segments: N"` line the user sees is printed by the **CT-FIRE pipeline itself**
(inside `tme-quant`), not by this server; the server logs `-> N fibers (backend)` at
`handle_client :1328`.

## 5. Grid search (`gridsearch :772`)

- Requires `backend=real`. `_grid_combos(grid, max_combos) :763` = Cartesian product of
  `{key:[values]}`, truncated to `max_combos`.
- GT lines → `_gt_segments :616` (`{mid, ang, len}` per polyline). `supervised = len(gt)>0`.
- Per combo (`:809`): `overrides = base_overrides ∪ combo`; run `analyze_real(imn, {prescaled:True,
  overrides})`; score; log a per-combo trace (seed/sens/ext/linka → n_fibers, score, matched).
- **Scoring:**
  - `_score_supervised :630` — greedy nearest match per GT segment within `match_dist` **and**
    `match_angle`; `recall = matched/n_gt`, `precision = matched/n_det`. Collects `matched_gt`
    (which GT indices were actually found) for the coverage view. Count closeness = flat band
    within `count_tol` else Gaussian. **Count mode formula:**
    `score = count_score · recall · (0.5 + 0.5·precision) · (0.7 + 0.2·len_factor +
    0.1·orient_factor)` — recall×precision force the *right* fibers, not just the right count (this
    replaced a saturating guard that let "17 detected but wrong 17" rank #1). **Match mode**
    (default) = F-beta (β=1.5) × orientation.
  - `_score_unsupervised :735` (no GT) — `coverage` of foreground by rasterized fibers +
    `0.4·straightness − degenerate(single-orientation) − runaway(too many)`.
- Ranking (`:837`): sort desc; `best_band` = overrides within 95% of best; only top_k keep `fibers`.

## 6. Worker isolation (`fire_worker.py`)

- Opt-in via `TMEQUANT_FIRE_WORKER=1` (default **off** → in-process; a segfault then ends the
  server, mitigated by the threshold/seed/downsample levers).
- When on: `ProcessPoolExecutor(max_workers=1, spawn, initializer=worker_init)`. `worker_init`
  repeats the pre-load-fiber_backend-first ordering (a spawned interpreter re-imports fresh).
  `worker_probe` confirms the worker can load the backend at startup, else falls back to
  in-process.
- `_run_fire :906` submits to the worker; `BrokenProcessPool` (segfault) → `_reset_executor` +
  a clear "FIRE crashed (too many seeds)" error; 600 s timeout → reset + timeout error;
  spawn/pickle infra errors → warn and drop to in-process. `MemoryError`/bad_alloc propagate to the
  `analyze_real` retry loop.

## 7. Post-processing (`postprocess_fibers :584`)

- `_dedup_fibers :497` — KDTree over centers; drop the shorter of two **cross-tile** near-duplicates
  (angle diff ≤20°, length ratio ≥0.5). Same-tile pairs skipped.
- `_stitch_fibers :527` — greedy end-to-end join of pieces split across tile **seams** (endpoints
  within `stitch_tol`, outward directions near-opposite within `angle_tol`), recomputing metrics.
- Global TACS re-run on the assembled boundary if a mask is present.
- `_fibers_from_fire :312` — reconstructs one full-centerline polyline per fiber from the
  `fire_2d_angle` dict (`Fai/Xai/Ra/M["L"]`), emitting `points` as `[col,row]` and `angle` in a
  y-down convention that matches the pipeline's own `draw_curvs` overlay, so QuPath lines match.

## 8. Gotchas for maintainers

- **`prescaled=true` is load-bearing.** It tells the server to trust the client's global 0-255
  scaling. If a caller forgets it, the server re-normalizes per tile and border tiles bloom noise.
- **Non-square padding is mandatory** until the upstream stride bug is fixed; never remove it.
- **The load-verification dance** (§3) is deliberately paranoid — do not simplify it to a bare
  `import`, or the server will lie about `backend=real`.
- **Int coercion** of `_INT_FIRE_PARAMS` is required; passing floats crashes the C++ side.
