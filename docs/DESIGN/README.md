# TME Quant — Design Docs

A from-the-code breakdown of how the TME Quant collagen-fiber system works and how the parts fit
together. Written 2026-08 after an extended debugging session; reflects the current code including
the recent normalization floor, self-test, and diagnostic-logging additions.

## Read in this order

1. **[00-ARCHITECTURE.md](00-ARCHITECTURE.md)** — the parts (QuPath extension ↔ Python server ↔
   CT-FIRE backend), the socket protocol, the end-to-end flows, and where bugs have lived. Start
   here.
2. **[03-PARAMETERS-AND-NORMALIZATION.md](03-PARAMETERS-AND-NORMALIZATION.md)** — **the crux.** How
   a UI setting becomes the number FIRE sees: the prescale/white-point, the `serverNormHi()`
   saturation floor, µm↔px conversion (and the advanced-param unit trap), the full parameter map,
   the two-stage Suggest search, known-open issues, and a "0 fibers" debugging checklist.
3. **[01-QUPATH-EXTENSION.md](01-QUPATH-EXTENSION.md)** — the Java/JavaFX client: class inventory,
   the `FiberAnalysisDialog` structure (UI sections, threading, tiling, the four run paths,
   follow-active-image), and fragile areas.
4. **[02-SOCKET-SERVER.md](02-SOCKET-SERVER.md)** — the Python server: wire protocol, backend
   loading (real vs synthetic), the `analyze_real` pipeline, grid-search scoring, worker isolation,
   post-processing.
5. **[04-INSTALL-LAUNCH-DIAGNOSTICS.md](04-INSTALL-LAUNCH-DIAGNOSTICS.md)** — MSYS2, the batch/shell
   scripts, QuPath auto-launch, and the Ping → Self-test → Preview-log diagnostic ladder + the
   no-shell rebuild.

## One-paragraph summary

QuPath's TME Quant extension is a thin **socket client**; all fiber math runs in a separate
**Python server** that wraps the compiled **CT-FIRE** engine. The extension reads a region's
pixels, **prescales them to 0-255** against a global white point, maps the user's native
**threshold** to FIRE's `thresh_im2`, converts µm distances to analysed pixels, and sends a region
PNG + parameter JSON. Nearly every "0 fibers" failure has been a **normalization** issue (white
point too low ⇒ bright collagen clips to a flat plateau ⇒ FIRE seeds nothing) or a **unit** issue
(advanced FIRE pixel-params inflated by µm→px conversion, held fixed across the whole search;
fixed in 0.2.4). The engine itself is proven healthy by the in-app **self-test**. The status of
each known issue is in
[03 §8](03-PARAMETERS-AND-NORMALIZATION.md#8-known-issues-and-their-status-updated-2026-10-20).

## Related existing docs

- `../COLLAGEN_SETTINGS.md`, `../PARAMETERS.md` — user-facing settings reference.
- `../INSTALLER_NOTES.md` — cmd.exe/MSYS2 quoting & line-ending gotchas for the scripts.
- `../../../fiber_socket_bridge/PROJECT_SUMMARY.md` — project history/status.
- `../../../fiber_socket_bridge/WINDOWS_SETUP_GUIDE.md` — step-by-step Windows setup.
