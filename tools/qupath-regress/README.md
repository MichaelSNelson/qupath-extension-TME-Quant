# QuPath regression checks

`regress.py` runs TME Quant inside a real QuPath and checks what ends up in the hierarchy. It drives
QuPath through the [qupath-mcp](https://github.com/earthmover-support/qupath-mcp) extension: it opens
the Analyze dialog, sets controls by their labels, presses **Apply to chosen area**, and reads the
result back with Groovy as a fingerprint (object count, classes, ROI length and sums of the main
measurements).

Run it before staging a jar:

```bash
python3 regress.py                    # all scenarios, compared with expected.json
python3 regress.py tacs flow_parallel # a subset
python3 regress.py --record           # after an intended change: rewrite expected.json
```

A run takes about 7 minutes and ends with `9/9 passed`, or a list of scenarios with the field that
changed in each (`dialog.n: 88 -> 97`). For each failed scenario it also saves every open QuPath window
(the dialog and the viewer) as PNGs in `$REGRESS_DIR/failures/<date>-<time>/`, plus a text outline
of the open TME Quant dialogs with every control's value (a screenshot shows only the scrolled-to part),
and lists the files. The main-window PNG is several MB on a HiDPI screen; nothing is saved for a pass.

## Scenarios

| Scenario | Checks on its own | Fingerprint |
|---|---|---|
| `flow_parallel` | Flow, Parallel tiles 4 gives exactly the serial result | 2400 px region |
| `centerline_parallel` | the same for Centerline | 2400 px region |
| `fire_parallel_requested` | FIRE with Parallel tiles 4 is unchanged and no tile times out | 1600 px region |
| `headless_vs_dialog` | `TMEQuant.detectFibersInAnnotations` equals the dialog on the same region; the dialog is opened at downsample 2 and switched to 1 first | both runs |
| `restrict_to_object` | fibres with both ends well inside an ellipse are unchanged by **Restrict to selected object** | the restricted run |
| `tacs` | TACS against an ellipse boundary assigns several TACS classes | 3000 px region |
| `batch_two_images` | **Run across project** over two images gives fibres in each, with a **Finished.** summary | per-image counts |
| `batch_skips_other_unit` | **Run across project** over a calibrated and an uncalibrated copy traces the calibrated one and skips the other, saying why (the dialog's µm settings would be read as px) | none |
| `large_postprocess` | a whole-image run over 20,000 fibres takes the blocked post-process path, both passes, no failed block | 35,583 fibres |

Every scenario first sets downsample, threshold, minimum length, tracer and the output checkboxes,
because the dialog persists its settings between runs.

## Setup

- QuPath 0.7 with this extension and qupath-mcp installed; its MCP server on `MCP_PORT` (default
  51516).
- The fibre server on 127.0.0.1:5101.
- `REGRESS_IMAGE`: the test image (default: the 16-bit liver birefringence tile used to record
  `expected.json`). The fingerprints belong to that image; record again for another one.
- `REGRESS_DIR` (default `~/tmequant-regress`): where the two-image test project is created.
- `REGRESS_QUPATH_LOG`: QuPath's console output, which the script reads to tell when a run has
  finished (default `~/tmequant-regress/qupath.out`, so start QuPath with its output sent there).

The script works on the open image in place: it clears its objects and never saves it.

## Scoring against hand-traced fibres

`gt_score.py` runs FIRE through the scripting API on an area annotation that holds hand-traced
LINE annotations and scores each run the way Gurrala et al. 2021 count oversegmentation: how many
detections follow one traced fibre (most of their length within `--tol` px and `--angle` degrees
of it), how many traced fibres are hit, pieces per hit fibre, and the share of traced length
covered. It opens the image from its file and copies the annotations into that scratch image, so
the project is only read. Edit `SETS` to compare parameter sets.

```bash
python3 gt_score.py --project /mnt/g/MH_FNA/project.qpproj --entry 258 \
    --image /mnt/g/MH_FNA/SlideImages/MetroHealth_FNA_PDAC_1_20x_38323_35919_7.0_biref_020.ome.tif \
    --thresh-im2 77 --out gt_score.json
```

It writes the scores, the traced lines and every detection to `--out`, so other tolerances can be
re-scored offline with `gt_score.score()`. It changes the dialog's saved threshold, downsample,
min length, seed spacing and extra params; reset them before running `regress.py`.

On that box (176 traced fibres, threshold mapped to `thresh_im2` 77, Min length 4 µm, Seed spacing
8 µm, 2026-10-21), 0.2.4's FIRE settings hit 11 traced fibres where 0.2.3's µm-converted ones hit 1
(8 px, 30°), and 17 versus 8 at a loose 20 px, 60°, at about one piece per fibre either way.

