# Changelog

## 0.2.2

**A batch run can be stopped, and says what it did.** "Run across project" offered one button,
"Run analysis", and nothing else: no way to close the window except the title-bar X, and — worse —
no way to stop a run at all. A batch over a project is hours of work, so the only way out was to
quit QuPath, losing the images still queued. There is now a **Stop**, which is noticed between
tiles rather than only between images (one whole-slide image is long enough that a per-image check
is no use), and a **Close**. Stopping is clean: images that finished keep their fibres, because
each image is saved as it completes, and the image part-way through is left untouched. The outcome
— images processed, fibres written, log file name — now stays on the dialog instead of only
appearing in a notification that is gone if you looked away.

## 0.2.1

**Whole-slide runs no longer fail at the last step.** A 1620-tile slide traced for two and a half
hours, assembled 153,675 fibres, stitched them — and then died reading the reply: at ~2.5 kB of
JSON per fibre the response was 379 MB, past the client's sanity cap. Dedup and stitch only ever
compare fibres whose endpoints are a few pixels apart, so a large set is now split by fibre centre
into disjoint spatial blocks of ~20k fibres and each block stitched on its own. The only thing
blocking can cost is a join whose two pieces fall either side of a block line, so the pass runs
twice, the second time on a grid offset by half a block. Checked against the single call on a
synthetic 40,000 px field tiled at 2128 px: 40,621 pieces stitch to 40,189 fibres either way —
blocking is lossless at realistic block sizes (20,000 px blocks against fibres of at most 1,200 px),
and no slower per pass. The offset pass is insurance for the case where a dense region forces blocks
small enough to matter; forced down to blocks only ~2x fibre length it recovers about two thirds of
what one pass misses. The stitch also stops being one opaque wait: the status bar now counts blocks. TACS is
deliberately NOT blocked spatially — it measures distance to the boundary, which needs the whole
mask — so it chunks the fibre list instead and sends the full mask with each chunk. The headless
scripting path got the same treatment.

**Fibres that doubled back on themselves are cut.** A traced path could turn around and come back
along its own route, giving one object with roughly twice the real length whose two ends sat side
by side. The orientation field is undirected, so a flow walk that loses the thread can flip sign
and retrace, and the walk's turn limit never objects — it caps the turn per 1.5 px step, not the
total. Such a path is now detected geometrically (two points far apart along the arc but close in
space), cut at the turn, and only the longer arm kept — the arms of a retrace are duplicates of
each other. A genuine wide U-bend is left alone: its arms stay too far apart to trigger. On test
shapes a 599 px retrace becomes 300 px while straight, wavy and U-bent fibres are untouched.

**Run logs now record enough to reproduce the run.** The header carried downsample, min length
and seed spacing, but not the threshold and — more importantly — not the intensity normalisation.
The server is sent a pre-scaled 0-255 image, so the `thresh_im2` in its transcript is meaningless
without the `normLo`/`serverNormHi` that produced it: recovering a past run's real threshold meant
solving backwards from a tile's foreground percentage. Each run now also writes three terse lines:
`norm=` (channel, downsample, region, pixel size, native threshold, normLo, normHi, serverNormHi,
thresh_im2, smoothing), `client=` (output type, clipping, TACS, exclusion source, extra params —
the settings that shape the result but never reach the server), and `meta=`, which is the ACTUAL
JSON sent to the server, built by the same code path the run uses, so every FIRE parameter, guard
and tracer choice is recorded under its own server key and cannot drift from what was applied.
Terse by design: this is for reproducing a run, not for reading.

**Cyclic colormaps for angle measurement maps.** Angle is an orientation of period 180°, where 1°
and 179° are two degrees apart — but every colormap QuPath ships is a line, so a map of
"Angle (deg)" painted those two nearly identical fibres at opposite ends of the scale, splitting one
population of near-horizontal collagen into two. Two cyclic maps are installed: **Angle (cyclic)**,
a constant-lightness circle in CIELAB where hue alone carries the angle (so no orientation looks
more important than another), and **Angle (cyclic vivid)**, the familiar saturated hue wheel. Set
the map's range to 0–180 to pin colours to absolute angles.

