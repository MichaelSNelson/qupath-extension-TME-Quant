# TME Quant — Parameters & Normalization (Design Doc 03)

> **The most important design doc.** Most "FIRE returns 0 fibers on a real image" bugs in this
> project have been a normalization or unit-conversion issue. This file explains exactly how a value
> the user sets in the dialog becomes the number CT-FIRE sees. All Java references are in
> `FiberAnalysisDialog.java` unless noted.
>
> **⚠ 2026-08-13 — thin collagen needs `sigma_im=0`; *any* smoothing >0 zeroes detection.** The
> *same* local backend traced fibres at **`sigma_im=0`** and returned **`Fiber segments: 0`** at
> **any `sigma_im>0`** on the same image. It is **not** a unit bug — `sigma_im` is honest pixels end
> to end (client sends it raw, server forwards it, `fire_2d_angle.smooth()` uses a `ceil(2σ)`-px
> Gaussian). The image reaching FIRE is `round_mlab(smooth(im, σ))` **then** thresholded, so on
> ~1-2 px collagen ridges a σ=1 kernel drops the peak below `thresh_im2`, the round pins it under,
> and nothing nucleates. FIRE's own default is `sigma_im=0` (`get_fire.py:23`); the engine is meant
> to run unsmoothed on thin fibres. The extension already defaults to 0 — a `sigma_im=1` comes from a
> manual change that then persists (`tmequant.smoothing`). Because the Suggest search holds smoothing
> FIXED (§7), a nonzero fixed value zeroes *every* combo at once (the original "all combos 0
> detected"). The earlier same-day "local traces / remote zeros" gap was **not** a compiled-binary
> difference — it was `sigma_im=0` locally vs a nonzero value remotely (§8.0, §9).

---

## 1. The core problem this subsystem solves

CT-FIRE expects an **8-bit (0-255) grayscale** image and thresholds/traces on *intensity ridges*.
QuPath images are often 16-bit with collagen occupying only the bright tail. Two conversions must
happen, and they must stay **mutually consistent**:

1. **Pixel intensities** (native, e.g. 0-28052) → **0-255** ("prescale"), using a white point.
2. The user's **Background threshold** (native) → **`thresh_im2`** (0-255), the value FIRE
   thresholds at.

If the prescale and the threshold use different reference points, the red mask the user tunes
against no longer matches what FIRE actually receives — and you get silent 0-fiber failures.

## 2. The intensity pipeline (native → what FIRE sees)

```
native pixel  ──prescale [normLo, HI]→[0,255]──▶  8-bit gray  ──FIRE thresholds at thresh_im2──▶ binary ──▶ distance map ──▶ nucleate ──▶ trace
```

- **`normLo`, `normHi`** — the white/black points. Set in `computeStats(channel)`:
  - Samples the region at `statDown = ceil(max(rw,rh) / 1024)` (i.e. ~1024 px on the long edge —
    finer than the original 256 px, which averaged thin bright fibers away and dragged `normHi`
    down).
  - `normLo = statMin` (sample min); `normHi = 99.5th percentile` of the sample (robust white
    point that ignores a few hot pixels).
- **`threshIm2()`** — maps the native threshold onto 0-255 for the **live mask**:
  `clamp((threshold − normLo) / (normHi − normLo) × 255, 0, 255)`.
- **`serverNormHi()` / `serverThreshIm2()`** — the *floored* versions used for the image **sent to
  FIRE** (see §3).

### Why "% of region above" and the mask can disagree
`fractionAbove()` (the "x% of region above" label) uses `statSample`; the mask uses `lastBgGray`
(a separately-read background image). If they sample at different resolutions they can disagree —
that mismatch was a real bug, fixed by sampling stats near the mask's resolution.

## 3. The saturation failure and the `serverNormHi()` floor  ★ critical

**Failure mode.** When the sampled region is mostly dark (e.g. previewing the **whole image**, or
any sparse-collagen field), the 99.5th-percentile `normHi` lands right at the collagen intensity.
Then `thresh_im2` computes to ~240-255. At that point the bright collagen has been prescaled into a
**flat, clipped plateau of 255** with no gradient. CT-FIRE's distance-map nucleation needs
*ridges*; a plateau has none, so it finds **0 nucleation points → 0 fibers**, for every threshold
and every search combo.

**Observed examples:** `thresh_im2=240` (first liver run), `thresh_im2=244` (whole-image run at
threshold 11000, where the whole-image `normHi≈11500`).

**The fix.** For the image **sent to the server only**, floor the white point so `thresh_im2` can
never saturate:

```java
private static final double MAX_SERVER_IM2 = 155.0;                 // ceiling for the sent threshold
private double serverNormHi(double lo, double hi) {
    double floor = lo + (thresholdSpinner.getValue() - lo) * (255.0 / MAX_SERVER_IM2);
    return Math.max(hi, floor);                                      // only ever raises the white point
}
private int serverThreshIm2(double lo, double hi) { … maps threshold through serverNormHi … } // ≤ 155
```

- Applied to the **sent** paths: `buildMeta` (`thresh_im2`), `detectTile` (prescale white point),
  `buildPieceFrom` (Suggest/batch pieces), `buildGridMeta`, `exportTile`.
- **Not** applied to the live mask (`refreshBackground`'s prescale, `buildThresholdOverlay`,
  `threshIm2()`). Rationale: the mask's *red region* is `{native > threshold}`, which is invariant
  to the white point (absent clipping), so the user's threshold-setting stays correct; the mask
  grayscale may show more clipping than the sent image, which is only cosmetic.

**Invariant to preserve:** `serverThreshIm2()` and the `serverNormHi()` used to prescale the sent
image **must use the same `(lo, hi)`**. They do, because both derive from the same fields (or the
same `gNormLo/gNormHi` in the Suggest/batch pieces, where the dialog freezes its fields to the
global scale first).

## 4. Units: µm vs px, and analysed-pixel conversion

- `calibrated` = image has a pixel size; `unitLabel()` returns `um` or `px`. `pixelSizeMicrons`
  holds µm/px.
- **`toAnalysedPx(userValue, downsample)`** = `(calibrated ? userValue / pixelSizeMicrons :
  userValue) / downsample`. So a µm value becomes full-res px, then analysed px at the current
  downsample. `fromAnalysedPx` is the inverse.
- This keeps physical params **downsample-independent**: change Downsample and the real-world size
  a param represents stays constant.

### ★ The advanced-param unit trap
Some "advanced FIRE" controls are **CT-FIRE algorithmic pixel values**, not physical microns —
e.g. `s_fiberdir` (the window used to estimate a fiber's local direction while extending it). The
extension currently treats them as µm and runs them through `toAnalysedPx`. On a **fine-pixel
image** (the liver test is 0.1725 µm/px) this inflates them badly:

| Control (default) | Treated as | At 0.1725 µm/px, downsample 1 |
|---|---|---|
| Direction window `s_fiberdir` = 6 | µm | **~35 px** (should be ~5-6 px) |
| Interp. spacing `s_maxspace` = 10 | µm | ~58 px |
| FIRE min length `thresh_flen` = 30 | µm | ~174 px (longer than the fibers!) |
| Dangling prune `thresh_dang_L` = 30 | µm | ~174 px |

A 35-px direction window spans an entire ~34-px wavy fiber, so the direction estimate is garbage
and extension dies → **0 segments**. Because these are **held fixed across the entire Suggest
search**, every combo fails *identically* — which is exactly the observed symptom. This is the
leading open suspect for the region-at-healthy-`thresh_im2` zero. **Status: not yet fixed** — see
§8. The workaround is the Extra-params JSON box (§6).

## 5. The full parameter set sent to FIRE

`buildMeta(downsample, hasMask, extra)` builds `overrides`, the FIRE value-dict. Mapping
UI control → FIRE key (all distances → analysed px via `toAnalysedPx`):

| UI control | FIRE key | Notes |
|---|---|---|
| Background threshold | `thresh_im2` | via `serverThreshIm2()` (floored, 0-255) |
| Min fiber length | `LL1` | analysed px; server-side length filter |
| Seed spacing | `thresh_LMPdist` | min distance between seeds (int px) |
| Smoothing | `sigma_im` | Gaussian blur before threshold+trace (**analysed px**, not µm) |
| Distance smoothing | `sigma_d` | smooth of the distance map (**analysed px**) |
| Seed sensitivity | `thresh_LMP` | nucleation threshold; **empirically 0.2 works, ≤0.12 can fail** |
| Cross-link search box | `s_xlinkbox` | int px |
| Link distance | `thresh_linkd` | gap bridged when joining pieces |
| Max bend angle | `thresh_ext` | = cos(deg) |
| Angle-extend | `thresh_dang_aextend` | = cos(deg) |
| Max link angle | `thresh_linka` | = −cos(deg) |
| Max fiber width | `widMAX` | server-side width filter (0=off) |
| Nucleation | `thresh_Dxlink` | |
| Direction decay | `lam_dirdecay` | |
| Direction window | `s_fiberdir` | **see §4 trap** |
| FIRE min length | `thresh_flen` | **see §4 trap** |
| Interp. spacing | `s_maxspace` | **see §4 trap** |
| Angle interval | `ang_interval` | deg |
| Beam regularisation | `lambda` | |
| Dangling prune | `thresh_dang_L` | **see §4 trap** |
| Extra params (JSON) | (any) | **merged last — overrides everything above** |

`meta` also carries `region {x,y,width,height,downsample}`, `fiber_mode`,
`use_ct_reconstruction=false`, `has_mask`, `distance_threshold` (TACS zone), and
`prescaled=true` (tells the server **not** to re-normalise per tile).

**Diagnostic logging (current build):** `analyzeOneTile` logs the full override JSON —
`Preview FIRE overrides sent: { … }` — so the exact values (including any Extra-JSON overrides) are
visible in QuPath's log. The server's `FIRE params:` line only echoes 3 of them.

## 6. Extra-params JSON escape hatch

The **Advanced ▸ Extra params (JSON)** box is parsed by `parseExtra()` and merged into `overrides`
*last*, so it wins. It's the way to force a raw FIRE value (e.g. `{"s_fiberdir": 5}`) without a
code change. It is **persisted** across sessions via the `tmequant.extraParams` preference
(`rememberText`) — earlier it was not, so it silently reset when the dialog reopened.

## 7. The two-stage Suggest search

`buildGridMeta` builds a Cartesian grid over detection axes; `thresh_im2`, `sigma_im`, downsample
and the advanced structural params are held **fixed** (the base overrides). Stages:

- **Stage A (count):** `thresh_LMPdist` (seed spacing), `thresh_LMP` (sensitivity),
  `thresh_linkd` (link distance), `thresh_Dxlink` (nucleation).
- **Stage B (geometry):** `thresh_ext` (bend), `thresh_linka` (link angle), `s_xlinkbox`.

Server scores each combo (see `02`); the extension aggregates across pieces and shows a ranked,
diversity-aware results window. **Consequence:** anything held fixed (a saturated `thresh_im2`, an
inflated `s_fiberdir`) makes *every* combo fail the same way — so "all combos scored 0" points at a
fixed/base parameter, not at the swept axes.

## 8. Known-open issues & recommended fixes (as of 2026-08)

0. **★ Thin collagen requires `sigma_im=0`; *any* smoothing >0 zeroes detection (2026-08-13,
   confirmed cause of the 0-fiber runs).** On the liver biref image the *same* backend traced fibres
   at `sigma_im=0` and returned `Fiber segments: 0` at *any* `sigma_im>0` — the core reports zero at
   the nucleation/extension stage, before any Python filter, even though the server measured ~23% of
   pixels above threshold. **This is not a unit bug** (a natural suspicion given §4): `sigma_im` is
   sent raw by the client (no `toAnalysedPx`), forwarded untouched by the server, and consumed by
   `fire_2d_angle.smooth()` as a `ceil(2σ)`-px Gaussian — pixels end to end, matching the label.
   Mechanism: the image reaching FIRE is `round_mlab(smooth(im, σ))` **then** thresholded (`:308`);
   collagen ridges are ~1-2 px wide, so a σ=1 kernel (radius 2 px) spreads the peak of a ~200-value
   ridge below `thresh_im2` (120 in the log), the integer round pins it under, the ridge stops being
   foreground, and nucleation gets nothing. Thick fibres survive the blur (the self-test runs at
   `sigma_im=1` and still passes); thin ones don't. FIRE's own default is `sigma_im=0`
   (`get_fire.py:23`; every repo template sets `"sigma_im": 0  # image smoothing (0 = off)`). Two
   consequences: (a) smoothing is held FIXED across the two-stage Suggest search (§7), so a nonzero
   fixed value zeroes *every* combo identically — the original "0 detected (target 91)" on every row;
   (b) although the extension **defaults smoothing to 0** (spinner + `tmequant.smoothing` pref), a
   manual change persists and silently keeps killing detection. **Immediate fix:** set `sigma_im=0`
   for thin collagen (raise above 0 only for thick/noisy fibres). **Product fixes:** keep the default
   at 0 and make the control clearer ("0 = off, best for thin fibres"); include `sigma_im` in the
   Suggest search (or retry `sigma_im=0` before reporting 0); warn in the preview when a run returns
   0 with `sigma_im>0`. **Note — retraction:** an earlier same-day hypothesis blamed the Windows
   `cp314-mingw` build vs the Linux `cp310` `.so`. That was a **confounded comparison** — local was
   `sigma_im=0`, remote nonzero; the *same* local binary also returns 0 at `sigma_im>0`. Source and
   both binaries are at commit `4692493`; the Python 3.10-vs-3.14 build difference was **not** the
   cause. (An intermediate correction here mistakenly said to *raise* smoothing to ≥2 — backwards;
   the working value is 0.)
1. **Advanced structural params treated as µm (§4).** Recommended: reclassify `s_fiberdir`,
   `s_maxspace`, `thresh_flen`, `thresh_dang_L` (and review `s_xlinkbox`, `thresh_linkd`) as
   **pixel** quantities at CT-FIRE's native scale — either send them un-converted, or default them
   from the server (as the self-test does by omitting them). Lowering `s_fiberdir` is the single
   highest-value change to try.
2. **Advanced defaults exceed real fiber size** (FIRE min length / dangling prune = 30 µm on
   ~13 µm fibers). Make calibration-aware or smaller.
3. **Unit-blind persistence.** Spatial prefs store one number with no µm/px tag, so a value saved
   on a px image reloads as µm. Recommended: unit-scoped persistence.
4. **`normHi` on huge/sparse regions.** The floor (§3) bounds `thresh_im2`, but the *mask* still
   uses the low percentile `normHi`; consider a higher percentile (99.9) or a foreground-aware
   white point so the displayed grayscale isn't over-clipped.

## 9. Debugging checklist for "0 fibers"

0. **Check smoothing first (§8.0).** If a real region returns 0, set **Smoothing (`sigma_im`) = 0**
   and re-run before anything else — on thin collagen *any* smoothing >0 collapses nucleation to
   `Fiber segments: 0` even with plenty of foreground. If Suggest returns "0 detected" on *every*
   combo, that's a fixed nonzero smoothing killing the whole grid; set it to 0 and re-search. (A
   whole-machine "traces nothing" that `sigma_im=0` does *not* fix, where a known-good server traces
   the byte-identical exported tile, would point at the compiled build — but the 2026-08 case was
   smoothing.)
1. `Ping` says `real`? If synthetic → engine not loaded (`04`).
2. Run **Self-test fiber engine** → PASS means the engine *loads and traces the synthetic image*.
   Note: a PASS does **not** prove the build traces *real* images correctly (§8.0) — it only rules
   out a totally-unloaded backend. If self-test passes but every real region zeros, go to step 0.
3. Check the server `FIRE params:` line: is `thresh_im2` in a healthy ~90-160 band, or saturated
   (~240)? Saturated ⇒ normalization/floor issue (§3).
4. Check `Preview FIRE overrides sent:` in QuPath's log: are `s_fiberdir` / `thresh_flen` etc.
   absurdly large (§4)? Force sane px values via Extra-JSON (§6).
5. Check the server extraction line: `Fiber segments: 0` at nucleation ⇒ stage-① or a fixed
   structural param; fibers traced then filtered ⇒ `LL1`/`widMAX`/`thresh_flen`.
