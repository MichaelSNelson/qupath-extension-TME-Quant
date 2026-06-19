# qupath-extension-TME-Quant

A [QuPath](https://qupath.github.io) extension for **collagen fiber analysis**. It sends a
selected image region to the **TMEQuant FIRE** fiber-extraction pipeline over a local socket,
previews the detected fibers interactively, and adds them back to the image as annotations or
detections — optionally classified by **TACS** (Tumour-Associated Collagen Signatures) relative
to a tumour boundary.

The fiber math runs in a small **Python server** (the *FIRE backend*, built on CT-FIRE). This
extension is the QuPath-side client + UI; it does **not** bundle the backend (see
[Architecture](#architecture) and [Acknowledgements](#acknowledgements)).

- 📖 **Settings reference:** [docs/COLLAGEN_SETTINGS.md](docs/COLLAGEN_SETTINGS.md) — every
  control, what it does, units, defaults, and tuning recipes.
- 🛠️ **Developer parameter detail:** [docs/PARAMETERS.md](docs/PARAMETERS.md).

---

## What you need

1. **QuPath 0.7.x** (the extension targets 0.7.0).
2. **The extension jar** (this repo's release) — installed in QuPath.
3. **The FIRE server** — a Python environment that runs the fiber pipeline. On Windows this uses
   **MSYS2 UCRT64**; the included `server/tmequant_server.bat` automates the whole setup.
4. **The TMEQuant FIRE pipeline** (`tme-quant`) — obtained separately (it is **not** redistributed
   here; see [Acknowledgements](#acknowledgements)). Place it next to the server scripts as
   described in the setup guide.

---

## Install

### 1. Install the extension in QuPath
Download `qupath-extension-tme-quant-<version>-all.jar` from the
[Releases](https://github.com/MichaelSNelson/qupath-extension-TME-Quant/releases) page and
**drag it onto the QuPath window** (or copy it into your QuPath *extensions* directory). Restart
QuPath. You'll get an **Extensions ▸ TME Quant** menu.

### 2. Set up + start the FIRE server (Windows, one file)
Lay out a folder so the server scripts and the pipeline sit side by side:

```
CTFireTest\
  fiber_socket_bridge\   <- the server\ scripts from this repo
  tme-quant\             <- the FIRE pipeline (obtained separately)
```

Then **double-click `fiber_socket_bridge\tmequant_server.bat`**. On first run it:

1. Installs **MSYS2** if it's missing (via `winget`, or opens the download page as a fallback),
2. runs the one-time setup (system update + `setup_fire_server.sh`: dependencies, virtual env,
   and validates/builds the FIRE backend), guarded by a `.tmequant_setup_ok` marker, then
3. starts the server. You'll see `Fiber socket server listening on 127.0.0.1:5101 (backend=real)`.

Leave that console window open while you work. **Later runs skip setup and start immediately.**

> Full step-by-step (including the manual fallback if a step fails):
> [server/WINDOWS_SETUP_GUIDE.md](server/WINDOWS_SETUP_GUIDE.md).

### 3. Let QuPath start the server for you
Open **Extensions ▸ TME Quant ▸ Analyze fibers in selected region…**. The first time, if the
server isn't running, QuPath offers to **locate the launcher** — point it at
`tmequant_server.bat`. From then on, opening the dialog **starts the server automatically** and
waits for it to be ready. You can change the launcher path any time via
**Extensions ▸ TME Quant ▸ Configure fiber server launcher…** or in QuPath
**Preferences ▸ TME Quant** (where you can also turn auto-launch off).

---

## Use

1. **Open an image** and draw/select an **area annotation** over the tissue you want to analyze.
2. **Extensions ▸ TME Quant ▸ Analyze fibers in selected region…**
3. **① Thresholding** — pick the collagen channel and set the **Background threshold** by eye: the
   red mask shows exactly which pixels FIRE will treat as collagen. Adjust **Downsample** and
   **Smoothing** here too. Collapse this section once it looks right.
4. **② Fiber detection** — click **Preview fibers** to trace. Scroll to zoom either preview, drag
   to pan (they stay in sync), and **double-click** to pick the tile for "One tile (fast)".
5. **Tune automatically (optional)** — trace a few real fibers as **line** annotations inside your
   region, then **Suggest parameters…**. It searches the detection knobs to match your traced
   count and ranks the results; select a row to load it, **Preview selected** to see it.
6. **Add to image** — commits the fibers as `Fiber` (and `TACS-2/3`) objects. The dialog stays
   open so you can run more regions; it follows your QuPath selection live.

For TACS: select a larger region, tick **Classify TACS…**, and choose a tumour-boundary annotation
that sits inside the region.

A complete walkthrough of every control is in
**[docs/COLLAGEN_SETTINGS.md](docs/COLLAGEN_SETTINGS.md)**.

---

## Architecture

```
QuPath (this extension)  --TCP socket (127.0.0.1:5101)-->  Python FIRE server  -->  CT-FIRE backend
   region PNG + params (JSON)                                analyze / gridsearch       (compiled C++)
   <-- fibers (JSON) ----------------------------------------
```

The extension only speaks a small socket protocol; all fiber math is delegated to the external
server. That keeps this repo free of the restricted upstream code.

---

## Building from source

```bash
./gradlew shadowJar
# -> build/libs/qupath-extension-tme-quant-<version>-all.jar
```

Requires a JDK compatible with QuPath 0.7 (Java 21+). The QuPath libraries are provided at
runtime (not bundled).

---

## Troubleshooting

- **"Could not reach fiber server"** — the server window isn't running, or is still loading (the
  first start takes ~30–40 s while the C++ backend loads). Start `tmequant_server.bat`, wait for
  the "listening" banner, then retry.
- **`backend = synthetic`** (from *Ping fiber server*) — the real FIRE backend didn't load, so
  you're getting an OpenCV fallback and the FIRE parameters have no effect. See the setup guide /
  `server/diagnose_backend.sh`.
- **Fibers look scrambled / all horizontal** — make sure the server is the current build; older
  backends mis-handled non-square tiles.
- **Preview is cropped to a small region** — that's the tuning region. Tick **Use whole image** or
  select a larger annotation (the dialog follows the live selection).

---

## Acknowledgements

This tool is a client for the **TMEQuant / FIRE** collagen pipeline and builds on work from the
**Laboratory for Optical and Computational Instrumentation (LOCI), University of
Wisconsin–Madison**. Please cite the underlying methods in any publication:

- **CT-FIRE / FIRE** — Bredfeldt et al., *Computational segmentation of collagen fibers from
  second-harmonic generation images of breast carcinoma*, J. Biomed. Opt. 19(1):016007 (2014).
- **CurveAlign** — Liu et al. (LOCI), collagen fiber quantification and alignment.
- **TWOMBLI** — Wershof et al., for fiber/matrix morphology context.

The FIRE pipeline (`tme-quant`), the compiled CT-FIRE backend, **CurveLab** (FDCT) and **FFTW**
are **not** distributed with this extension and carry their own licenses — notably **CurveLab is
restricted to non-commercial/academic use and cannot be redistributed**; obtain and accept those
licenses separately if you enable the optional curvelet path. This extension uses the FIRE-only
path, which does not require CurveLab.

---

## License

[Apache License 2.0](LICENSE). This license covers **this extension's own code** (the QuPath
client + bridge scripts). It does **not** relicense QuPath (GPLv3, used via its public extension
API at runtime) or any upstream pipeline you install separately.
