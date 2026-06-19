# TME Quant — FIRE parameter reference

Every input the CT-FIRE fiber-extraction engine accepts, which ones the GUI
exposes, which are reachable only through the **Extra params (JSON)** box, and
which currently have **no effect** in the server's FIRE-only path. Defaults are
from `DEFAULT_CTFIRE_PARAMS` (`pycurvelets/get_fire.py`); readable names from
`ctfire_py/parameter_mapping.py`.

> **Audience:** users tuning detection, and developers deciding what to promote
> to a GUI control. If you just want good settings, use **Suggest parameters…**
> in the dialog.

---

## How a parameter reaches the engine

```
GUI field ─┐
JSON box ──┼─► request "overrides" ─► server analyze_real ─► resolved params
           │                                                   │
           │   _TOP_LEVEL keys {LL1, widMAX, num_scales,        ├─ resolved["value"]  ──► fire_2d_angle(p=value)
           │   coefficient_percentile, fiber_threshold}         │        │
           │   go to the TOP level …                            │        └─ C++ process_fibers / check_danglers
           └───────────────────────────────────────────────────┘           (get the whole value dict)
```

Two consequences that drive everything below:

1. **Only `resolved["value"]` is forwarded to FIRE.** Any key the server routes
   to the *top level* (`widMAX`, `num_scales`, `coefficient_percentile`,
   `fiber_threshold`) **never reaches the engine** in this FIRE-only path — it is
   **inert**. (`LL1` is the exception: the server applies it itself, see below.)
2. **`LL1` is a server-side post-trace filter**, not a FIRE input — the server
   drops any reconstructed fiber whose arc length ≤ LL1 (default 30). The GUI
   "Min fiber length" maps to it.

The **Extra params (JSON)** box merges onto these overrides and wins over the GUI
controls. Keys you put there land in the `value` dict (so they reach FIRE) unless
they are one of the top-level keys above (then inert, except `LL1`).

---

## 1. Exposed in the GUI

| GUI control | Param | Default | Effect |
|---|---|---|---|
| Collagen channel | *(QuPath-side)* | ch 0 | Which channel is sent to the engine. |
| Downsample | *(QuPath-side)* | auto | Resolution reduction before analysis. |
| Background threshold | `thresh_im2` | 5 *(GUI default = 80th pct)* | Intensity cutoff (native units → 0–255). Pixels at/below = background. |
| Min fiber length | `LL1` *(server filter)* | 30 | Drops fibers shorter than this (µm or px). |
| Seed spacing | `thresh_LMPdist` | 2 *(GUI default 8)* | Min distance between fiber start-points. |
| Fiber mode | `fiber_mode` *(pipeline arg)* | 2 | Merged fibers (2) vs raw segments (1). |
| Smoothing | `sigma_im` | 0 | Gaussian blur before tracing. |
| Distance smoothing | `sigma_d` | 0.3 | Smooths the internal distance map before seeding (noise/faint companion to Smoothing). |
| Seed sensitivity | `thresh_LMP` | 0.2 | How easily a spot becomes a seed (lower → more). |
| Max bend angle (deg) | `thresh_ext` | 70° → cos = 0.342 | Largest turn per tracing step. Higher = follow curvier collagen. Entered in degrees, sent as a cosine. |
| Link distance | `thresh_linkd` | 15 | Max gap bridged when joining fiber pieces. |
| Max link angle (deg) | `thresh_linka` | 30° → −0.866 | Max bend-from-straight when joining two pieces. Higher = link curvier fibers. Entered in degrees, sent as −cos. |
| Max fiber width | `widMAX` | 20 | **Server-side filter**: fibers wider than this (analysis px) are dropped; 0 = off. (The FIRE backend itself ignores widMAX, so the server applies it, matching tme-quant `extraction.py`.) |
| TACS zone width | `distance_threshold` *(server TACS)* | 100 | Max boundary distance for TACS classification. |

---

## 2. JSON-only — effective (reach FIRE through the `value` dict)

Put these in the **Extra params (JSON)** box, e.g. `{"thresh_ext": 0.5, "thresh_linka": -0.5}`.

### Image preprocessing
| Key | Readable name | Default | What it does | Promote? |
|---|---|---|---|---|
| `sigma_d` | distance-map smoothing | 0.3 | Blurs the distance transform before seeding; widen (0.5–2) to merge broken ridges on noisy/wavy collagen. Complements `sigma_im`. | ✅ **Now in GUI** (Distance smoothing) |
| `dtype` | distance method | `cityblock` | L1 (`cityblock`) vs L2 (`euclidean`) distance transform. Minor effect. | Low |
| `thresh_im` | relative intensity threshold | `[]` | If set (fraction of max), **overrides** `thresh_im2`. Conflicts with the GUI threshold — leave in JSON. | Leave-JSON |

### Seeding (nucleation)
| Key | Readable name | Default | What it does | Promote? |
|---|---|---|---|---|
| `thresh_Dxlink` | crosslink distance thresh | 1.5 | Min distance-map value for a seed; filters seeds in thin/faint fibers. | Medium |
| `s_xlinkbox` | crosslink box size | 8 | Local-max suppression window (px); the distance-space twin of `thresh_LMPdist`. | Medium |

