# TME Quant — Parameters & Normalization (Design Doc 03)

> **The most important design doc.** Most "FIRE returns 0 fibers on a real image" bugs in this
> project have been a normalization or unit-conversion issue. This file explains exactly how a value
> the user sets in the dialog becomes the number CT-FIRE sees. All Java references are in
> `FiberAnalysisDialog.java` unless noted.
>
> **Smoothing above 0 used to give 0 fibres. That was a bug, fixed on 2026-09-12.** On
> 2026-08-13 this doc called it "not a unit bug" and blamed a σ=1 blur for flattening thin
> ridges below `thresh_im2`. That explanation was wrong. The cause was in tme-quant's
> `fire_2d_angle.smooth()`: the 2-D image arrives shaped `(1, J, I)`, and `smooth()` convolved
> every axis, including the size-1 channel axis, with zero padding. Along that axis the
> convolution only multiplies the image by the kernel's centre weight, about 0.4 for any
> sigma > 0. The whole image dropped to ~40% of its brightness, the client's `thresh_im2`
> (computed for the unattenuated image) then rejected almost everything, and nothing nucleated.
> `sigma_im = 0` returns early, which is why only 0 worked. The fix skips any axis of size 1. It
> lives in `tme-quant/src/ctfire_py/fire_2d_angle.py` (and its twin under
> `src/tme_quant/src/ctfire_py/`), which this repository does not ship. It is submitted upstream
> as [uw-loci/tme-quant#62](https://github.com/uw-loci/tme-quant/pull/62) (open, not yet merged as
> of 2026-10-21); until it is merged, a tme-quant checkout has the fix only if it has that branch
> or the same local edit. FIRE's own default is still `sigma_im = 0`, and the extension defaults to it.

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
- **`displayNormHiNative()`** (0.2.4) — the white point of the **live mask**'s grayscale:
  `max(normHi, brightCollagenLevelNative())`, the second being the 99.9th percentile of the
  above-threshold sample (the same level the sent image is scaled to cover, §3).
- **`threshIm2For(threshold, whitePoint)`** — maps the native threshold onto 0-255 through the
  white point the mask's grayscale was built with:
  `clamp((threshold − normLo) / (whitePoint − normLo) × 255, 0, 255)`. Each grayscale stores its
  white point (`lastBgWhitePointNative`, `pickedTileWhitePointNative`), so gray and threshold
  always share a scale.
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
- The **ratio floor** is not applied to the live mask, so the mask keeps its contrast. The mask's
  violet region is `{native > threshold}`, which does not depend on the white point as long as
  the threshold sits below it.
- The **bright-collagen level** is applied to the live mask since 0.2.4 (`displayNormHiNative`,
  §2). Before that the mask used plain `normHi`, and on a large or sparse region that was not
  only cosmetic. With `normHi` at the collagen's own intensity, every bundle drew as flat white,
  and a threshold above `normHi` mapped to 255 and drew an **empty mask**, although pixels above
  the threshold existed. When the threshold is raised to within 5% of the white point a mask was
  built with, the background is re-read (`refreshBackgroundIfThresholdNearWhitePoint`).

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

### The advanced-param unit trap (fixed in 0.2.4)
Four "advanced FIRE" controls are CT-FIRE algorithmic values, not physical microns. Until 0.2.4
the extension treated them as µm and ran them through `toAnalysedPx`. On a fine-pixel image (the
liver test is 0.1725 µm/px) that inflated them badly:

| Control (old default) | At 0.1725 µm/px, downsample 1 | What FIRE actually reads |
|---|---|---|
| Direction window `s_fiberdir` = 6 µm | 35 | a COUNT of vertices back along the fiber (`_end_direction` uses `fv[sp]`); FIRE default 4 |
| Interp. spacing `s_maxspace` = 10 µm | 58 px | resampling spacing in analysis px; FIRE default 5 |
| FIRE min length `thresh_flen` = 30 µm | 174 px, longer than the fibres | compared with the summed point-to-point length in analysis px; FIRE default 15 |
| Dangling prune `thresh_dang_L` = 30 µm | 174 px | analysis px; inactive in this build; FIRE default 15 |

At the usual downsample 4 the old FIRE min length was still 43 px, above the 22 px of the 15 µm
**Min fiber length**, so FIRE's internal cut, not the control users tune, set the shortest fibre
kept.

