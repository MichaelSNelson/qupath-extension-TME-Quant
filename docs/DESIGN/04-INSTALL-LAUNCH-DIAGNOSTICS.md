# TME Quant — Install, Launch & Diagnostics (Design Doc 04)

> How the server gets onto a Windows machine, how QuPath brings it up, and how to tell a broken
> engine from bad settings. Scripts live in `fiber_socket_bridge/`.

---

## 1. Deploy layout & prerequisites

```
<root>\                       e.g. D:\python_microscope_server\tmequant\
  fiber_socket_bridge\        server scripts + fiber_socket_server.py
  tme-quant\                  the FIRE pipeline (sibling; note the hyphen) — obtained separately
  venv_fire\                  created by setup (--system-site-packages)
```

- **MSYS2** at `C:\msys64` (or set `MSYS2_ROOT`), with the **UCRT64** toolchain. The compiled
  CT-FIRE `.pyd` only loads under UCRT64 — not MSYS/MINGW64/CLANG64, and not plain CMD/PowerShell.
- **QuPath 0.7.x** + the extension jar.
- Internet on first run (pacman + pip downloads).

## 2. The script family

| Script | Runs where | Purpose |
|---|---|---|
| `tmequant_server.bat` | double-click / CMD | **The one file QuPath points at.** Finds/installs MSYS2, first-run setup, then launches. |
| `tmequant_boot.sh` | inside UCRT64 (called by the .bat) | first-run `pacman -Syu` + `setup_fire_server.sh` guarded by `.tmequant_setup_ok`, then `start_fire_server.sh`. |
| `setup_fire_server.sh` | UCRT64 | idempotent one-time setup: pacman packages, `venv_fire` (system-site-packages), validate/auto-rebuild the backend. |
| `build_backend.sh` | UCRT64 | force-recompile the CT-FIRE `.pyd` from source for this machine. |
| `start_fire_server.sh` | UCRT64 | per-session launch; binds 127.0.0.1:5101; prints the "listening" banner. |
| `rebuild_backend.bat` + `rebuild_backend.sh` | double-click / UCRT64 | **no-shell rebuild:** runs `build_backend.sh` + `setup_fire_server.sh` inside UCRT64. Use when the self-test says the engine loaded but traces 0. |
| `test_fire_server.sh` | UCRT64 | CLI round-trip test using `real1.tif` (needs `fiber_socket_client_test.py`, which some deploys lack — prefer the in-app self-test). |

### How the .bat invokes MSYS2 (the fragile bit)
```
cd /d "%~dp0."
call "%MSYS2_ROOT%\msys2_shell.cmd" -ucrt64 -defterm -no-start -here -c "./tmequant_boot.sh %PORT%"
```
- `-ucrt64` selects the toolchain, `-defterm` uses the current console, `-no-start` avoids a
  relaunch, `-here` lands MSYS2 in the current dir.
- **Keep the `-c` string trivial.** Earlier versions put `cd "$(cygpath …)"` inside `-c`; cmd.exe
  mis-parses the `$()`/quotes → "The syntax of the command is incorrect." and the window closes
  instantly. The fix: `cd` in cmd first, then a bare `./script.sh` in `-c`. See
  `docs/INSTALLER_NOTES.md`.
- Line endings matter: `.bat` = CRLF, `.sh` = LF. A `.sh` saved with CRLF throws `$'\r'` errors.

