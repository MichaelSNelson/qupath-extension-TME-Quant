# Changelog

## 0.2.3

**The dialog used the intensity scale of the downsample it was opened at.** Changing **Downsample** in an
open dialog re-read the preview but not the statistics behind it, so a run kept the black point, white
point and bright-collagen sample of the previous downsample. Opened at 2 and run at 1, one 1600 px region
was sent `thresh_im2` 68 instead of 73 and traced 97 FIRE fibres instead of 88. The statistics are now
recomputed when the downsample changes, keeping your threshold as a region change does.

**The scripting API now normalises exactly as the dialog does.** `TMEQuant.detectFibers` left out the
bright-collagen floor of the white point (on the region above it sent `thresh_im2` 103 instead of 73, and
found 89 fibres) and coarsened large regions where the dialog reads a central patch at the analysis
downsample. On that region the two now agree on every fibre.

Both were found by a new end-to-end suite, `tools/qupath-regress/`, which drives the dialog, the batch
dialog and the scripting API inside a real QuPath and compares the resulting objects with recorded
fingerprints. It also confirmed, inside QuPath: Flow and Centerline with Parallel tiles 4 equal serial;
FIRE ignores the setting without timing out; restrict-to-object leaves fibres inside the object
unchanged; TACS; a two-image batch; and a 35,672-fibre whole-image run through the blocked post-process
(the size that used to fail with "Unreasonable response length").

**Width median alongside width mean, on every tracer.** A fibre's width samples are skewed by the
junctions and blobs its trace passes through — a junction doubles the local distance-map radius — so
the mean overstates a clean fibre while the median tracks its body, and the ratio flags a contaminated
trace (29% of centreline fibres on the ground-truth box carried more than 20% skew). Every object now
has `Width median` next to `Width mean`. This also removes an inconsistency: the Flow tracer was
sending its *median* as `width` while FIRE and Centerline sent their *mean*, so the one column — and
the Max fiber width filter built on it — meant different things depending on the tracer. `width` is
the mean everywhere now; Flow users will see slightly larger `Width mean` values than before and the
median they had under that name.

**Two-pass relabelled for the dim-fibre use.** The mechanism always allowed the second threshold to be
lower than the first — the spinner spans the full range — but the label and tooltips only described the
higher setting (carving thick bundles). With the Flow tracer, a modest step DOWN is the useful direction:
on the ground-truth box 77+70 raised recall from 48% to 51% at 98% of true length, while 77+55 reached
60% recall but over-traced to 122%. The checkbox is now "Two-pass (second threshold)", the tooltips give
both directions with that guidance, and the run status names which one is running.

**Run ID now survives being stored.** The first run of the extension inside QuPath under an agent
(the qupath-mcp action log driving the dialog and reading the detections back) showed two runs 80 s
apart carrying the SAME `Run ID`, 20261011193856 — a value that was never a real stamp. QuPath keeps
every measurement as a 32-bit float, whichever list type an object is created with, and a float holds
integers exactly only below 16,777,216; near the old 14-digit `yyyyMMddHHmmss` ids it steps by
2,097,152, so the hour, minute and second were rounded away and nothing in `runs.json` could ever
match a detection. The id is now the run's start time in minutes since 2024-01-01 (exact until 2055;
a run in the same minute as the previous one takes the next free value) and `runs.json` carries the
full stamp. Objects made by 0.2.0–0.2.2 still carry the rounded ids and cannot be re-linked.

**Batch: the overall tile count was wrong, and the log described the wrong run.** Found the same way.
A whole-image batch showed "9 / 30 tiles" while it was running tile 9 of 64: the tiler clipped tiles
against the dialog's own region origin, which at counting time was still the region the dialog was
launched from, so tiles past the image edge *as measured from there* were dropped. The tiler now takes
its origin and image size explicitly and the count matches the run. The batch log's `--- reproduce ---`
block also carried the launch dialog's region and intensity scale rather than the batch's global
scale; it now says so, and each region logs its actual `norm=` line as it starts.


**The flow tracer is about twice as fast, with bit-identical output.** Profiling one tile put
66% of the whole trace in `_flow_sample`: the walk reads three fields — coherence and the two
direction components — at the SAME positions on every step, and was re-deriving the clip, the
integer conversion, the fractions and all four corner indices separately for each one. Computed
once per step now, shared, with flat `take()` gathers instead of 2-D fancy indexing (1.51x).
Then the trajectory buffer: `(max_steps, n, 2)` float32 at 20,000 seeds per batch is a 128 MB
array, allocated and NaN-filled twice per batch and strided through on every step — the tracer
was memory-bound, not compute-bound. Batching at 2,000 keeps it to ~13 MB (2.8x on its own).
Together 2.07x, and the output is unchanged to the last digit: 250 fibres, 11,689 points,
checksum 13546663.878083 before and after.

**Parallel tiles.** A new "Parallel tiles" setting (Advanced server settings, default 1, so
nothing changes until you raise it) traces several tiles at once. One number drives both sides:
the client sends that many tiles concurrently and the server spends it as worker PROCESSES.
Processes, not threads, because measurement said so — running the tracer in 4 threads was SLOWER
than serial (0.86x) while its individual building blocks each scale 2.2-2.8x under the same test,
so the tracer is GIL-bound on its Python-level loops. End to end over the socket, 6 tiles at once
run 1.90x faster and every response is identical to the serial one. Higher is not always better:
8 workers measured worse than 6, because what limits the tracer is memory bandwidth rather than
cores. FIRE is excluded — it keeps its own isolated worker for the compiled backend and its
crash-and-retry handling.

Worker threads do only the read-and-trace; ordering, counters, the live overlay, the log and the
ETA all stay in one consuming loop that walks the results in submission order, so the assembled
fibre list matches a serial run and nothing downstream had to become thread-safe.

**Stopping the server no longer leaves worker processes behind.** Spawned workers do not die
with their parent, and only the SHUTDOWN command reached the cleanup — Ctrl-C unwound past it and
a kill ended the process outright, so every other way of stopping the server orphaned one process
per pool slot, and a few restarts stacked them up. The accept loop is now wrapped in a `finally`,
SIGINT and SIGTERM set the shutdown flag instead of killing the process, and `atexit` is the
backstop. Verified: worker count returns to its starting value after the server stops.

**The server was serialising itself.** `_run_file_logging` held its lock across the entire
analysis, so with run logging on — the default — a second concurrent request waited for the first
to finish tracing. It is reference counted now, with the lock held only to install and remove the
handler. Tiles of one run share a log file, so they share one handler.

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