### Fiber extension (tracing)
| Key | Readable name | Default | What it does | Promote? |
|---|---|---|---|---|
| `thresh_ext` | extension angle thresh | 0.342 (cos 70°) | **Most important knob for wavy collagen.** Max bend allowed per step, as a cosine. Lower (e.g. 0.17 = cos 80°) allows very tortuous fibers; higher (0.5 = cos 60°) keeps only straighter ones. | ✅ **Now in GUI** (Max bend angle, in degrees) |
| `lam_dirdecay` | direction momentum | 0.5 | How much the running direction smooths the trace. Higher (→0.8) tracks persistent curves; lower reacts to sharp bends. | Medium |
| `s_minstep` | min step size | 2 | Min pixels advanced per tracing step. | Low |
| `s_maxstep` | max step size | 6 | Max pixels advanced per step. | Low |
| `s_fiberdir` | direction window | 4 | Past vertices used for the running-mean direction. | Low |

### Fiber linking / cleanup
| Key | Readable name | Default | What it does | Promote? |
|---|---|---|---|---|
| `thresh_linka` | link **angle** thresh | -0.866 (cos 150°) | The angle partner to `thresh_linkd` (already in the GUI). Two ends link only if near-collinear. Looser (-0.5 = 60°) for wavy collagen; stricter (→ -1) for straight. **Tune alongside Link distance.** | ✅ **Now in GUI** (Max link angle, in degrees) |
| `thresh_flen` | min linked length | 15 | Removes short vertex chains during linking (in vertices; distinct from `LL1`). | Medium |
| `thresh_numv` | min vertices/fiber | 3 | Drops fibers with fewer vertices. | Low |
| `s_boundthick` | edge exclusion (px) | 10 | Discards fibers hugging the image edge. (Tiling already trims edges Java-side.) | Medium |
| `s_maxspace` | interp. gap | 5 | Max gap the spline resampling bridges. | Low |
| `lambda` | spline tension | 0.01 | Stiffness of fiber interpolation (geometry only, not count). | Low |

### Angles / scaling
| Key | Readable name | Default | What it does | Promote? |
|---|---|---|---|---|
| `ang_interval` | angle bin | 3 | Vertex interval for per-fiber angle sampling (affects orientation/TACS resolution). | Low |
| `scale` | xyz scale | `[1,1,1]` | **Do not set** — calibration is applied QuPath-side; this would double-scale. | Leave-JSON |
| `blist` | boundary list | 1 | Internal boundary-handling flag. | Leave-JSON |

---

## 3. JSON-only — currently BYPASSED (dangler removal)

`fire_2d_angle.py` hard-sets `faithful_danglers = True` (the MATLAB-faithful path,
which is effectively a no-op — only `trimxfv` runs). So these are *nominally wired*
but have little/no effect until that override is made configurable:

| Key | Readable name | Default | Note |
|---|---|---|---|
| `thresh_dang_aextend` | dangler extension angle | 0.9848 (cos 10°) | Bypassed in current build. |
| `thresh_dang_L` | dangler length | 15 | Bypassed in current build. |
| `thresh_short_L` | short-fiber removal | 15 | Partially active (used by `trimxfv`). |

---

## 4. No effect — do not bother setting

These are **inert in the server's FIRE-only path**. The server routes them to the
top level, but only the `value` dict reaches FIRE (verified empirically for
`widMAX`: identical output for 2 vs 50). The curvelet params only matter for the
full CT reconstruction path, which is off (`use_ct_reconstruction=False`).

| Key | Why inert |
|---|---|
| `num_scales`, `coefficient_percentile`, `fiber_threshold` | Curvelet-reconstruction params; not used in FIRE-only mode. |
| `widcon{wid_mm, wid_mp, wid_sigma, wid_max, wid_opt}` | Width-computation options for the full CT-FIRE pipeline; not reached here. |

> `widMAX` is **no longer inert**: the GUI "Max fiber width" control is honoured by
> the server as a post-trace width filter (see §1), since the backend itself ignores it.

---

## 5. Recipes (Extra params JSON)

- **Wavy / curly collagen under-traced:** `{"thresh_ext": 0.17, "thresh_linka": -0.5, "lam_dirdecay": 0.7}`
- **Noisy / faint SHG, broken fibers:** `{"sigma_d": 1.0, "thresh_Dxlink": 1.0}` (plus raise GUI Smoothing)
- **Dense network, too many short overlaps:** raise GUI Seed spacing, or `{"s_xlinkbox": 12, "thresh_flen": 25}`
- **Keep only long straight fibers:** raise GUI Min length, plus `{"thresh_ext": 0.5, "thresh_linka": -0.9}`

---

## 6. Roadmap — promote to GUI controls

**Done (2026-06-15):** `thresh_ext` (Max bend angle), `thresh_linka` (Max link
angle), `sigma_d` (Distance smoothing) are now dedicated Advanced controls, and
`widMAX` (Max fiber width) is a working server-side width filter.

Remaining candidates: `s_xlinkbox` / `thresh_Dxlink` (seed density), `thresh_flen`
and `s_boundthick` (cleanup). Before exposing the dangler params
(`thresh_dang_*`), make `faithful_danglers` configurable so they take effect.