### MSYS2 not on C:
Set once: `setx MSYS2_ROOT "D:\msys64"`, then **restart QuPath** (the auto-launched child inherits
QuPath's environment, so the var must be set before QuPath starts). `winget`'s `--location` is
unreliable for the MSYS2 package, so for a non-C: drive use the GUI installer's folder picker
(the `.bat`'s `[M]` option).

## 3. QuPath auto-launch (`ServerLauncher.java`, `FiberSocketPreferences`)

- Prefs: `tmequant.serverLauncher` (path to `tmequant_server.bat`) and `tmequant.autoLaunchServer`
  (default true). Set via **Extensions ▸ TME Quant ▸ Configure fiber server launcher…** or QuPath
  **Preferences ▸ TME Quant**.
- When the dialog opens and the server isn't reachable, `ServerLauncher.ensureReachable` either
  auto-launches the batch and polls until ready, or (if unset) prompts to **Locate launcher…**.
- On Windows it launches detached in its own console (`cmd /c start "" "<path>"`) so the user sees
  the ~30 s load banner; a non-Windows fallback (`bash <path>`) keeps the dev box working.

## 4. Diagnostics — telling a broken engine from bad settings

Three levels, increasing specificity:

1. **Ping fiber server** — reachability + `backend`. `real` only proves the backend *imported*.
   **It cannot detect an engine that loaded but traces nothing.**
2. **Self-test fiber engine…** (`EngineSelfTest.java`) — the important one. Sends a **built-in
   synthetic image of obvious straight fibers** through the normal `ANALYZE_` path with a **minimal
   9-parameter set** (server defaults for the rest) and checks `nTraced`:
   - `backend=real` & `nTraced>0` → the engine **loads and traces the synthetic image**. (A healthy
     run traces ~19-63 fibers on the test image.) **Caveat (2026-08-13):** a PASS proves the engine
     loads and can trace *thick* fibers — it does NOT prove your *settings* will trace a real region.
     The self-test itself runs at `sigma_im=1` and still passes because its synthetic fibres are
     thick; thin real collagen at **any Smoothing (`sigma_im`) > 0** returns `Fiber segments: 0`
     (confirmed cause of the 0-fiber runs — see `03 §8.0`). So: self-test PASS + real-image zeros ⇒
     set smoothing to 0 and re-check before suspecting the build.
   - `backend=real` & `nTraced==0` → **engine PROBLEM**: compiled backend present but not working
     on this machine → **double-click `rebuild_backend.bat`**. The dialog offers to open the docs.
   - `synthetic` → real backend never loaded → troubleshooting/rebuild.
   - The verdict is logged (`Fiber engine self-test: backend=… traced=…`) so it's copyable from
     **View ▸ Show log** (the pop-up notification is transient).
   - **Why it uses only 9 params:** by omitting the advanced structural params it isolates the
     *engine* from the extension's µm→px conversions. If the self-test passes but a real region
     fails, the difference is those extra params (see `03 §4`) — that's the whole diagnostic point.
3. **Preview override log** — `Preview FIRE overrides sent: { … }` in QuPath's log shows the full
   parameter set actually sent (including any Extra-JSON), so you can compare against the passing
   self-test's 9 and spot an inflated value.

### The rebuild path (no shell)
Self-test says PROBLEM → put `rebuild_backend.bat` + `rebuild_backend.sh` in `fiber_socket_bridge`
→ double-click the `.bat` → it runs `build_backend.sh` + `setup_fire_server.sh` in UCRT64 →
restart the server → self-test again. Equivalent manual commands (UCRT64 shell):
`./build_backend.sh` then `./setup_fire_server.sh`.

## 5. First-run expectations

First `tmequant_server.bat` run downloads several hundred MB and compiles the engine: **~5-15 min**,
not frozen. It writes `.tmequant_setup_ok` so later runs skip straight to launch (seconds). Success
= `Fiber socket server listening on 127.0.0.1:5101 (backend=real)`.

## 6. Common failure signatures

| Symptom | Meaning | Fix |
|---|---|---|
| Window opens then closes instantly | old `.bat` with the `$()`-in-`-c` bug, or `.sh` has CRLF | get the current scripts; keep `.bat` CRLF / `.sh` LF |
| `backend=synthetic` | real backend didn't load | not in UCRT64, or rebuild: `rebuild_backend.bat` |
| `DLL load failed … procedure could not be found` | prebuilt `.pyd` ≠ this machine's libs | `build_backend.sh` (rebuild) |
| Ping OK but every analysis is 0 fibers | settings (usually **smoothing**), or engine loaded-but-not-tracing | set **Smoothing to 0** first (`03 §8.0`); if still 0, run **Self-test** — PASS ⇒ settings (`03 §9`) |
| Fibers all horizontal / scrambled | old backend mishandling non-square tiles | update the server build (padding workaround) |
| Analyze menu items grayed out | no image open (menu binds to `imageDataProperty().isNull()`) | open an image; check for duplicate jars if it persists |
