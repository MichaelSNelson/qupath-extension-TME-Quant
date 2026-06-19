# Collagen-detection settings reference

Every control in the **TME Quant** dialog, what it does, its units and default, and
when to change it. The dialog is organised in three stages plus a tuner:

- **①  Thresholding** — what FIRE *sees* (live preview, no analysis).
- **②  Fiber detection** — how FIRE *traces* fibers (the slow step).
- **Advanced FIRE settings** — rarely-touched backend knobs.
- **Parameter tuning (Suggest)** — automatic search for the detection knobs.

> **Units.** When your image has a pixel size (calibrated), every distance/length/width
> field is in **microns (µm)** and is converted to analysis pixels at run time — so changing
> the **Downsample** never changes the physical size you asked for. On an uncalibrated image
> the same fields are in **full-resolution pixels**. Angles are always in **degrees**.

> **If you just want good settings:** set Thresholding by eye (①), trace a few real fibers as
> line annotations, and click **Suggest parameters…** — it searches the detection knobs for you.

---

## ①  Thresholding (what FIRE sees)

These are **instant**: the red mask updates live with no server call, so you tune them by eye.
The mask shows exactly which pixels FIRE will treat as collagen.

| Setting | Param | Default | What it does |
|---|---|---|---|
| **Collagen channel** | *(QuPath-side)* | channel 0 | Which image channel holds the collagen signal (e.g. SHG, or a collagen stain). Only this channel is sent to the engine. |
| **Downsample** | *(QuPath-side)* | auto (≈ longest side / 1200) | Resolution reduction before analysis. `1` = full res (small/high-mag regions); `2–8` for large regions. The engine is tuned for ~500 px images, so whole slides need a higher value. Faster + coarser as it rises. |
| **Smoothing (sigma)** | `sigma_im` | 0 | Gaussian blur applied **before** thresholding and tracing. Raise to suppress noise/grain; lower toward 0 to keep fine detail. (In analysis pixels — it's a blur radius, not a physical distance.) |
| **Background threshold** | `thresh_im2` | 80th percentile | Intensity cutoff in the image's **native** units (the values you see when mousing over the image). Pixels at/below it are background. The feedback label shows the % of the region above it. **This is the single biggest lever** — get it right first using the mask. |

---

## ②  Fiber detection (normal)

The expensive step. **"Preview fibers"** runs FIRE on the region (a few seconds); **"Add to
image"** always does the full tiled run.

| Setting | Param | Default | What it does |
|---|---|---|---|
| **Preview scope** | — | One tile (fast) | `One tile` traces just one tile for fast tuning — **double-click the fiber preview** to choose which tile (yellow box). `Whole region` traces all tiles + stitch + TACS (slower). |
| **Seed spacing** | `thresh_LMPdist` | 16 µm | Minimum distance between fiber **start-points**. Larger → fewer, more spread-out fibers and less memory; smaller → denser detection. (Sets spacing, not the seed strength.) |
| **Seed sensitivity** | `thresh_LMP` | 0.2 | How easily a point becomes a start-point. **Lower** accepts weaker spots → **more** seeds → more fibers; raise to seed only the brightest cores. |
| **Cross-link search box** | `s_xlinkbox` | 6 µm | Search-box radius for finding nucleation points / cross-links. Larger merges nearby maxima into one seed; smaller keeps them separate. |
| **Max bend angle** | `thresh_ext` | 70° | Largest turn the tracer may make **at each step**. **Raise to follow curvier/wavier collagen**; lower to keep only straighter fibers. *The most important knob for tortuous collagen.* |
| **Angle-extend threshold** | `thresh_dang_aextend` | 10° | Largest turn a fiber **end** may take while still being extended through a junction. |
| **Max link angle** | `thresh_linka` | 30° | How far two fiber ends may differ in direction and still be **joined** into one fiber. Raise to link curvier fibers across gaps; lower for near-straight joins. Pairs with **Link distance**. |
| **Min fiber length** | `LL1` *(server filter)* | 15 µm | Fibers shorter than this are discarded **after** tracing. Raise to drop noise specks/fragments; lower to keep short fibers. |
| **Fiber mode** | `fiber_mode` | Merged fibers | `Merged fibers` joins traced pieces into whole continuous fibers (best for long bundles, most analyses). `Segments` keeps each traced piece separate. |

> **Max link angle vs CT-FIRE.** This control is a deviation-from-straight (0–90°); CT-FIRE's
> internal `thresh_linka` uses the 180−θ convention, so **30° here ≡ CT-FIRE 150**.

### TACS classification (optional)

| Setting | Param | Default | What it does |
|---|---|---|---|
| **Classify TACS relative to a boundary** | — | off | Classify each fiber by its Tumour-Associated Collagen Signature: **TACS-2** (straightened, parallel to the boundary) vs **TACS-3** (perpendicular, radiating away). Needs a boundary annotation inside the region with stroma around it. |
| **Boundary annotation** | — | first annotation | The tumour–stroma boundary outline (a separate annotation inside the region). Fibers are classified by angle + distance relative to it. |
| **TACS zone width** | `distance_threshold` | 100 µm | Fibers whose centre is within this distance of the boundary are classified; farther ones are left unclassified. Widen to include more stroma; narrow to focus on the peri-tumoural rim. |

### Output options

| Setting | Default | What it does |
|---|---|---|
| **Create detections (not annotations)** | off | Add fibers as lightweight *detection* objects instead of *annotations*. |
| **Use whole image (ignore selection)** | off | Analyze the **entire image** with the current parameters instead of the selected annotation. Handy after tuning on a small region. While ticked, the dialog ignores the QuPath selection. |
| **Remove existing Fiber/TACS objects in the region first** | on | Before adding, delete previously-created `Fiber`/`TACS-1/2/3` objects whose centre is inside the region, so repeated runs don't stack. Objects elsewhere are kept. |

---

## Advanced FIRE settings

Pipeline defaults — leave them alone unless you have a reason. All distances/lengths are in
µm (calibrated) / full-res px (uncalibrated).

| Setting | Param | Default | What it does |
|---|---|---|---|
| **Distance smoothing** | `sigma_d` | 0.3 | Extra smoothing of the internal distance map before seeds are placed. Raise (0.5–2) on noisy/faint collagen to merge broken ridges. The companion to **Smoothing**. |
| **Link distance** | `thresh_linkd` | 30 µm | Largest gap that can be bridged to join two fiber pieces. Raise to connect fragmented bundles; lower if separate fibers merge. |
| **Max fiber width** | `widMAX` | 40 µm | Drops detected fibers **wider** than this — rejects thick blob/vessel detections. Lower if wide artefacts slip through; set high (or max) to keep all. **0 disables it.** (Applied server-side after tracing.) |
| **Nucleation threshold** | `thresh_Dxlink` | 1.0 | How strong a distance-map peak must be to seed a cross-link. (CT-FIRE GUI ships 1.5.) |
| **Direction decay** | `lam_dirdecay` | 0.5 | How strongly the tracer keeps its current heading vs. following the local ridge (0–1). Higher tracks persistent curves; lower reacts to sharp bends. |
| **Direction window** | `s_fiberdir` | 6 µm | Window used to estimate a fiber's local direction. Larger = smoother/steadier direction. |
| **FIRE min length** | `thresh_flen` | 30 µm | FIRE's *own* short-fiber removal during linking. Distinct from **Min fiber length** (the post-trace filter). |
| **Interp. spacing** | `s_maxspace` | 10 µm | Vertex spacing when interpolating each fiber centerline. |
| **Angle interval** | `ang_interval` | 5° | Sampling interval used when computing per-fiber angles (orientation/TACS resolution). |
| **Beam regularisation** | `lambda` | 0.01 | Stiffness of the fiber interpolation (geometry only; doesn't change the count). |
| **Dangling prune** | `thresh_dang_L` | 30 µm | Short spurs up to this length at a fiber end are trimmed. |
| **Extra params (JSON)** | — | — | Override any FIRE `value`-dict parameter directly, e.g. `{"thresh_Dxlink": 1.5, "lam_dirdecay": 0.7}`. These win over the controls above. See *Inert parameters* below. |

### Inert parameters (no effect in this FIRE-only build)

The server runs the FIRE-only path, so these are **ignored** even if you set them in the JSON box:
`num_scales`, `coefficient_percentile` (curvelet-reconstruction only), `s_minstep`, `s_maxstep`,
`thresh_short_L`, `thresh_numv`. Unknown keys are ignored. (Full developer detail:
[PARAMETERS.md](PARAMETERS.md).)

---

## Parameter tuning (Suggest)

**Suggest parameters…** grid-searches the four detection knobs that have **no live preview** —
**seed spacing, seed sensitivity, bend angle, link angle** — and ranks the results. Threshold,
smoothing and downsample are held **fixed** at your current values (you set those by eye in ①).

**Workflow:** ① set threshold/smoothing/downsample by eye → ② select the parent **area**
annotation → trace **all** real fibers inside it as **line** annotations (the search targets that
total count) → click **Suggest**. Disjoint annotations are pooled; only pixels inside the
annotation are used. Annotate ≥ ~8 fibers for a reliable target.

| Setting | Default | What it does |
|---|---|---|
| **Count tolerance (±%)** | ±15% | How close the detected count must be to your annotated count to score perfectly. Human annotation is imperfect, so a band beats an exact target. |
| **Refinement rounds** | 3 | Iterative zoom: after a coarse grid, the search re-centres a finer grid on the best detection settings and repeats, up to this many rounds (stops early once it stops improving). |
| **Disjoint pieces** | Flat union | How to combine the pieces of a multi-part annotation. `Flat union` pools all detected/ground-truth fibres into one total (recommended). `Area-weighted` weights each piece's quality terms by its area. |

Every combo is a **real FIRE run**, so keep the tuning region small. The results window is
non-modal: select a row to load its parameters (the mask updates live), **Preview selected** to
trace its fibres, and **Save measurements…** to append a TSV row so you can compare areas.

---

## Quick recipes

| Symptom | Try |
|---|---|
| Wavy/curly collagen under-traced | Raise **Max bend angle** (→ 80°) and **Max link angle** (→ 50°); raise **Direction decay** (→ 0.7). |
| Noisy/faint SHG, broken fibers | Raise **Smoothing** and **Distance smoothing** (0.5–2); lower **Nucleation threshold** (→ 1.0). |
| Too few fibers | Lower **Seed sensitivity** (→ 0.1) and **Seed spacing**; check the **Background threshold** isn't too high (look at the mask). |
| Dense network, too many short overlaps | Raise **Seed spacing**; raise **FIRE min length**. |
| Keep only long straight fibers | Raise **Min fiber length**; lower **Max bend angle** (→ 60°) and **Max link angle** (→ 10°). |
| Thick vessels detected as fibers | Lower **Max fiber width**. |
