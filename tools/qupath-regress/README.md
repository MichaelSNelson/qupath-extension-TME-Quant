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

A run takes about 6 minutes and ends with `8/8 passed`, or a list of scenarios with the field that
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