**Since 0.2.4** these four are typed in FIRE's own units (vertices, analysis px) at FIRE's own
defaults (`ct_fire.py`) and sent as typed. Their preference keys are new
(`tmequant.fiberDirVertices`, `tmequant.fireFlenAnalysedPx`, `tmequant.maxSpaceAnalysedPx`,
`tmequant.dangLenAnalysedPx`), so an old micron value is never read in the new unit. A version-1
settings file's `fiberDir`, `fireFlen`, `maxSpace` and `dangLen` are ignored, with a notice.

### Unit-scoped persistence (0.2.4)
Every user-unit distance (Min fiber length, Seed spacing, Cross-link box, Link distance, Max
width, TACS zone, Parallel fibre distance, exclusion Grow regions) is stored once per unit
(`FiberSocketPreferences.UnitScopedDouble`): `tmequant.<name>Um` for calibrated images and
`tmequant.<name>FullResPx` for uncalibrated ones. The dialog reads and writes the store for the
current image's calibration and reloads all of them, and relabels them, when it follows the
active image to one with the other calibration (`onUnitChanged`). `FiberParams.fromPreferences
(calibrated)` does the same for the scripting API. Before, a value tuned on an uncalibrated image
came back as microns on a calibrated one.

Two related guards: loading a settings file saved in the other unit skips the distance keys
(`USER_UNIT_SETTING_KEYS`) instead of applying them, and **Run across project** skips an image
whose calibration differs from the dialog's image. Exclusion **Grow regions** on an uncalibrated
image is now full-resolution px, like every other distance (it was analysis px before).

## 5. The full parameter set sent to FIRE

`buildMeta(downsample, hasMask, extra)` builds `overrides`, the FIRE value-dict. Mapping
UI control → FIRE key (user-unit distances → analysed px via `toAnalysedPx`; the four FIRE
internals of §4 are sent as typed):

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
| Direction window | `s_fiberdir` | vertices, sent as typed (§4) |
| FIRE min length | `thresh_flen` | analysis px, sent as typed (§4) |
| Interp. spacing | `s_maxspace` | analysis px, sent as typed (§4) |
| Angle interval | `ang_interval` | deg |
| Beam regularisation | `lambda` | |
| Dangling prune | `thresh_dang_L` | analysis px, sent as typed (§4) |
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

## 8. Known issues and their status (updated 2026-10-20)

0. **Smoothing above 0 gave 0 fibres: fixed 2026-09-12, in tme-quant.** The cause was
   `fire_2d_angle.smooth()` convolving the size-1 channel axis of the `(1, J, I)` image with zero
   padding, which scaled the whole image to about 40% (see the note at the top). The 2026-08-13
   entry that stood here called it "not a unit bug" and blamed the blur itself; that explanation
   was wrong, and its advice to keep `sigma_im = 0` on thin collagen is not needed with the fix.
   The fix is [uw-loci/tme-quant#62](https://github.com/uw-loci/tme-quant/pull/62), open and not
   yet merged as of 2026-10-21.
1. **Advanced structural params treated as µm: fixed in 0.2.4** (§4). `s_fiberdir` is a vertex
   count; `thresh_flen`, `s_maxspace` and `thresh_dang_L` are analysis px. All four are sent as
   typed. `s_xlinkbox` and `thresh_linkd` stay in the user unit: both are real distances in the
   image (a search box, a gap), so following the pixel size is right for them.
2. **Advanced defaults exceeded real fiber size: fixed in 0.2.4.** The defaults are FIRE's own
   (4, 15, 5, 15), so FIRE min length no longer overrides **Min fiber length**.
3. **Unit-blind persistence: fixed in 0.2.4** (§4, *Unit-scoped persistence*).
4. **`normHi` over-clipping the mask on huge/sparse regions: fixed in 0.2.4** (§3): the mask's
   white point covers the bright collagen, and its threshold is mapped through the same value.

## 9. Debugging checklist for "0 fibers"

0. **Check smoothing (§8.0).** If every run at Smoothing > 0 returns `Fiber segments: 0` and
   Smoothing = 0 traces, the server is running a tme-quant without the 2026-09-12 `smooth()` fix.
   Apply it (or set Smoothing to 0) and restart the server.
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