**Angle was mirrored on restrict-to-object runs.** Image y grows downward, and the client's own
angle calculation did not account for it, so a run restricted to an object reported 180−θ where
every other run reported θ. Any angle statistic mixing the two was comparing mirrored populations.

## 0.2.0

**Exclusion regions (new stage ⓪).** Keep fibre tracing out of regions you choose — nuclei
segmented from a paired brightfield image, or any objects. Two sources: objects already in the
image, or a GeoJSON file (streamed, so a 350 MB cell export loads in seconds rather than becoming
123k QuPath objects). Applied to the traced geometry rather than by deleting pixels: a fibre that
IS the excluded object is dropped, spokes radiating out of one are trimmed, and a fibre that
merely crosses one is left whole. Measured on a nucleus-rich field, 6 of the 7 longest fibres pass
through a nucleus while sitting only ~6% inside one, so erasing would have chopped the best fibres
to remove the junk. Removes ~33% of traced length on such a field.

**Connected-web guard.** Tiles whose collagen forms one connected network ran to the full timeout
and returned nothing. Density did not predict this — a 30.8% foreground tile traced in 7 s while a
16.2% one timed out — but the skeleton size of the largest connected component does. The guard
raises the threshold until the web breaks, or fails the tile in seconds instead of minutes. One
tile went from 200 s and nothing to 7.4 s.

**Clipping fix: the white point now follows the collagen.** It was pinned to a fixed ratio of the
threshold, which left only ~1.6x headroom while real collagen runs several times brighter — so the
tops of thick bundles clipped to flat white and their internal structure was destroyed before
tracing. Mean clipping across nine tiles drops from 10.1% to 0.02% (one tile: 33% to 0.05%) with
fibre counts unchanged.

**Ridge mode now picks a usable threshold.** It used Otsu, which assumes two modes; a fibre-ness
map is a long tail with no second mode, so Otsu landed far too high and kept only the strongest
ridges. On a thick band it chose 74 and traced 32 fibres where the 60th percentile chose 19 and
traced 98, over four times the length.

**Duplicate fibres removed from the assembled set.** The global dedup compares centroids and skips
same-tile pairs by design, so anything producing two copies within one tile after tracing — e.g.
two-pass detection, where both passes trace the same bundle — survived. The path-overlap dedup now
runs on the assembled set too.

**Orientation fields no longer pad with zeros.** Both the structure tensor and the ridge field used
skimage's default `mode='constant'`, so the image border became a step edge and the field reported
"the collagen runs along this border", confidently (73° wrong at 0.53 confidence on a synthetic
test). Near a tile edge this inverted the flow filter: genuine fibres meeting an edge at an angle
were removed, while edge-parallel artifacts were kept.

**Run provenance.** Every object carries a `Run ID` measurement (the run timestamp), and
`<project>/logs/fiber-analysis/runs.json` maps it to that run's settings and log files. The log now
records tracer, preset, two-pass, ridge, raw mode, exclusion and the guard caps — so "which
settings produced these detections?" has an answer after the fact.

**Anti-flow check (optional).** Scores each fibre against an independent estimate of the local
collagen direction and adds it as an `Anti-flow (deg)` measurement; nothing is removed. Independent
because it uses a different operator from the one the tracer follows — checking a tracer against
its own field only ever agrees with it, which is how the padding artifact went unnoticed.

**Settings are remembered.** Tracer, parallel fibre distance, anti-flow check, connected-web cap
and every exclusion-region control now persist across restarts, since these describe a way of
working rather than a property of one slide. The settings and exclusion file choosers also open in
the project folder instead of at the top of the machine.

**Fixes.** Waviness measurements (kink, crimp, curvature) were only written when a run restricted
to an object; they now appear on both paths. Overlay colours reworked for legibility: the mask is
deep violet and fibre traces are thick yellow, which raises their contrast from 1.9:1 to 6.1:1.

## 0.1.0

Initial release.
